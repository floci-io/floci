package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EventRuleQueue;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3EventBridgeEventShapeIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @Inject
    ObjectMapper objectMapper;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({
            "us-east-1,aws,PutObject,PutObject,Object Created",
            "us-east-1,aws,POST Object,POST Object,Object Created",
            "us-east-1,aws,CopyObject,CopyObject,Object Created",
            "us-east-1,aws,CompleteMultipartUpload,CompleteMultipartUpload,Object Created",
            "cn-north-1,aws-cn,PutObject,PutObject,Object Created",
            "us-east-1,aws,DeleteObject,DeleteObject,Object Deleted",
            "us-east-1,aws,DeleteMarker,DeleteObject,Object Deleted",
            "us-east-1,aws,PutObjectAnnotation,PutObjectAnnotation,Object Annotation Created",
            "us-east-1,aws,DeleteObjectAnnotation,DeleteObjectAnnotation,Object Annotation Removed"
    })
    void eventCarriesApiReasonAndBucketArn(String regionId, String partition, String operation,
                                           String reason, String detailType) throws Exception {
        Region region = Region.of(regionId);
        String name = "s3-eb-shape-" + UUID.randomUUID();
        String bucketArn = "arn:" + partition + ":s3:::" + name;
        String authorization = EnforcementFixtures.auth(ACCOUNT, regionId, "s3");
        String pattern = "{\"source\":[\"aws.s3\"],\"detail\":{\"bucket\":{\"name\":[\"" + name + "\"]}}}";
        try (S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, region);
                EventBridgeClient events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, region);
                EventRuleQueue queue = EventRuleQueue.route(ACCOUNT, regionId, name, pattern)) {
            s3.createBucket(request -> {
                request.bucket(name);
                if (!region.equals(Region.US_EAST_1)) {
                    request.createBucketConfiguration(configuration -> configuration.locationConstraint(regionId));
                }
            });
            int port = RestAssured.port;
            cleanup.register(() -> EnforcementFixtures.removeBucket(port, authorization, name));
            if (operation.equals("DeleteMarker")) {
                s3.putBucketVersioning(request -> request.bucket(name)
                        .versioningConfiguration(configuration -> configuration.status("Enabled")));
            }
            // Seed inputs before enabling notifications so only the operation under test is delivered.
            s3.putObject(request -> request.bucket(name).key("source"), RequestBody.fromString("content"));
            if (operation.startsWith("Delete") || operation.equals("PutObjectAnnotation")) {
                s3.putObject(request -> request.bucket(name).key("created"), RequestBody.fromString("content"));
            }
            if (operation.equals("DeleteObjectAnnotation")) {
                given().header("Authorization", authorization).queryParam("annotation", "")
                        .queryParam("annotationName", "note").body("{}").put("/" + name + "/created")
                        .then().statusCode(200);
            }
            s3.putBucketNotificationConfiguration(request -> request.bucket(name)
                    .notificationConfiguration(configuration -> configuration.eventBridgeConfiguration(bridge -> {})));

            switch (operation) {
                case "PutObject" -> s3.putObject(request -> request.bucket(name).key("created"),
                        RequestBody.fromString("content"));
                case "POST Object" -> given().header("Authorization", authorization)
                        .multiPart("key", "created")
                        .multiPart("file", "created", "content".getBytes(StandardCharsets.UTF_8), "text/plain")
                        .when().post("/" + name).then().statusCode(204);
                case "CopyObject" -> s3.copyObject(request -> request.copySource(name + "/source")
                        .destinationBucket(name).destinationKey("created"));
                case "CompleteMultipartUpload" -> {
                    String uploadId = s3.createMultipartUpload(request -> request.bucket(name).key("created")).uploadId();
                    String etag = s3.uploadPart(request -> request.bucket(name).key("created")
                            .uploadId(uploadId).partNumber(1), RequestBody.fromString("content")).eTag();
                    s3.completeMultipartUpload(request -> request.bucket(name).key("created").uploadId(uploadId)
                            .multipartUpload(upload -> upload.parts(CompletedPart.builder()
                                    .partNumber(1).eTag(etag).build())));
                }
                case "DeleteObject", "DeleteMarker" -> s3.deleteObject(request -> request.bucket(name).key("created"));
                case "PutObjectAnnotation" -> given().header("Authorization", authorization).queryParam("annotation", "")
                        .queryParam("annotationName", "note").body("{}").put("/" + name + "/created")
                        .then().statusCode(200);
                case "DeleteObjectAnnotation" -> given().header("Authorization", authorization).queryParam("annotation", "")
                        .queryParam("annotationName", "note").delete("/" + name + "/created").then().statusCode(204);
                default -> throw new AssertionError("Unknown S3 API: " + operation);
            }

            List<JsonNode> messages = queue.drain();
            assertEquals(1, messages.size(), "Expected the S3 event through EventBridge and SQS");
            JsonNode event = messages.getFirst();
            String consumerPattern = "{\"source\":[\"aws.s3\"],\"resources\":[\"" + bucketArn
                    + "\"],\"detail\":{\"reason\":[\"" + reason + "\"]}}";
            assertAll(
                    () -> assertEquals(detailType, event.path("detail-type").asText()),
                    () -> assertEquals(regionId, event.path("region").asText()),
                    () -> assertEquals("created", event.path("detail").path("object").path("key").asText()),
                    () -> assertEquals(reason, event.path("detail").path("reason").asText()),
                    () -> assertEquals(objectMapper.valueToTree(List.of(bucketArn)), event.path("resources")),
                    () -> assertTrue(events.testEventPattern(request -> request.eventPattern(consumerPattern)
                            .event(event.toString())).result(), "Consumers must be able to filter on the API reason and bucket ARN"));
        }
    }
}
