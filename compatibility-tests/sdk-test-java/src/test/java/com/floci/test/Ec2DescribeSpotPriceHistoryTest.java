package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2DescribeSpotPriceHistoryTest {

    private static final String ZONE_A = TestFixtures.region().id() + "a";
    private static final String ZONE_B = TestFixtures.region().id() + "b";

    @Test
    void describeSpotPriceHistoryReturnsEntriesForKnownInstanceTypesWithoutFilters() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder().build());

            assertThat(response.spotPriceHistory()).isNotEmpty();
            SpotPrice first = response.spotPriceHistory().get(0);
            assertThat(first.instanceTypeAsString()).isNotBlank();
            assertThat(first.productDescriptionAsString()).isNotBlank();
            assertThat(first.spotPrice()).isNotBlank();
            assertThat(first.timestamp()).isNotNull();
            assertThat(first.availabilityZone()).isNotBlank();
        }
    }

    @Test
    void describeSpotPriceHistoryFiltersByInstanceTypeAvailabilityZoneAndProductDescription() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .instanceTypes(InstanceType.M5_LARGE)
                            .productDescriptions("Linux/UNIX")
                            .availabilityZone(ZONE_A)
                            .build());

            assertThat(response.spotPriceHistory()).hasSize(1);
            SpotPrice entry = response.spotPriceHistory().get(0);
            assertThat(entry.instanceType()).isEqualTo(InstanceType.M5_LARGE);
            assertThat(entry.productDescriptionAsString()).isEqualTo("Linux/UNIX");
            assertThat(entry.availabilityZone()).isEqualTo(ZONE_A);
            assertThat(Double.parseDouble(entry.spotPrice())).isPositive();
        }
    }

    @Test
    void describeSpotPriceHistoryFiltersUsingGenericFilters() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .filters(
                                    Filter.builder().name("instance-type").values("t3.micro").build(),
                                    Filter.builder().name("availability-zone").values(ZONE_B).build(),
                                    Filter.builder().name("product-description").values("Linux/UNIX*").build()
                            )
                            .build());

            assertThat(response.spotPriceHistory()).isNotEmpty();
            for (SpotPrice entry : response.spotPriceHistory()) {
                assertThat(entry.instanceTypeAsString()).isEqualTo("t3.micro");
                assertThat(entry.availabilityZone()).isEqualTo(ZONE_B);
                assertThat(entry.productDescriptionAsString()).startsWith("Linux/UNIX");
            }
        }
    }

    @Test
    void describeSpotPriceHistoryReturnsEmptyForUnknownInstanceType() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .instanceTypes(InstanceType.fromValue("z99.custom"))
                            .build());

            assertThat(response.spotPriceHistory()).isEmpty();
            assertThat(response.nextToken()).isEmpty();
        }
    }

    @Test
    void describeSpotPriceHistoryReturnsStableIdenticalPrices() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
                    .instanceTypes(InstanceType.M5_LARGE)
                    .productDescriptions("Linux/UNIX")
                    .availabilityZone(ZONE_A)
                    .build();

            DescribeSpotPriceHistoryResponse firstResponse = ec2.describeSpotPriceHistory(request);
            DescribeSpotPriceHistoryResponse secondResponse = ec2.describeSpotPriceHistory(request);

            assertThat(firstResponse.spotPriceHistory()).hasSize(1);
            assertThat(secondResponse.spotPriceHistory()).hasSize(1);
            assertThat(firstResponse.spotPriceHistory().get(0).spotPrice())
                    .isEqualTo(secondResponse.spotPriceHistory().get(0).spotPrice());
        }
    }

    @Test
    void describeSpotPriceHistoryPriceIsBelowOnDemandAndMonotonicWithCapacity() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .instanceTypes(InstanceType.T3_MICRO, InstanceType.T3_SMALL, InstanceType.M5_LARGE)
                            .productDescriptions("Linux/UNIX")
                            .availabilityZone(ZONE_A)
                            .build());

            List<SpotPrice> items = response.spotPriceHistory();
            assertThat(items).hasSize(3);

            SpotPrice t3Micro = items.stream()
                    .filter(i -> i.instanceType() == InstanceType.T3_MICRO)
                    .findFirst().orElseThrow();
            SpotPrice t3Small = items.stream()
                    .filter(i -> i.instanceType() == InstanceType.T3_SMALL)
                    .findFirst().orElseThrow();
            SpotPrice m5Large = items.stream()
                    .filter(i -> i.instanceType() == InstanceType.M5_LARGE)
                    .findFirst().orElseThrow();

            double t3Price = Double.parseDouble(t3Micro.spotPrice());
            double t3SmallPrice = Double.parseDouble(t3Small.spotPrice());
            double m5Price = Double.parseDouble(m5Large.spotPrice());

            // Known on-demand prices for us-east-1:
            // t3.micro = $0.0104, m5.large = $0.0960
            assertThat(t3Price).isLessThan(0.0104);
            assertThat(m5Price).isLessThan(0.0960);

            // Larger instances cost more
            assertThat(t3Price).isLessThan(t3SmallPrice);
            assertThat(t3SmallPrice).isLessThan(m5Price);
        }
    }

    @Test
    void describeSpotPriceHistorySupportsPagination() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse page1 = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .maxResults(2)
                            .build());

            assertThat(page1.spotPriceHistory()).hasSize(2);
            assertThat(page1.nextToken()).isNotBlank();

            DescribeSpotPriceHistoryResponse page2 = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .maxResults(2)
                            .nextToken(page1.nextToken())
                            .build());

            assertThat(page2.spotPriceHistory()).hasSize(2);
            assertThat(page1.spotPriceHistory()).doesNotContainAnyElementsOf(page2.spotPriceHistory());
        }
    }

    @Test
    void describeSpotPriceHistorySupportsContinuationWithNextTokenOnly() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse page1 = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .maxResults(2)
                            .build());

            assertThat(page1.spotPriceHistory()).hasSize(2);
            assertThat(page1.nextToken()).isNotBlank();

            DescribeSpotPriceHistoryResponse continuation = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .nextToken(page1.nextToken())
                            .build());

            assertThat(continuation.spotPriceHistory()).isNotEmpty();
            assertThat(page1.spotPriceHistory()).doesNotContainAnyElementsOf(continuation.spotPriceHistory());
        }
    }

    @Test
    void describeSpotPriceHistoryReturnsPricesForWindowsPlatform() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .instanceTypes(InstanceType.M5_LARGE)
                            .productDescriptions("Windows")
                            .availabilityZone(ZONE_A)
                            .build());

            assertThat(response.spotPriceHistory()).hasSize(1);
            SpotPrice entry = response.spotPriceHistory().get(0);
            assertThat(entry.instanceType()).isEqualTo(InstanceType.M5_LARGE);
            assertThat(entry.productDescriptionAsString()).isEqualTo("Windows");
            assertThat(entry.availabilityZone()).isEqualTo(ZONE_A);
            assertThat(Double.parseDouble(entry.spotPrice())).isPositive();
        }
    }

    @Test
    void describeSpotPriceHistoryReturnsEmptyForFutureStartTime() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                            .startTime(Instant.now().plusSeconds(86400))
                            .build());

            assertThat(response.spotPriceHistory()).isEmpty();
            assertThat(response.nextToken()).isEmpty();
        }
    }

    @Test
    void describeSpotPriceHistoryRejectsInvalidNextToken() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String invalidToken = Base64.getEncoder().encodeToString(
                    "{\"offset\":-1}".getBytes(StandardCharsets.UTF_8));
            Ec2Exception exception = assertThrows(Ec2Exception.class,
                    () -> ec2.describeSpotPriceHistory(DescribeSpotPriceHistoryRequest.builder()
                            .nextToken(invalidToken)
                            .build()));

            assertThat(exception.statusCode()).isEqualTo(400);
            assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
        }
    }

    @Test
    void describeSpotPriceHistoryRejectsInvalidProductDescription() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception exception = assertThrows(Ec2Exception.class,
                    () -> ec2.describeSpotPriceHistory(DescribeSpotPriceHistoryRequest.builder()
                            .productDescriptions("InvalidOperatingSystem")
                            .build()));

            assertThat(exception.statusCode()).isEqualTo(400);
            assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
        }
    }

    @Test
    void describeSpotPriceHistoryRejectsStartTimeAfterEndTime() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Instant now = Instant.now();
            Ec2Exception exception = assertThrows(Ec2Exception.class,
                    () -> ec2.describeSpotPriceHistory(DescribeSpotPriceHistoryRequest.builder()
                            .startTime(now.plusSeconds(3600))
                            .endTime(now)
                            .build()));

            assertThat(exception.statusCode()).isEqualTo(400);
            assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
        }
    }

    @Test
    void describeSpotPriceHistoryHonorsDryRun() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception exception = assertThrows(Ec2Exception.class,
                    () -> ec2.describeSpotPriceHistory(DescribeSpotPriceHistoryRequest.builder()
                            .dryRun(true)
                            .build()));

            assertThat(exception.statusCode()).isEqualTo(412);
            assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("DryRunOperation");
        }
    }
}
