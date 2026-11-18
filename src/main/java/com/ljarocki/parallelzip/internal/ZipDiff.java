package com.ljarocki.parallelzip.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight entry-by-entry ZIP Central Directory comparator for debugging reproducibility
 * mismatches (entry presence/order, {@code mtime}, Unix mode, CRC-32, compression method,
 * sizes, and extra bytes).
 */
public final class ZipDiff {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int CD_ENTRY_SIG = 0x02014b50;

    private ZipDiff() { }

    public record EntryMeta(
            String name,
            int index,
            int versionMadeBy,
            int compressionMethod,
            int dosTime,
            int dosDate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            int externalAttr,
            byte[] extra,
            String comment
    ) {
        public int unixMode() {
            return (externalAttr >>> 16) & 0xFFFF;
        }
    }

    public record DiffResult(int leftCount, int rightCount, List<String> differences) {
        public boolean isIdentical() {
            return differences.isEmpty();
        }

        public String formatReport(Path left, Path right, int maxLines) {
            StringBuilder sb = new StringBuilder();
            sb.append("ZIP reproducibility diff: ")
                    .append(left.getFileName()).append(" (").append(leftCount).append(" entries) vs ")
                    .append(right.getFileName()).append(" (").append(rightCount).append(" entries)\n");
            int limit = Math.min(differences.size(), maxLines);
            for (int i = 0; i < limit; i++) {
                sb.append("  - ").append(differences.get(i)).append('\n');
            }
            if (differences.size() > limit) {
                sb.append("  ... and ").append(differences.size() - limit).append(" more difference(s)\n");
            }
            return sb.toString();
        }
    }

    public static DiffResult compare(Path leftPath, Path rightPath, boolean checkOrder) throws IOException {
        Map<String, EntryMeta> left = readCentralDirectory(leftPath);
        Map<String, EntryMeta> right = readCentralDirectory(rightPath);
        List<String> diffs = new ArrayList<>();

        for (String name : left.keySet()) {
            if (!right.containsKey(name)) {
                diffs.add(name + ": missing in " + rightPath.getFileName());
            }
        }
        for (String name : right.keySet()) {
            if (!left.containsKey(name)) {
                diffs.add(name + ": extra in " + rightPath.getFileName());
            }
        }

        for (Map.Entry<String, EntryMeta> e : left.entrySet()) {
            EntryMeta a = e.getValue();
            EntryMeta b = right.get(e.getKey());
            if (b == null) continue;

            if (checkOrder && a.index() != b.index()) {
                diffs.add(a.name() + ": entry order mismatch (index " + a.index() + " vs " + b.index() + ")");
            }
            if (a.compressionMethod() != b.compressionMethod()) {
                diffs.add(a.name() + ": compression method mismatch ("
                        + methodName(a.compressionMethod()) + " vs " + methodName(b.compressionMethod()) + ")");
            }
            if (a.dosTime() != b.dosTime() || a.dosDate() != b.dosDate()) {
                diffs.add(a.name() + ": mtime mismatch ("
                        + formatDosDateTime(a.dosDate(), a.dosTime()) + " vs "
                        + formatDosDateTime(b.dosDate(), b.dosTime()) + ")");
            }
            if (a.crc32() != b.crc32()) {
                diffs.add(String.format("%s: crc mismatch (0x%08x vs 0x%08x)", a.name(), a.crc32(), b.crc32()));
            }
            if (a.uncompressedSize() != b.uncompressedSize()) {
                diffs.add(a.name() + ": uncompressed size mismatch ("
                        + a.uncompressedSize() + " vs " + b.uncompressedSize() + ")");
            }
            if (a.compressedSize() != b.compressedSize()) {
                diffs.add(a.name() + ": compressed size mismatch ("
                        + a.compressedSize() + " vs " + b.compressedSize() + ")");
            }
            if (a.unixMode() != b.unixMode() || a.externalAttr() != b.externalAttr()) {
                diffs.add(String.format("%s: Unix mode / externalAttr mismatch (0%o / 0x%08x vs 0%o / 0x%08x)",
                        a.name(), a.unixMode(), a.externalAttr(), b.unixMode(), b.externalAttr()));
            }
            if (a.versionMadeBy() != b.versionMadeBy()) {
                diffs.add(String.format("%s: versionMadeBy mismatch (0x%04x vs 0x%04x)",
                        a.name(), a.versionMadeBy(), b.versionMadeBy()));
            }
            if (!Arrays.equals(a.extra(), b.extra())) {
                diffs.add(a.name() + ": extra bytes mismatch ("
                        + a.extra().length + "B vs " + b.extra().length + "B)");
            }
            if (!a.comment().equals(b.comment())) {
                diffs.add(a.name() + ": comment mismatch");
            }
        }

        return new DiffResult(left.size(), right.size(), diffs);
    }

    static Map<String, EntryMeta> readCentralDirectory(Path zipPath) throws IOException {
        try (FileChannel ch = FileChannel.open(zipPath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < 22) {
                throw new IOException("Not a valid ZIP file (too small): " + zipPath);
            }
            int tailSize = (int) Math.min(fileSize, 65_557L);
            ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(tail, fileSize - tailSize);
            tail.flip();

            int eocdRel = -1;
            for (int i = tailSize - 22; i >= 0; i--) {
                if (tail.getInt(i) == EOCD_SIG) {
                    eocdRel = i;
                    break;
                }
            }
            if (eocdRel < 0) {
                throw new IOException("No ZIP End of Central Directory found in " + zipPath);
            }

            long cdOffset = Integer.toUnsignedLong(tail.getInt(eocdRel + 16));
            long cdSize = Integer.toUnsignedLong(tail.getInt(eocdRel + 12));

            // Check for ZIP64 EOCD Locator immediately before EOCD (20 bytes).
            if (eocdRel >= 20 && tail.getInt(eocdRel - 20) == ZIP64_LOCATOR_SIG) {
                long z64EocdOffset = tail.getLong(eocdRel - 12);
                ByteBuffer z64 = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                ch.read(z64, z64EocdOffset);
                z64.flip();
                if (z64.remaining() >= 56 && z64.getInt(0) == ZIP64_EOCD_SIG) {
                    cdSize = z64.getLong(40);
                    cdOffset = z64.getLong(48);
                }
            }

            if (cdSize > Integer.MAX_VALUE) {
                throw new IOException("Central Directory exceeds 2 GiB in " + zipPath);
            }
            ByteBuffer cd = ByteBuffer.allocate((int) cdSize).order(ByteOrder.LITTLE_ENDIAN);
            long pos = cdOffset;
            while (cd.hasRemaining()) {
                int r = ch.read(cd, pos);
                if (r < 0) break;
                pos += r;
            }
            cd.flip();

            Map<String, EntryMeta> entries = new LinkedHashMap<>();
            int idx = 0;
            while (cd.remaining() >= 46) {
                int sig = cd.getInt();
                if (sig != CD_ENTRY_SIG) {
                    throw new IOException("Invalid Central Directory signature in " + zipPath);
                }
                int versionMadeBy = Short.toUnsignedInt(cd.getShort());
                cd.getShort(); // versionNeeded
                cd.getShort(); // flags
                int method = Short.toUnsignedInt(cd.getShort());
                int dosTime = Short.toUnsignedInt(cd.getShort());
                int dosDate = Short.toUnsignedInt(cd.getShort());
                long crc = Integer.toUnsignedLong(cd.getInt());
                long compSize = Integer.toUnsignedLong(cd.getInt());
                long uncompSize = Integer.toUnsignedLong(cd.getInt());
                int nameLen = Short.toUnsignedInt(cd.getShort());
                int extraLen = Short.toUnsignedInt(cd.getShort());
                int commentLen = Short.toUnsignedInt(cd.getShort());
                cd.getShort(); // diskStart
                cd.getShort(); // internalAttr
                int externalAttr = cd.getInt();
                cd.getInt(); // localHeaderOffset

                byte[] nameBytes = new byte[nameLen];
                cd.get(nameBytes);
                byte[] extraBytes = new byte[extraLen];
                cd.get(extraBytes);
                byte[] commentBytes = new byte[commentLen];
                cd.get(commentBytes);

                if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL) {
                    long[] z64Sizes = decodeZip64Extra(extraBytes, uncompSize == 0xFFFFFFFFL, compSize == 0xFFFFFFFFL);
                    if (z64Sizes[0] >= 0) uncompSize = z64Sizes[0];
                    if (z64Sizes[1] >= 0) compSize = z64Sizes[1];
                }

                String name = new String(nameBytes, StandardCharsets.UTF_8);
                String comment = new String(commentBytes, StandardCharsets.UTF_8);
                entries.put(name, new EntryMeta(
                        name, idx++, versionMadeBy, method, dosTime, dosDate,
                        crc, compSize, uncompSize, externalAttr, extraBytes, comment));
            }
            return entries;
        }
    }

    private static long[] decodeZip64Extra(byte[] extra, boolean needUncomp, boolean needComp) {
        long[] res = {-1L, -1L};
        ByteBuffer b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 4) {
            int id = Short.toUnsignedInt(b.getShort());
            int len = Short.toUnsignedInt(b.getShort());
            if (len > b.remaining()) break;
            if (id == 0x0001) {
                if (needUncomp && b.remaining() >= 8) res[0] = b.getLong();
                if (needComp && b.remaining() >= 8) res[1] = b.getLong();
                return res;
            }
            b.position(b.position() + len);
        }
        return res;
    }

    private static String methodName(int method) {
        return switch (method) {
            case 0 -> "STORED(0)";
            case 8 -> "DEFLATED(8)";
            default -> "METHOD(" + method + ")";
        };
    }

    private static String formatDosDateTime(int dosDate, int dosTime) {
        int year = ((dosDate >>> 9) & 0x7F) + 1980;
        int month = (dosDate >>> 5) & 0x0F;
        int day = dosDate & 0x1F;
        int hour = (dosTime >>> 11) & 0x1F;
        int min = (dosTime >>> 5) & 0x3F;
        int sec = (dosTime & 0x1F) * 2;
        return String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, min, sec);
    }
}
