package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SES publishing to SNS under IAM enforcement: SES publishes as {@code ses.amazonaws.com}, so the
 * topic's policy decides, with the configuration set as {@code aws:SourceArn} for an event
 * destination and the identity for an identity notification. The caller's own SNS permissions play
 * no part, so a caller refused {@code sns:Publish} cannot reach a topic through SES that the topic's
 * policy does not open to SES.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SesSnsPublishAuthorizationIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String OWNER = "131313131313";
    private static final String OTHER = "242424242424";
    private static final String BOUNCE = "bounce@simulator.amazonses.com";
    private static final String SUCCESS = "success@simulator.amazonses.com";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI baseUri;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aCallerRefusedSnsPublishCannotReachATopicThatRefusesSesThroughAConfigurationSet() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        setTopicPolicy(OWNER, topicArn, rootOnly(topicArn));
        String sender = createSender();
        String user = "ses-only-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                EnforcementFixtures.auth(OWNER, "iam"), user).accessKeyId();
        query(OWNER, "iam", "PutUserPolicy", Map.of("Version", "2010-05-08", "UserName", user,
                "PolicyName", "ses-only", "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"ses:*","Resource":"*"}]}""")).statusCode(200);
        given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "sns"))
                .header("X-Amz-Target", "SNS_20100331.Publish")
                .contentType("application/x-amz-json-1.0")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Message\":\"hello\"}")
        .when()
                .post("/")
        .then()
                .statusCode(allOf(greaterThanOrEqualTo(400), lessThan(500)));

        String configurationSet = createConfigurationSet(accessKeyId, topicArn);
        sendEmail(accessKeyId, sender, SUCCESS, configurationSet);

        assertEquals(0, receive(OWNER, queueUrl));
    }

    @Test
    void anEventDestinationOnATopicWithTheDefaultPolicyGetsTheEvent() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String sender = createSender();
        String configurationSet = createConfigurationSet(topicArn);

        sendEmail(sender, SUCCESS, configurationSet);

        assertEquals(1, receive(OWNER, queueUrl));
    }

    @Test
    void anEventDestinationTopicGrantingSesForAnotherConfigurationSetGetsNothing() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String sender = createSender();
        String configurationSet = createConfigurationSet(topicArn);
        setTopicPolicy(OWNER, topicArn, sesGrant(topicArn, "configuration-set/other-" + suffix()));

        sendEmail(sender, SUCCESS, configurationSet);

        assertEquals(0, receive(OWNER, queueUrl));
    }

    @Test
    void anEventDestinationTopicGrantingSesForItsConfigurationSetGetsTheEvent() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String sender = createSender();
        String configurationSet = createConfigurationSet(topicArn);
        setTopicPolicy(OWNER, topicArn, sesGrant(topicArn, "configuration-set/" + configurationSet));

        sendEmail(sender, SUCCESS, configurationSet);

        assertEquals(1, receive(OWNER, queueUrl));
    }

    @Test
    void aBounceTopicWithTheDefaultPolicyGetsTheNotification() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String sender = createSender();
        setIdentityNotificationTopic(sender, "Bounce", topicArn);

        sendEmail(sender, BOUNCE, null);

        assertEquals(1, receive(OWNER, queueUrl));
    }

    @Test
    void aBounceTopicGrantingSesForTheDomainOfTheSenderGetsTheNotification() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String domain = "authz-" + suffix() + ".floci.test";
        ses(OWNER, "VerifyDomainIdentity", Map.of("Domain", domain)).statusCode(200);
        cleanup.register(() -> ses(OWNER, "DeleteIdentity", Map.of("Identity", domain)).statusCode(200));
        String sender = "sender@" + domain;
        verifyEmailIdentity(sender);
        setIdentityNotificationTopic(domain, "Bounce", topicArn);
        setTopicPolicy(OWNER, topicArn, sesGrant(topicArn, "identity/" + domain));

        sendEmail(sender, BOUNCE, null);

        assertEquals(1, receive(OWNER, queueUrl));
    }

    @Test
    void aTopicThatRefusesSesIsTakenOutOfTheBounceAndComplaintSettingsAndForwardingTurnsBackOn() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String sender = createSender();
        setIdentityNotificationTopic(sender, "Bounce", topicArn);
        setIdentityNotificationTopic(sender, "Complaint", topicArn);
        setIdentityNotificationTopic(sender, "Delivery", topicArn);
        ses(OWNER, "SetIdentityFeedbackForwardingEnabled",
                Map.of("Identity", sender, "ForwardingEnabled", "false")).statusCode(200);
        setTopicPolicy(OWNER, topicArn, rootOnly(topicArn));

        sendEmail(sender, BOUNCE, null);

        assertEquals(0, receive(OWNER, queueUrl));
        notificationAttributes(sender)
                .body(containsString("<BounceTopic></BounceTopic>"))
                .body(containsString("<ComplaintTopic></ComplaintTopic>"))
                .body(containsString("<DeliveryTopic>" + topicArn + "</DeliveryTopic>"))
                .body(containsString("<ForwardingEnabled>true</ForwardingEnabled>"));
    }

    @Test
    void aDeliveryTopicThatRefusesSesStaysConfigured() {
        String queueUrl = createQueue(OWNER);
        String topicArn = createSubscribedTopic(OWNER, queueUrl);
        String sender = createSender();
        setIdentityNotificationTopic(sender, "Delivery", topicArn);
        setTopicPolicy(OWNER, topicArn, rootOnly(topicArn));

        sendEmail(sender, SUCCESS, null);

        assertEquals(0, receive(OWNER, queueUrl));
        notificationAttributes(sender)
                .body(containsString("<DeliveryTopic>" + topicArn + "</DeliveryTopic>"));
    }

    @Test
    void anotherAccountsTopicWithoutAnSesGrantRefusesTheNotification() {
        String topicArn = createTopic(OTHER, "foreign-" + suffix());
        String sender = createSender();
        setIdentityNotificationTopic(sender, "Bounce", topicArn);

        sendEmail(sender, BOUNCE, null);

        notificationAttributes(sender).body(containsString("<BounceTopic></BounceTopic>"));
    }

    @Test
    void anotherAccountsTopicThatGrantsSesKeepsItsSettingThoughFlociCannotDeliverThere() {
        String sender = createSender();
        String topicArn = createTopic(OTHER, "foreign-granted-" + suffix());
        setTopicPolicy(OTHER, topicArn, sesGrant(topicArn, "identity/" + sender));
        setIdentityNotificationTopic(sender, "Bounce", topicArn);

        sendEmail(sender, BOUNCE, null);

        notificationAttributes(sender).body(containsString("<BounceTopic>" + topicArn + "</BounceTopic>"));
    }

    /**
     * CreateReceiptRule answers InvalidSnsTopic when "Amazon SES could not publish to the topic,
     * possibly due to permissions issues" (SES API Reference).
     */
    @Test
    void aReceiptRuleCannotNameATopicThatRefusesSes() {
        String ruleSet = "authz-" + suffix();
        ses(OWNER, "CreateReceiptRuleSet", Map.of("RuleSetName", ruleSet)).statusCode(200);
        cleanup.register(() -> ses(OWNER, "DeleteReceiptRuleSet", Map.of("RuleSetName", ruleSet)).statusCode(200));
        String open = createTopic(OWNER, "rule-open-" + suffix());
        String closed = createTopic(OWNER, "rule-closed-" + suffix());
        setTopicPolicy(OWNER, closed, rootOnly(closed));
        String foreign = createTopic(OTHER, "rule-foreign-" + suffix());

        receiptRule(ruleSet, "to-open", open).statusCode(200);
        for (String topicArn : List.of(closed, foreign)) {
            receiptRule(ruleSet, "to-" + suffix(), topicArn)
                    .statusCode(400)
                    .body(containsString("<Code>InvalidSnsTopic</Code>"))
                    .body(containsString("Could not publish to SNS topic: " + topicArn));
        }
    }

    private static ValidatableResponse receiptRule(String ruleSet, String name, String topicArn) {
        return ses(OWNER, "CreateReceiptRule", Map.of("RuleSetName", ruleSet, "Rule.Name", name,
                "Rule.Actions.member.1.SNSAction.TopicArn", topicArn));
    }

    /** The SES Developer Guide's topic policy, granting SES for one resource of the owner's account. */
    private static String sesGrant(String topicArn, String sesResource) {
        return """
                {"Version":"2012-10-17","Id":"notification-policy","Statement":[{
                  "Effect":"Allow","Principal":{"Service":"ses.amazonaws.com"},"Action":"sns:Publish",
                  "Resource":"%s",
                  "Condition":{"StringEquals":{"AWS:SourceAccount":"%s","AWS:SourceArn":"arn:aws:ses:%s:%s:%s"}}}]}"""
                .formatted(topicArn, OWNER, REGION, OWNER, sesResource);
    }

    /** A topic policy that lets only the owner's account publish, replacing the default. */
    private static String rootOnly(String topicArn) {
        return """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sns:Publish",
                  "Resource":"%s"}]}""".formatted(OWNER, topicArn);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createSender() {
        String sender = "sender-" + suffix() + "@floci.test";
        verifyEmailIdentity(sender);
        return sender;
    }

    private void verifyEmailIdentity(String email) {
        ses(OWNER, "VerifyEmailIdentity", Map.of("EmailAddress", email)).statusCode(200);
        cleanup.register(() -> ses(OWNER, "DeleteIdentity", Map.of("Identity", email)).statusCode(200));
    }

    private String createConfigurationSet(String topicArn) {
        return createConfigurationSet(OWNER, topicArn);
    }

    /** A configuration set whose one event destination sends its send events to {@code topicArn}. */
    private String createConfigurationSet(String accessKeyId, String topicArn) {
        String name = "authz-" + suffix();
        ses(accessKeyId, "CreateConfigurationSet", Map.of("ConfigurationSet.Name", name)).statusCode(200);
        cleanup.register(() -> ses(OWNER, "DeleteConfigurationSet", Map.of("ConfigurationSetName", name))
                .statusCode(200));
        ses(accessKeyId, "CreateConfigurationSetEventDestination", Map.of(
                "ConfigurationSetName", name,
                "EventDestination.Name", "to-sns",
                "EventDestination.Enabled", "true",
                "EventDestination.MatchingEventTypes.member.1", "send",
                "EventDestination.SNSDestination.TopicARN", topicArn)).statusCode(200);
        return name;
    }

    private static void setIdentityNotificationTopic(String identity, String type, String topicArn) {
        ses(OWNER, "SetIdentityNotificationTopic",
                Map.of("Identity", identity, "NotificationType", type, "SnsTopic", topicArn)).statusCode(200);
    }

    private static ValidatableResponse notificationAttributes(String identity) {
        return ses(OWNER, "GetIdentityNotificationAttributes", Map.of("Identities.member.1", identity))
                .statusCode(200);
    }

    private static void sendEmail(String sender, String recipient, String configurationSet) {
        sendEmail(OWNER, sender, recipient, configurationSet);
    }

    private static void sendEmail(String accessKeyId, String sender, String recipient, String configurationSet) {
        RequestSpecification spec = given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "ses"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendEmail")
                .formParam("Source", sender)
                .formParam("Destination.ToAddresses.member.1", recipient)
                .formParam("Message.Subject.Data", "hello")
                .formParam("Message.Body.Text.Data", "hello");
        if (configurationSet != null) {
            spec.formParam("ConfigurationSetName", configurationSet);
        }
        spec.when().post("/").then().statusCode(200);
    }

    private String createTopic(String account, String name) {
        return EnforcementFixtures.createTopic(cleanup, baseUri.getPort(), EnforcementFixtures.auth(account, "sns"), name);
    }

    private String createSubscribedTopic(String account, String queueUrl) {
        String topicArn = createTopic(account, "ses-authz-" + suffix());
        String queueArn = sqs(account, "GetQueueAttributes",
                "{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        sns(account, "Subscribe", Map.of("TopicArn", topicArn, "Protocol", "sqs", "Endpoint", queueArn))
                .statusCode(200);
        return topicArn;
    }

    private void setTopicPolicy(String account, String topicArn, String policy) {
        EnforcementFixtures.setTopicPolicy(baseUri.getPort(), EnforcementFixtures.auth(account, "sns"), topicArn, policy);
    }

    private String createQueue(String account) {
        String queueUrl = sqs(account, "CreateQueue", "{\"QueueName\":\"ses-authz-" + suffix() + "\"}")
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        cleanup.register(() -> sqs(account, "DeleteQueue", "{\"QueueUrl\":\"" + queueUrl + "\"}").statusCode(200));
        return queueUrl;
    }

    /** How many messages the queue holds; SNS delivers to SQS before the publish returns. */
    private static int receive(String account, String queueUrl) {
        List<Object> messages = sqs(account, "ReceiveMessage",
                "{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":0}")
                .statusCode(200)
                .extract().jsonPath().getList("Messages");
        return messages == null ? 0 : messages.size();
    }

    private static ValidatableResponse ses(String account, String action, Map<String, String> params) {
        return query(account, "ses", action, params);
    }

    private static ValidatableResponse sns(String account, String action, Map<String, String> params) {
        return query(account, "sns", action, params);
    }

    private static ValidatableResponse query(String account, String service, String action,
                                             Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", EnforcementFixtures.auth(account, service))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse sqs(String account, String action, String body) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(account, "sqs"))
                .header("X-Amz-Target", "AmazonSQS." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body)
        .when()
                .post("/")
        .then();
    }
}
