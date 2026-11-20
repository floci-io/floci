package io.floci.aws.services.s3vectors;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.AccessDeniedException;
import software.amazon.awssdk.services.s3vectors.model.NotFoundException;
import software.amazon.awssdk.services.s3vectors.model.ValidationException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3VectorsTaggingIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkTagsMergeAndRemoveWithoutChangingTheOtherResource(boolean index) {
        try (S3VectorsClient client = client("us-east-1"); Fixture fixture = fixture(client)) {
            String arn = index ? fixture.indexArn() : fixture.bucketArn();
            String otherArn = index ? fixture.bucketArn() : fixture.indexArn();
            assertEquals(Map.of("created", "original", "keep", "value"),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            assertEquals(200, client.tagResource(request -> request.resourceArn(arn)
                    .tags(Map.of("created", "updated", "remove", "yes", "empty", ""))).sdkHttpResponse().statusCode());
            assertEquals(Map.of("created", "updated", "keep", "value", "remove", "yes", "empty", ""),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            assertEquals(200, client.untagResource(request -> request.resourceArn(arn)
                    .tagKeys("remove", "absent")).sdkHttpResponse().statusCode());
            assertEquals(Map.of("created", "updated", "keep", "value", "empty", ""),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            assertEquals(Map.of("created", "original", "keep", "value"),
                    client.listTagsForResource(request -> request.resourceArn(otherArn)).tags());
            client.untagResource(request -> request.resourceArn(arn).tagKeys("created", "keep", "empty"));
            assertEquals(Map.of(), client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            client.tagResource(request -> request.resourceArn(arn).tags(Map.of()));
            assertThrows(ValidationException.class,
                    () -> client.untagResource(request -> request.resourceArn(arn).tagKeys(List.of())));
            assertTrue(client.listTagsForResource(request -> request.resourceArn(arn)).tags().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quotaCountsMergedKeysAndFailedUpdatesAreAtomic(boolean index) {
        try (S3VectorsClient client = client("us-east-1"); Fixture fixture = fixture(client)) {
            String arn = index ? fixture.indexArn() : fixture.bucketArn();
            Map<String, String> tags = new LinkedHashMap<>();
            for (int i = 0; i < 48; i++) {
                tags.put("key" + i, "value");
            }
            client.tagResource(request -> request.resourceArn(arn).tags(tags));
            Map<String, String> before = client.listTagsForResource(request -> request.resourceArn(arn)).tags();
            assertEquals(50, before.size());
            ValidationException overLimit = assertThrows(ValidationException.class,
                    () -> client.tagResource(request -> request.resourceArn(arn)
                            .tags(Map.of("overflow", "value", "created", "should-not-change"))));
            assertEquals("1 validation error detected: Value at 'tags' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 50", overLimit.awsErrorDetails().errorMessage());
            assertEquals(before, client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            client.tagResource(request -> request.resourceArn(arn).tags(Map.of("created", "replaced")));
            assertEquals("replaced", client.listTagsForResource(request -> request.resourceArn(arn)).tags().get("created"));
            client.untagResource(request -> request.resourceArn(arn).tagKeys("key0"));
            client.tagResource(request -> request.resourceArn(arn).tags(Map.of("new", "value")));
            assertEquals(50, client.listTagsForResource(request -> request.resourceArn(arn)).tags().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidTagsAndMissingResourcesReturnModeledErrors(boolean index) {
        try (S3VectorsClient client = client("us-east-1"); Fixture fixture = fixture(client)) {
            String arn = index ? fixture.indexArn() : fixture.bucketArn();
            for (Map<String, String> tags : List.of(
                    Map.of("", "value"), Map.of("x".repeat(129), "value"), Map.of("invalid!", "value"),
                    Map.of("aws:reserved", "value"), Map.of("valid", "x".repeat(257)), Map.of("valid", "invalid!"))) {
                ValidationException invalid = assertThrows(ValidationException.class,
                        () -> client.tagResource(request -> request.resourceArn(arn).tags(tags)));
                assertTrue(invalid.awsErrorDetails().errorMessage().startsWith("1 validation error detected: "));
                assertTrue(invalid.awsErrorDetails().errorMessage().contains("'tags."));
            }
            ValidationException invalidKey = assertThrows(ValidationException.class,
                    () -> client.untagResource(request -> request.resourceArn(arn).tagKeys("")));
            assertTrue(invalidKey.awsErrorDetails().errorMessage().startsWith("1 validation error detected: "));
            assertTrue(invalidKey.awsErrorDetails().errorMessage().contains("'tagKeys'"));
            assertEquals(Map.of("created", "original", "keep", "value"),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            String missing = index ? fixture.bucketArn() + "/index/missing" : fixture.bucketArn() + "-missing";
            String missingMessage = index ? "The specified index could not be found"
                    : "The specified vector bucket could not be found";
            NotFoundException missingList = assertThrows(NotFoundException.class,
                    () -> client.listTagsForResource(request -> request.resourceArn(missing)));
            NotFoundException missingTag = assertThrows(NotFoundException.class,
                    () -> client.tagResource(request -> request.resourceArn(missing).tags(Map.of("key", "value"))));
            NotFoundException missingUntag = assertThrows(NotFoundException.class,
                    () -> client.untagResource(request -> request.resourceArn(missing).tagKeys("key")));
            assertEquals(missingMessage, missingList.awsErrorDetails().errorMessage());
            assertEquals(missingMessage, missingTag.awsErrorDetails().errorMessage());
            assertEquals(missingMessage, missingUntag.awsErrorDetails().errorMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "cn-north-1"})
    void taggingUsesTheResourceArnInOtherPartitions(String region) {
        try (S3VectorsClient client = client(region); Fixture fixture = fixture(client)) {
            for (String arn : List.of(fixture.bucketArn(), fixture.indexArn())) {
                client.tagResource(request -> request.resourceArn(arn).tags(Map.of("partition", region)));
                assertEquals(region, client.listTagsForResource(request -> request.resourceArn(arn)).tags().get("partition"));
                client.untagResource(request -> request.resourceArn(arn).tagKeys("partition"));
                assertFalse(client.listTagsForResource(request -> request.resourceArn(arn)).tags().containsKey("partition"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalResourceNamesInDifferentAccountsKeepTheirOwnTags(boolean index) {
        String name = "tag-accounts-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3VectorsClient firstClient = client("us-east-1", "111111111111");
                S3VectorsClient secondClient = client("us-east-1", "222222222222");
                Fixture first = fixture(firstClient, name);
                Fixture second = fixture(secondClient, name)) {
            String firstArn = index ? first.indexArn() : first.bucketArn();
            String secondArn = index ? second.indexArn() : second.bucketArn();
            firstClient.tagResource(request -> request.resourceArn(firstArn).tags(Map.of("owner", "first")));
            secondClient.tagResource(request -> request.resourceArn(secondArn).tags(Map.of("owner", "second")));
            Map<String, String> firstTags = firstClient.listTagsForResource(request -> request.resourceArn(firstArn)).tags();
            Map<String, String> secondTags = secondClient.listTagsForResource(request -> request.resourceArn(secondArn)).tags();
            assertEquals("first", firstTags.get("owner"));
            assertEquals("second", secondTags.get("owner"));

            assertThrows(AccessDeniedException.class, () -> firstClient.listTagsForResource(request -> request.resourceArn(secondArn)));
            assertThrows(AccessDeniedException.class, () -> firstClient.tagResource(request -> request
                    .resourceArn(secondArn).tags(Map.of("owner", "changed"))));
            assertThrows(AccessDeniedException.class, () -> firstClient.untagResource(request -> request
                    .resourceArn(secondArn).tagKeys("owner")));
            assertEquals(firstTags, firstClient.listTagsForResource(request -> request.resourceArn(firstArn)).tags());
            assertEquals(secondTags, secondClient.listTagsForResource(request -> request.resourceArn(secondArn)).tags());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "us-gov-west-1", "cn-north-1"})
    void tagOperationsRejectResourcesOutsideTheRequestRegion(String resourceRegion) {
        try (S3VectorsClient eastClient = client("us-east-1");
                S3VectorsClient owner = client(resourceRegion);
                Fixture fixture = fixture(owner)) {
            for (String arn : List.of(fixture.bucketArn(), fixture.indexArn())) {
                Map<String, String> before = owner.listTagsForResource(request -> request.resourceArn(arn)).tags();
                assertThrows(ValidationException.class, () -> eastClient.listTagsForResource(request -> request.resourceArn(arn)));
                assertThrows(ValidationException.class, () -> eastClient.tagResource(request -> request
                        .resourceArn(arn).tags(Map.of("created", "changed"))));
                assertThrows(ValidationException.class, () -> eastClient.untagResource(request -> request
                        .resourceArn(arn).tagKeys("created")));
                assertEquals(before, owner.listTagsForResource(request -> request.resourceArn(arn)).tags());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagOperationsRejectAnArnWithTheWrongPartition(boolean index) {
        try (S3VectorsClient client = client("us-east-1"); Fixture fixture = fixture(client)) {
            String arn = index ? fixture.indexArn() : fixture.bucketArn();
            String invalid = arn.replace("arn:aws:", "arn:aws-cn:");
            assertThrows(ValidationException.class, () -> client.listTagsForResource(request -> request.resourceArn(invalid)));
            assertThrows(ValidationException.class, () -> client.tagResource(request -> request
                    .resourceArn(invalid).tags(Map.of("created", "changed"))));
            assertThrows(ValidationException.class, () -> client.untagResource(request -> request
                    .resourceArn(invalid).tagKeys("created")));
            assertEquals(Map.of("created", "original", "keep", "value"),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedJsonDoesNotChangeTags(boolean index) {
        try (S3VectorsClient client = client("us-east-1"); Fixture fixture = fixture(client)) {
            String arn = index ? fixture.indexArn() : fixture.bucketArn();
            String path = "tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
            Map<String, String> before = client.listTagsForResource(request -> request.resourceArn(arn)).tags();
            for (String trailing : List.of("garbage", "{}", "null")) {
                given().urlEncodingEnabled(false).contentType("application/json")
                        .body("{\"tags\":{\"created\":\"changed\"}} " + trailing)
                        .when().post(endpoint.resolve(path))
                        .then().statusCode(400).body(containsString("ValidationException"));
                assertEquals(before, client.listTagsForResource(request -> request.resourceArn(arn)).tags());
            }
        }
    }

    private Fixture fixture(S3VectorsClient client) {
        String name = "tag-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        return fixture(client, name);
    }

    private Fixture fixture(S3VectorsClient client, String name) {
        Map<String, String> tags = Map.of("created", "original", "keep", "value");
        String bucketArn = client.createVectorBucket(request -> request.vectorBucketName(name).tags(tags)).vectorBucketArn();
        try {
            String indexArn = client.createIndex(request -> request.vectorBucketName(name).indexName("index")
                    .dimension(2).dataType("float32").distanceMetric("euclidean").tags(tags)).indexArn();
            return new Fixture(client, name, bucketArn, indexArn);
        } catch (RuntimeException | Error failure) {
            try {
                client.deleteVectorBucket(request -> request.vectorBucketName(name));
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private S3VectorsClient client(String region) {
        return client(region, "test");
    }

    private S3VectorsClient client(String region, String account) {
        return S3VectorsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private record Fixture(S3VectorsClient client, String name, String bucketArn, String indexArn) implements AutoCloseable {
        @Override
        public void close() {
            client.deleteIndex(request -> request.vectorBucketName(name).indexName("index"));
            client.deleteVectorBucket(request -> request.vectorBucketName(name));
        }
    }
}
