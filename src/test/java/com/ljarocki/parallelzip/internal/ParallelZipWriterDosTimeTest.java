package com.ljarocki.parallelzip.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the locale-dependent DOS timestamp bug fixed in 1.4.3.
 *
 * <p>{@link ParallelZipWriter#dosDateTime} used {@code Calendar.getInstance()}, whose {@code YEAR}
 * field follows the JVM default locale's calendar system. On a Thai locale that is the Buddhist
 * year (Gregorian + 543), which overflows the 7-bit DOS year field and corrupts every entry's
 * modification date; on a Japanese-imperial locale it is the era year, which underflows to the
 * pre-1980 fallback. The fix switched to {@link LocalDateTime}, which is always proleptic
 * Gregorian.</p>
 */
class ParallelZipWriterDosTimeTest {

    /** Decodes a {@code {dosTime, dosDate}} pair into {year, month, day, hour, minute, second}. */
    private static int[] decode(int[] dos) {
        int time = dos[0], date = dos[1];
        return new int[]{
                ((date >> 9) & 0x7f) + 1980,
                (date >> 5) & 0x0f,
                date & 0x1f,
                (time >> 11) & 0x1f,
                (time >> 5) & 0x3f,
                (time & 0x1f) * 2,
        };
    }

    private static long millisOf(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void gregorianDateIsCorrectUnderAnyDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            LocalDateTime when = LocalDateTime.of(2026, 6, 15, 12, 30, 44);
            for (Locale loc : new Locale[]{
                    Locale.US,
                    new Locale("th", "TH"),
                    Locale.forLanguageTag("th-TH-u-ca-buddhist"),
                    Locale.forLanguageTag("ja-JP-u-ca-japanese"),
            }) {
                Locale.setDefault(loc);
                int[] d = decode(ParallelZipWriter.dosDateTime(millisOf(when)));
                assertEquals(2026, d[0], "year under locale " + loc.toLanguageTag());
                assertEquals(6, d[1], "month under locale " + loc.toLanguageTag());
                assertEquals(15, d[2], "day under locale " + loc.toLanguageTag());
                assertEquals(12, d[3], "hour under locale " + loc.toLanguageTag());
                assertEquals(30, d[4], "minute under locale " + loc.toLanguageTag());
                assertEquals(44, d[5], "second under locale " + loc.toLanguageTag());
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void instantsOutsideTheDosRangeFallBackToTheFixedStamp() {
        int[] pre1980 = ParallelZipWriter.dosDateTime(millisOf(LocalDateTime.of(1975, 1, 1, 0, 0)));
        assertEquals(ParallelZipWriter.FIXED_DOS_DATE, pre1980[1], "pre-1980 -> fixed date");
        assertEquals(ParallelZipWriter.FIXED_DOS_TIME, pre1980[0], "pre-1980 -> fixed time");

        int[] post2107 = ParallelZipWriter.dosDateTime(millisOf(LocalDateTime.of(2200, 1, 1, 0, 0)));
        assertEquals(ParallelZipWriter.FIXED_DOS_DATE, post2107[1], "post-2107 -> fixed date (no overflow)");
        assertEquals(ParallelZipWriter.FIXED_DOS_TIME, post2107[0], "post-2107 -> fixed time");
    }
}
