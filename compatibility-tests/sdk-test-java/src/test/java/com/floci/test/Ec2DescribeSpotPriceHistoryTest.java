package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2DescribeSpotPriceHistoryTest {

    @Test
    void describeSpotPriceHistoryReturnsEmptyHistoryWhenNoSnapshotIsConfigured() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            var response = ec2.describeSpotPriceHistory(DescribeSpotPriceHistoryRequest.builder()
                    .instanceTypes(InstanceType.M5_LARGE, InstanceType.fromValue("t4g.medium"))
                    .productDescriptions("Linux/UNIX")
                    .availabilityZone("us-east-1a")
                    .startTime(Instant.parse("2026-01-01T00:00:00Z"))
                    .endTime(Instant.parse("2026-01-02T00:00:00Z"))
                    .maxResults(100)
                    .build());

            assertThat(response.spotPriceHistory()).isEmpty();
        }
    }
}
