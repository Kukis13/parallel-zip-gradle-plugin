package com.ljarocki.parallelzip.internal;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes POSIX USTAR/PAX {@code .tar} and pigz-style parallel block {@code .tar.gz} archives.
 *
 * <p>When {@code gzip} is enabled, the TAR byte stream is chunked into independent blocks
 * (default 2 MiB), compressed in parallel across {@code threads} workers using
 * {@link LibdeflateNative} (with a {@link Deflater} fallback), and emitted in order as
 * concatenated RFC 1952 gzip members.</p>
 */
public final class ParallelTarWriter {

    public static final int DEFAULT_BLOCK_SIZE = 2 << 20; // 2 MiB
    private static final int TAR_BLOCK_SIZE = 512;
    private static final long MAX_USTAR_SIZE = 077777777777L; // 8 GiB - 1

    private ParallelTarWriter() { }

    public static final class Entry {
        public final String name;
        public final boolean dir;
        public final long size;
        public final long mtimeSeconds;
        public final int unixMode;
        public final Path file;
        public final boolean fileIsTemp;
        public final byte[] data;

        private Entry(String name, boolean dir, long size, long mtimeSeconds, int unixMode,
                      Path file, boolean fileIsTemp, byte[] data) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtimeSeconds = mtimeSeconds;
            this.unixMode = unixMode;
            this.file = file;
            this.fileIsTemp = fileIsTemp;
            this.data = data;
        }

        public static Entry dirEntry(String name, long mtimeSeconds, int unixMode) {
            String normalized = name.endsWith("/") ? name : name + "/";
            return new Entry(normalized, true, 0L, mtimeSeconds, unixMode, null, false, null);
        }

        public static Entry fileEntry(String name, Path file, boolean fileIsTemp, long size,
                                      long mtimeSeconds, int unixMode) {
            return new Entry(name, false, size, mtimeSeconds, unixMode, file, fileIsTemp, null);
        }

        public static Entry inlineEntry(String name, byte[] data, long mtimeSeconds, int unixMode) {
            return new Entry(name, false, data.length, mtimeSeconds, unixMode, null, false, data);
        }
    }

    public static final class Sink implements AutoCloseable {
        private final OutputStream out;
        private final byte[] ioBuf = new byte[64 * 1024];
        private final byte[] zeroPad = new byte[TAR_BLOCK_SIZE];
        private boolean finished;

        public Sink(Path archive, boolean gzip, int level, int threads, int blockSizeBytes) throws IOException {
            this(archive, gzip, level, threads, blockSizeBytes, null);
        }

        public Sink(Path archive, boolean gzip, int level, int threads, int blockSizeBytes,
                    ExecutorService sharedPool) throws IOException {
            Path parent = archive.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(archive), 1 << 20);
            if (gzip) {
                int lvl = (level < 0) ? 6 : Math.max(1, Math.min(12, level));
                this.out = new BlockGzipOutputStream(fileOut, lvl, Math.max(1, threads),
                        Math.max(64 * 1024, blockSizeBytes), sharedPool);
            } else {
                this.out = fileOut;
            }
        }

        public void add(Entry entry) throws IOException {
            writeEntryHeader(entry);
            if (!entry.dir) {
                if (entry.data != null) {
                    out.write(entry.data);
                } else if (entry.file != null) {
                    try (InputStream in = Files.newInputStream(entry.file)) {
                        int n;
                        while ((n = in.read(ioBuf)) >= 0) {
                            if (n > 0) out.write(ioBuf, 0, n);
                        }
                    } finally {
                        if (entry.fileIsTemp) {
                            Files.deleteIfExists(entry.file);
                        }
                    }
                }
                int rem = (int) (entry.size % TAR_BLOCK_SIZE);
                if (rem != 0) {
                    out.write(zeroPad, 0, TAR_BLOCK_SIZE - rem);
                }
            }
        }

        public void finish() throws IOException {
            if (finished) return;
            finished = true;
            // Standard TAR end-of-archive marker: two 512-byte zero blocks.
            out.write(zeroPad);
            out.write(zeroPad);
            out.close();
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                out.close();
            }
        }

        private void writeEntryHeader(Entry entry) throws IOException {
            byte[] nameBytes = entry.name.getBytes(StandardCharsets.UTF_8);
            boolean needPaxPath = nameBytes.length > 100 || !isAscii(entry.name);
            boolean needPaxSize = !entry.dir && entry.size > MAX_USTAR_SIZE;
            if (needPaxPath || needPaxSize) {
                ByteArrayOutputStream pax = new ByteArrayOutputStream();
                if (needPaxPath) {
                    pax.write(paxRecord("path", entry.name));
                }
                if (needPaxSize) {
                    pax.write(paxRecord("size", Long.toString(entry.size)));
                }
                byte[] paxBytes = pax.toByteArray();
                String paxName = "PaxHeader/" + truncateName(entry.name, 88);
                byte[] paxHeader = buildUstarHeader(
                        paxName.getBytes(StandardCharsets.UTF_8),
                        0644, paxBytes.length, entry.mtimeSeconds, (byte) 'x');
                out.write(paxHeader);
                out.write(paxBytes);
                int rem = paxBytes.length % TAR_BLOCK_SIZE;
                if (rem != 0) {
                    out.write(zeroPad, 0, TAR_BLOCK_SIZE - rem);
                }
            }

            byte[] headerName = needPaxPath ? Arrays.copyOf(nameBytes, 100) : nameBytes;
            long headerSize = needPaxSize ? 0L : (entry.dir ? 0L : entry.size);
            byte typeFlag = entry.dir ? (byte) '5' : (byte) '0';
            out.write(buildUstarHeader(headerName, entry.unixMode, headerSize, entry.mtimeSeconds, typeFlag));
        }

        private static byte[] buildUstarHeader(byte[] nameBytes, int mode, long size,
                                               long mtimeSeconds, byte typeFlag) {
            byte[] h = new byte[TAR_BLOCK_SIZE];
            int nameLen = Math.min(nameBytes.length, 100);
            System.arraycopy(nameBytes, 0, h, 0, nameLen);
            writeOctal(h, 100, 8, mode & 07777);
            writeOctal(h, 108, 8, 0); // uid
            writeOctal(h, 116, 8, 0); // gid
            writeOctal(h, 124, 12, size);
            writeOctal(h, 136, 12, Math.max(0L, mtimeSeconds));
            Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = typeFlag;
            byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, h, 257, 6);
            h[263] = '0';
            h[264] = '0';

            long sum = 0;
            for (byte b : h) {
                sum += (b & 0xFF);
            }
            writeOctalChecksum(h, 148, sum);
            return h;
        }

        private static void writeOctal(byte[] buf, int offset, int length, long value) {
            int idx = offset + length - 1;
            buf[idx--] = 0;
            long v = value;
            for (; idx >= offset; idx--) {
                buf[idx] = (byte) ('0' + (v & 7L));
                v >>>= 3;
            }
        }

        private static void writeOctalChecksum(byte[] buf, int offset, long sum) {
            long v = sum;
            for (int i = offset + 5; i >= offset; i--) {
                buf[i] = (byte) ('0' + (v & 7L));
                v >>>= 3;
            }
            buf[offset + 6] = 0;
            buf[offset + 7] = ' ';
        }

        private static byte[] paxRecord(String key, String value) {
            byte[] kv = (key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
            int len = kv.length + 3; // guess: 1 digit + space + kv
            while (true) {
                String prefix = len + " ";
                int actual = prefix.length() + kv.length;
                if (actual == len) {
                    byte[] out = new byte[actual];
                    byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(p, 0, out, 0, p.length);
                    System.arraycopy(kv, 0, out, p.length, kv.length);
                    return out;
                }
                len = actual;
            }
        }

        private static boolean isAscii(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 0x7F) return false;
            }
            return true;
        }

        private static String truncateName(String name, int maxBytes) {
            byte[] b = name.getBytes(StandardCharsets.UTF_8);
            if (b.length <= maxBytes) return name;
            return new String(b, 0, maxBytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Buffers uncompressed bytes into fixed-size blocks, compresses each block in parallel into
     * an independent RFC 1952 gzip member, and writes members in order to {@code target}.
     */
    static final class BlockGzipOutputStream extends OutputStream {
        private static final byte[] GZIP_HEADER = {
                0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff
        };

        private final OutputStream target;
        private final int level;
        private final int blockSize;
        private final ExecutorService pool;
        private final boolean ownsPool;
        private final Semaphore inFlight;
        private final Queue<Future<byte[]>> pending = new ArrayDeque<>();
        private final java.util.concurrent.ConcurrentHashMap<Thread, Long> nativeHandles =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> allocatedHandles = new ConcurrentLinkedQueue<>();

        private byte[] buf;
        private int pos;
        private boolean closed;

        BlockGzipOutputStream(OutputStream target, int level, int threads, int blockSize, ExecutorService sharedPool) {
            this.target = target;
            this.level = level;
            this.blockSize = blockSize;
            if (sharedPool != null) {
                this.pool = sharedPool;
                this.ownsPool = false;
            } else {
                this.pool = Executors.newFixedThreadPool(threads);
                this.ownsPool = true;
            }
            this.inFlight = new Semaphore(threads * 2);
            this.buf = new byte[blockSize];
        }

        @Override
        public void write(int b) throws IOException {
            if (pos == blockSize) {
                flushBlock();
            }
            buf[pos++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                if (pos == blockSize) {
                    flushBlock();
                }
                int space = blockSize - pos;
                int copy = Math.min(space, remaining);
                System.arraycopy(b, offset, buf, pos, copy);
                pos += copy;
                offset += copy;
                remaining -= copy;
            }
        }

        private void flushBlock() throws IOException {
            if (pos == 0) return;
            final byte[] block = buf;
            final int len = pos;
            buf = new byte[blockSize];
            pos = 0;

            drainCompleted();
            try {
                inFlight.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for gzip block slot", e);
            }
            try {
                Future<byte[]> future = pool.submit(() -> {
                    try {
                        return compressGzipMember(block, len);
                    } finally {
                        inFlight.release();
                    }
                });
                pending.add(future);
            } catch (RuntimeException ex) {
                inFlight.release();
                throw ex;
            }
        }

        private void drainCompleted() throws IOException {
            while (!pending.isEmpty() && pending.peek().isDone()) {
                writeFuture(pending.poll());
            }
        }

        private void writeFuture(Future<byte[]> f) throws IOException {
            try {
                target.write(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted writing gzip block", e);
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof IOException ioe) throw ioe;
                throw new IOException("Parallel gzip block compression failed", c);
            }
        }

        private byte[] compressGzipMember(byte[] in, int len) {
            long handle = nativeHandles.computeIfAbsent(Thread.currentThread(), t -> {
                long h = LibdeflateNative.available() ? LibdeflateNative.allocCompressor(level) : 0L;
                if (h != 0L) {
                    allocatedHandles.add(h);
                }
                return h;
            });
            if (handle != 0L) {
                byte[] out = new byte[10 + len + (len >>> 10) + 64 + 8];
                long[] crcOut = new long[1];
                int compLen = LibdeflateNative.compress(handle, in, 0, len, out, 10, out.length - 18, crcOut);
                if (compLen > 0) {
                    System.arraycopy(GZIP_HEADER, 0, out, 0, 10);
                    writeGzipTrailer(out, 10 + compLen, crcOut[0], len);
                    return Arrays.copyOf(out, 10 + compLen + 8);
                }
                if (compLen == LibdeflateNative.STORE_SENTINEL) {
                    return buildStoredGzipMember(in, len, crcOut[0]);
                }
            }
            return compressWithJdkDeflater(in, len);
        }

        private static byte[] buildStoredGzipMember(byte[] in, int len, long crc32) {
            int numChunks = (len + 65534) / 65535;
            if (numChunks == 0) numChunks = 1;
            byte[] out = new byte[10 + len + numChunks * 5 + 8];
            System.arraycopy(GZIP_HEADER, 0, out, 0, 10);
            int p = 10;
            int off = 0;
            int rem = len;
            do {
                int chunk = Math.min(rem, 65535);
                boolean last = (rem - chunk) == 0;
                out[p++] = (byte) (last ? 0x01 : 0x00);
                out[p++] = (byte) (chunk & 0xFF);
                out[p++] = (byte) ((chunk >>> 8) & 0xFF);
                int nlen = ~chunk & 0xFFFF;
                out[p++] = (byte) (nlen & 0xFF);
                out[p++] = (byte) ((nlen >>> 8) & 0xFF);
                if (chunk > 0) {
                    System.arraycopy(in, off, out, p, chunk);
                    p += chunk;
                    off += chunk;
                    rem -= chunk;
                }
            } while (rem > 0);
            writeGzipTrailer(out, p, crc32, len);
            return out;
        }

        private byte[] compressWithJdkDeflater(byte[] in, int len) {
            CRC32 crc = new CRC32();
            crc.update(in, 0, len);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(len, 64 * 1024));
            baos.writeBytes(GZIP_HEADER);
            Deflater deflater = new Deflater(Math.min(9, level), true);
            try {
                deflater.setInput(in, 0, len);
                deflater.finish();
                byte[] tmp = new byte[32 * 1024];
                while (!deflater.finished()) {
                    int n = deflater.deflate(tmp);
                    if (n > 0) baos.write(tmp, 0, n);
                }
            } finally {
                deflater.end();
            }
            byte[] trailer = new byte[8];
            writeGzipTrailer(trailer, 0, crc.getValue(), len);
            baos.writeBytes(trailer);
            return baos.toByteArray();
        }

        private static void writeGzipTrailer(byte[] out, int off, long crc32, int isize) {
            int c = (int) crc32;
            out[off] = (byte) (c & 0xFF);
            out[off + 1] = (byte) ((c >>> 8) & 0xFF);
            out[off + 2] = (byte) ((c >>> 16) & 0xFF);
            out[off + 3] = (byte) ((c >>> 24) & 0xFF);
            out[off + 4] = (byte) (isize & 0xFF);
            out[off + 5] = (byte) ((isize >>> 8) & 0xFF);
            out[off + 6] = (byte) ((isize >>> 16) & 0xFF);
            out[off + 7] = (byte) ((isize >>> 24) & 0xFF);
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (pos > 0) {
                    flushBlock();
                }
                while (!pending.isEmpty()) {
                    writeFuture(pending.poll());
                }
            } finally {
                // Always await any remaining in-flight tasks before freeing native compressors
                // (critical when borrowing the shared BuildService pool and an earlier block failed).
                boolean interrupted = false;
                while (!pending.isEmpty()) {
                    Future<byte[]> f = pending.poll();
                    while (true) {
                        try {
                            f.get();
                            break;
                        } catch (InterruptedException ie) {
                            interrupted = true;
                        } catch (ExecutionException ignored) {
                            break;
                        }
                    }
                }
                if (ownsPool) {
                    pool.shutdown();
                    try {
                        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                            pool.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                        pool.shutdownNow();
                    }
                }
                nativeHandles.clear();
                for (Long h : allocatedHandles) {
                    LibdeflateNative.freeCompressor(h);
                }
                allocatedHandles.clear();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                target.close();
            }
        }
    }
}
