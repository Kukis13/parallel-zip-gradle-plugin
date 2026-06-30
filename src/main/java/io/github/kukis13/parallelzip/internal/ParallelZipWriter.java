package io.github.kukis13.parallelzip.internal;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * <p>ZIP64 is not yet implemented; the writer fails fast if the archive would require it
 * (&gt; 65,535 entries, any file &ge; 4 GiB, or total size &ge; 4 GiB).</p>
 */
public final class ParallelZipWriter {

    private ParallelZipWriter() {}

    static final int LFH_SIG = 0x04034b50;
    static final int CDH_SIG = 0x02014b50;
    static final int EOCD_SIG = 0x06054b50;
    static final int FLAG_UTF8 = 0x0800;
    static final long MAX32 = 0xFFFFFFFFL;

    // Fixed DOS date/time used when timestamps are not preserved: 1980-01-01 00:00:00.
    static final int FIXED_DOS_TIME = 0;
    static final int FIXED_DOS_DATE = 0x21;

    /** A source tree to be added under an optional archive path prefix. */
    public record Source(Path baseDir, String prefix) {}

    public record Result(long archiveSize, int entryCount, long rawBytes, long storedBytes,
                         long enumerateMs, long compressWriteMs) {}

    static final class Entry {
        String name;
        Path file;          // null for directory
        boolean dir;
        int dosTime, dosDate;
        byte[] nameBytes;
        long size;
    }

    static final class Compressed {
        final Entry e;
        final byte[] data;
        final long crc, rawSize, compSize;
        final int method;
        Compressed(Entry e, byte[] data, long crc, long rawSize, long compSize, int method) {
            this.e = e; this.data = data; this.crc = crc;
            this.rawSize = rawSize; this.compSize = compSize; this.method = method;
        }
    }

    public static Result write(List<Source> sources, Path out, boolean store, int level,
                               int threads, boolean preserveTimestamps)
            throws IOException, InterruptedException {

        long t0 = System.nanoTime();
        List<Entry> entries = enumerate(sources, preserveTimestamps);
        long tEnum = System.nanoTime();

        if (entries.size() > 0xFFFF) {
            throw new UnsupportedOperationException(
                    "Archive has " + entries.size() + " entries; > 65535 requires ZIP64 (not yet supported).");
        }

        Files.createDirectories(out.toAbsolutePath().getParent());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ThreadLocal<Deflater> deflaters = ThreadLocal.withInitial(() -> new Deflater(level, true));
        Semaphore inFlight = new Semaphore(threads * 4);
        BlockingQueue<Future<Compressed>> queue = new ArrayBlockingQueue<>(threads * 4 + 1);

        long[] stats = new long[2]; // raw, stored
        final long[] archiveSize = {0};
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
                    byte[] lh = localHeader(c.e, c.crc, c.compSize, c.rawSize, c.method);
                    os.write(lh);
                    os.write(c.data, 0, (int) c.compSize);
                    offset += lh.length + c.compSize;
                    if (offset > MAX32) {
                        throw new UnsupportedOperationException(
                                "Archive exceeds 4 GiB; ZIP64 required (not yet supported).");
                    }
                    order.add(c.e);
                    meta.add(new long[]{c.crc, c.compSize, c.rawSize, c.method, localOffset});
                    stats[0] += c.rawSize; stats[1] += c.compSize;
                }
                byte[] cd = buildCentralDirectory(order, meta, offset);
                os.write(cd);
                archiveSize[0] = offset + cd.length;
            } catch (IOException ex) {
                writerError[0] = ex;
            } catch (Exception ex) {
                writerError[0] = new IOException(ex);
            }
        }, "parallel-zip-writer");
        writer.start();

        for (Entry e : entries) {
            inFlight.acquire();
            Future<Compressed> fut = pool.submit(() -> compress(e, store, deflaters.get()));
            queue.put(fut);
        }
        queue.put(POISON);
        writer.join();
        pool.shutdown();

        if (writerError[0] != null) {
            throw writerError[0];
        }
        long tEnd = System.nanoTime();
        return new Result(archiveSize[0], entries.size(), stats[0], stats[1],
                (tEnum - t0) / 1_000_000L, (tEnd - tEnum) / 1_000_000L);
    }

    private static Compressed compress(Entry e, boolean store, Deflater def) {
        try {
            if (e.dir) return new Compressed(e, new byte[0], 0, 0, 0, 0);
            byte[] raw = Files.readAllBytes(e.file);
            CRC32 crc = new CRC32();
            crc.update(raw);
            if (store || raw.length == 0) {
                return new Compressed(e, raw, crc.getValue(), raw.length, raw.length, 0);
            }
            def.reset();
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] tmp = new byte[1 << 16];
            while (!def.finished()) {
                int n = def.deflate(tmp);
                bos.write(tmp, 0, n);
            }
            byte[] comp = bos.toByteArray();
            if (comp.length >= raw.length) { // deflate didn't help: store this entry
                return new Compressed(e, raw, crc.getValue(), raw.length, raw.length, 0);
            }
            return new Compressed(e, comp, crc.getValue(), raw.length, comp.length, 8);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress " + e.name, ex);
        }
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
                    if (e.size > MAX32) {
                        throw new UnsupportedOperationException(
                                "File >= 4 GiB requires ZIP64 (not yet supported): " + name);
                    }
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

    private static byte[] localHeader(Entry e, long crc, long compSize, long rawSize, int method) {
        ByteBuffer b = ByteBuffer.allocate(30 + e.nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(LFH_SIG);
        b.putShort((short) 20);
        b.putShort((short) FLAG_UTF8);
        b.putShort((short) method);
        b.putShort((short) e.dosTime);
        b.putShort((short) e.dosDate);
        b.putInt((int) crc);
        b.putInt((int) compSize);
        b.putInt((int) rawSize);
        b.putShort((short) e.nameBytes.length);
        b.putShort((short) 0);
        b.put(e.nameBytes);
        return b.array();
    }

    private static byte[] buildCentralDirectory(List<Entry> order, List<long[]> meta, long cdOffset) {
        int size = 0;
        for (Entry e : order) size += 46 + e.nameBytes.length;
        ByteBuffer b = ByteBuffer.allocate(size + 22).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < order.size(); i++) {
            Entry e = order.get(i);
            long[] m = meta.get(i); // crc, comp, raw, method, offset
            b.putInt(CDH_SIG);
            b.putShort((short) 20);
            b.putShort((short) 20);
            b.putShort((short) FLAG_UTF8);
            b.putShort((short) (int) m[3]);
            b.putShort((short) e.dosTime);
            b.putShort((short) e.dosDate);
            b.putInt((int) m[0]);
            b.putInt((int) m[1]);
            b.putInt((int) m[2]);
            b.putShort((short) e.nameBytes.length);
            b.putShort((short) 0);
            b.putShort((short) 0);
            b.putShort((short) 0);
            b.putShort((short) 0);
            b.putInt(e.dir ? 0x10 : 0);
            b.putInt((int) m[4]);
            b.put(e.nameBytes);
        }
        int count = order.size();
        b.putInt(EOCD_SIG);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) count);
        b.putShort((short) count);
        b.putInt(size);
        b.putInt((int) cdOffset);
        b.putShort((short) 0);
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

    // Sentinel marking the end of the work queue.
    static final Future<Compressed> POISON = new Future<>() {
        public boolean cancel(boolean i) { return false; }
        public boolean isCancelled() { return false; }
        public boolean isDone() { return true; }
        public Compressed get() { return null; }
        public Compressed get(long t, java.util.concurrent.TimeUnit u) { return null; }
    };
}
