package io.github.kukis13.parallelzip.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Core parallel ZIP writer. Stateless and reusable; the Gradle task is a thin wrapper.
 *
 * <p>Pipeline: every entry is compressed (STORE or DEFLATE) on a worker pool, while a
 * single writer thread drains results <em>in a fixed, name-sorted order</em> and streams
 * them to the archive. Parallelism never affects the byte layout, so output is
 * deterministic regardless of thread count. With {@code preserveTimestamps=false} the
 * archive is byte-for-byte reproducible across rebuilds of identical content.</p>
 *
 * <p>Small entries are compressed entirely in memory (the fast path). Entries larger than
 * {@link #SPILL_THRESHOLD} are streamed through the deflater to a temporary file (or read
 * directly from the source when stored), so memory stays bounded and a single entry may be
 * arbitrarily large. ZIP64 is emitted automatically for archives &gt; 4 GiB, more than
 * 65,535 entries, or per-entry sizes/offsets beyond 4 GiB.</p>
 */
public final class ParallelZipWriter {

    private ParallelZipWriter() {}

    static final int LFH_SIG = 0x04034b50;
    static final int CDH_SIG = 0x02014b50;
    static final int EOCD_SIG = 0x06054b50;
    static final int EOCD64_SIG = 0x06064b50;
    static final int EOCD64_LOC_SIG = 0x07064b50;
    static final int Z64_EXTRA_ID = 0x0001;
    static final int FLAG_UTF8 = 0x0800;
    static final long MAX32 = 0xFFFFFFFFL;
    static final int MAX16 = 0xFFFF;
    /** Entries larger than this are streamed (spilled) instead of held in memory. */
    static final long SPILL_THRESHOLD = 128L << 20; // 128 MiB
    static final int STREAM_BUF = 1 << 16;

    // Fixed DOS date/time used when timestamps are not preserved: 1980-01-01 00:00:00.
    static final int FIXED_DOS_TIME = 0;
    static final int FIXED_DOS_DATE = 0x21;

    /** A source tree to be added under an optional archive path prefix. */
    public record Source(Path baseDir, String prefix) {}

    public record Result(long archiveSize, int entryCount, long rawBytes, long storedBytes,
                         long enumerateMs, long compressWriteMs, boolean zip64) {}

    static final class Entry {
        String name;
        Path file;          // null for directory
        boolean dir;
        int dosTime, dosDate;
        byte[] nameBytes;
        long size;
    }

    /**
     * The compressed form of one entry. Either {@code data} holds the payload in memory,
     * or {@code streamFrom} points to a file (a spill temp, or the source itself when
     * stored) that the writer streams {@code compSize} bytes from.
     */
    static final class Compressed {
        final Entry e;
        final byte[] data;         // in-memory payload, or null when streamed
        final Path streamFrom;     // file to stream from, or null when in memory
        final boolean streamIsTemp;// delete streamFrom after writing
        final long crc, rawSize, compSize;
        final int method;
        Compressed(Entry e, byte[] data, Path streamFrom, boolean streamIsTemp,
                   long crc, long rawSize, long compSize, int method) {
            this.e = e; this.data = data; this.streamFrom = streamFrom; this.streamIsTemp = streamIsTemp;
            this.crc = crc; this.rawSize = rawSize; this.compSize = compSize; this.method = method;
        }
    }

    /** Public entry point used by the Gradle task. */
    public static Result write(List<Source> sources, Path out, boolean store, int level,
                               int threads, boolean preserveTimestamps)
            throws IOException, InterruptedException {
        return write(sources, out, store, level, threads, preserveTimestamps, false, SPILL_THRESHOLD);
    }

    /** Test overload: force ZIP64 encoding regardless of size. */
    static Result write(List<Source> sources, Path out, boolean store, int level,
                        int threads, boolean preserveTimestamps, boolean forceZip64)
            throws IOException, InterruptedException {
        return write(sources, out, store, level, threads, preserveTimestamps, forceZip64, SPILL_THRESHOLD);
    }

    /**
     * @param forceZip64     test hook: emit ZIP64 records/extra fields even when values fit.
     * @param spillThreshold entries larger than this are streamed; tests pass a small value
     *                       to force the streaming path without huge inputs.
     */
    static Result write(List<Source> sources, Path out, boolean store, int level,
                        int threads, boolean preserveTimestamps, boolean forceZip64, long spillThreshold)
            throws IOException, InterruptedException {

        long t0 = System.nanoTime();
        List<Entry> entries = enumerate(sources, preserveTimestamps);
        long tEnum = System.nanoTime();

        Path outAbs = out.toAbsolutePath();
        Files.createDirectories(outAbs.getParent());
        Path spillDir = Files.createTempDirectory(outAbs.getParent(), ".pzip-");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ThreadLocal<Deflater> deflaters = ThreadLocal.withInitial(() -> new Deflater(level, true));
        Semaphore inFlight = new Semaphore(threads * 4);
        BlockingQueue<Future<Compressed>> queue = new ArrayBlockingQueue<>(threads * 4 + 1);

        long[] stats = new long[2]; // raw, stored
        final long[] archiveSize = {0};
        final boolean[] usedZip64 = {false};
        final IOException[] writerError = {null};

        Thread writer = new Thread(() -> {
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
                List<Entry> order = new ArrayList<>(entries.size());
                List<long[]> meta = new ArrayList<>(entries.size()); // crc, comp, raw, method, offset
                long offset = 0;
                while (true) {
                    Future<Compressed> f = queue.take();
                    if (f == POISON) break;
                    Compressed c = f.get();
                    inFlight.release();
                    long localOffset = offset;
                    byte[] lh = localHeader(c.e, c.crc, c.compSize, c.rawSize, c.method, forceZip64);
                    os.write(lh);
                    writePayload(os, c);
                    offset += lh.length + c.compSize;
                    order.add(c.e);
                    meta.add(new long[]{c.crc, c.compSize, c.rawSize, c.method, localOffset});
                    stats[0] += c.rawSize; stats[1] += c.compSize;
                }
                byte[] tail = buildCentralDirectoryAndEnd(order, meta, offset, forceZip64, usedZip64);
                os.write(tail);
                archiveSize[0] = offset + tail.length;
            } catch (IOException ex) {
                writerError[0] = ex;
            } catch (Exception ex) {
                writerError[0] = new IOException(ex);
            }
        }, "parallel-zip-writer");
        writer.start();

        try {
            for (Entry e : entries) {
                inFlight.acquire();
                Future<Compressed> fut = pool.submit(() -> compress(e, store, level, deflaters.get(), spillThreshold, spillDir));
                queue.put(fut);
            }
            queue.put(POISON);
            writer.join();
        } finally {
            pool.shutdown();
            deleteRecursively(spillDir);
        }

        if (writerError[0] != null) {
            throw writerError[0];
        }
        long tEnd = System.nanoTime();
        return new Result(archiveSize[0], entries.size(), stats[0], stats[1],
                (tEnum - t0) / 1_000_000L, (tEnd - tEnum) / 1_000_000L, usedZip64[0]);
    }

    private static void writePayload(OutputStream os, Compressed c) throws IOException {
        if (c.data != null) {
            os.write(c.data, 0, (int) c.compSize);
            return;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(c.streamFrom), 1 << 20)) {
            byte[] buf = new byte[STREAM_BUF];
            long remaining = c.compSize;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("Unexpected EOF streaming " + c.e.name);
                os.write(buf, 0, n);
                remaining -= n;
            }
        } finally {
            if (c.streamIsTemp) Files.deleteIfExists(c.streamFrom);
        }
    }

    private static Compressed compress(Entry e, boolean store, int level, Deflater def,
                                       long spillThreshold, Path spillDir) {
        try {
            if (e.dir) return new Compressed(e, new byte[0], null, false, 0, 0, 0, 0);
            if (e.size > spillThreshold) {
                return compressLarge(e, store, level, spillDir);
            }
            byte[] raw = Files.readAllBytes(e.file);
            CRC32 crc = new CRC32();
            crc.update(raw);
            if (store || raw.length == 0) {
                return new Compressed(e, raw, null, false, crc.getValue(), raw.length, raw.length, 0);
            }
            def.reset();
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] tmp = new byte[STREAM_BUF];
            while (!def.finished()) {
                int n = def.deflate(tmp);
                bos.write(tmp, 0, n);
            }
            byte[] comp = bos.toByteArray();
            if (comp.length >= raw.length) { // deflate didn't help: store this entry
                return new Compressed(e, raw, null, false, crc.getValue(), raw.length, raw.length, 0);
            }
            return new Compressed(e, comp, null, false, crc.getValue(), raw.length, comp.length, 8);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress " + e.name, ex);
        }
    }

    /** Streams a large entry: deflate to a temp file, or (when stored / not helped) read from source. */
    private static Compressed compressLarge(Entry e, boolean store, int level, Path spillDir) throws IOException {
        CRC32 crc = new CRC32();
        if (store) {
            crcOf(e.file, crc);
            // Stream the source itself; nothing is copied.
            return new Compressed(e, null, e.file, false, crc.getValue(), e.size, e.size, 0);
        }
        Path tmp = Files.createTempFile(spillDir, "e", ".z");
        long compSize = deflateToFile(e.file, tmp, level, crc);
        if (compSize >= e.size) { // deflate didn't help: drop temp, stream the source stored
            Files.deleteIfExists(tmp);
            return new Compressed(e, null, e.file, false, crc.getValue(), e.size, e.size, 0);
        }
        return new Compressed(e, null, tmp, true, crc.getValue(), e.size, compSize, 8);
    }

    private static void crcOf(Path src, CRC32 crc) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(src), 1 << 20)) {
            byte[] buf = new byte[STREAM_BUF];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) crc.update(buf, 0, n);
        }
    }

    private static long deflateToFile(Path src, Path dst, int level, CRC32 crc) throws IOException {
        Deflater def = new Deflater(level, true);
        long compSize = 0;
        byte[] in = new byte[STREAM_BUF];
        byte[] out = new byte[STREAM_BUF];
        try (InputStream is = new BufferedInputStream(Files.newInputStream(src), 1 << 20);
             OutputStream os = new BufferedOutputStream(Files.newOutputStream(dst), 1 << 20)) {
            int n;
            while ((n = is.read(in)) >= 0) {
                if (n == 0) continue;
                crc.update(in, 0, n);
                def.setInput(in, 0, n);
                while (!def.needsInput()) {
                    int c = def.deflate(out);
                    if (c > 0) { os.write(out, 0, c); compSize += c; }
                }
            }
            def.finish();
            while (!def.finished()) {
                int c = def.deflate(out);
                if (c > 0) { os.write(out, 0, c); compSize += c; }
            }
        } finally {
            def.end();
        }
        return compSize;
    }

    private static List<Entry> enumerate(List<Source> sources, boolean preserveTimestamps) throws IOException {
        List<Entry> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Source s : sources) {
            String prefix = normalizePrefix(s.prefix());
            Path base = s.baseDir();
            if (!Files.exists(base)) continue;
            try (var stream = Files.walk(base)) {
                List<Path> paths = stream.toList();
                for (Path p : paths) {
                    if (p.equals(base)) continue;
                    boolean dir = Files.isDirectory(p);
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    String name = prefix + rel + (dir ? "/" : "");
                    if (!seen.add(name)) continue; // first one wins on duplicates
                    Entry e = new Entry();
                    e.dir = dir;
                    e.name = name;
                    e.nameBytes = name.getBytes(StandardCharsets.UTF_8);
                    e.file = dir ? null : p;
                    e.size = dir ? 0 : Files.size(p);
                    int[] dt = preserveTimestamps
                            ? dosDateTime(Files.getLastModifiedTime(p).toMillis())
                            : new int[]{FIXED_DOS_TIME, FIXED_DOS_DATE};
                    e.dosTime = dt[0];
                    e.dosDate = dt[1];
                    list.add(e);
                }
            }
        }
        list.sort(Comparator.comparing(e -> e.name));
        return list;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        String p = prefix.replace('\\', '/');
        if (!p.endsWith("/")) p = p + "/";
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static byte[] localHeader(Entry e, long crc, long compSize, long rawSize,
                                      int method, boolean force) {
        boolean z64 = force || compSize >= MAX32 || rawSize >= MAX32;
        int extraLen = z64 ? 20 : 0; // id(2)+size(2)+uncompressed(8)+compressed(8)
        ByteBuffer b = ByteBuffer.allocate(30 + e.nameBytes.length + extraLen).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(LFH_SIG);
        b.putShort((short) (z64 ? 45 : 20));       // version needed
        b.putShort((short) FLAG_UTF8);
        b.putShort((short) method);
        b.putShort((short) e.dosTime);
        b.putShort((short) e.dosDate);
        b.putInt((int) crc);
        b.putInt((int) (z64 ? MAX32 : compSize));  // compressed size (or sentinel)
        b.putInt((int) (z64 ? MAX32 : rawSize));   // uncompressed size (or sentinel)
        b.putShort((short) e.nameBytes.length);
        b.putShort((short) extraLen);
        b.put(e.nameBytes);
        if (z64) {
            b.putShort((short) Z64_EXTRA_ID);
            b.putShort((short) 16);                // data size: two 8-byte values
            b.putLong(rawSize);                    // original size
            b.putLong(compSize);                   // compressed size
        }
        return b.array();
    }

    /** Builds the central directory plus the (ZIP64) end-of-central-directory records. */
    private static byte[] buildCentralDirectoryAndEnd(List<Entry> order, List<long[]> meta,
                                                      long cdOffset, boolean force, boolean[] usedZip64) {
        int cap = 98 + 64; // EOCD64(56) + locator(20) + EOCD(22) + slack
        for (Entry e : order) cap += 46 + e.nameBytes.length + 28; // 28 = max ZIP64 extra
        ByteBuffer b = ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN);

        boolean anyEntryZ64 = false;
        for (int i = 0; i < order.size(); i++) {
            Entry e = order.get(i);
            long[] m = meta.get(i);               // crc, comp, raw, method, offset
            long comp = m[1], raw = m[2], off = m[4];
            int method = (int) m[3];

            boolean z64raw = force || raw >= MAX32;
            boolean z64comp = force || comp >= MAX32;
            boolean z64off = force || off >= MAX32;
            boolean z64 = z64raw || z64comp || z64off;
            anyEntryZ64 |= z64;
            int extraData = (z64raw ? 8 : 0) + (z64comp ? 8 : 0) + (z64off ? 8 : 0);
            int extraLen = z64 ? 4 + extraData : 0;

            b.putInt(CDH_SIG);
            b.putShort((short) (z64 ? 45 : 20));   // version made by
            b.putShort((short) (z64 ? 45 : 20));   // version needed
            b.putShort((short) FLAG_UTF8);
            b.putShort((short) method);
            b.putShort((short) e.dosTime);
            b.putShort((short) e.dosDate);
            b.putInt((int) m[0]);                  // crc
            b.putInt((int) (z64comp ? MAX32 : comp));
            b.putInt((int) (z64raw ? MAX32 : raw));
            b.putShort((short) e.nameBytes.length);
            b.putShort((short) extraLen);
            b.putShort((short) 0);                 // comment length
            b.putShort((short) 0);                 // disk number start
            b.putShort((short) 0);                 // internal attrs
            b.putInt(e.dir ? 0x10 : 0);            // external attrs
            b.putInt((int) (z64off ? MAX32 : off));
            b.put(e.nameBytes);
            if (z64) {
                b.putShort((short) Z64_EXTRA_ID);
                b.putShort((short) extraData);
                if (z64raw) b.putLong(raw);        // fixed order: uncompressed, compressed, offset
                if (z64comp) b.putLong(comp);
                if (z64off) b.putLong(off);
            }
        }

        long cdSize = b.position();
        int count = order.size();
        boolean needZip64 = force || anyEntryZ64 || count >= MAX16
                || cdOffset >= MAX32 || cdSize >= MAX32;
        usedZip64[0] = needZip64;

        if (needZip64) {
            long eocd64Offset = cdOffset + cdSize;
            // ZIP64 end of central directory record.
            b.putInt(EOCD64_SIG);
            b.putLong(44);                         // size of remaining record
            b.putShort((short) 45);                // version made by
            b.putShort((short) 45);                // version needed
            b.putInt(0);                           // this disk
            b.putInt(0);                           // disk with start of CD
            b.putLong(count);                      // entries on this disk
            b.putLong(count);                      // total entries
            b.putLong(cdSize);
            b.putLong(cdOffset);
            // ZIP64 end of central directory locator.
            b.putInt(EOCD64_LOC_SIG);
            b.putInt(0);                           // disk with ZIP64 EOCD
            b.putLong(eocd64Offset);
            b.putInt(1);                           // total number of disks
        }

        // Regular end of central directory record (sentinels when values overflow).
        b.putInt(EOCD_SIG);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) (count >= MAX16 ? MAX16 : count));
        b.putShort((short) (count >= MAX16 ? MAX16 : count));
        b.putInt((int) (cdSize >= MAX32 ? MAX32 : cdSize));
        b.putInt((int) (cdOffset >= MAX32 ? MAX32 : cdOffset));
        b.putShort((short) 0);                     // comment length

        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static int[] dosDateTime(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        int year = c.get(java.util.Calendar.YEAR);
        if (year < 1980) return new int[]{FIXED_DOS_TIME, FIXED_DOS_DATE};
        int date = ((year - 1980) << 9) | ((c.get(java.util.Calendar.MONTH) + 1) << 5) | c.get(java.util.Calendar.DAY_OF_MONTH);
        int time = (c.get(java.util.Calendar.HOUR_OF_DAY) << 11) | (c.get(java.util.Calendar.MINUTE) << 5) | (c.get(java.util.Calendar.SECOND) >> 1);
        return new int[]{time, date};
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    // Sentinel marking the end of the work queue.
    static final Future<Compressed> POISON = new Future<>() {
        public boolean cancel(boolean i) { return false; }
        public boolean isCancelled() { return false; }
        public boolean isDone() { return true; }
        public Compressed get() { return null; }
        public Compressed get(long t, java.util.concurrent.TimeUnit u) { return null; }
    };
}
