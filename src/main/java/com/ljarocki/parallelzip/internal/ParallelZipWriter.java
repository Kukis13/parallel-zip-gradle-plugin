package com.ljarocki.parallelzip.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

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
 * <p>Small entries are compressed in memory; entries larger than {@link #SPILL_THRESHOLD} are
 * mapped straight from the source file into the native accelerator (see
 * {@link #MMAP_THRESHOLD}) when one is available, or otherwise streamed through the JDK
 * deflater to a temp file, so a single entry may be arbitrarily large either way. ZIP64 is
 * emitted automatically for archives &gt; 4 GiB, more than 65,535 entries, or per-entry
 * sizes/offsets beyond 4 GiB.</p>
 */
public final class ParallelZipWriter {

    private ParallelZipWriter() {}

    private static final Logger LOG = Logging.getLogger(ParallelZipWriter.class);
    /**
     * Below this daemon max heap, warn once per daemon that large-entry archives (bundled
     * JREs, big jars) risk {@code OutOfMemoryError} even though {@link Sink#budgetMiB} bounds
     * total buffered bytes -- a small heap can still exhaust itself on GC fragmentation from a
     * handful of large individual buffers well before that nominal budget is reached.
     */
    private static final long WARN_HEAP_FLOOR_MIB = 2048;
    private static final AtomicBoolean lowHeapWarned = new AtomicBoolean(false);

    static final int LFH_SIG = 0x04034b50;
    static final int CDH_SIG = 0x02014b50;
    static final int EOCD_SIG = 0x06054b50;
    static final int EOCD64_SIG = 0x06064b50;
    static final int EOCD64_LOC_SIG = 0x07064b50;
    static final int Z64_EXTRA_ID = 0x0001;
    static final int FLAG_UTF8 = 0x0800;
    /**
     * High byte of the central-directory "version made by" field: host system 3 = UNIX. Without
     * it the host reads as 0 (MS-DOS/FAT), and extractors then interpret "external file
     * attributes" as MS-DOS attribute bits and ignore the Unix mode ({@code st_mode}) packed
     * into the high 16 bits -- so {@code unzip} on Linux drops the executable bit off every
     * extracted file. See PKWARE APPNOTE 4.4.2.
     */
    static final int MADE_BY_UNIX = 3 << 8;
    /** Default Unix mode forced into an entry's external attributes when the front-end supplies none. */
    static final int DEFAULT_FILE_MODE = 0644;
    static final int DEFAULT_DIR_MODE = 0755;
    /** {@code st_mode} type bits ({@code S_IFMT}); ORed into external attrs so extractors see a real file type. */
    static final int S_IFREG = 0100000;
    static final int S_IFDIR = 0040000;
    static final long MAX32 = 0xFFFFFFFFL;
    static final int MAX16 = 0xFFFF;
    /** Entries larger than this are streamed (spilled) instead of held in memory. */
    public static final long SPILL_THRESHOLD = 8L << 20; // 8 MiB
    /**
     * Spilled entries up to this size are still compressed via the native libdeflate
     * accelerator, fed directly from a memory-mapped view of the source file instead of a
     * {@code Files.readAllBytes} heap copy (see {@link #tryMmapNativeDeflate}). Bounded well
     * under the ~2 GiB {@link FileChannel#map} limit. This is only the absolute ceiling --
     * each {@code Sink} scales the actual eligibility cutoff down further
     * ({@code effectiveMmapThreshold}) on small heaps, so a single entry's compressed-output
     * buffer never eats more than half the byte budget and never becomes a heap-fragmenting
     * "humongous" allocation. Entries beyond the effective cutoff always use the plain
     * streaming JDK {@link Deflater} path, which has no size limit.
     */
    static final long MMAP_THRESHOLD = 128L << 20; // 128 MiB
    static final int STREAM_BUF = 1 << 16;
    static final int IO_BUF = 256 * 1024;

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
        return write(sources, out, store, true, level, threads, preserveTimestamps, forceZip64, spillThreshold);
    }

    static Result write(List<Source> sources, Path out, boolean store, boolean skipAlreadyCompressed, int level,
                        int threads, boolean preserveTimestamps, boolean forceZip64, long spillThreshold)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        List<Entry> entries = enumerate(sources, preserveTimestamps);
        long enumerateMs = (System.nanoTime() - t0) / 1_000_000L;

        try (LazySpillDir spillDir = new LazySpillDir(prepareParent(out))) {
            Sink sink = new Sink(out, store, skipAlreadyCompressed, level, threads, forceZip64,
                    spillThreshold, spillDir);
            for (Entry e : entries) sink.add(e);
            Result r = sink.finish();
            return new Result(r.archiveSize(), r.entryCount(), r.rawBytes(), r.storedBytes(),
                    enumerateMs, r.compressWriteMs(), r.zip64());
        }
    }

    static Path prepareParent(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return parent;
    }

    /** Thread-safe lazy temporary spill directory: only created on disk if an entry actually spills. */
    static final class LazySpillDir implements Supplier<Path>, AutoCloseable {
        private final Path parent;
        private volatile Path dir;

        LazySpillDir(Path parent) {
            this.parent = parent;
        }

        @Override
        public Path get() {
            Path d = dir;
            if (d != null) return d;
            synchronized (this) {
                d = dir;
                if (d == null) {
                    try {
                        dir = d = Files.createTempDirectory(parent, ".pzip-");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return d;
            }
        }

        @Override
        public void close() {
            Path d;
            synchronized (this) {
                d = dir;
            }
            if (d != null) {
                deleteRecursively(d);
            }
        }
    }

    /**
     * Core: compress {@code entries} in parallel and write them to {@code out} in list order.
     * The caller owns {@code spillDir} (creation and cleanup).
     */
    public static Result writeEntries(List<Entry> entries, Path out, boolean store, int level,
                                      int threads, boolean forceZip64, long spillThreshold, Path spillDir)
            throws IOException, InterruptedException {
        return writeEntries(entries, out, store, true, level, threads, forceZip64, spillThreshold, spillDir);
    }

    public static Result writeEntries(List<Entry> entries, Path out, boolean store, boolean skipAlreadyCompressed,
                                      int level, int threads, boolean forceZip64, long spillThreshold, Path spillDir)
            throws IOException, InterruptedException {
        Sink sink = new Sink(out, store, skipAlreadyCompressed, level, threads, forceZip64, spillThreshold, spillDir);
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
        private final boolean ownsPool;
        private final java.util.concurrent.ConcurrentHashMap<Thread, Deflater> deflaters =
                new java.util.concurrent.ConcurrentHashMap<>();
        // One native libdeflate compressor per worker thread, reused for every entry that
        // thread compresses (allocating one is real cost -- it sizes match-finder tables --
        // so paying it once per thread beats once per entry). Only allocated when store is
        // false and the native library is loaded; otherwise every thread's handle is 0,
        // which every native call site treats as "unavailable, use the JDK Deflater".
        private final java.util.concurrent.ConcurrentHashMap<Thread, Long> nativeHandles =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> allocatedNativeHandles = new ConcurrentLinkedQueue<>();
        // Mirrors the condition nativeHandles' initializer uses, so permitsFor (called from
        // the producer thread, before any worker thread has touched nativeHandles) can budget
        // for the mmap fast path without allocating a compressor just to check.
        private final boolean useNative;
        private final Semaphore countFlight;   // bounds the NUMBER of in-flight entries
        private final Semaphore byteFlight;     // bounds in-memory BYTES held (in MiB units)
        private final int budgetMiB;
        // Never more than half the byte budget in a single mmap-path output buffer, so at
        // least two such entries can be in flight without one alone dominating -- and, on a
        // small heap, so this buffer doesn't become a multi-hundred-MiB "humongous object"
        // that fragments a G1 old generation sized for a much smaller nominal footprint.
        private final long effectiveMmapThreshold;
        private final BlockingQueue<Item> queue;
        private final Thread writerThread;
        private final boolean store;
        private final boolean skipAlreadyCompressed;
        private final int level;
        private final long spillThreshold;
        private final Supplier<Path> spillDir;
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

        public Sink(Path out, boolean store, boolean skipAlreadyCompressed, int level, int threads,
                    boolean forceZip64, long spillThreshold, Path spillDir) {
            this(out, store, skipAlreadyCompressed, level, threads, forceZip64, spillThreshold, () -> spillDir, null);
        }

        Sink(Path out, boolean store, boolean skipAlreadyCompressed, int level, int threads,
             boolean forceZip64, long spillThreshold, Supplier<Path> spillDir) {
            this(out, store, skipAlreadyCompressed, level, threads, forceZip64, spillThreshold, spillDir, null);
        }

        Sink(Path out, boolean store, boolean skipAlreadyCompressed, int level, int threads,
             boolean forceZip64, long spillThreshold, Supplier<Path> spillDir, ExecutorService sharedPool) {
            this.store = store;
            this.skipAlreadyCompressed = skipAlreadyCompressed;
            this.level = level;
            this.spillThreshold = spillThreshold;
            this.spillDir = spillDir;
            if (sharedPool != null) {
                this.pool = sharedPool;
                this.ownsPool = false;
            } else {
                this.pool = Executors.newFixedThreadPool(threads);
                this.ownsPool = true;
            }
            this.useNative = !store && LibdeflateNative.available();
            this.countFlight = new Semaphore(threads * 4);
            // Cap in-memory bytes to a fraction of the heap so entries can't OOM the daemon.
            long maxMemory = Runtime.getRuntime().maxMemory();
            this.budgetMiB = (int) Math.max(16, Math.min(1024, maxMemory / 6 / MIB));
            this.effectiveMmapThreshold = Math.min(MMAP_THRESHOLD, Math.max(1, budgetMiB / 2) * MIB);
            this.byteFlight = new Semaphore(budgetMiB);
            long maxMemoryMiB = maxMemory / MIB;
            if (maxMemoryMiB < WARN_HEAP_FLOOR_MIB && lowHeapWarned.compareAndSet(false, true)) {
                LOG.warn("parallel-zip: Gradle daemon max heap is only ~{} MiB. Archives with "
                        + "large entries (bundled JREs, big jars) can hit OutOfMemoryError below "
                        + "~{} MiB; if that happens, raise the daemon heap, e.g. "
                        + "org.gradle.jvmargs=-Xmx2g in gradle.properties.",
                        maxMemoryMiB, WARN_HEAP_FLOOR_MIB);
            }
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
                    pool.submit(() -> {
                        Thread t = Thread.currentThread();
                        Deflater def = deflaters.computeIfAbsent(t, k -> new Deflater(jdkLevel(level), true));
                        long nh = nativeHandles.computeIfAbsent(t, k -> {
                            if (!useNative) return 0L;
                            long h = allocNativeCompressor(level);
                            if (h != 0) allocatedNativeHandles.add(h);
                            return h;
                        });
                        return compressBatch(batch, store, skipAlreadyCompressed, level, def,
                                nh, spillThreshold, spillDir, effectiveMmapThreshold);
                    });
            queue.put(new Item(f, permits));
        }

        private static int jdkLevel(int level) {
            return level < 0 ? Deflater.DEFAULT_COMPRESSION : Math.min(9, level);
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
            if (e.inline != null || (e.file != null && e.size <= spillThreshold)) {
                long mem = e.size; // read into a byte[]
                if (!store) mem *= 2; // raw + compressed buffer resident together
                return (int) Math.max(1, Math.min(budgetMiB, (mem + MIB - 1) / MIB));
            }
            if (e.file != null && useNative && e.size <= effectiveMmapThreshold) {
                // The mmap fast path (tryMmapNativeDeflate) never materializes the input as a
                // byte[] -- it's a page-cache-backed mapping, not heap memory -- so only the
                // one compressed-output buffer counts against the budget, not 2x.
                return (int) Math.max(1, Math.min(budgetMiB, (e.size + MIB - 1) / MIB));
            }
            return 0; // directory, or large entry that streams via a temp file
        }

        public Result finish() throws IOException, InterruptedException {
            boolean interrupted = false;
            try {
                flushBatch();
                queue.put(POISON_ITEM);
            } catch (InterruptedException e) {
                interrupted = true;
                // Best-effort poison so writerThread exits and drains all in-flight tasks.
                while (true) {
                    try {
                        queue.put(POISON_ITEM);
                        break;
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                }
            } finally {
                // Always wait for writerThread to finish draining all compression and pwrite
                // tasks before freeing Deflaters and native libdeflate handles (prevents UAF
                // on shared BuildService pools).
                while (writerThread.isAlive()) {
                    try {
                        writerThread.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (ownsPool) {
                    pool.shutdown();
                }
                for (Deflater d : deflaters.values()) {
                    d.end();
                }
                deflaters.clear();
                nativeHandles.clear();
                for (Long h : allocatedNativeHandles) LibdeflateNative.freeCompressor(h);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (interrupted) throw new InterruptedException("Interrupted while finishing archive");
            if (writerError != null) throw writerError;
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            return new Result(archiveSize, entryCount, stats[0], stats[1], 0, ms, usedZip64[0]);
        }

        private static final boolean IS_WINDOWS =
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

        private void runWriter(Path out, boolean forceZip64) {
            List<Entry> order = new ArrayList<>();
            List<long[]> meta = new ArrayList<>();
            List<Future<?>> pwriteFutures = new ArrayList<>();
            boolean failed = false;
            long offset = 0;
            ByteBuffer lhScratch = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer stage = ByteBuffer.allocate(1 << 20);
            try (FileChannel outChannel = FileChannel.open(out,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
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
                        byte[] lhBytes = buildLocalHeaderBytes(lhScratch, c.e, c.crc, c.compSize, c.rawSize, c.method, forceZip64);
                        int lhLen = lhBytes.length;
                        if (!IS_WINDOWS && c.compSize >= (256L << 10)) {
                            // Bound concurrent in-flight pwrite buffers so memory stays capped.
                            // Positional pwrite on a single FileChannel is atomic/concurrent on POSIX;
                            // on Windows synchronous handles, keep writes on writerThread to avoid
                            // kernel OVERLAPPED/EOF races.
                            if (pwriteFutures.size() >= 16) {
                                drainPwriteFutures(pwriteFutures, false);
                            }
                            offset = flushStage(outChannel, stage, offset);
                            writeChannelFully(outChannel, ByteBuffer.wrap(lhBytes), localOffset);
                            final long dataOffset = localOffset + lhLen;
                            final Compressed fc = c;
                            pwriteFutures.add(pool.submit(() -> {
                                try {
                                    pwritePayload(outChannel, fc, dataOffset);
                                } catch (IOException ioe) {
                                    throw new UncheckedIOException(ioe);
                                }
                            }));
                            offset = dataOffset + c.compSize;
                        } else {
                            if (c.data == null) {
                                // Flush staged entries before writing header + streamed payload directly.
                                offset = flushStage(outChannel, stage, offset);
                                writeChannelFully(outChannel, ByteBuffer.wrap(lhBytes), localOffset);
                                if (c.streamFrom != null) {
                                    pwritePayload(outChannel, c, localOffset + lhLen);
                                }
                            } else if (lhLen + c.compSize > stage.capacity()) {
                                offset = flushStage(outChannel, stage, offset);
                                writeChannelFully(outChannel, ByteBuffer.wrap(lhBytes), localOffset);
                                if (c.compSize > 0) {
                                    writeChannelFully(outChannel, ByteBuffer.wrap(c.data, 0, (int) c.compSize),
                                            localOffset + lhLen);
                                }
                            } else {
                                if (stage.remaining() < lhLen + c.compSize) {
                                    flushStageAtCurrentPos(outChannel, stage, offset - stage.position());
                                }
                                stage.put(lhBytes);
                                if (c.compSize > 0) {
                                    stage.put(c.data, 0, (int) c.compSize);
                                }
                            }
                            offset += lhLen + c.compSize;
                        }
                        order.add(c.e);
                        meta.add(new long[]{c.crc, c.compSize, c.rawSize, c.method, localOffset});
                        stats[0] += c.rawSize; stats[1] += c.compSize;
                    }
                }
                if (!failed) {
                    flushStageAtCurrentPos(outChannel, stage, offset - stage.position());
                    drainPwriteFutures(pwriteFutures, false);
                    byte[] tail = buildCentralDirectoryAndEnd(order, meta, offset, forceZip64, usedZip64);
                    writeChannelFully(outChannel, ByteBuffer.wrap(tail), offset);
                    archiveSize = offset + tail.length;
                    entryCount = order.size();
                } else {
                    // Always await in-flight pwrites before outChannel closes.
                    drainPwriteFutures(pwriteFutures, true);
                }
            } catch (Exception ex) {
                if (writerError == null) writerError = asIO(ex);
            } finally {
                // Defensive drain in case an exception escaped before the if/else above.
                try {
                    drainPwriteFutures(pwriteFutures, true);
                } catch (IOException ignored) { }
            }
        }

        private static void drainPwriteFutures(List<Future<?>> futures, boolean ignoreErrors) throws IOException {
            if (futures.isEmpty()) return;
            IOException firstErr = null;
            for (Future<?> pf : futures) {
                while (true) {
                    try {
                        pf.get();
                        break;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ex) {
                        if (!ignoreErrors && firstErr == null) {
                            firstErr = asIO(ex);
                        }
                        break;
                    }
                }
            }
            futures.clear();
            if (firstErr != null) throw firstErr;
        }

        private static long flushStage(FileChannel ch, ByteBuffer stage, long currentOffset) throws IOException {
            if (stage.position() > 0) {
                flushStageAtCurrentPos(ch, stage, currentOffset - stage.position());
            }
            return currentOffset;
        }

        private static void flushStageAtCurrentPos(FileChannel ch, ByteBuffer stage, long pos) throws IOException {
            if (stage.position() == 0) return;
            stage.flip();
            writeChannelFully(ch, stage, pos);
            stage.clear();
        }

        private static void writeChannelFully(FileChannel ch, ByteBuffer buf, long offset) throws IOException {
            long pos = offset;
            while (buf.hasRemaining()) {
                int w = ch.write(buf, pos);
                pos += w;
            }
        }

        private static void pwritePayload(FileChannel outChannel, Compressed c, long dataOffset) throws IOException {
            if (c.data != null) {
                if (c.compSize > 0) {
                    writeChannelFully(outChannel, ByteBuffer.wrap(c.data, 0, (int) c.compSize), dataOffset);
                }
                return;
            }
            if (c.streamFrom == null) return;
            try {
                if (c.compSize > 0) {
                    byte[] buf = new byte[1 << 20];
                    ByteBuffer bb = ByteBuffer.wrap(buf);
                    try (FileChannel in = FileChannel.open(c.streamFrom, StandardOpenOption.READ)) {
                        long inPos = 0;
                        long outPos = dataOffset;
                        while (inPos < c.compSize) {
                            bb.clear();
                            int r = in.read(bb, inPos);
                            if (r <= 0) break;
                            bb.flip();
                            writeChannelFully(outChannel, bb, outPos);
                            inPos += r;
                            outPos += r;
                        }
                    }
                }
            } finally {
                if (c.streamIsTemp) Files.deleteIfExists(c.streamFrom);
            }
        }

        private static byte[] buildLocalHeaderBytes(ByteBuffer scratch, Entry e, long crc, long compSize,
                                                    long rawSize, int method, boolean forceZip64) {
            byte[] nameBytes = e.nameBytes;
            boolean needZip64 = forceZip64 || rawSize >= MAX32 || compSize >= MAX32;
            int extraLen = needZip64 ? 20 : 0;
            int total = 30 + nameBytes.length + extraLen;
            ByteBuffer b = scratch.capacity() >= total
                    ? scratch : ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            b.clear();
            b.putInt(LFH_SIG);
            b.putShort((short) (needZip64 ? 45 : 20));
            b.putShort((short) FLAG_UTF8);
            b.putShort((short) method);
            b.putShort((short) e.dosTime);
            b.putShort((short) e.dosDate);
            b.putInt((int) crc);
            b.putInt(needZip64 ? (int) MAX32 : (int) compSize);
            b.putInt(needZip64 ? (int) MAX32 : (int) rawSize);
            b.putShort((short) nameBytes.length);
            b.putShort((short) extraLen);
            b.put(nameBytes);
            if (needZip64) {
                b.putShort((short) Z64_EXTRA_ID);
                b.putShort((short) 16);
                b.putLong(rawSize);
                b.putLong(compSize);
            }
            byte[] out = new byte[total];
            b.flip();
            b.get(out);
            return out;
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

    private static void writePayload(OutputStream os, Compressed c, byte[] buf) throws IOException {
        if (c.data != null) {
            os.write(c.data, 0, (int) c.compSize);
            return;
        }
        try (InputStream in = Files.newInputStream(c.streamFrom)) {
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
    private static List<Compressed> compressBatch(List<Entry> batch, boolean store, boolean skipAlreadyCompressed,
                                                  int level, Deflater def, long nativeHandle, long spillThreshold,
                                                  Supplier<Path> spillDir, long mmapThreshold) {
        if (!store && nativeHandle != 0 && batch.size() > 1) {
            return compressBatchNative(batch, def, nativeHandle, skipAlreadyCompressed);
        }
        List<Compressed> out = new ArrayList<>(batch.size());
        for (Entry e : batch) {
            out.add(compress(e, store, skipAlreadyCompressed, level, def, nativeHandle, spillThreshold, spillDir,
                    mmapThreshold));
        }
        return out;
    }

    /**
     * Compresses every (small, in-memory, non-directory) entry in the batch with one
     * native call, reusing {@code nativeHandle} for all of them. Any entry the native call
     * can't handle falls back individually to the JDK {@link Deflater}, same as the
     * single-entry path -- one entry's failure never affects the rest of the batch.
     *
     * <p>Entries {@link #looksAlreadyCompressed} recognizes (when {@code skipAlreadyCompressed}
     * is set) are stored directly here too, same as the singleton path -- pulled out of the
     * batch before the native call so no output buffer is even allocated for them, mirroring
     * {@link #compressBytes}'s reasoning for why bothering to try is wasted work.</p>
     */
    private static List<Compressed> compressBatchNative(List<Entry> batch, Deflater def, long nativeHandle,
                                                         boolean skipAlreadyCompressed) {
        int n = batch.size();
        Compressed[] result = new Compressed[n];

        // Fast native file-open/sniff/compress batch for unfiltered disk files (avoids Java
        // Files.readAllBytes + InputStream wrappers on 20k-file corpora).
        int fileCount = 0;
        int[] fileIdx = new int[n];
        for (int i = 0; i < n; i++) {
            Entry e = batch.get(i);
            if (e.file != null && !e.fileIsTemp && e.inline == null && e.size > 0) {
                fileIdx[fileCount++] = i;
            }
        }
        if (fileCount >= 4) {
            String[] paths = new String[fileCount];
            byte[][] outs = new byte[fileCount][];
            int[] outLens = new int[fileCount];
            long[] rawSizes = new long[fileCount];
            long[] crcs = new long[fileCount];
            for (int k = 0; k < fileCount; k++) {
                Entry e = batch.get(fileIdx[k]);
                paths[k] = e.file.toAbsolutePath().toString();
                int len = (int) e.size;
                outs[k] = new byte[len + (len >> 12) + 64];
                rawSizes[k] = e.size;
            }
            LibdeflateNative.compressFilesBatch(
                    nativeHandle, paths, outs, outLens, rawSizes, crcs, fileCount, false, skipAlreadyCompressed);
            for (int k = 0; k < fileCount; k++) {
                int i = fileIdx[k];
                Entry e = batch.get(i);
                int len = (int) e.size;
                if (outLens[k] == LibdeflateNative.STORE_SENTINEL) {
                    result[i] = new Compressed(e, outs[k], null, false, crcs[k], len, len, 0);
                } else if (outLens[k] > 0 && outLens[k] < len) {
                    result[i] = new Compressed(e, outs[k], null, false, crcs[k], len, outLens[k], 8);
                }
            }
        }

        byte[][] rawByIndex = new byte[n][];
        int[] nativeIdx = new int[n];
        int nativeCount = 0;
        CRC32 crc = null;

        for (int i = 0; i < n; i++) {
            if (result[i] != null) continue;
            Entry e = batch.get(i);
            byte[] raw = readSmallEntry(e);
            rawByIndex[i] = raw;
            if (raw.length == 0) {
                result[i] = new Compressed(e, raw, null, false, 0L, 0, 0, 0); // CRC-32 of empty input is 0
            } else if (skipAlreadyCompressed && looksAlreadyCompressed(raw, raw.length)) {
                if (crc == null) crc = new CRC32(); else crc.reset();
                crc.update(raw);
                result[i] = new Compressed(e, raw, null, false, crc.getValue(), raw.length, raw.length, 0);
            } else {
                nativeIdx[nativeCount++] = i;
            }
        }

        if (nativeCount > 0) {
            int m = nativeCount;
            byte[][] raws = new byte[m][];
            byte[][] outs = new byte[m][];
            for (int k = 0; k < m; k++) {
                byte[] raw = rawByIndex[nativeIdx[k]];
                raws[k] = raw;
                // A distinct (not aliased) array: passing the same array object as both input
                // and output would pin it twice under GetPrimitiveArrayCritical in the native
                // call, which is unspecified behavior.
                outs[k] = new byte[raw.length + (raw.length >> 12) + 64];
            }

            // CRC-32 is computed natively, per entry, in the same pinned-array pass as
            // compression (see pzip_libdeflate.c) instead of a dedicated Java CRC32.update
            // loop over every raw[] beforehand.
            int[] outLens = new int[m];
            long[] crcs = new long[m];
            LibdeflateNative.compressBatch(nativeHandle, raws, outs, outLens, crcs, m);

            for (int k = 0; k < m; k++) {
                int i = nativeIdx[k];
                Entry e = batch.get(i);
                byte[] raw = raws[k];
                if (outLens[k] == LibdeflateNative.STORE_SENTINEL || outLens[k] >= raw.length) {
                    // sniff judged it already-compressed, or deflate didn't help: store this entry
                    result[i] = new Compressed(e, raw, null, false, crcs[k], raw.length, raw.length, 0);
                } else if (outLens[k] <= 0) {
                    // native failed: JDK fallback, reusing the CRC the native call already computed
                    result[i] = compressWithJdk(e, raw, def, crcs[k]);
                } else {
                    result[i] = new Compressed(e, outs[k], null, false, crcs[k], raw.length, outLens[k], 8);
                }
            }
        }
        return Arrays.asList(result);
    }

    private static byte[] readSmallEntry(Entry e) {
        try {
            return e.inline != null ? e.inline : Files.readAllBytes(e.file);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress " + e.name, ex);
        }
    }

    private static Compressed compress(Entry e, boolean store, boolean skipAlreadyCompressed, int level,
                                       Deflater def, long nativeHandle, long spillThreshold, Supplier<Path> spillDir,
                                       long mmapThreshold) {
        try {
            if (e.dir) return new Compressed(e, new byte[0], null, false, 0, 0, 0, 0);
            if (e.inline != null) {
                return compressBytes(e, e.inline, store, skipAlreadyCompressed, def, nativeHandle);
            }
            if (e.size > spillThreshold) {
                return compressLarge(e, store, skipAlreadyCompressed, level, def, spillDir, nativeHandle, mmapThreshold);
            }
            return compressBytes(e, Files.readAllBytes(e.file), store, skipAlreadyCompressed, def, nativeHandle);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress " + e.name, ex);
        }
    }

    private static Compressed compressBytes(Entry e, byte[] raw, boolean store, boolean skipAlreadyCompressed,
                                            Deflater def, long nativeHandle) {
        if (store || raw.length == 0 || (skipAlreadyCompressed && looksAlreadyCompressed(raw, raw.length))) {
            CRC32 crc = new CRC32();
            crc.update(raw);
            return new Compressed(e, raw, null, false, crc.getValue(), raw.length, raw.length, 0);
        }
        if (nativeHandle != 0) {
            // crcOut is always filled in by the native call regardless of outcome (see
            // pzip_libdeflate.c), so a real native failure below still has a valid CRC to
            // hand the JDK fallback -- no separate Java CRC32 pass either way.
            long[] crcOut = new long[1];
            Compressed viaNative = tryNativeDeflate(e, raw, nativeHandle, crcOut);
            if (viaNative != null) return viaNative;
            return compressWithJdk(e, raw, def, crcOut[0]);
        }
        return compressWithJdk(e, raw, def); // no native at all: fuse CRC into the JDK deflate loop
    }

    /**
     * Fuses CRC-32 into the same chunked pass that feeds the JDK {@link Deflater}, mirroring
     * {@link #deflateToFile}'s streaming loop, instead of a dedicated {@code crc.update(raw)}
     * pass over the whole array beforehand. Used only when no native CRC is already available
     * (native unavailable, or never attempted for this entry).
     */
    private static Compressed compressWithJdk(Entry e, byte[] raw, Deflater def) {
        CRC32 crc = new CRC32();
        def.reset();
        GrowBuffer bos = new GrowBuffer(raw.length + (raw.length >> 10) + 64);
        byte[] tmp = new byte[STREAM_BUF];
        int off = 0;
        while (off < raw.length) {
            int chunk = Math.min(STREAM_BUF, raw.length - off);
            crc.update(raw, off, chunk);
            def.setInput(raw, off, chunk);
            off += chunk;
            while (!def.needsInput()) {
                int n = def.deflate(tmp);
                bos.write(tmp, 0, n);
            }
        }
        def.finish();
        while (!def.finished()) {
            int n = def.deflate(tmp);
            bos.write(tmp, 0, n);
        }
        return finishJdkCompress(e, raw, bos, crc.getValue());
    }

    /** As {@link #compressWithJdk(Entry, byte[], Deflater)}, but reusing an already-known CRC. */
    private static Compressed compressWithJdk(Entry e, byte[] raw, Deflater def, long crcValue) {
        def.reset();
        def.setInput(raw);
        def.finish();
        GrowBuffer bos = new GrowBuffer(raw.length + (raw.length >> 10) + 64);
        byte[] tmp = new byte[STREAM_BUF];
        while (!def.finished()) {
            int n = def.deflate(tmp);
            bos.write(tmp, 0, n);
        }
        return finishJdkCompress(e, raw, bos, crcValue);
    }

    private static Compressed finishJdkCompress(Entry e, byte[] raw, GrowBuffer bos, long crcValue) {
        int compLen = bos.size();
        if (compLen >= raw.length) { // deflate didn't help: store this entry
            return new Compressed(e, raw, null, false, crcValue, raw.length, raw.length, 0);
        }
        // data may be longer than compLen; Compressed writes exactly compSize bytes.
        return new Compressed(e, bos.raw(), null, false, crcValue, raw.length, compLen, 8);
    }

    /**
     * Returns null (caller falls back to the JDK Deflater) if the native call fails for any
     * reason. {@code crcOut[0]} is always set by the native call, win or lose, so the caller
     * can reuse it for the JDK fallback instead of recomputing.
     */
    private static Compressed tryNativeDeflate(Entry e, byte[] raw, long nativeHandle, long[] crcOut) {
        int cap = raw.length + (raw.length >> 12) + 64;
        byte[] out = new byte[cap];
        int n = LibdeflateNative.compress(nativeHandle, raw, 0, raw.length, out, 0, cap, crcOut);
        if (n == LibdeflateNative.STORE_SENTINEL || (n > 0 && n >= raw.length)) {
            // sniff judged it already-compressed, or deflate didn't help: store this entry
            return new Compressed(e, raw, null, false, crcOut[0], raw.length, raw.length, 0);
        }
        if (n <= 0) return null; // real failure: caller uses the JDK Deflater
        return new Compressed(e, out, null, false, crcOut[0], raw.length, n, 8);
    }

    /** A ByteArrayOutputStream that exposes its backing array to avoid a trimming copy. */
    private static final class GrowBuffer extends ByteArrayOutputStream {
        GrowBuffer(int cap) { super(cap); }
        byte[] raw() { return buf; }
    }

    // Incompressibility sniff for large (>SPILL_THRESHOLD) entries (JREs, big archives) --
    // the Java-side mirror of the native in-memory sniff in pzip_libdeflate.c, used before
    // either large-entry compression path (mmap-native or streamed JDK Deflater) is
    // attempted. Probe the head of the file; if it barely shrinks, skip deflating the whole
    // already-compressed entry and STORE it (still reading the file once for its CRC).
    // Conservative threshold so genuinely compressible entries are never mis-stored;
    // content-based, so deterministic.
    static final int SNIFF_MIN_INPUT = 256 * 1024;
    static final int SNIFF_SAMPLE = 64 * 1024;
    static final int SNIFF_KEEP_PCT = 98; // store when the probe compresses to >= this % of its size

    /** Streams a large file-backed entry: deflate to a temp, or read/stream the source when stored. */
    private static Compressed compressLarge(Entry e, boolean store, boolean skipAlreadyCompressed, int level, Deflater def,
                                            Supplier<Path> spillDir, long nativeHandle, long mmapThreshold) throws IOException {
        if (store || (skipAlreadyCompressed && looksAlreadyCompressed(e.file))) {
            CRC32 crc = new CRC32();
            crcOf(e.file, crc);
            return new Compressed(e, null, e.file, e.fileIsTemp, crc.getValue(), e.size, e.size, 0);
        }
        if (nativeHandle != 0 && e.size <= mmapThreshold) {
            // Skip Java-side looksIncompressible here: tryMmapNativeDeflate runs the 64 KiB
            // sniff natively via sniffIncompressibleDirect (before allocating the output byte[])
            // and computes CRC-32 with libdeflate over the mapped view.
            Compressed viaMmap = tryMmapNativeDeflate(e, nativeHandle);
            if (viaMmap != null) return viaMmap;
            // fell through: mmap or the native call failed for some reason, stream via the
            // JDK Deflater below instead -- same fallback contract as tryNativeDeflate.
        }
        if (looksIncompressible(e.file, e.size, def)) {
            CRC32 crc = new CRC32();
            crcOf(e.file, crc);
            return new Compressed(e, null, e.file, e.fileIsTemp, crc.getValue(), e.size, e.size, 0);
        }
        if (e.size > mmapThreshold) {
            Compressed chunked = tryParallelChunkedDeflate(e, level, nativeHandle != 0, spillDir);
            if (chunked != null) {
                return chunked;
            }
        }
        CRC32 crc = new CRC32();
        Path tmp = Files.createTempFile(spillDir.get(), "e", ".z");
        long compSize = deflateToFile(e.file, tmp, def, crc);
        if (compSize >= e.size) { // deflate didn't help: stream the input as STORE
            Files.deleteIfExists(tmp);
            return new Compressed(e, null, e.file, e.fileIsTemp, crc.getValue(), e.size, e.size, 0);
        }
        if (e.fileIsTemp) Files.deleteIfExists(e.file); // input temp fully consumed
        return new Compressed(e, null, tmp, true, crc.getValue(), e.size, compSize, 8);
    }

    private static final int CHUNKED_BLOCK_BYTES = 4 << 20; // 4 MiB

    /**
     * Compresses a very large (>128 MiB) file in 4 MiB chunks across worker threads using
     * {@link LibdeflateNative#compressNonFinal} (with {@link Deflater#SYNC_FLUSH} fallback),
     * stitching the byte-aligned raw DEFLATE streams and combining per-chunk CRCs via
     * {@link #crc32Combine}.
     */
    private static Compressed tryParallelChunkedDeflate(Entry e, int level, boolean useNative, Supplier<Path> spillDir)
            throws IOException {
        long totalSize = e.size;
        int numChunks = (int) ((totalSize + CHUNKED_BLOCK_BYTES - 1) / CHUNKED_BLOCK_BYTES);
        if (numChunks <= 1) return null;

        int nativeLevel = level < 0 ? 6 : Math.max(1, Math.min(12, level));
        int jdkLevel = level < 0 ? Deflater.DEFAULT_COMPRESSION : Math.min(9, Math.max(0, level));
        int workers = Math.min(numChunks, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        ExecutorService chunkPool = Executors.newFixedThreadPool(workers);
        Path tmp = Files.createTempFile(spillDir.get(), "e-chunked", ".z");
        try {
            record ChunkOut(byte[] data, int compLen, long crc, int rawLen, boolean hasFinal) {}
            List<Future<ChunkOut>> futures = new ArrayList<>(numChunks);
            for (int i = 0; i < numChunks; i++) {
                final int chunkIndex = i;
                final boolean isLast = (i == numChunks - 1);
                final long offset = (long) i * CHUNKED_BLOCK_BYTES;
                final int len = (int) Math.min(CHUNKED_BLOCK_BYTES, totalSize - offset);
                futures.add(chunkPool.submit(() -> {
                    byte[] raw = new byte[len];
                    try (FileChannel ch = FileChannel.open(e.file, StandardOpenOption.READ)) {
                        ByteBuffer bb = ByteBuffer.wrap(raw);
                        long p = offset;
                        while (bb.hasRemaining()) {
                            int r = ch.read(bb, p);
                            if (r < 0) break;
                            p += r;
                        }
                    }
                    if (useNative) {
                        long h = LibdeflateNative.allocCompressor(nativeLevel);
                        if (h != 0L) {
                            try {
                                byte[] outBuf = new byte[len + (len >> 10) + 64];
                                long[] crcOut = new long[1];
                                int rc = isLast
                                        ? LibdeflateNative.compress(h, raw, 0, len, outBuf, 0, outBuf.length, crcOut)
                                        : LibdeflateNative.compressNonFinal(h, raw, 0, len, outBuf, 0, outBuf.length, crcOut);
                                if (rc > 0) {
                                    return new ChunkOut(outBuf, rc, crcOut[0], len, isLast);
                                }
                            } finally {
                                LibdeflateNative.freeCompressor(h);
                            }
                        }
                    }
                    CRC32 c = new CRC32();
                    c.update(raw, 0, len);
                    Deflater d = new Deflater(jdkLevel, true);
                    try {
                        d.setInput(raw, 0, len);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(len + 1024);
                        byte[] outBuf = new byte[64 * 1024];
                        while (!d.needsInput()) {
                            int n = d.deflate(outBuf, 0, outBuf.length, Deflater.NO_FLUSH);
                            if (n > 0) baos.write(outBuf, 0, n);
                        }
                        int flushed;
                        do {
                            flushed = d.deflate(outBuf, 0, outBuf.length, Deflater.SYNC_FLUSH);
                            if (flushed > 0) baos.write(outBuf, 0, flushed);
                        } while (flushed == outBuf.length);
                        byte[] outBytes = baos.toByteArray();
                        return new ChunkOut(outBytes, outBytes.length, c.getValue(), len, false);
                    } finally {
                        d.end();
                    }
                }));
            }

            long combinedCrc = 0L;
            long compSize = 0L;
            boolean lastHadFinal = false;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
                for (int i = 0; i < numChunks; i++) {
                    ChunkOut co = futures.get(i).get();
                    out.write(co.data(), 0, co.compLen());
                    compSize += co.compLen();
                    combinedCrc = (i == 0) ? co.crc() : crc32Combine(combinedCrc, co.crc(), co.rawLen());
                    lastHadFinal = co.hasFinal();
                }
                if (!lastHadFinal) {
                    // Terminate the raw DEFLATE stream with a BFINAL=1 empty fixed-Huffman block.
                    out.write(0x03);
                    out.write(0x00);
                    compSize += 2;
                }
            }

            if (compSize >= e.size) {
                Files.deleteIfExists(tmp);
                return new Compressed(e, null, e.file, e.fileIsTemp, combinedCrc, e.size, e.size, 0);
            }
            if (e.fileIsTemp) Files.deleteIfExists(e.file);
            return new Compressed(e, null, tmp, true, combinedCrc, e.size, compSize, 8);
        } catch (Exception ex) {
            Files.deleteIfExists(tmp);
            return null;
        } finally {
            chunkPool.shutdownNow();
        }
    }

    /** Combines two CRC-32 values: {@code crc32(buf1 + buf2) == crc32Combine(crc32(buf1), crc32(buf2), buf2.length)}. */
    static long crc32Combine(long crc1, long crc2, long len2) {
        if (len2 <= 0) return crc1;
        long[] even = new long[32];
        long[] odd = new long[32];
        odd[0] = 0xEDB88320L;
        long row = 1L;
        for (int n = 1; n < 32; n++) {
            odd[n] = row;
            row <<= 1;
        }
        gf2MatrixSquare(even, odd);
        gf2MatrixSquare(odd, even);
        long c1 = crc1;
        long rem = len2;
        do {
            gf2MatrixSquare(even, odd);
            if ((rem & 1L) != 0) c1 = gf2MatrixTimes(even, c1);
            rem >>>= 1;
            if (rem == 0) break;
            gf2MatrixSquare(odd, even);
            if ((rem & 1L) != 0) c1 = gf2MatrixTimes(odd, c1);
            rem >>>= 1;
        } while (rem != 0);
        return (c1 ^ crc2) & 0xFFFFFFFFL;
    }

    private static long gf2MatrixTimes(long[] mat, long vec) {
        long sum = 0;
        int idx = 0;
        long v = vec;
        while (v != 0) {
            if ((v & 1L) != 0) sum ^= mat[idx];
            v >>>= 1;
            idx++;
        }
        return sum;
    }

    private static void gf2MatrixSquare(long[] square, long[] mat) {
        for (int n = 0; n < 32; n++) {
            square[n] = gf2MatrixTimes(mat, mat[n]);
        }
    }

    /**
     * Compresses a large-but-mmap-eligible entry by memory-mapping the source file and
     * handing the native accelerator a direct view of it -- zero-copy on the read side,
     * unlike every other path here, which reads through a JVM {@code byte[]}. Only the
     * compressed output (and, for an already-compressed entry, nothing at all -- the STORE
     * branch below streams the original file, same as the non-mmap sniffed-STORE case above)
     * ever touches the JVM heap. Returns null (caller falls back to the streaming JDK
     * Deflater path) if mapping the file fails or the native call itself fails.
     */
    private static Compressed tryMmapNativeDeflate(Entry e, long nativeHandle) {
        int size = (int) e.size; // safe: caller already checked e.size against the (<=MMAP_THRESHOLD) mmap eligibility cap
        try (FileChannel ch = FileChannel.open(e.file, StandardOpenOption.READ)) {
            if (ch.size() != e.size) return null; // file changed since it was enumerated
            try (MappedFileView view = MappedFileView.open(ch, size)) {
                ByteBuffer mapped = view.buffer();
                long[] crcOut = new long[1];
                if (size >= SNIFF_MIN_INPUT
                        && LibdeflateNative.sniffIncompressibleDirect(nativeHandle, mapped, size, crcOut)) {
                    return new Compressed(e, null, e.file, e.fileIsTemp, crcOut[0], e.size, e.size, 0);
                }
                int cap = size + (size >> 12) + 64;
                byte[] out = new byte[cap];
                int n = LibdeflateNative.compressDirect(nativeHandle, mapped, size, out, 0, cap, crcOut);
                if (n == LibdeflateNative.STORE_SENTINEL || (n > 0 && n >= size)) {
                    // sniff judged it already-compressed, or deflate didn't help: stream the
                    // original file as STORE instead of materializing it into a byte[].
                    return new Compressed(e, null, e.file, e.fileIsTemp, crcOut[0], e.size, e.size, 0);
                }
                if (n <= 0) return null; // real failure: caller uses the streaming JDK Deflater
                return new Compressed(e, out, null, false, crcOut[0], e.size, n, 8);
            }
        } catch (IOException ex) {
            return null; // mapping failed (e.g. platform limits): caller uses the streaming JDK Deflater
        }
    }

    /**
     * Header signatures (at offset 0 unless noted) for formats that are already compressed, or
     * already store final encoded pixel/audio/stream data, so re-running DEFLATE over them costs
     * real CPU for negligible gain. Checked before {@link #looksIncompressible}: that probe only
     * samples the first {@link #SNIFF_SAMPLE} bytes, which can be unrepresentative for a
     * container format whose leading entry is small or stored (e.g. a jar's
     * {@code META-INF/MANIFEST.MF}), so a signature match here skips both the probe and the
     * compression attempt outright rather than risk paying full compression cost anyway.
     *
     * <p>Cheap, exact alternative to the statistical {@link #looksIncompressible} sniff: matches
     * {@code header} against ZIP/jar/war/ear/apk/aar/whl/nupkg/docx/xlsx/pptx/odt, gzip, bzip2,
     * xz, 7-zip, zstd, rar 1.5-5.0, lz4, png, jpeg, gif, webm/mkv, ogg, woff/woff2, WebP's
     * {@code RIFF....WEBP}, and ISO-BMFF's {@code ftyp} at offset 4 (mp4/mov/m4a). {@code len}
     * is the number of valid bytes actually read into {@code header}.</p>
     */
    private static boolean looksAlreadyCompressed(byte[] header, int len) {
        if (len < 2) return false;
        switch (header[0]) {
            case 0x50: // ZIP family: 50 4B (03 04 | 05 06 | 07 08)
                return len >= 4 && header[1] == 0x4B
                        && ((header[2] == 0x03 && header[3] == 0x04)
                            || (header[2] == 0x05 && header[3] == 0x06)
                            || (header[2] == 0x07 && header[3] == 0x08));
            case 0x1F: // gzip
                return header[1] == (byte) 0x8B;
            case 0x42: // bzip2
                return len >= 3 && header[1] == 0x5A && header[2] == 0x68;
            case (byte) 0xFD: // xz
                return len >= 6 && header[1] == 0x37 && header[2] == 0x7A
                        && header[3] == 0x58 && header[4] == 0x5A && header[5] == 0x00;
            case 0x37: // 7-zip
                return len >= 6 && header[1] == 0x7A && header[2] == (byte) 0xBC
                        && header[3] == (byte) 0xAF && header[4] == 0x27 && header[5] == 0x1C;
            case 0x28: // zstd
                return len >= 4 && header[1] == (byte) 0xB5 && header[2] == 0x2F && header[3] == (byte) 0xFD;
            case 0x52: // rar 1.5-5.0 OR RIFF....WEBP
                if (len >= 7 && header[1] == 0x61 && header[2] == 0x72 && header[3] == 0x21
                        && header[4] == 0x1A && header[5] == 0x07
                        && (header[6] == 0x00 || (len >= 8 && header[6] == 0x01 && header[7] == 0x00))) {
                    return true;
                }
                return len >= 12 && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            case 0x04: // lz4 frame
                return len >= 4 && header[1] == 0x22 && header[2] == 0x4D && header[3] == 0x18;
            case (byte) 0x89: // png
                return len >= 8 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                        && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A;
            case (byte) 0xFF: // jpeg
                return len >= 3 && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF;
            case 0x47: // gif87a / gif89a
                return len >= 4 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38;
            case 0x1A: // webm/mkv (EBML)
                return len >= 4 && header[1] == 0x45 && header[2] == (byte) 0xDF && header[3] == (byte) 0xA3;
            case 0x4F: // ogg
                return len >= 4 && header[1] == 0x67 && header[2] == 0x67 && header[3] == 0x53;
            case 0x77: // woff / woff2
                return len >= 4 && header[1] == 0x4F && header[2] == 0x46
                        && (header[3] == 0x46 || header[3] == 0x32);
            default:
                break;
        }
        return len >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    /** As {@link #looksAlreadyCompressed(byte[], int)}, reading just the header of a file-backed entry. */
    private static boolean looksAlreadyCompressed(Path file) throws IOException {
        byte[] header = new byte[16];
        int got;
        try (InputStream in = Files.newInputStream(file)) {
            got = in.readNBytes(header, 0, header.length);
        }
        return looksAlreadyCompressed(header, got);
    }

    /**
     * Probes the first {@link #SNIFF_SAMPLE} bytes of {@code file} with the JDK Deflater at
     * {@code level}; returns true when that sample barely shrinks, i.e. the file is almost
     * certainly already compressed and should be STOREd rather than deflated in full. Files
     * below {@link #SNIFF_MIN_INPUT}, or where fewer than a full sample can be read, are never
     * judged incompressible (they just take the normal compress path).
     */
    private static boolean looksIncompressible(Path file, long size, Deflater def) throws IOException {
        if (size < SNIFF_MIN_INPUT) return false;
        byte[] sample = new byte[SNIFF_SAMPLE];
        int got;
        try (InputStream in = Files.newInputStream(file)) {
            got = in.readNBytes(sample, 0, sample.length);
        }
        if (got < SNIFF_SAMPLE) return false;
        def.reset();
        def.setInput(sample, 0, got);
        def.finish();
        byte[] tmp = new byte[STREAM_BUF];
        long comp = 0;
        while (!def.finished()) comp += def.deflate(tmp);
        return comp * 100 >= (long) got * SNIFF_KEEP_PCT;
    }

    private static void crcOf(Path src, CRC32 crc) throws IOException {
        try (InputStream in = Files.newInputStream(src)) {
            byte[] buf = new byte[IO_BUF];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) crc.update(buf, 0, n);
        }
    }

    private static long deflateToFile(Path src, Path dst, Deflater def, CRC32 crc) throws IOException {
        def.reset();
        long compSize = 0;
        byte[] in = new byte[IO_BUF];
        byte[] out = new byte[STREAM_BUF];
        try (InputStream is = Files.newInputStream(src);
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
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(base)) {
                        addPath(dir, true, attrs);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    addPath(file, false, attrs);
                    return FileVisitResult.CONTINUE;
                }

                private void addPath(Path p, boolean dir, BasicFileAttributes attrs) {
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    String name = dir ? (prefix + rel + "/") : (prefix + rel);
                    if (!seen.add(name)) return; // first one wins on duplicates
                    int dosTime;
                    int dosDate;
                    if (preserveTimestamps) {
                        long packed = dosDateTimePacked(attrs.lastModifiedTime().toMillis());
                        dosTime = (int) (packed >>> 32);
                        dosDate = (int) packed;
                    } else {
                        dosTime = FIXED_DOS_TIME;
                        dosDate = FIXED_DOS_DATE;
                    }
                    int extAttr = (unixMode(p, dir) << 16) | (dir ? 0x10 : 0);
                    list.add(dir
                            ? dirEntry(name, dosTime, dosDate, extAttr)
                            : fileEntry(name, p, false, attrs.size(), dosTime, dosDate, extAttr));
                }
            });
        }
        list.sort(Comparator.comparing(e -> e.name));
        return list;
    }

    /**
     * The source file's Unix permission bits, so the directory front-end preserves the
     * executable bit (and the rest of {@code st_mode}) into the archive the same way the Gradle
     * {@code CopyAction} does. Falls back to {@link #DEFAULT_DIR_MODE} / {@link #DEFAULT_FILE_MODE}
     * on a non-POSIX filesystem (e.g. Windows), where the bits aren't available.
     */
    private static int unixMode(Path p, boolean dir) {
        try {
            int mode = 0;
            for (PosixFilePermission perm : Files.getPosixFilePermissions(p)) {
                mode |= switch (perm) {
                    case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
                    case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
                };
            }
            return mode;
        } catch (UnsupportedOperationException | IOException e) {
            return dir ? DEFAULT_DIR_MODE : DEFAULT_FILE_MODE;
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        String p = prefix.replace('\\', '/');
        if (!p.endsWith("/")) p = p + "/";
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    /**
     * External file attributes for the central directory. The high 16 bits carry the Unix mode
     * ({@code st_mode}); this normalizes the front-end's value so a UNIX-host archive (see
     * {@link #MADE_BY_UNIX}) always extracts sanely on POSIX systems: a missing permission mode
     * falls back to {@link #DEFAULT_FILE_MODE} / {@link #DEFAULT_DIR_MODE}, and the
     * {@code S_IFREG} / {@code S_IFDIR} type bits are ORed in (matching Gradle's own {@code Zip},
     * which sets {@code UnixStat.FILE_FLAG} / {@code DIR_FLAG}). The low 16 bits (MS-DOS attribute
     * byte, e.g. the directory bit) are left untouched.
     */
    private static int externalAttrFor(Entry e) {
        int dos = e.externalAttr & 0xFFFF;
        int mode = (e.externalAttr >>> 16) & 07777;
        if (mode == 0) mode = e.dir ? DEFAULT_DIR_MODE : DEFAULT_FILE_MODE;
        mode |= e.dir ? S_IFDIR : S_IFREG;
        return (mode << 16) | dos;
    }

    private static int writeLocalHeader(OutputStream os, ByteBuffer scratch, Entry e, long crc,
                                        long compSize, long rawSize, int method, boolean force) throws IOException {
        boolean z64 = force || compSize >= MAX32 || rawSize >= MAX32;
        int extraLen = z64 ? 20 : 0; // id(2)+size(2)+uncompressed(8)+compressed(8)
        int needed = 30 + e.nameBytes.length + extraLen;
        ByteBuffer b = scratch.capacity() >= needed
                ? scratch.clear()
                : ByteBuffer.allocate(needed).order(ByteOrder.LITTLE_ENDIAN);
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
        os.write(b.array(), 0, needed);
        return needed;
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
            b.putShort((short) (MADE_BY_UNIX | (z64 ? 45 : 20)));   // version made by: UNIX host
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
            b.putInt(externalAttrFor(e));          // external attrs (Unix st_mode in the high word)
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
            b.putShort((short) (MADE_BY_UNIX | 45)); // version made by: UNIX host
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

    /**
     * Converts an epoch-milli instant to a {@code {dosTime, dosDate}} pair in the JVM's local
     * time zone -- MS-DOS timestamps carry no zone, the same convention {@link java.util.zip.ZipEntry}
     * uses.
     *
     * <p>Deliberately uses {@link LocalDateTime}, not {@link java.util.Calendar}:
     * {@code Calendar.getInstance()} follows the default <em>locale's</em> calendar system, so on a
     * Thai ({@code th}) locale it returns a Buddhist calendar (year = Gregorian + 543) and on a
     * Japanese-imperial locale an era year -- either way {@code get(YEAR)} is not the Gregorian year,
     * and the {@code (year - 1980)} arithmetic below then silently produces a corrupt DOS date. The
     * DOS date field only spans 1980-2107, so instants outside that range fall back to the fixed
     * 1980-01-01 stamp rather than overflowing.</p>
     */
    public static long dosDateTimePacked(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        int year = t.getYear();
        if (year < 1980 || year > 2107) {
            return ((long) FIXED_DOS_TIME << 32) | (FIXED_DOS_DATE & 0xFFFFFFFFL);
        }
        int date = ((year - 1980) << 9) | (t.getMonthValue() << 5) | t.getDayOfMonth();
        int time = (t.getHour() << 11) | (t.getMinute() << 5) | (t.getSecond() >> 1);
        return ((long) time << 32) | (date & 0xFFFFFFFFL);
    }

    public static int[] dosDateTime(long millis) {
        long packed = dosDateTimePacked(millis);
        return new int[]{(int) (packed >>> 32), (int) packed};
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
