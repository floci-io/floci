package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@QuarkusTest
class S3NotificationEventIntegrationTest {
    @InjectSpy SqsService sqs;
    @InjectSpy SnsService sns;

    @Test
    void queueAndTopicCopiesKeepOperationMetadataAndTheirOwnRuleIds() throws Exception {
        String name = "notification-fields-" + UUID.randomUUID();
        String region = "us-east-1";
        String queue = sqs.createQueue(name, Map.of(), region).getQueueUrl();
        String queueArn = sqs.getQueueAttributes(queue, List.of("QueueArn"), region).get("QueueArn");
        String topicArn = sns.createTopic(name, Map.of(), Map.of(), region).getTopicArn();
        given().put("/" + name).then().statusCode(200);
        try {
            given().header("x-amz-skip-destination-validation", "true")
                    .body("<NotificationConfiguration>"
                            + "<QueueConfiguration><Id>queue-rule</Id><Queue>" + queueArn
                            + "</Queue><Event>s3:ObjectCreated:*</Event></QueueConfiguration>"
                            + "<TopicConfiguration><Id>topic-rule</Id><Topic>" + topicArn
                            + "</Topic><Event>s3:ObjectCreated:*</Event></TopicConfiguration>"
                            + "</NotificationConfiguration>")
                    .put("/" + name + "?notification").then().statusCode(200);
            given().body("payload").put("/" + name + "/a%20b%2Bc.txt").then().statusCode(200);

            ArgumentCaptor<String> queueBody = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> topicBody = ArgumentCaptor.forClass(String.class);
            verify(sqs).sendMessage(eq(queue), queueBody.capture(), eq(0), eq(region));
            verify(sns).publish(eq(topicArn), isNull(), topicBody.capture(),
                    eq("Amazon S3 Notification"), eq(region));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode queueEvent = mapper.readTree(queueBody.getValue()).path("Records").get(0);
            JsonNode topicEvent = mapper.readTree(topicBody.getValue()).path("Records").get(0);
            assertEquals("queue-rule", queueEvent.path("s3").path("configurationId").asText());
            assertEquals("topic-rule", topicEvent.path("s3").path("configurationId").asText());
            assertFalse(queueEvent.path("responseElements").path("x-amz-id-2").asText().isBlank());
            assertEquals("000000000000", queueEvent.path("s3").path("bucket")
                    .path("ownerIdentity").path("principalId").asText());
            assertEquals(queueEvent.path("eventTime"), topicEvent.path("eventTime"));
            assertEquals(queueEvent.path("responseElements"), topicEvent.path("responseElements"));
            assertEquals(queueEvent.path("s3").path("object"), topicEvent.path("s3").path("object"));
        } finally {
            given().delete("/" + name + "/a%20b%2Bc.txt").then().statusCode(204);
            given().delete("/" + name).then().statusCode(204);
            sqs.deleteQueue(queue, region);
            sns.deleteTopic(topicArn, region);
        }
    }
}
