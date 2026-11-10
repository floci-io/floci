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
            List<InstanceTypeOffering> expected = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION)).instanceTypeOfferings();
            assertTrue(expected.size() > 5, "The catalogue must contain more than one page");

            DescribeInstanceTypeOfferingsResponse page = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.REGION).maxResults(5));
            assertEquals(5, page.instanceTypeOfferings().size(), "MaxResults must limit the first page");
            assertTrue(page.nextToken() != null && !page.nextToken().isBlank(),
                    "A partial result must include NextToken");

            List<InstanceTypeOffering> collected = new ArrayList<>(page.instanceTypeOfferings());
            Set<String> tokens = new HashSet<>();
            while (page.nextToken() != null && !page.nextToken().isBlank()) {
                String nextToken = page.nextToken();
                assertTrue(tokens.add(nextToken), "Continuation tokens must advance");
                page = ec2.describeInstanceTypeOfferings(r -> r
                        .locationType(LocationType.REGION).maxResults(5).nextToken(nextToken));
                assertFalse(page.instanceTypeOfferings().isEmpty(), "Continuation must return remaining offerings");
                assertTrue(page.instanceTypeOfferings().size() <= 5, "MaxResults must limit every page");
                collected.addAll(page.instanceTypeOfferings());
                assertTrue(collected.size() <= expected.size(), "Pagination must terminate within the catalogue");
            }

            assertEquals(expected.size(), collected.size(), "Pagination must return each offering once");
            assertEquals(new HashSet<>(expected), new HashSet<>(collected),
                    "Pagination must neither omit nor invent offerings");
        }
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
