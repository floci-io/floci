package io.floci.aws.services.sns;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.InvalidParameterException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A topic policy may only grant SNS's own actions. There is no {@code sns:PublishBatch}: the
 * batching guide says PublishBatch "uses the same Publish API action for IAM policies", and AWS
 * answers a statement that grants an action SNS does not have with {@code InvalidParameter},
 * as {@code AddPermission} already does here.
 */
@QuarkusTest
class SnsTopicPolicyActionScopeIntegrationTest {

    private static final String OUT_OF_SCOPE = "Invalid parameter: Policy statement action out of service scope!";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void setTopicAttributesRefusesAPolicyGrantingAnActionSnsDoesNotHave() {
        try (SnsClient sns = client()) {
            String topicArn = sns.createTopic(r -> r.name("policy-scope-" + UUID.randomUUID())).topicArn();
            String defaultPolicy = policyOf(sns, topicArn);

            InvalidParameterException refused = assertThrows(InvalidParameterException.class,
                    () -> sns.setTopicAttributes(r -> r.topicArn(topicArn).attributeName("Policy")
                            .attributeValue(policy(topicArn, "[\"sns:Publish\", \"sns:PublishBatch\"]"))));

            assertEquals(OUT_OF_SCOPE, refused.awsErrorDetails().errorMessage());
            assertEquals(defaultPolicy, policyOf(sns, topicArn));
        }
    }

    @Test
    void createTopicRefusesAPolicyGrantingAnActionSnsDoesNotHaveAndCreatesNoTopic() {
        try (SnsClient sns = client()) {
            String name = "policy-scope-" + UUID.randomUUID();
            String topicArn = "arn:aws:sns:us-east-1:000000000000:" + name;

            InvalidParameterException refused = assertThrows(InvalidParameterException.class,
                    () -> sns.createTopic(r -> r.name(name)
                            .attributes(Map.of("Policy", policy(topicArn, "\"sns:PublishBatch\"")))));

            assertEquals(OUT_OF_SCOPE, refused.awsErrorDetails().errorMessage());
            assertFalse(sns.listTopics().topics().stream().anyMatch(topic -> topic.topicArn().equals(topicArn)));
        }
    }

    @Test
    void actionNamesAreMatchedIgnoringCaseAndReceiveIsAccepted() {
        try (SnsClient sns = client()) {
            String topicArn = sns.createTopic(r -> r.name("policy-scope-" + UUID.randomUUID())).topicArn();
            String policy = policy(topicArn, "[\"sns:publish\", \"SNS:Receive\"]");

            sns.setTopicAttributes(r -> r.topicArn(topicArn).attributeName("Policy").attributeValue(policy));

            assertEquals(policy, policyOf(sns, topicArn));
        }
    }

    @Test
    void addPermissionAcceptsReceive() {
        try (SnsClient sns = client()) {
            String topicArn = sns.createTopic(r -> r.name("policy-scope-" + UUID.randomUUID())).topicArn();

            sns.addPermission(r -> r.topicArn(topicArn).label("receive-grant")
                    .awsAccountIds("111122223333").actionNames("Receive"));
        }
    }

    private static String policy(String topicArn, String actions) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":" + actions + ",\"Resource\":\"" + topicArn + "\"}]}";
    }

    private static String policyOf(SnsClient sns, String topicArn) {
        return sns.getTopicAttributes(r -> r.topicArn(topicArn)).attributes().get("Policy");
    }

    private SnsClient client() {
        return AwsTestClients.create(SnsClient.builder(), "000000000000", Region.US_EAST_1, endpoint);
    }
}
