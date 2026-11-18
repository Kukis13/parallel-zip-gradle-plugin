package com.ljarocki.parallelzip.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental ZIP patcher: compares incoming entries against a prior archive's Central
 * Directory and copies unchanged entries' already-compressed payloads directly from the prior
 * archive without re-compressing, recompressing only new or modified entries.
 */
public final class ZipPatcher {

    private ZipPatcher() { }

    public record PriorEntry(
            ZipDiff.EntryMeta meta,
            long localHeaderOffset,
            long dataOffset
    ) { }

    /**
     * Reads Central Directory metadata and local header offsets from {@code priorArchive}.
     */
    public static Map<String, PriorEntry> indexPriorArchive(Path priorArchive) throws IOException {
        Map<String, ZipDiff.EntryMeta> cd = ZipDiff.readCentralDirectory(priorArchive);
        Map<String, PriorEntry> indexed = new HashMap<>(cd.size() * 2);
        // Walk local headers sequentially starting at 0? No, read CD directly to get localHeaderOffset.
        // We can reuse ZipDiff's CD or read local header offset from CD.
        try (FileChannel ch = FileChannel.open(priorArchive, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            int tailSize = (int) Math.min(fileSize, 65_557L);
            ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(tail, fileSize - tailSize);
            tail.flip();
            int eocdRel = -1;
            for (int i = tailSize - 22; i >= 0; i--) {
                if (tail.getInt(i) == 0x06054b50) {
                    eocdRel = i;
                    break;
                }
            }
            if (eocdRel < 0) return indexed;
            long cdOffset = Integer.toUnsignedLong(tail.getInt(eocdRel + 16));
            long cdSize = Integer.toUnsignedLong(tail.getInt(eocdRel + 12));
            if (eocdRel >= 20 && tail.getInt(eocdRel - 20) == 0x07064b50) {
                long z64Off = tail.getLong(eocdRel - 12);
                ByteBuffer z64 = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                ch.read(z64, z64Off);
                z64.flip();
                if (z64.remaining() >= 56 && z64.getInt(0) == 0x06064b50) {
                    cdSize = z64.getLong(40);
                    cdOffset = z64.getLong(48);
                }
            }
            ByteBuffer cdBuf = ByteBuffer.allocate((int) cdSize).order(ByteOrder.LITTLE_ENDIAN);
            long p = cdOffset;
            while (cdBuf.hasRemaining()) {
                int r = ch.read(cdBuf, p);
                if (r < 0) break;
                p += r;
            }
            cdBuf.flip();
            ByteBuffer lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            while (cdBuf.remaining() >= 46) {
                if (cdBuf.getInt() != 0x02014b50) break;
                cdBuf.position(cdBuf.position() + 24);
                int nameLen = Short.toUnsignedInt(cdBuf.getShort());
                int extraLen = Short.toUnsignedInt(cdBuf.getShort());
                int commentLen = Short.toUnsignedInt(cdBuf.getShort());
                cdBuf.position(cdBuf.position() + 8);
                long lhOff = Integer.toUnsignedLong(cdBuf.getInt());
                byte[] nameBytes = new byte[nameLen];
                cdBuf.get(nameBytes);
                cdBuf.position(cdBuf.position() + extraLen + commentLen);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                ZipDiff.EntryMeta meta = cd.get(name);
                if (meta != null) {
                    lh.clear();
                    ch.read(lh, lhOff);
                    lh.flip();
                    if (lh.remaining() == 30 && lh.getInt(0) == 0x04034b50) {
                        int lhNameLen = Short.toUnsignedInt(lh.getShort(26));
                        int lhExtraLen = Short.toUnsignedInt(lh.getShort(28));
                        long dataOff = lhOff + 30L + lhNameLen + lhExtraLen;
                        indexed.put(name, new PriorEntry(meta, lhOff, dataOff));
                    }
                }
            }
        }
        return indexed;
    }

    /**
     * Patches {@code targetArchive} incrementally from {@code priorArchive} for the given
     * {@code entries}. Entries whose {@code name}, {@code size}, {@code dosTime}, {@code dosDate},
     * and {@code externalAttr} match {@code priorArchive} are copied directly as raw compressed
     * bytes; changed or new entries are compressed via {@link ParallelZipWriter}.
     */
    public static int patch(Path priorArchive,
                            Path targetArchive,
                            List<ParallelZipWriter.Entry> entries,
                            boolean store,
                            boolean skipAlreadyCompressed,
                            int level,
                            int threads) throws IOException {
        Map<String, PriorEntry> prior = indexPriorArchive(priorArchive);
        Path tmpNew = Files.createTempFile(targetArchive.toAbsolutePath().getParent(), "patch-", ".zip");
        int reused = 0;
        List<ParallelZipWriter.Entry> toCompress = new ArrayList<>();
        Map<String, PriorEntry> reusedMap = new HashMap<>();

        for (ParallelZipWriter.Entry e : entries) {
            PriorEntry pe = prior.get(e.name);
            if (!e.dir && pe != null
                    && pe.meta().uncompressedSize() == e.size
                    && pe.meta().dosTime() == e.dosTime
                    && pe.meta().dosDate() == e.dosDate
                    && ((pe.meta().externalAttr() >>> 16) & 07777) == ((e.externalAttr >>> 16) & 07777)) {
                reusedMap.put(e.name, pe);
                reused++;
            } else {
                toCompress.add(e);
            }
        }

        if (reused == 0) {
            Files.deleteIfExists(tmpNew);
            try {
                ParallelZipWriter.writeEntries(entries, targetArchive, store, skipAlreadyCompressed, level, threads,
                        false, ParallelZipWriter.SPILL_THRESHOLD, null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted writing archive", ex);
            }
            return 0;
        }

        // Write changed/new entries into a staging zip, then assemble final archive copying raw payloads.
        Path deltaZip = Files.createTempFile(targetArchive.toAbsolutePath().getParent(), "delta-", ".zip");
        try {
            if (!toCompress.isEmpty()) {
                try {
                    ParallelZipWriter.writeEntries(toCompress, deltaZip, store, skipAlreadyCompressed, level, threads,
                            false, ParallelZipWriter.SPILL_THRESHOLD, null);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted writing delta archive", ex);
                }
            }
            Map<String, PriorEntry> deltaIdx = toCompress.isEmpty() ? Map.of() : indexPriorArchive(deltaZip);

            try (FileChannel priorCh = FileChannel.open(priorArchive, StandardOpenOption.READ);
                 FileChannel deltaCh = toCompress.isEmpty() ? null : FileChannel.open(deltaZip, StandardOpenOption.READ);
                 FileChannel outCh = FileChannel.open(tmpNew,
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                long offset = 0;
                List<ZipDiff.EntryMeta> cdOut = new ArrayList<>(entries.size());
                List<Long> offsetsOut = new ArrayList<>(entries.size());

                for (ParallelZipWriter.Entry e : entries) {
                    PriorEntry src = reusedMap.get(e.name);
                    FileChannel srcCh = priorCh;
                    if (src == null) {
                        src = deltaIdx.get(e.name);
                        srcCh = deltaCh;
                    }
                    if (src == null || srcCh == null) continue;

                    long totalEntryBytes = (src.dataOffset() - src.localHeaderOffset()) + src.meta().compressedSize();
                    offsetsOut.add(offset);
                    cdOut.add(src.meta());
                    long copied = 0;
                    while (copied < totalEntryBytes) {
                        long n = srcCh.transferTo(src.localHeaderOffset() + copied, totalEntryBytes - copied, outCh);
                        if (n <= 0) break;
                        copied += n;
                    }
                    offset += copied;
                }

                writeCentralDirectoryAndEocd(outCh, cdOut, offsetsOut, offset);
            }
            Files.move(tmpNew, targetArchive, StandardCopyOption.REPLACE_EXISTING);
            return reused;
        } finally {
            Files.deleteIfExists(deltaZip);
            Files.deleteIfExists(tmpNew);
        }
    }

    private static void writeCentralDirectoryAndEocd(FileChannel outCh,
                                                     List<ZipDiff.EntryMeta> entries,
                                                     List<Long> offsets,
                                                     long cdOffset) throws IOException {
        int cdSize = 0;
        for (ZipDiff.EntryMeta m : entries) {
            byte[] nameBytes = m.name().getBytes(StandardCharsets.UTF_8);
            cdSize += 46 + nameBytes.length + m.extra().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(cdSize + 22).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < entries.size(); i++) {
            ZipDiff.EntryMeta m = entries.get(i);
            byte[] nameBytes = m.name().getBytes(StandardCharsets.UTF_8);
            buf.putInt(0x02014b50);
            buf.putShort((short) m.versionMadeBy());
            buf.putShort((short) 20);
            buf.putShort((short) (1 << 11));
            buf.putShort((short) m.compressionMethod());
            buf.putShort((short) m.dosTime());
            buf.putShort((short) m.dosDate());
            buf.putInt((int) m.crc32());
            buf.putInt((int) m.compressedSize());
            buf.putInt((int) m.uncompressedSize());
            buf.putShort((short) nameBytes.length);
            buf.putShort((short) m.extra().length);
            buf.putShort((short) 0);
            buf.putShort((short) 0);
            buf.putShort((short) 0);
            buf.putInt(m.externalAttr());
            buf.putInt(offsets.get(i).intValue());
            buf.put(nameBytes);
            buf.put(m.extra());
        }
        buf.putInt(0x06054b50);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) entries.size());
        buf.putShort((short) entries.size());
        buf.putInt(cdSize);
        buf.putInt((int) cdOffset);
        buf.putShort((short) 0);
        buf.flip();
        while (buf.hasRemaining()) {
            outCh.write(buf);
        }
    }
}
