package com.ljarocki.parallelzip.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The central directory must advertise a UNIX host in "version made by" and carry each entry's
 * Unix mode in the high 16 bits of the external file attributes. Without the UNIX host byte,
 * extractors read the archive as MS-DOS/FAT and ignore those mode bits entirely -- so
 * {@code unzip} on Linux drops file permissions (notably the executable bit) on extract.
 * Regression test for the reported "unzipped files lose +x on Linux" bug.
 */
class ParallelZipWriterPermissionsTest {

    @TempDir
    Path tmp;

    private record Cdh(int versionMadeBy, int externalAttr) {}

    /** Parses every central-directory header of a small (non-ZIP64) archive, keyed by entry name. */
    private static Map<String, Cdh> centralDirectory(byte[] zip) {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0; i--) {
            if (b.getInt(i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new IllegalStateException("no EOCD found");
        int count = b.getShort(eocd + 10) & 0xFFFF;
        int offset = b.getInt(eocd + 16);

        Map<String, Cdh> out = new HashMap<>();
        int p = offset;
        for (int e = 0; e < count; e++) {
            if (b.getInt(p) != 0x02014b50) throw new IllegalStateException("bad CDH signature at " + p);
            int versionMadeBy = b.getShort(p + 4) & 0xFFFF;
            int nameLen = b.getShort(p + 28) & 0xFFFF;
            int extraLen = b.getShort(p + 30) & 0xFFFF;
            int commentLen = b.getShort(p + 32) & 0xFFFF;
            int externalAttr = b.getInt(p + 38);
            String name = new String(zip, p + 46, nameLen, StandardCharsets.UTF_8);
            out.put(name, new Cdh(versionMadeBy, externalAttr));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    @Test
    void centralDirectoryAdvertisesUnixHostForEveryEntry() throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("bin"));
        Files.writeString(src.resolve("bin/run.sh"), "#!/bin/sh\necho hi\n");
        Files.writeString(src.resolve("readme.txt"), "hello\n");

        Path out = tmp.resolve("out.zip");
        ParallelZipWriter.write(List.of(new ParallelZipWriter.Source(src, "")), out, true, -1, 2, false);

        Map<String, Cdh> cd = centralDirectory(Files.readAllBytes(out));
        assertEquals(3, cd.size(), "two files plus the bin/ directory");
        for (Map.Entry<String, Cdh> entry : cd.entrySet()) {
            assertEquals(3, entry.getValue().versionMadeBy() >>> 8,
                    "version-made-by host byte must be 3 (UNIX) for " + entry.getKey());
            assertTrue((entry.getValue().externalAttr() >>> 16) != 0,
                    "external attrs must carry a non-zero Unix mode for " + entry.getKey());
        }
    }

    @Test
    void directoryFrontEndPreservesSourcePermissionBits() throws Exception {
        Path src = tmp.resolve("psrc");
        Files.createDirectories(src);
        Path script = src.resolve("run.sh");
        Path plain = src.resolve("data.txt");
        Files.writeString(script, "#!/bin/sh\n");
        Files.writeString(plain, "x\n");
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException notPosix) {
            return; // Windows: no POSIX bits to preserve; the host-byte test above still applies
        }

        Path out = tmp.resolve("perm.zip");
        ParallelZipWriter.write(List.of(new ParallelZipWriter.Source(src, "")), out, true, -1, 2, false);

        Map<String, Cdh> cd = centralDirectory(Files.readAllBytes(out));
        assertEquals(0755, (cd.get("run.sh").externalAttr() >>> 16) & 0777,
                "executable source file must keep mode 0755 in the archive");
        assertEquals(0644, (cd.get("data.txt").externalAttr() >>> 16) & 0777,
                "non-executable source file must keep mode 0644 in the archive");
    }

    @Test
    void externalAttrsGetDefaultModeWhenTheEntrySuppliesNone() throws Exception {
        // inlineEntry with externalAttr 0 mirrors any bare caller that doesn't set permissions:
        // the writer must still emit a usable mode so a UNIX-host archive isn't extracted as 000.
        ParallelZipWriter.Entry file =
                ParallelZipWriter.inlineEntry("f.txt", "data".getBytes(StandardCharsets.UTF_8),
                        ParallelZipWriter.FIXED_DOS_TIME, ParallelZipWriter.FIXED_DOS_DATE, 0);
        ParallelZipWriter.Entry dir =
                ParallelZipWriter.dirEntry("d", ParallelZipWriter.FIXED_DOS_TIME, ParallelZipWriter.FIXED_DOS_DATE, 0);

        Path out = tmp.resolve("bare.zip");
        Path spillDir = Files.createTempDirectory(tmp, "spill-");
        ParallelZipWriter.writeEntries(List.of(dir, file), out, true, -1, 1, false,
                ParallelZipWriter.SPILL_THRESHOLD, spillDir);

        Map<String, Cdh> cd = centralDirectory(Files.readAllBytes(out));
        Cdh f = cd.get("f.txt");
        assertNotNull(f);
        assertEquals(0644, (f.externalAttr() >>> 16) & 0777, "default file mode");
        assertEquals(0755, (cd.get("d/").externalAttr() >>> 16) & 0777, "default directory mode");
        assertEquals(0100000, (f.externalAttr() >>> 16) & 0170000, "regular-file type bit (S_IFREG)");
        assertEquals(0040000, (cd.get("d/").externalAttr() >>> 16) & 0170000, "directory type bit (S_IFDIR)");
    }
}
