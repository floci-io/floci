package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3IntelligentTieringConfigurationTest {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private static String body(String inner) {
        return "<IntelligentTieringConfiguration xmlns=\"" + NS + "\">" + inner
                + "</IntelligentTieringConfiguration>";
    }

    private static String tiering(String accessTier, int days) {
        return "<Tiering><AccessTier>" + accessTier + "</AccessTier><Days>" + days + "</Days></Tiering>";
    }

    @Test
    void parsesAMinimalConfiguration() {
        S3IntelligentTieringConfiguration parsed = S3IntelligentTieringConfiguration.parse(
                body("<Id>EntireBucket</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90)));

        assertEquals("EntireBucket", parsed.id());
        assertEquals("<Id>EntireBucket</Id><Status>Enabled</Status>"
                + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>",
                parsed.innerXml());
    }

    @Test
    void parsesMultipleTieringsInOrder() {
        String parsed = S3IntelligentTieringConfiguration.parse(body(
                "<Id>a</Id><Status>Disabled</Status>"
                        + tiering("ARCHIVE_ACCESS", 90)
                        + tiering("DEEP_ARCHIVE_ACCESS", 180))).innerXml();

        assertEquals("<Id>a</Id><Status>Disabled</Status>"
                + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>"
                + "<Tiering><AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier><Days>180</Days></Tiering>",
                parsed);
    }

    @Test
    void parsesEachSingleFilterPredicate() {
        assertEquals("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter><Status>Enabled</Status>"
                        + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body("<Id>a</Id>"
                        + "<Filter><Prefix>logs/</Prefix></Filter><Status>Enabled</Status>"
                        + tiering("ARCHIVE_ACCESS", 90))).innerXml());

        assertEquals("<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>"
                        + "<Status>Enabled</Status>"
                        + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body("<Id>a</Id>"
                        + "<Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>"
                        + "<Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90))).innerXml());
    }

    @Test
    void parsesAnAndConjunctionKeepingEveryTag() {
        // IntelligentTieringAndOperator.Tags is a flattened list named Tag, so the tags repeat
        // with no wrapping element and all of them have to survive the round trip.
        String parsed = S3IntelligentTieringConfiguration.parse(body("""
                <Id>a</Id>
                <Filter>
                    <And>
                        <Prefix>logs/</Prefix>
                        <Tag><Key>env</Key><Value>prod</Value></Tag>
                        <Tag><Key>team</Key><Value>core</Value></Tag>
                    </And>
                </Filter>
                <Status>Enabled</Status>
                """ + tiering("ARCHIVE_ACCESS", 90))).innerXml();

        assertEquals("<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag>"
                + "<Tag><Key>team</Key><Value>core</Value></Tag></And></Filter>"
                + "<Status>Enabled</Status>"
                + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>", parsed);
    }

    @Test
    void escapesValuesOnTheWayBackOut() {
        String parsed = S3IntelligentTieringConfiguration.parse(body(
                "<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter><Status>Enabled</Status>"
                        + tiering("ARCHIVE_ACCESS", 90))).innerXml();

        assertEquals("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter>"
                + "<Status>Enabled</Status>"
                + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>", parsed);
    }

    @Test
    void rejectsBodiesThatDoNotMatchTheSchema() {
        // Missing id, missing status, missing tiering, wrong root element, empty and non-XML
        // bodies are all MalformedXML on AWS.
        for (String invalid : new String[]{
                body(""),
                body("<Id>   </Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90)),
                body("<Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90)),
                body("<Id>a</Id>" + tiering("ARCHIVE_ACCESS", 90)),
                body("<Id>a</Id><Status>Enabled</Status>"),
                "<SomethingElse><Id>a</Id></SomethingElse>",
                "not xml at all",
                ""}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3IntelligentTieringConfiguration.parse(invalid),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    void rejectsStatusesOutsideTheEnum() {
        for (String status : new String[]{"enabled", "ENABLED", "Suspended", ""}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3IntelligentTieringConfiguration.parse(body(
                            "<Id>a</Id><Status>" + status + "</Status>" + tiering("ARCHIVE_ACCESS", 90))),
                    () -> "expected rejection of status: " + status);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }

    @Test
    void rejectsTieringsOutsideTheSchema() {
        String unknownAccessTier = "<Tiering><AccessTier>GLACIER</AccessTier><Days>90</Days></Tiering>";
        String missingDays = "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier></Tiering>";
        String missingAccessTier = "<Tiering><Days>90</Days></Tiering>";
        String zeroDays = "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>0</Days></Tiering>";
        String nonNumericDays = "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>soon</Days></Tiering>";

        for (String invalid : new String[]{
                unknownAccessTier, missingDays, missingAccessTier, zeroDays, nonNumericDays}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3IntelligentTieringConfiguration.parse(body(
                            "<Id>a</Id><Status>Enabled</Status>" + invalid)),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }

    @Test
    void rejectsDaysThatOverflowIntegerRange() {
        // A value above Integer.MAX_VALUE matches the \\d+ regex but overflows parseInt.
        // AWS answers MalformedXML, not InternalError.
        String overflowDays = "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier>"
                + "<Days>99999999999999999999</Days></Tiering>";
        AwsException e = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + overflowDays)));
        assertEquals("MalformedXML", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void rejectsArchiveAccessDaysBelowMinimum() {
        // ARCHIVE_ACCESS requires at least 90 days; AWS answers InvalidArgument, not MalformedXML.
        AwsException e = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 89))));
        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Tiering.Days", e.getExtendedData().get("ArgumentName"));
        assertEquals("89", e.getExtendedData().get("ArgumentValue"));
    }

    @Test
    void rejectsDeepArchiveAccessDaysBelowMinimum() {
        // DEEP_ARCHIVE_ACCESS requires at least 180 days; 90 is valid for ARCHIVE_ACCESS but not here.
        AwsException e = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("DEEP_ARCHIVE_ACCESS", 90))));
        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Tiering.Days", e.getExtendedData().get("ArgumentName"));
        assertEquals("90", e.getExtendedData().get("ArgumentValue"));
    }

    @Test
    void rejectsDaysAboveMaximum() {
        // Both tiers are capped at 730 days.
        AwsException archive = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 731))));
        assertEquals("InvalidArgument", archive.getErrorCode());
        assertEquals(400, archive.getHttpStatus());
        assertEquals("Tiering.Days", archive.getExtendedData().get("ArgumentName"));
        assertEquals("731", archive.getExtendedData().get("ArgumentValue"));

        AwsException deep = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("DEEP_ARCHIVE_ACCESS", 731))));
        assertEquals("InvalidArgument", deep.getErrorCode());
        assertEquals(400, deep.getHttpStatus());
        assertEquals("Tiering.Days", deep.getExtendedData().get("ArgumentName"));
        assertEquals("731", deep.getExtendedData().get("ArgumentValue"));
    }

    @Test
    void acceptsDaysAtTierBoundaries() {
        // The minimum and maximum are inclusive.
        assertEquals("<Id>a</Id><Status>Enabled</Status>"
                        + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90))).innerXml());
        assertEquals("<Id>a</Id><Status>Enabled</Status>"
                        + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>730</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 730))).innerXml());
        assertEquals("<Id>a</Id><Status>Enabled</Status>"
                        + "<Tiering><AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier><Days>180</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("DEEP_ARCHIVE_ACCESS", 180))).innerXml());
        assertEquals("<Id>a</Id><Status>Enabled</Status>"
                        + "<Tiering><AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier><Days>730</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>" + tiering("DEEP_ARCHIVE_ACCESS", 730))).innerXml());
    }

    @Test
    void rejectsOutOfRangeDaysInOneOfSeveralTierings() {
        // A valid tiering followed by an out-of-range one must still be rejected.
        AwsException e = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(body(
                        "<Id>a</Id><Status>Enabled</Status>"
                                + tiering("ARCHIVE_ACCESS", 90)
                                + tiering("DEEP_ARCHIVE_ACCESS", 179))));
        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void rejectsFiltersThatAreNotExactlyOnePredicate() {
        String suffix = "<Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90);
        String bothPrefixAndTag = "<Id>a</Id><Filter><Prefix>logs/</Prefix>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag></Filter>" + suffix;
        String emptyFilter = "<Id>a</Id><Filter></Filter>" + suffix;
        String andWithOnePredicate = "<Id>a</Id><Filter><And><Prefix>logs/</Prefix></And></Filter>" + suffix;
        String andWithOneTag = "<Id>a</Id><Filter><And>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag></And></Filter>" + suffix;
        String tagWithoutAKey = "<Id>a</Id><Filter><Tag><Value>prod</Value></Tag></Filter>" + suffix;
        String tagWithoutAValue = "<Id>a</Id><Filter><Tag><Key>env</Key></Tag></Filter>" + suffix;
        String unknownPredicate = "<Id>a</Id><Filter><Something>x</Something></Filter>" + suffix;
        String andWithAnUnknownConjunct = "<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                + "<Something>x</Something></And></Filter>" + suffix;

        for (String invalid : new String[]{
                bothPrefixAndTag, emptyFilter, andWithOnePredicate, andWithOneTag, tagWithoutAKey,
                tagWithoutAValue, unknownPredicate, andWithAnUnknownConjunct}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3IntelligentTieringConfiguration.parse(body(invalid)),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }

    @Test
    void rejectsDuplicateOrMisplacedElements() {
        String valid = "<Id>a</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90);
        // Normalizing these away would store a configuration that is not the one that was sent.
        String twoIds = "<Id>a</Id><Id>b</Id><Status>Enabled</Status>" + tiering("ARCHIVE_ACCESS", 90);
        String twoStatuses = "<Id>a</Id><Status>Enabled</Status><Status>Disabled</Status>"
                + tiering("ARCHIVE_ACCESS", 90);
        String twoFilters = "<Id>a</Id><Filter><Prefix>x</Prefix></Filter>"
                + "<Filter><Prefix>y</Prefix></Filter><Status>Enabled</Status>"
                + tiering("ARCHIVE_ACCESS", 90);
        String strayElement = valid + "<Prefix>logs/</Prefix>";

        for (String invalid : new String[]{twoIds, twoStatuses, twoFilters, strayElement}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3IntelligentTieringConfiguration.parse(body(invalid)),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }

    @Test
    void acceptsElementsInAnyOrder() {
        // Element order is not something to be strict about; the set of elements is.
        assertEquals("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter><Status>Enabled</Status>"
                        + "<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier><Days>90</Days></Tiering>",
                S3IntelligentTieringConfiguration.parse(body(tiering("ARCHIVE_ACCESS", 90)
                        + "<Status>Enabled</Status><Filter><Prefix>logs/</Prefix></Filter>"
                        + "<Id>a</Id>")).innerXml());
    }

    @Test
    void refusesToResolveExternalEntities() {
        // The parser must not read local files on behalf of a request body.
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<IntelligentTieringConfiguration><Id>&xxe;</Id></IntelligentTieringConfiguration>";

        AwsException e = assertThrows(AwsException.class,
                () -> S3IntelligentTieringConfiguration.parse(xxe));
        assertEquals("MalformedXML", e.getErrorCode());
    }
}
