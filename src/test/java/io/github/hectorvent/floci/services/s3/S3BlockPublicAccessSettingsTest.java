package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3BlockPublicAccessSettingsTest {

    @Test
    void parsesAllFourFlags() {
        S3BlockPublicAccessSettings settings = S3BlockPublicAccessSettings.parse(configuration(
                true, true, true, true));

        assertTrue(settings.blockPublicAcls());
        assertTrue(settings.ignorePublicAcls());
        assertTrue(settings.blockPublicPolicy());
        assertTrue(settings.restrictPublicBuckets());
    }

    @Test
    void parsesFlagsIndependently() {
        S3BlockPublicAccessSettings settings = S3BlockPublicAccessSettings.parse(configuration(
                false, true, false, true));

        assertFalse(settings.blockPublicAcls());
        assertTrue(settings.ignorePublicAcls());
        assertFalse(settings.blockPublicPolicy());
        assertTrue(settings.restrictPublicBuckets());
    }

    @Test
    void anOmittedFlagIsOff() {
        S3BlockPublicAccessSettings settings = S3BlockPublicAccessSettings.parse("""
                <PublicAccessBlockConfiguration>
                  <BlockPublicPolicy>true</BlockPublicPolicy>
                </PublicAccessBlockConfiguration>
                """);

        assertTrue(settings.blockPublicPolicy());
        assertFalse(settings.blockPublicAcls());
        assertFalse(settings.ignorePublicAcls());
        assertFalse(settings.restrictPublicBuckets());
    }

    @Test
    void anAbsentConfigurationBlocksNothing() {
        assertEquals(S3BlockPublicAccessSettings.NONE, S3BlockPublicAccessSettings.parse(null));
        assertEquals(S3BlockPublicAccessSettings.NONE, S3BlockPublicAccessSettings.parse(""));
    }

    @Test
    void malformedXmlBlocksNothing() {
        assertEquals(S3BlockPublicAccessSettings.NONE,
                S3BlockPublicAccessSettings.parse("<PublicAccessBlockConfiguration"));
    }

    @Test
    void mostRestrictiveCombinesFlagsFromBothLevels() {
        S3BlockPublicAccessSettings bucket = S3BlockPublicAccessSettings.parse(configuration(
                true, false, false, false));
        S3BlockPublicAccessSettings account = S3BlockPublicAccessSettings.parse(configuration(
                false, false, true, false));

        S3BlockPublicAccessSettings effective = bucket.mostRestrictive(account);

        assertTrue(effective.blockPublicAcls());
        assertTrue(effective.blockPublicPolicy());
        assertFalse(effective.ignorePublicAcls());
        assertFalse(effective.restrictPublicBuckets());
    }

    @Test
    void mostRestrictiveNeverRelaxesAFlag() {
        S3BlockPublicAccessSettings all = S3BlockPublicAccessSettings.parse(configuration(
                true, true, true, true));

        assertEquals(all, all.mostRestrictive(S3BlockPublicAccessSettings.NONE));
        assertEquals(all, S3BlockPublicAccessSettings.NONE.mostRestrictive(all));
    }

    @Test
    void mostRestrictiveTreatsAMissingConfigurationAsNoFlags() {
        S3BlockPublicAccessSettings bucket = S3BlockPublicAccessSettings.parse(configuration(
                false, true, false, false));

        assertEquals(bucket, bucket.mostRestrictive(null));
    }

    @Test
    void anyFlagSetIsReported() {
        assertFalse(S3BlockPublicAccessSettings.NONE.blocksAnything());
        assertTrue(S3BlockPublicAccessSettings.parse(configuration(false, false, false, true))
                .blocksAnything());
    }

    private static String configuration(boolean blockPublicAcls, boolean ignorePublicAcls,
                                        boolean blockPublicPolicy, boolean restrictPublicBuckets) {
        return """
                <PublicAccessBlockConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <BlockPublicAcls>%s</BlockPublicAcls>
                  <IgnorePublicAcls>%s</IgnorePublicAcls>
                  <BlockPublicPolicy>%s</BlockPublicPolicy>
                  <RestrictPublicBuckets>%s</RestrictPublicBuckets>
                </PublicAccessBlockConfiguration>
                """.formatted(blockPublicAcls, ignorePublicAcls, blockPublicPolicy, restrictPublicBuckets);
    }
}
