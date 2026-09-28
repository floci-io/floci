package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.AwsPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionStartupValidatorTest {

    @ParameterizedTest
    @CsvSource({
            "us-east-1,       aws",
            "cn-north-1,      aws-cn",
            "us-gov-west-1,   aws-us-gov",
            "us-iso-east-1,   aws-iso",
            "us-isob-east-1,  aws-iso-b",
            "eu-isoe-west-1,  aws-iso-e",
            "us-isof-south-1, aws-iso-f",
            "eusc-de-east-1,  aws-eusc"})
    void thePartitionIsDerivedFromTheDefaultRegion(String region, String partition) {
        AwsPartition resolved = PartitionStartupValidator.validate(region, Optional.empty());
        assertEquals(partition, resolved.id());
    }

    @Test
    void anExplicitPartitionThatAgreesWithTheRegionIsAccepted() {
        assertEquals("aws-cn", PartitionStartupValidator.validate("cn-north-1", Optional.of("aws-cn")).id());
        assertEquals("aws-cn", PartitionStartupValidator.validate("cn-north-1", Optional.of(" AWS-CN ")).id());
    }

    @Test
    void anUnknownPartitionIdIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PartitionStartupValidator.validate("us-east-1", Optional.of("aws-mars")));
        assertTrue(error.getMessage().contains("aws-mars"));
        assertTrue(error.getMessage().contains("aws-cn"), "the message lists the published partitions");
    }

    @Test
    void aPartitionThatContradictsARecognisedRegionIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PartitionStartupValidator.validate("cn-north-1", Optional.of("aws")));
        assertTrue(error.getMessage().contains("cn-north-1"));
        assertTrue(error.getMessage().contains("aws-cn"));
    }

    /** A region the vendored data does not know only warns; the explicit partition wins. */
    @Test
    void anUnpublishedRegionIsAcceptedWithAWarning() {
        assertEquals("aws-us-gov", PartitionStartupValidator.validate("us-gov-north-9", Optional.of("aws-us-gov")).id());
        assertEquals("aws", PartitionStartupValidator.validate("xx-nowhere-9", Optional.empty()).id());
    }

    @Test
    void aBlankPartitionIdMeansUnset() {
        assertEquals("aws-us-gov", PartitionStartupValidator.validate("us-gov-west-1", Optional.of("  ")).id());
    }
}
