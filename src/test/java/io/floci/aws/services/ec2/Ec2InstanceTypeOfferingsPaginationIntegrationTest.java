package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.floci.aws.testing.Ec2Requests.ec2;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2InstanceTypeOfferingsPaginationIntegrationTest {

    @Test
    void maxResultsLimitsPagesAndContinuationReturnsEveryOfferingOnce() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            DescribeInstanceTypeOfferingsResponse page = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(5));
            assertEquals(5, page.instanceTypeOfferings().size(), "MaxResults must limit the first page");
            assertTrue(page.nextToken() != null && !page.nextToken().isBlank(),
                    "A partial result must include NextToken");
        }
        assertCompleteTraversal(DescribeInstanceTypeOfferingsRequest.builder()
                .locationType(LocationType.REGION).build());
    }

    @Test
    void singleTypeFilterReturnsCompleteResultWithoutContinuation() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            DescribeInstanceTypeOfferingsResponse response = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(5)
                    .filters(f -> f.name("instance-type").values("t3.micro")));

            assertEquals(1, response.instanceTypeOfferings().size());
            assertEquals("t3.micro", response.instanceTypeOfferings().getFirst().instanceTypeAsString());
            assertTrue(response.nextToken() == null || response.nextToken().isBlank(),
                    "A complete filtered result must not include NextToken");
        }
    }

    @Test
    void availabilityZonePagesKeepDistinctLocationsForTheSameInstanceType() {
        assertCompleteTraversal(DescribeInstanceTypeOfferingsRequest.builder()
                .locationType(LocationType.AVAILABILITY_ZONE).build());
    }

    @Test
    void filtersApplyBeforePaginationOnEveryPage() {
        assertCompleteTraversal(DescribeInstanceTypeOfferingsRequest.builder()
                .locationType(LocationType.AVAILABILITY_ZONE)
                .filters(f -> f.name("instance-type").values("t3.*"),
                        f -> f.name("location").values("us-east-1a", "us-east-1b")).build());
    }

    @Test
    void emptyFilteredResultHasNoContinuation() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            DescribeInstanceTypeOfferingsResponse response = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(5)
                    .filters(f -> f.name("location").values("eu-west-1")));
            assertTrue(response.instanceTypeOfferings().isEmpty());
            assertTrue(response.nextToken() == null || response.nextToken().isBlank());
        }
    }

    @Test
    void maximumPageSizeReturnsCompleteCatalogue() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeOffering> expected = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION)).instanceTypeOfferings();
            DescribeInstanceTypeOfferingsResponse response = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(1000));
            assertEquals(new HashSet<>(expected), new HashSet<>(response.instanceTypeOfferings()));
            assertTrue(response.nextToken() == null || response.nextToken().isBlank());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4, 1001})
    void invalidPageSizeReturnsAwsError(int maxResults) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(maxResults)));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidMaxResults", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void malformedTokenReturnsAwsError() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(5).nextToken("not-a-valid-token!")));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void continuationWithoutPageSizeReturnsRemainingOfferings() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeOffering> expected = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION)).instanceTypeOfferings();
            DescribeInstanceTypeOfferingsResponse first = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(5));
            assertTrue(first.nextToken() != null && !first.nextToken().isBlank());
            DescribeInstanceTypeOfferingsResponse rest = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).nextToken(first.nextToken()));
            List<InstanceTypeOffering> collected = new ArrayList<>(first.instanceTypeOfferings());
            collected.addAll(rest.instanceTypeOfferings());
            assertEquals(expected.size(), collected.size());
            assertEquals(new HashSet<>(expected), new HashSet<>(collected));
            assertTrue(rest.nextToken() == null || rest.nextToken().isBlank());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add-filter", "remove-filter", "change-filter", "add-location-filter",
            "change-location-type", "change-region"})
    void changedSelectionRejectsContinuation(String change) {
        DescribeInstanceTypeOfferingsRequest.Builder original = DescribeInstanceTypeOfferingsRequest.builder()
                .locationType(LocationType.REGION).maxResults(5);
        if (change.equals("remove-filter") || change.equals("change-filter")) {
            original.filters(f -> f.name("instance-type").values("*"));
        }
        try (Ec2Client source = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1);
                Ec2Client destination = AwsTestClients.create(Ec2Client.builder(), "test",
                        change.equals("change-region") ? Region.EU_WEST_1 : Region.US_EAST_1)) {
            DescribeInstanceTypeOfferingsResponse first = source.describeInstanceTypeOfferings(original.build());
            assertTrue(first.nextToken() != null && !first.nextToken().isBlank());
            DescribeInstanceTypeOfferingsRequest.Builder continuation = original.nextToken(first.nextToken());
            DescribeInstanceTypeOfferingsRequest changed = switch (change) {
                case "add-filter" -> continuation.filters(f -> f.name("instance-type").values("t3.*")).build();
                case "remove-filter" -> continuation.filters(List.of()).build();
                case "change-filter" -> continuation.filters(f -> f.name("instance-type").values("t4g.*")).build();
                case "add-location-filter" -> continuation.filters(f -> f.name("location").values("us-east-1")).build();
                case "change-location-type" -> continuation.locationType(LocationType.AVAILABILITY_ZONE).build();
                case "change-region" -> continuation.build();
                default -> throw new IllegalArgumentException(change);
            };
            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> destination.describeInstanceTypeOfferings(changed));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidPaginationToken.DataMismatch", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void continuationAllowsChangingPageSize() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            DescribeInstanceTypeOfferingsRequest request = DescribeInstanceTypeOfferingsRequest.builder()
                    .locationType(LocationType.REGION).maxResults(5).build();
            DescribeInstanceTypeOfferingsResponse first = ec2.describeInstanceTypeOfferings(request);
            assertTrue(first.nextToken() != null && !first.nextToken().isBlank());
            DescribeInstanceTypeOfferingsRequest continuation = request.toBuilder().nextToken(first.nextToken()).build();
            List<InstanceTypeOffering> expected = ec2.describeInstanceTypeOfferings(continuation).instanceTypeOfferings();
            List<InstanceTypeOffering> largerPage = ec2.describeInstanceTypeOfferings(continuation.toBuilder()
                    .maxResults(10).build()).instanceTypeOfferings();
            assertEquals(10, largerPage.size());
            assertEquals(expected, largerPage.subList(0, 5));
            assertTrue(first.instanceTypeOfferings().stream().noneMatch(largerPage::contains));
        }
    }

    @Test
    void changedExplicitInstanceTypesRejectContinuation() {
        String token = ec2("DescribeInstanceTypeOfferings", "test", "us-east-1")
                .formParam("LocationType", "region").formParam("MaxResults", 5)
                .post("/").then().statusCode(200)
                .extract().xmlPath().getString("DescribeInstanceTypeOfferingsResponse.nextToken");
        assertTrue(token != null && !token.isBlank());
        ec2("DescribeInstanceTypeOfferings", "test", "us-east-1")
                .formParam("LocationType", "region").formParam("MaxResults", 5)
                .formParam("NextToken", token).formParam("InstanceType.1", "t3.micro")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidPaginationToken.DataMismatch"));
    }

    @Test
    void nonIntegerPageSizeReturnsXmlError() {
        ec2("DescribeInstanceTypeOfferings", "test", "us-east-1")
                .formParam("MaxResults", "five")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidMaxResults"));
    }

    private void assertCompleteTraversal(DescribeInstanceTypeOfferingsRequest request) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeOffering> expected = ec2.describeInstanceTypeOfferings(request).instanceTypeOfferings();
            assertTrue(expected.size() > 5, "The filtered catalogue must contain more than one page");
            List<InstanceTypeOffering> collected = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String token = null;
            do {
                DescribeInstanceTypeOfferingsResponse page = ec2.describeInstanceTypeOfferings(request.toBuilder()
                        .maxResults(5).nextToken(token).build());
                assertFalse(page.instanceTypeOfferings().isEmpty());
                assertTrue(page.instanceTypeOfferings().size() <= 5);
                assertTrue(expected.containsAll(page.instanceTypeOfferings()), "Every page must satisfy the filters");
                collected.addAll(page.instanceTypeOfferings());
                assertTrue(collected.size() <= expected.size());
                token = page.nextToken();
                if (token != null && !token.isBlank()) {
                    assertTrue(tokens.add(token), "Continuation tokens must advance");
                }
            } while (token != null && !token.isBlank());
            assertEquals(expected.size(), collected.size());
            assertEquals(new HashSet<>(expected), new HashSet<>(collected));
        }
    }
}
