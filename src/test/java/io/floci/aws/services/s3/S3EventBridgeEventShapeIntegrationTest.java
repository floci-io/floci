package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

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

    @ParameterizedTest
    @CsvSource({
            "us-east-1,aws,PutObject",
            "us-east-1,aws,POST Object",
            "us-east-1,aws,CopyObject",
            "us-east-1,aws,CompleteMultipartUpload",
            "cn-north-1,aws-cn,PutObject"
    })
    void creationEventCarriesApiReasonAndBucketArn(String regionId, String partition, String reason) throws Exception {
        Region region = Region.of(regionId);
        String name = "s3-eb-shape-" + UUID.randomUUID();
        String bucketArn = "arn:" + partition + ":s3:::" + name;
        S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, region);
        cleanup.register(s3::close);
        SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, region);
        cleanup.register(sqs::close);
        EventBridgeClient events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, region);
        cleanup.register(events::close);
        s3.createBucket(request -> {
            request.bucket(name);
            if (!region.equals(Region.US_EAST_1)) {
                request.createBucketConfiguration(configuration -> configuration.locationConstraint(regionId));
            }
        });
        cleanup.register(() -> s3.deleteBucket(request -> request.bucket(name)));
        String queueUrl = sqs.createQueue(request -> request.queueName(name)).queueUrl();
        cleanup.register(() -> sqs.deleteQueue(request -> request.queueUrl(queueUrl)));
        String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        events.putRule(request -> request.name(name).eventPattern("{\"source\":[\"aws.s3\"]}"));
        cleanup.register(() -> events.deleteRule(request -> request.name(name)));
        cleanup.register(() -> events.removeTargets(request -> request.rule(name).ids("queue")));
        assertEquals(0, events.putTargets(request -> request.rule(name)
                .targets(Target.builder().id("queue").arn(queueArn).build())).failedEntryCount());
        // Seed copy input before enabling notifications, so the queue receives only the operation under test.
        cleanup.register(() -> s3.deleteObject(request -> request.bucket(name).key("source")));
        s3.putObject(request -> request.bucket(name).key("source"), RequestBody.fromString("content"));
        cleanup.register(() -> s3.putBucketNotificationConfiguration(request -> request.bucket(name)
                .notificationConfiguration(configuration -> {})));
        s3.putBucketNotificationConfiguration(request -> request.bucket(name)
                .notificationConfiguration(configuration -> configuration.eventBridgeConfiguration(bridge -> {})));

        cleanup.register(() -> s3.deleteObject(request -> request.bucket(name).key("created")));
        switch (reason) {
            case "PutObject" -> s3.putObject(request -> request.bucket(name).key("created"),
                    RequestBody.fromString("content"));
            case "POST Object" -> given()
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261121/"
                            + regionId + "/s3/aws4_request, SignedHeaders=host, Signature=test")
                    .multiPart("key", "created")
                    .multiPart("file", "created", "content".getBytes(StandardCharsets.UTF_8), "text/plain")
                    .when().post("/" + name).then().statusCode(204);
            case "CopyObject" -> s3.copyObject(request -> request.copySource(name + "/source")
                    .destinationBucket(name).destinationKey("created"));
            case "CompleteMultipartUpload" -> {
                String uploadId = s3.createMultipartUpload(request -> request.bucket(name).key("created"))
                        .uploadId();
                String etag = s3.uploadPart(request -> request.bucket(name).key("created")
                        .uploadId(uploadId).partNumber(1), RequestBody.fromString("content")).eTag();
                s3.completeMultipartUpload(request -> request.bucket(name).key("created").uploadId(uploadId)
                        .multipartUpload(upload -> upload.parts(CompletedPart.builder()
                                .partNumber(1).eTag(etag).build())));
            }
            default -> throw new AssertionError("Unknown creation API: " + reason);
        }

        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                .waitTimeSeconds(2).maxNumberOfMessages(1)).messages();
        assertEquals(1, messages.size(), "Expected the S3 event through EventBridge and SQS");
        String body = messages.getFirst().body();
        JsonNode event = objectMapper.readTree(body);
        String pattern = "{\"source\":[\"aws.s3\"],\"resources\":[\"" + bucketArn
                + "\"],\"detail\":{\"reason\":[\"" + reason + "\"]}}";
        assertAll(
                () -> assertEquals("Object Created", event.path("detail-type").asText()),
                () -> assertEquals(regionId, event.path("region").asText()),
                () -> assertEquals("created", event.path("detail").path("object").path("key").asText()),
                () -> assertEquals(reason, event.path("detail").path("reason").asText()),
                () -> assertEquals(objectMapper.valueToTree(List.of(bucketArn)), event.path("resources")),
                () -> assertTrue(events.testEventPattern(request -> request.eventPattern(pattern).event(body))
                        .result(), "Consumers must be able to filter on the API reason and bucket ARN"));
    }
}
