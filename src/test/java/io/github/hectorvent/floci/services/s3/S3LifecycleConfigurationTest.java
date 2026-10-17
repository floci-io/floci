package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3LifecycleConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void dayCountsRoundUpToTheNextMidnightUtc() {
        // The user guide's examples: created 1/15/2014 10:30 UTC plus 3 days is 1/19/2014 00:00 UTC.
        assertEquals(Instant.parse("2014-01-19T00:00:00Z"),
                S3LifecycleConfiguration.afterDays(Instant.parse("2014-01-15T10:30:00Z"), 3));
        assertEquals(Instant.parse("2014-01-17T00:00:00Z"),
                S3LifecycleConfiguration.afterDays(Instant.parse("2014-01-15T23:59:59.999Z"), 1));
    }

    @Test
    void disabledRulesAndMalformedBodiesHaveNoRules() {
        assertThat(parse(rule("off", "Disabled", "<Filter><Prefix></Prefix></Filter>",
                "<Expiration><Days>1</Days></Expiration>")).rules(), empty());
        assertThat(S3LifecycleConfiguration.parse("<LifecycleConfiguration><Rule>").rules(), empty());
        assertThat(S3LifecycleConfiguration.parse("not xml").rules(), empty());
    }

    @Test
    void theLegacyRulePrefixIsTheFilterWhenNoFilterIsGiven() {
        S3LifecycleConfiguration config = S3LifecycleConfiguration.parse("<LifecycleConfiguration><Rule>"
                + "<ID>legacy</ID><Prefix>logs/</Prefix><Status>Enabled</Status>"
                + "<Expiration><Days>1</Days></Expiration></Rule></LifecycleConfiguration>");

        assertTrue(config.rules().getFirst().filter().matches("logs/a", Map.of(), 1));
        assertFalse(config.rules().getFirst().filter().matches("other/a", Map.of(), 1));
    }

    @Test
    void anAndFilterNeedsThePrefixEveryTagAndTheExclusiveSizeRange() {
        S3LifecycleConfiguration.Filter filter = parse(rule("and", "Enabled", "<Filter><And>"
                + "<Prefix>tmp/</Prefix>"
                + "<Tag><Key>env</Key><Value>dev</Value></Tag>"
                + "<Tag><Key>flag</Key><Value></Value></Tag>"
                + "<ObjectSizeGreaterThan>10</ObjectSizeGreaterThan>"
                + "<ObjectSizeLessThan>20</ObjectSizeLessThan>"
                + "</And></Filter>", "<Expiration><Days>1</Days></Expiration>")).rules().getFirst().filter();
        Map<String, String> tags = Map.of("env", "dev", "flag", "", "extra", "x");

        assertTrue(filter.matches("tmp/a", tags, 15));
        assertFalse(filter.matches("keep/a", tags, 15));
        assertFalse(filter.matches("tmp/a", Map.of("env", "dev", "flag", ""), 10));
        assertFalse(filter.matches("tmp/a", Map.of("env", "dev", "flag", ""), 20));
        assertFalse(filter.matches("tmp/a", Map.of("env", "prod", "flag", ""), 15));
        assertFalse(filter.matches("tmp/a", Map.of("env", "dev"), 15));
        assertFalse(filter.matches("tmp/a", Map.of("env", "dev", "flag", "set"), 15));
    }

    @Test
    void anObjectGetsTheEarliestExpiryOfTheRulesThatMatchIt() {
        S3LifecycleConfiguration config = parse(
                rule("ten days", "Enabled", "<Filter><Prefix></Prefix></Filter>",
                        "<Expiration><Days>10</Days></Expiration>")
                + rule("by date", "Enabled", "<Filter><Prefix>tmp/</Prefix></Filter>",
                        "<Expiration><Date>2026-10-08T00:00:00.000Z</Date></Expiration>")
                + rule("transition only", "Enabled", "<Filter><Prefix></Prefix></Filter>",
                        "<Transition><Days>1</Days><StorageClass>GLACIER</StorageClass></Transition>"));

        S3LifecycleConfiguration.Expiry tmp = config.currentVersionExpiry("tmp/a", Map.of(), 1, NOW);
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), tmp.date());
        assertEquals("expiry-date=\"Thu, 08 Oct 2026 00:00:00 GMT\", rule-id=\"by%20date\"", tmp.header());

        S3LifecycleConfiguration.Expiry other = config.currentVersionExpiry("other/a", Map.of(), 1, NOW);
        assertEquals(Instant.parse("2026-10-17T00:00:00Z"), other.date());
        assertEquals("ten days", other.ruleId());
    }

    @Test
    void anExpirationDateMayBeWrittenAsAPlainDate() {
        S3LifecycleConfiguration config = parse(rule("plain", "Enabled", "<Filter></Filter>",
                "<Expiration><Date>2020-01-01</Date></Expiration>"));

        assertEquals(Instant.parse("2020-01-01T00:00:00Z"),
                config.currentVersionExpiry("a", Map.of(), 1, NOW).date());
    }

    @Test
    void aRuleS3WouldRefuseIsDroppedWholeAndTheOtherRulesStillApply() {
        String good = rule("good", "Enabled", "<Filter></Filter>", "<Expiration><Days>1</Days></Expiration>");
        List<String> refused = List.of(
                rule("days soon", "Enabled", "<Filter></Filter>", "<Expiration><Days>soon</Days></Expiration>"),
                rule("days zero", "Enabled", "<Filter></Filter>", "<Expiration><Days>0</Days></Expiration>"),
                rule("bad date", "Enabled", "<Filter></Filter>", "<Expiration><Date>someday</Date></Expiration>"),
                rule("marker and days", "Enabled", "<Filter></Filter>", "<Expiration><Days>1</Days>"
                        + "<ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>"),
                rule("marker maybe", "Enabled", "<Filter></Filter>",
                        "<Expiration><ExpiredObjectDeleteMarker>maybe</ExpiredObjectDeleteMarker></Expiration>"),
                rule("size word", "Enabled", "<Filter><ObjectSizeGreaterThan>big</ObjectSizeGreaterThan></Filter>",
                        "<Expiration><Days>1</Days></Expiration>"),
                rule("size negative", "Enabled", "<Filter><ObjectSizeLessThan>-1</ObjectSizeLessThan></Filter>",
                        "<Expiration><Days>1</Days></Expiration>"),
                rule("tag without value", "Enabled", "<Filter><Tag><Key>env</Key></Tag></Filter>",
                        "<Expiration><Days>1</Days></Expiration>"),
                rule("tag twice", "Enabled", "<Filter><And><Tag><Key>env</Key><Value>a</Value></Tag>"
                        + "<Tag><Key>env</Key><Value>b</Value></Tag></And></Filter>",
                        "<Expiration><Days>1</Days></Expiration>"),
                "<Rule><ID>no filter</ID><Status>Enabled</Status><Expiration><Days>1</Days></Expiration></Rule>",
                rule("noncurrent zero", "Enabled", "<Filter></Filter>",
                        "<NoncurrentVersionExpiration><NoncurrentDays>0</NoncurrentDays></NoncurrentVersionExpiration>"),
                rule("retain too many", "Enabled", "<Filter></Filter>", "<NoncurrentVersionExpiration>"
                        + "<NoncurrentDays>1</NoncurrentDays><NewerNoncurrentVersions>101</NewerNoncurrentVersions>"
                        + "</NoncurrentVersionExpiration>"),
                "<Rule><ID>retain without filter</ID><Prefix></Prefix><Status>Enabled</Status>"
                        + "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>"
                        + "<NewerNoncurrentVersions>1</NewerNoncurrentVersions></NoncurrentVersionExpiration></Rule>",
                rule("abort negative", "Enabled", "<Filter></Filter>", "<AbortIncompleteMultipartUpload>"
                        + "<DaysAfterInitiation>-1</DaysAfterInitiation></AbortIncompleteMultipartUpload>"),
                rule("status typo", "enabled", "<Filter></Filter>", "<Expiration><Days>1</Days></Expiration>"));

        for (String bad : refused) {
            List<S3LifecycleConfiguration.Rule> rules = parse(bad + good).rules();
            assertEquals(List.of("good"), rules.stream().map(S3LifecycleConfiguration.Rule::id).toList(), bad);
        }
    }

    @Test
    void aBodyWithMalformedContentAfterTheConfigurationHasNoRules() {
        String rules = rule("past", "Enabled", "<Filter></Filter>",
                "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>");

        assertThat(S3LifecycleConfiguration.parse(
                "<LifecycleConfiguration>" + rules + "</LifecycleConfiguration><Rule>").rules(), empty());
        assertThat(S3LifecycleConfiguration.parse(
                "<LifecycleConfiguration>" + rules + "</LifecycleConfiguration>trailing").rules(), empty());
        assertThat(S3LifecycleConfiguration.parse("<Other>" + rules + "</Other>").rules(), empty());
    }

    @Test
    void anExpiredObjectDeleteMarkerGoesAtOnceOrOnceOldEnoughForADaysRule() {
        S3LifecycleConfiguration explicit = parse(rule("markers", "Enabled", "<Filter></Filter>",
                "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>"));
        S3LifecycleConfiguration days = parse(rule("days", "Enabled", "<Filter></Filter>",
                "<Expiration><Days>2</Days></Expiration>"));

        assertTrue(explicit.removesExpiredObjectDeleteMarker("a", NOW, NOW));
        assertNull(explicit.currentVersionExpiry("a", Map.of(), 1, NOW));
        assertFalse(days.removesExpiredObjectDeleteMarker("a", NOW, Instant.parse("2026-10-08T23:59:59Z")));
        assertTrue(days.removesExpiredObjectDeleteMarker("a", NOW, Instant.parse("2026-10-09T00:00:00Z")));
    }

    @Test
    void aNoncurrentVersionNeedsBothItsAgeAndEnoughNewerVersionsRetained() {
        S3LifecycleConfiguration config = parse(rule("noncurrent", "Enabled", "<Filter></Filter>",
                "<NoncurrentVersionExpiration><NoncurrentDays>3</NoncurrentDays>"
                        + "<NewerNoncurrentVersions>2</NewerNoncurrentVersions></NoncurrentVersionExpiration>"));
        Instant becameNoncurrent = Instant.parse("2014-01-15T10:30:00Z");
        Instant due = Instant.parse("2014-01-19T00:00:00Z");

        assertTrue(config.expiresNoncurrentVersion("a", Map.of(), 1, becameNoncurrent, 2, due));
        assertFalse(config.expiresNoncurrentVersion("a", Map.of(), 1, becameNoncurrent, 1, due));
        assertFalse(config.expiresNoncurrentVersion("a", Map.of(), 1, becameNoncurrent, 2,
                due.minusMillis(1)));
    }

    @Test
    void onlyAPrefixFilterSelectsMultipartUploadsToAbort() {
        S3LifecycleConfiguration prefix = parse(rule("abort", "Enabled", "<Filter><Prefix>big/</Prefix></Filter>",
                "<AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation>"
                        + "</AbortIncompleteMultipartUpload>"));
        S3LifecycleConfiguration tagged = parse(rule("abort", "Enabled",
                "<Filter><Tag><Key>k</Key><Value>v</Value></Tag></Filter>",
                "<AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation>"
                        + "</AbortIncompleteMultipartUpload>"));
        Instant due = Instant.parse("2026-10-08T00:00:00Z");

        assertTrue(prefix.abortsUpload("big/a", NOW, due));
        assertFalse(prefix.abortsUpload("big/a", NOW, due.minusMillis(1)));
        assertFalse(prefix.abortsUpload("small/a", NOW, due));
        assertFalse(tagged.abortsUpload("big/a", NOW, due));
    }

    @Test
    void everyEnabledRuleIsRead() {
        assertThat(parse(rule("a", "Enabled", "<Filter></Filter>", "<Expiration><Days>1</Days></Expiration>")
                + rule("b", "Enabled", "<Filter></Filter>", "<Expiration><Days>2</Days></Expiration>")
                + rule("c", "Disabled", "<Filter></Filter>", "<Expiration><Days>3</Days></Expiration>")).rules(),
                hasSize(2));
    }

    private static S3LifecycleConfiguration parse(String rules) {
        return S3LifecycleConfiguration.parse(
                "<LifecycleConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                        + rules + "</LifecycleConfiguration>");
    }

    private static String rule(String id, String status, String filter, String actions) {
        return "<Rule><ID>" + id + "</ID>" + filter + "<Status>" + status + "</Status>" + actions + "</Rule>";
    }
}
