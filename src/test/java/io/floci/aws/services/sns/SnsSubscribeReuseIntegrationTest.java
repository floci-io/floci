package io.floci.aws.services.sns;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SnsSubscribeReuseIntegrationTest {

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingSubscribePreservesExistingAttributesAndExplicitUpdates(boolean json) {
        try (SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1)) {
            String topic = sns.createTopic(request -> request.name("reuse-" + UUID.randomUUID())).topicArn();
            String endpoint = "arn:aws:sqs:us-east-1:000000000000:reuse";
            try {
                String subscription = sns.subscribe(request -> request.topicArn(topic).protocol("sqs")
                        .endpoint(endpoint).attributes(Map.of("RawMessageDelivery", "true"))).subscriptionArn();
                if (json) {
                    given().contentType("application/x-amz-json-1.0")
                            .header("X-Amz-Target", "SNS_20100331.Subscribe")
                            .body(Map.of("TopicArn", topic, "Protocol", "sqs", "Endpoint", endpoint,
                                    "Attributes", Map.of("RawMessageDelivery", "false")))
                            .when().post("/").then().statusCode(400)
                            .body(containsString("InvalidParameter"));
                } else {
                    SnsException error = assertThrows(SnsException.class, () -> sns.subscribe(request -> request
                            .topicArn(topic).protocol("sqs").endpoint(endpoint)
                            .attributes(Map.of("RawMessageDelivery", "false"))));
                    assertEquals("InvalidParameter", error.awsErrorDetails().errorCode());
                }
                assertEquals("true", sns.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                        .attributes().get("RawMessageDelivery"));
                assertEquals(subscription, sns.subscribe(request -> request.topicArn(topic).protocol("sqs")
                        .endpoint(endpoint)).subscriptionArn());
                sns.setSubscriptionAttributes(request -> request.subscriptionArn(subscription)
                        .attributeName("RawMessageDelivery").attributeValue("false"));
                assertEquals(subscription, sns.subscribe(request -> request.topicArn(topic).protocol("sqs")
                        .endpoint(endpoint).attributes(Map.of("RawMessageDelivery", "false"))).subscriptionArn());
                assertEquals("false", sns.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                        .attributes().get("RawMessageDelivery"));
            } finally {
                sns.deleteTopic(request -> request.topicArn(topic));
            }
        }
    }
}
