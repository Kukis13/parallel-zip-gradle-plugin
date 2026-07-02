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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Core parallel ZIP writer. Stateless and reusable; both the directory front-end
 * ({@link #write}) and the Gradle {@code CopyAction} feed it a list of {@link Entry}.
 *
 * <p>Pipeline: every entry is compressed (STORE or DEFLATE) on a worker pool, while a
 * single writer thread drains results <em>in the given list order</em> and streams them
 * to the archive. Parallelism never affects the byte layout, so output is deterministic
 * regardless of thread count. With fixed timestamps the archive is byte-for-byte
 * reproducible across rebuilds of identical content.</p>
 *
 * <p>Small entries are compressed in memory; entries larger than {@link #SPILL_THRESHOLD}
 * are streamed through the deflater to a temp file so a single entry may be arbitrarily
 * large. ZIP64 is emitted automatically for archives &gt; 4 GiB, more than 65,535 entries,
 * or per-entry sizes/offsets beyond 4 GiB.</p>
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
    public static final long SPILL_THRESHOLD = 8L << 20; // 8 MiB
    static final int STREAM_BUF = 1 << 16;

    // Fixed DOS date/time used when timestamps are not preserved: 1980-01-01 00:00:00.
    public static final int FIXED_DOS_TIME = 0;
    public static final int FIXED_DOS_DATE = 0x21;

    /** A source tree to be added under an optional archive path prefix (directory front-end). */
    public record Source(Path baseDir, String prefix) {}

    public record Result(long archiveSize, int entryCount, long rawBytes, long storedBytes,
                         long enumerateMs, long compressWriteMs, boolean zip64) {}

    /**
     * One archive entry. Content is one of: a directory (no content), inline {@code data}
     * (materialized in memory), or a {@code file} to read/stream (a real source or a temp).
     */
    public static final class Entry {
        String name;
        boolean dir;
        int dosTime, dosDate;
        int externalAttr;
        byte[] nameBytes;
        long size;
        Path file;          // file-backed content (real or temp); null otherwise
        byte[] inline;      // in-memory content; null otherwise
        boolean fileIsTemp; // delete file after it has been consumed
    }

    public static Entry dirEntry(String name, int dosTime, int dosDate, int externalAttr) {
        Entry e = new Entry();
        e.dir = true;
        e.name = name.endsWith("/") ? name : name + "/";
        e.nameBytes = e.name.getBytes(StandardCharsets.UTF_8);
        e.dosTime = dosTime; e.dosDate = dosDate;
        e.externalAttr = externalAttr;
        return e;
    }

    public static Entry fileEntry(String name, Path file, boolean fileIsTemp, long size,
                                  int dosTime, int dosDate, int externalAttr) {
        Entry e = baseFile(name, dosTime, dosDate, externalAttr);
        e.file = file; e.fileIsTemp = fileIsTemp; e.size = size;
        return e;
    }

    public static Entry inlineEntry(String name, byte[] data, int dosTime, int dosDate, int externalAttr) {
        Entry e = baseFile(name, dosTime, dosDate, externalAttr);
        e.inline = data; e.size = data.length;
        return e;
    }

    private static Entry baseFile(String name, int dosTime, int dosDate, int externalAttr) {
        Entry e = new Entry();
        e.name = name;
        e.nameBytes = name.getBytes(StandardCharsets.UTF_8);
        e.dosTime = dosTime; e.dosDate = dosDate;
        e.externalAttr = externalAttr;
        return e;
    }

    /** Directory front-end: walk source trees and archive them. Used by tests and the simple API. */
    public static Result write(List<Source> sources, Path out, boolean store, int level,
                               int threads, boolean preserveTimestamps)
            throws IOException, InterruptedException {
        return write(sources, out, store, level, threads, preserveTimestamps, false, SPILL_THRESHOLD);
    }

    static Result write(List<Source> sources, Path out, boolean store, int level,
                        int threads, boolean preserveTimestamps, boolean forceZip64)
            throws IOException, InterruptedException {
        return write(sources, out, store, level, threads, preserveTimestamps, forceZip64, SPILL_THRESHOLD);
    }

    static Result write(List<Source> sources, Path out, boolean store, int level,
                        int threads, boolean preserveTimestamps, boolean forceZip64, long spillThreshold)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        List<Entry> entries = enumerate(sources, preserveTimestamps);
        long enumerateMs = (System.nanoTime() - t0) / 1_000_000L;

        Path spillDir = Files.createTempDirectory(prepareParent(out), ".pzip-");
        try {
            Result r = writeEntries(entries, out, store, level, threads, forceZip64, spillThreshold, spillDir);
            return new Result(r.archiveSize(), r.entryCount(), r.rawBytes(), r.storedBytes(),
                    enumerateMs, r.compressWriteMs(), r.zip64());
        } finally {
            deleteRecursively(spillDir);
        }
    }

    static Path prepareParent(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return parent;
    }

    /**
     * Core: compress {@code entries} in parallel and write them to {@code out} in list order.
     * The caller owns {@code spillDir} (creation and cleanup).
     */
    public static Result writeEntries(List<Entry> entries, Path out, boolean store, int level,
                                      int threads, boolean forceZip64, long spillThreshold, Path spillDir)
            throws IOException, InterruptedException {
        Sink sink = new Sink(out, store, level, threads, forceZip64, spillThreshold, spillDir);
        for (Entry e : entries) sink.add(e);
        return sink.finish();
    }

    /**
     * A running compress-and-write pipeline. Entries are handed in via {@link #add} (which
     * blocks when the bounded in-flight window is full, providing backpressure), a worker
     * pool compresses them, and a single writer thread streams them out in submission order.
     * This lets a producer feed entries incrementally without materializing them all at once.
     */
    public static final class Sink {
        private static final long MIB = 1L << 20;
        private final ExecutorService pool;
        private final ThreadLocal<Deflater> deflaters;
        // One native libdeflate compressor per worker thread, reused for every entry that
        // thread compresses (allocating one is real cost -- it sizes match-finder tables --
        // so paying it once per thread beats once per entry). Only allocated when store is
        // false and the native library is loaded; otherwise every thread's handle is 0,
        // which every native call site treats as "unavailable, use the JDK Deflater".
        private final ThreadLocal<Long> nativeHandles;
        private final ConcurrentLinkedQueue<Long> allocatedNativeHandles = new ConcurrentLinkedQueue<>();
        private final Semaphore countFlight;   // bounds the NUMBER of in-flight entries
        private final Semaphore byteFlight;     // bounds in-memory BYTES held (in MiB units)
        private final int budgetMiB;
        private final BlockingQueue<Item> queue;
        private final Thread writerThread;
        private final boolean store;
        private final int level;
        private final long spillThreshold;
        private final Path spillDir;
        private final long startNanos;

        private final long[] stats = new long[2];   // raw, stored
        private final boolean[] usedZip64 = {false};
        private long archiveSize;
        private int entryCount;
        private volatile IOException writerError;

        // Small entries are batched into one compression task instead of one-per-entry:
        // many-small-file archives (e.g. a tree of .class files) can spend more time on
        // per-task scheduling (queue/semaphore/Future overhead) than on the compression
        // itself. Batching amortizes that overhead across up to BATCH_MAX_ENTRIES entries
        // without changing what gets compressed or the order entries are written in.
        private static final long BATCHABLE_MAX_ENTRY_BYTES = 64L << 10;  // 64 KiB
        private static final int BATCH_MAX_ENTRIES = 256;
        private static final long BATCH_MAX_BYTES = 4L << 20;             // 4 MiB combined
        private final List<Entry> pendingBatch = new ArrayList<>();
        private long pendingBatchBytes;

        /** A submitted future together with the byte-budget permits it holds. */
        private record Item(Future<List<Compressed>> future, int permits) {}
        private static final Item POISON_ITEM = new Item(null, 0);

        public Sink(Path out, boolean store, int level, int threads,
                    boolean forceZip64, long spillThreshold, Path spillDir) {
            this.store = store;
            this.level = level;
            this.spillThreshold = spillThreshold;
            this.spillDir = spillDir;
            this.pool = Executors.newFixedThreadPool(threads);
            this.deflaters = ThreadLocal.withInitial(() -> new Deflater(level, true));
            boolean useNative = !store && LibdeflateNative.available();
            this.nativeHandles = ThreadLocal.withInitial(() -> {
                if (!useNative) return 0L;
                long h = allocNativeCompressor(level);
                if (h != 0) allocatedNativeHandles.add(h);
                return h;
            });
            this.countFlight = new Semaphore(threads * 4);
            // Cap in-memory bytes to a fraction of the heap so entries can't OOM the daemon.
            this.budgetMiB = (int) Math.max(16, Math.min(1024, Runtime.getRuntime().maxMemory() / 6 / MIB));
            this.byteFlight = new Semaphore(budgetMiB);
            this.queue = new ArrayBlockingQueue<>(threads * 4 + 1);
            this.startNanos = System.nanoTime();
            this.writerThread = new Thread(() -> runWriter(out, forceZip64), "parallel-zip-writer");
            this.writerThread.start();
        }

        public void add(Entry e) throws InterruptedException {
            if (isBatchable(e)) {
                pendingBatch.add(e);
                pendingBatchBytes += e.size;
                if (pendingBatch.size() >= BATCH_MAX_ENTRIES || pendingBatchBytes >= BATCH_MAX_BYTES) {
                    flushBatch();
                }
                return;
            }
            // Flush first: any already-buffered small entries must reach the writer's
            // queue before this one, or their relative order in the archive would break.
            flushBatch();
            submit(List.of(e));
        }

        /** Directories and streamed (large) entries stay as their own submitted unit. */
        private boolean isBatchable(Entry e) {
            return !e.dir && e.size <= BATCHABLE_MAX_ENTRY_BYTES;
        }

        private void flushBatch() throws InterruptedException {
            if (pendingBatch.isEmpty()) return;
            List<Entry> batch = new ArrayList<>(pendingBatch);
            pendingBatch.clear();
            pendingBatchBytes = 0;
            submit(batch);
        }

        private void submit(List<Entry> batch) throws InterruptedException {
            int permits = permitsForBatch(batch);
            byteFlight.acquire(permits);  // backpressure on memory; released by the writer
            countFlight.acquire();
            Future<List<Compressed>> f =
                    pool.submit(() -> compressBatch(batch, store, level, deflaters.get(), nativeHandles.get(),
                            spillThreshold, spillDir));
            queue.put(new Item(f, permits));
        }

        /** Returns 0 (meaning "unavailable, use the JDK Deflater") on any allocation failure. */
        private static long allocNativeCompressor(int level) {
            int nativeLevel = level < 0 ? 6 : level; // -1 sentinel means "zlib default"
            try {
                return LibdeflateNative.allocCompressor(nativeLevel);
            } catch (Throwable t) {
                return 0L;
            }
        }

        /**
         * Heap a not-yet-written batch costs, in MiB permits. A DEFLATE entry holds the raw
         * bytes AND the compressed buffer at once (~2x), a STORE entry only the raw bytes,
         * and a streamed (large) entry ~nothing. Kept conservative to avoid OOM on small heaps.
         */
        private int permitsForBatch(List<Entry> batch) {
            if (batch.size() == 1) return permitsFor(batch.get(0));
            long mem = 0;
            for (Entry e : batch) mem += e.size; // isBatchable guarantees these are all in-memory-sized
            if (!store) mem *= 2; // raw + compressed buffer resident together
            return (int) Math.max(1, Math.min(budgetMiB, (mem + MIB - 1) / MIB));
        }

        private int permitsFor(Entry e) {
            long mem;
            if (e.inline != null) mem = e.size;
            else if (e.file != null && e.size <= spillThreshold) mem = e.size; // read into a byte[]
            else return 0; // directory, or large entry that streams via a temp file
            if (!store) mem *= 2; // raw + compressed buffer resident together
            return (int) Math.max(1, Math.min(budgetMiB, (mem + MIB - 1) / MIB));
        }

        public Result finish() throws IOException, InterruptedException {
            try {
                flushBatch();
                queue.put(POISON_ITEM);
                // The writer thread only reaches POISON after it has already called
                // future().get() on every real item in submission order, so every
                // compress task has fully completed by the time join() returns below --
                // freeing native handles right after is safe, no task can still be using one.
                writerThread.join();
            } finally {
                pool.shutdown();
                for (Long h : allocatedNativeHandles) LibdeflateNative.freeCompressor(h);
            }
            if (writerError != null) throw writerError;
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            return new Result(archiveSize, entryCount, stats[0], stats[1], 0, ms, usedZip64[0]);
        }

        private void runWriter(Path out, boolean forceZip64) {
            List<Entry> order = new ArrayList<>();
            List<long[]> meta = new ArrayList<>();
            boolean failed = false;
            long offset = 0;
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
                while (true) {
                    Item item = queue.take();
                    if (item == POISON_ITEM) break;
                    List<Compressed> results = null;
                    try {
                        results = item.future().get();
                    } catch (Exception ex) {
                        if (writerError == null) writerError = asIO(ex);
                        failed = true;
                    }
                    countFlight.release();               // always release to avoid producer deadlock
                    byteFlight.release(item.permits());
                    if (failed || results == null) continue;   // keep draining the queue
                    for (Compressed c : results) {
                        long localOffset = offset;
                        byte[] lh = localHeader(c.e, c.crc, c.compSize, c.rawSize, c.method, forceZip64);
                        os.write(lh);
                        writePayload(os, c);
                        offset += lh.length + c.compSize;
                        order.add(c.e);
                        meta.add(new long[]{c.crc, c.compSize, c.rawSize, c.method, localOffset});
                        stats[0] += c.rawSize; stats[1] += c.compSize;
                    }
                }
                if (!failed) {
                    byte[] tail = buildCentralDirectoryAndEnd(order, meta, offset, forceZip64, usedZip64);
                    os.write(tail);
                    archiveSize = offset + tail.length;
                    entryCount = order.size();
                }
            } catch (Exception ex) {
                if (writerError == null) writerError = asIO(ex);
            }
        }

        private static IOException asIO(Exception ex) {
            Throwable cause = (ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
            return (cause instanceof IOException) ? (IOException) cause : new IOException(cause);
        }
    }

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

    /**
     * Compresses the batch on one submitted task. When it's a genuine multi-entry batch
     * (guaranteed by the caller to be all small, in-memory, non-directory entries) and a
     * native compressor is available, every buffer in the batch is compressed with a
     * single native call instead of one call per entry -- see
     * {@link LibdeflateNative#compressBatch}. Singleton "batches" (directories, streamed
     * entries, or the lone trailing entry after a flush) go through the regular per-entry
     * path, which still uses the same reused native handle for a single-buffer call.
     */
    private static List<Compressed> compressBatch(List<Entry> batch, boolean store, int level, Deflater def,
                                                  long nativeHandle, long spillThreshold, Path spillDir) {
        if (!store && nativeHandle != 0 && batch.size() > 1) {
            return compressBatchNative(batch, def, nativeHandle);
        }
        List<Compressed> out = new ArrayList<>(batch.size());
        for (Entry e : batch) {
            out.add(compress(e, store, level, def, nativeHandle, spillThreshold, spillDir));
        }
        return out;
    }

    /**
     * Compresses every (small, in-memory, non-directory) entry in the batch with one
     * native call, reusing {@code nativeHandle} for all of them. Any entry the native call
     * can't handle falls back individually to the JDK {@link Deflater}, same as the
     * single-entry path -- one entry's failure never affects the rest of the batch.
     */
    private static List<Compressed> compressBatchNative(List<Entry> batch, Deflater def, long nativeHandle) {
        int n = batch.size();
        byte[][] raws = new byte[n][];
        long[] crcs = new long[n];
        byte[][] outs = new byte[n][];
        boolean[] empty = new boolean[n];
        for (int i = 0; i < n; i++) {
            byte[] raw = readSmallEntry(batch.get(i));
            raws[i] = raw;
            CRC32 crc = new CRC32();
            crc.update(raw);
            crcs[i] = crc.getValue();
            empty[i] = raw.length == 0;
            // A distinct (not aliased) array even when empty: passing the same array
            // object as both input and output would pin it twice under
            // GetPrimitiveArrayCritical in the native call, which is unspecified behavior.
            outs[i] = empty[i] ? new byte[0] : new byte[raw.length + (raw.length >> 12) + 64];
        }

        int[] outLens = new int[n];
        LibdeflateNative.compressBatch(nativeHandle, raws, outs, outLens, n);

        List<Compressed> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Entry e = batch.get(i);
            byte[] raw = raws[i];
            if (empty[i]) {
                result.add(new Compressed(e, raw, null, false, crcs[i], 0, 0, 0));
            } else if (outLens[i] == LibdeflateNative.STORE_SENTINEL || outLens[i] >= raw.length) {
                // sniff judged it already-compressed, or deflate didn't help: store this entry
                result.add(new Compressed(e, raw, null, false, crcs[i], raw.length, raw.length, 0));
            } else if (outLens[i] <= 0) {
                result.add(compressWithJdk(e, raw, def, crcs[i])); // native failed: JDK fallback
            } else {
                result.add(new Compressed(e, outs[i], null, false, crcs[i], raw.length, outLens[i], 8));
            }
        }
        return result;
    }

    private static byte[] readSmallEntry(Entry e) {
        try {
            return e.inline != null ? e.inline : Files.readAllBytes(e.file);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress " + e.name, ex);
        }
    }

    private static Compressed compress(Entry e, boolean store, int level, Deflater def,
                                       long nativeHandle, long spillThreshold, Path spillDir) {
        try {
            if (e.dir) return new Compressed(e, new byte[0], null, false, 0, 0, 0, 0);
            if (e.inline != null) {
                return compressBytes(e, e.inline, store, def, nativeHandle);
            }
            if (e.size > spillThreshold) {
                return compressLarge(e, store, level, spillDir);
            }
            return compressBytes(e, Files.readAllBytes(e.file), store, def, nativeHandle);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress " + e.name, ex);
        }
    }

    private static Compressed compressBytes(Entry e, byte[] raw, boolean store, Deflater def,
                                            long nativeHandle) {
        CRC32 crc = new CRC32();
        crc.update(raw);
        if (store || raw.length == 0) {
            return new Compressed(e, raw, null, false, crc.getValue(), raw.length, raw.length, 0);
        }
        if (nativeHandle != 0) {
            Compressed viaNative = tryNativeDeflate(e, raw, nativeHandle, crc.getValue());
            if (viaNative != null) return viaNative;
            // fell through: native call failed for some reason, use the JDK path below
        }
        return compressWithJdk(e, raw, def, crc.getValue());
    }

    private static Compressed compressWithJdk(Entry e, byte[] raw, Deflater def, long crcValue) {
        def.reset();
        def.setInput(raw);
        def.finish();
        // Pre-size to the raw length (deflate output is at most ~raw + a few bytes for
        // incompressible data) so the buffer never doubles, and expose it directly instead
        // of copying via toByteArray() -- Compressed writes only compSize bytes anyway.
        GrowBuffer bos = new GrowBuffer(raw.length + (raw.length >> 10) + 64);
        byte[] tmp = new byte[STREAM_BUF];
        while (!def.finished()) {
            int n = def.deflate(tmp);
            bos.write(tmp, 0, n);
        }
        int compLen = bos.size();
        if (compLen >= raw.length) { // deflate didn't help: store this entry
            return new Compressed(e, raw, null, false, crcValue, raw.length, raw.length, 0);
        }
        // data may be longer than compLen; Compressed writes exactly compSize bytes.
        return new Compressed(e, bos.raw(), null, false, crcValue, raw.length, compLen, 8);
    }

    /** Returns null (caller falls back to the JDK Deflater) if the native call fails for any reason. */
    private static Compressed tryNativeDeflate(Entry e, byte[] raw, long nativeHandle, long crcValue) {
        int cap = raw.length + (raw.length >> 12) + 64;
        byte[] out = new byte[cap];
        int n = LibdeflateNative.compress(nativeHandle, raw, 0, raw.length, out, 0, cap);
        if (n == LibdeflateNative.STORE_SENTINEL || (n > 0 && n >= raw.length)) {
            // sniff judged it already-compressed, or deflate didn't help: store this entry
            return new Compressed(e, raw, null, false, crcValue, raw.length, raw.length, 0);
        }
        if (n <= 0) return null; // real failure: caller uses the JDK Deflater
        return new Compressed(e, out, null, false, crcValue, raw.length, n, 8);
    }

    /** A ByteArrayOutputStream that exposes its backing array to avoid a trimming copy. */
    private static final class GrowBuffer extends ByteArrayOutputStream {
        GrowBuffer(int cap) { super(cap); }
        byte[] raw() { return buf; }
    }

    // Incompressibility sniff for streamed (large) entries -- the Java-side mirror of the
    // native in-memory sniff in pzip_libdeflate.c, for the >SPILL_THRESHOLD files (JREs,
    // big archives) that stream through the JDK Deflater instead. Probe the head of the
    // file; if it barely shrinks, skip deflating the whole already-compressed entry and
    // STORE it (still reading the file once for its CRC). Conservative threshold so
    // genuinely compressible entries are never mis-stored; content-based, so deterministic.
    static final int SNIFF_MIN_INPUT = 256 * 1024;
    static final int SNIFF_SAMPLE = 64 * 1024;
    static final int SNIFF_KEEP_PCT = 98; // store when the probe compresses to >= this % of its size

    /** Streams a large file-backed entry: deflate to a temp, or read/stream the source when stored. */
    private static Compressed compressLarge(Entry e, boolean store, int level, Path spillDir) throws IOException {
        CRC32 crc = new CRC32();
        if (store || looksIncompressible(e.file, e.size, level)) {
            crcOf(e.file, crc);
            return new Compressed(e, null, e.file, e.fileIsTemp, crc.getValue(), e.size, e.size, 0);
        }
        Path tmp = Files.createTempFile(spillDir, "e", ".z");
        long compSize = deflateToFile(e.file, tmp, level, crc);
        if (compSize >= e.size) { // deflate didn't help: stream the input as STORE
            Files.deleteIfExists(tmp);
            return new Compressed(e, null, e.file, e.fileIsTemp, crc.getValue(), e.size, e.size, 0);
        }
        if (e.fileIsTemp) Files.deleteIfExists(e.file); // input temp fully consumed
        return new Compressed(e, null, tmp, true, crc.getValue(), e.size, compSize, 8);
    }

    /**
     * Probes the first {@link #SNIFF_SAMPLE} bytes of {@code file} with the JDK Deflater at
     * {@code level}; returns true when that sample barely shrinks, i.e. the file is almost
     * certainly already compressed and should be STOREd rather than deflated in full. Files
     * below {@link #SNIFF_MIN_INPUT}, or where fewer than a full sample can be read, are never
     * judged incompressible (they just take the normal compress path).
     */
    private static boolean looksIncompressible(Path file, long size, int level) throws IOException {
        if (size < SNIFF_MIN_INPUT) return false;
        byte[] sample = new byte[SNIFF_SAMPLE];
        int got;
        try (InputStream in = Files.newInputStream(file)) {
            got = in.readNBytes(sample, 0, sample.length);
        }
        if (got < SNIFF_SAMPLE) return false;
        Deflater d = new Deflater(level, true);
        try {
            d.setInput(sample, 0, got);
            d.finish();
            byte[] tmp = new byte[STREAM_BUF];
            long comp = 0;
            while (!d.finished()) comp += d.deflate(tmp);
            return comp * 100 >= (long) got * SNIFF_KEEP_PCT;
        } finally {
            d.end();
        }
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
                for (Path p : stream.toList()) {
                    if (p.equals(base)) continue;
                    boolean dir = Files.isDirectory(p);
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    String name = prefix + rel;
                    if (dir) name = name + "/";
                    if (!seen.add(name)) continue; // first one wins on duplicates
                    int[] dt = preserveTimestamps
                            ? dosDateTime(Files.getLastModifiedTime(p).toMillis())
                            : new int[]{FIXED_DOS_TIME, FIXED_DOS_DATE};
                    int extAttr = dir ? 0x10 : 0;
                    list.add(dir
                            ? dirEntry(name, dt[0], dt[1], extAttr)
                            : fileEntry(name, p, false, Files.size(p), dt[0], dt[1], extAttr));
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
            b.putInt(e.externalAttr);              // external attrs
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
            b.putInt(EOCD64_LOC_SIG);
            b.putInt(0);                           // disk with ZIP64 EOCD
            b.putLong(eocd64Offset);
            b.putInt(1);                           // total number of disks
        }

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

    public static int[] dosDateTime(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        int year = c.get(java.util.Calendar.YEAR);
        if (year < 1980) return new int[]{FIXED_DOS_TIME, FIXED_DOS_DATE};
        int date = ((year - 1980) << 9) | ((c.get(java.util.Calendar.MONTH) + 1) << 5) | c.get(java.util.Calendar.DAY_OF_MONTH);
        int time = (c.get(java.util.Calendar.HOUR_OF_DAY) << 11) | (c.get(java.util.Calendar.MINUTE) << 5) | (c.get(java.util.Calendar.SECOND) >> 1);
        return new int[]{time, date};
    }

    static void deleteRecursively(Path root) {
        if (root == null) return;
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
