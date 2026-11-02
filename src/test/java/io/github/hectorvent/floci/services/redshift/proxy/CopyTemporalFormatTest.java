package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyTemporalFormatTest {

    private static CopyTemporalFormat date(String spec) {
        return CopyTemporalFormat.parse(spec, false);
    }

    private static CopyTemporalFormat time(String spec) {
        return CopyTemporalFormat.parse(spec, true);
    }

    @Test
    void theDocumentedDateExamplesConvertToIso() {
        assertEquals("2003-03-31", date("MM/DD/YYYY").toDate("03/31/2003"));
        assertEquals("2003-03-31", date("MON DD, YYYY").toDate("March 31, 2003"));
        assertEquals("1999-01-01", date("MON-DD-YYYY").toDate("Jan-01-1999"));
        assertEquals("1999-01-01", date("YYYY-MM-DD").toDate("1999-01-01"));
    }

    @Test
    void theDocumentedTimeExamplesConvertToIso() {
        assertEquals("2003-03-31 18:45:05", time("MM.DD.YYYY HH:MI:SS").toTimestamp("03.31.2003 18:45:05"));
        assertEquals("2003-03-31 18:45:05.123456",
                time("MM.DD.YYYY HH:MI:SS").toTimestamp("03.31.2003 18:45:05.123456"));
    }

    @Test
    void aTwelveHourClockUsesTheMeridian() {
        CopyTemporalFormat format = time("YYYY-MM-DD HH12:MI AM");

        assertEquals("2003-03-31 18:45:00", format.toTimestamp("2003-03-31 06:45 PM"));
        assertEquals("2003-03-31 00:05:00", format.toTimestamp("2003-03-31 12:05 AM"));
        assertEquals("2003-03-31 12:05:00", format.toTimestamp("2003-03-31 12:05 PM"));
        assertThrows(IllegalArgumentException.class, () -> format.toTimestamp("2003-03-31 13:05 PM"));
    }

    @Test
    void aTimeFormatWithoutATimeOfDayGivesMidnightAndADateFormatDropsTheTime() {
        assertEquals("2003-03-31 00:00:00", time("MM/DD/YYYY").toTimestamp("03/31/2003"));
        assertEquals("2003-03-31", date("MM/DD/YYYY HH:MI:SS").toDate("03/31/2003 18:45:05"));
    }

    @Test
    void aTwoDigitYearPivotsAtSeventy() {
        assertEquals("2069-01-02", date("YY-MM-DD").toDate("69-01-02"));
        assertEquals("1970-01-02", date("YY-MM-DD").toDate("70-01-02"));
    }

    @Test
    void tokensAreReadWhateverTheirCase() {
        assertEquals("2003-03-31", date("mm/dd/yyyy").toDate("03/31/2003"));
    }

    @Test
    void epochKeywordsConvertInUtc() {
        assertEquals("2023-11-14 22:13:20", time("epochsecs").toTimestamp("1700000000"));
        assertEquals("2023-11-14 22:13:20.123", time("epochmillisecs").toTimestamp("1700000000123"));
        assertThrows(IllegalArgumentException.class, () -> time("epochsecs").toTimestamp("soon"));
    }

    @Test
    void aValueThatDoesNotMatchTheFormatIsRejectedAndNamesTheFormat() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> date("MM/DD/YYYY").toDate("2003-03-31"));
        assertTrue(failure.getMessage().contains("MM/DD/YYYY"), failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> date("YYYY-MM-DD").toDate("2003-02-30"));
        assertThrows(IllegalArgumentException.class, () -> date("MON DD, YYYY").toDate("Smarch 31, 2003"));
    }

    @Test
    void autoLeavesEveryValueToPostgres() {
        CopyTemporalFormat auto = date("auto");

        assertFalse(auto.converts());
        assertEquals("J2451187", auto.toDate("J2451187"));
        assertEquals("2019-02-11T05:09:12.195Z", time("auto").toTimestamp("2019-02-11T05:09:12.195Z"));
    }

    @Test
    void numericTokensWithNothingBetweenThemNeedExactWidths() {
        CopyTemporalFormat compact = date("YYYYMMDD");

        assertEquals("2020-01-01", compact.toDate("20200101"));
        assertThrows(IllegalArgumentException.class, () -> compact.toDate("2020011"));
        assertEquals("2020-01-01 04:08:09", time("YYYYMMDD HHMISS").toTimestamp("20200101 040809"));
    }

    @Test
    void separatedTokensMayBeOneDigit() {
        assertEquals("2020-01-02", date("YYYY-MM-DD").toDate("2020-1-2"));
    }

    @Test
    void aRepeatedPartOrAnUnpairedMeridianIsNotParsed() {
        assertNull(date("YYYY-YYYY"));
        assertNull(date("YYYY-MM-MM"));
        assertNull(date("YYYY-MON-MM"));
        assertNull(time("YYYY-MM-DD HH:HH24"));
        assertNull(time("YYYY-MM-DD HH12:MI"));
        assertNull(time("YYYY-MM-DD MI AM"));
        assertNotNull(time("YYYY-MM-DD HH:MI AM"));
    }

    @Test
    void anEpochOutsideTheSupportedYearsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> time("epochsecs").toTimestamp("999999999999999"));
        assertThrows(IllegalArgumentException.class, () -> time("epochsecs").toTimestamp("-99999999999"));
    }

    @Test
    void theIsoFallbackReadsADateWithOrWithoutATimeOfDay() {
        CopyTemporalFormat iso = CopyTemporalFormat.defaultIso();

        assertEquals("2020-01-01 00:00:00", iso.toTimestamp("2020-01-01"));
        assertEquals("2020-01-01 10:00:00", iso.toTimestamp("2020-01-01T10:00"));
        assertEquals("2020-01-01 10:00:05.500", iso.toTimestamp("2020-01-01 10:00:05.5"));
        assertEquals("2020-01-01", iso.toDate("2020-01-01 10:00:05"));
        assertThrows(IllegalArgumentException.class, () -> iso.toDate("00/00/00"));
        assertThrows(IllegalArgumentException.class, () -> iso.toDate("2020-02-30"));
    }

    @Test
    void formatsRedshiftRefusesAreNotParsed() {
        assertNull(date(""));
        assertNull(date("foo"));
        assertNull(date("AUTO"));
        assertNull(date("epochsecs"));
        assertNull(time("YYYY-MM-DD HH:MI:SS TZ"));
        assertNull(time("YYYY-MM-DD HH:MI:SSOF"));
        assertNull(date("YYYY2MM"));
        assertNotNull(time("epochmillisecs"));
        assertNotNull(date("YYYY-MM-DD"));
    }
}
