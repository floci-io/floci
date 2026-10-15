package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SNS topic policies under IAM enforcement: they decide the calls AWS services make to a topic,
 * through {@link ServiceCallAuthorizer}, and take part in IAM callers' requests the way any resource
 * policy does.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SnsTopicPolicyEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String OWNER = "121212121212";
    private static final String OTHER = "343434343434";

    @Inject
    ServiceCallAuthorizer authorizer;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI baseUri;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theDefaultTopicPolicyAdmitsAServiceActingForTheOwnersAccount() {
        String topicArn = createTopic(OWNER, "default-" + suffix());

        assertEquals(Decision.ALLOW, sesPublish(topicArn, configurationSet(OWNER, "my-set"), OWNER));
        assertEquals(Decision.DENY, sesPublish(topicArn, configurationSet(OTHER, "my-set"), OTHER));
    }

    @Test
    void aTopicPolicyGrantingSesAdmitsTheConfigurationSetItNames() {
        String topicArn = createTopic(OWNER, "granted-" + suffix());
        setTopicPolicy(OWNER, topicArn, """
                {"Version":"2012-10-17","Id":"notification-policy","Statement":[{
                  "Effect":"Allow","Principal":{"Service":"ses.amazonaws.com"},"Action":"sns:Publish",
                  "Resource":"%s",
                  "Condition":{"StringEquals":{"AWS:SourceAccount":"%s","AWS:SourceArn":"%s"}}}]}"""
                .formatted(topicArn, OWNER, configurationSet(OWNER, "my-set")));

        assertEquals(Decision.ALLOW, sesPublish(topicArn, configurationSet(OWNER, "my-set"), OWNER));
        assertEquals(Decision.DENY, sesPublish(topicArn, configurationSet(OWNER, "other-set"), OWNER));
        assertEquals(Decision.DENY, sesPublish(topicArn, configurationSet(OTHER, "my-set"), OTHER));
    }

    @Test
    void aTopicWhosePolicyNoLongerAdmitsSesRefusesIt() {
        String topicArn = createTopic(OWNER, "replaced-" + suffix());
        setTopicPolicy(OWNER, topicArn, """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sns:Publish",
                  "Resource":"%s"}]}""".formatted(OWNER, topicArn));

        assertEquals(Decision.DENY, sesPublish(topicArn, configurationSet(OWNER, "my-set"), OWNER));
    }

    @Test
    void aTopicThatDoesNotExistRefusesEveryService() {
        String topicArn = "arn:aws:sns:" + REGION + ":" + OWNER + ":missing-" + suffix();

        assertEquals(Decision.DENY, sesPublish(topicArn, configurationSet(OWNER, "my-set"), OWNER));
    }

    @Test
    void aPublishToAnotherAccountsTopicNeedsItsTopicPolicy() {
        String topicArn = createTopic(OWNER, "foreign-" + suffix());
        String publisher = createUser(OTHER, "publisher-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"*"}]}""");

        publish(publisher, topicArn)
                .statusCode(allOf(greaterThanOrEqualTo(400), lessThan(500)));
    }

    @Test
    void aSameAccountTopicPolicyGrantsOnItsOwn() {
        String topicArn = createTopic(OWNER, "own-grant-" + suffix());
        String userName = "granted-" + suffix();
        String publisher = createUser(OWNER, userName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"}]}""");
        setTopicPolicy(OWNER, topicArn, """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:user/%s"},"Action":"sns:Publish",
                  "Resource":"%s"}]}""".formatted(OWNER, userName, topicArn));

        publish(publisher, topicArn).statusCode(200);
    }

    /**
     * IAM User Guide, "AWS JSON policy elements: NotPrincipal": a Deny's NotPrincipal naming a user
     * without its account "might deny access to the entire account", and it "will always deny any IAM
     * principal that has a permissions boundary policy attached".
     */
    @Test
    void aDenysNotPrincipalSparesAUserNamedWithItsAccountAndNoBoundary() {
        String topicArn = createTopic(OWNER, "not-principal-" + suffix());
        String userName = "spared-" + suffix();
        String publisher = createUser(OWNER, userName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"*"}]}""");
        String userArn = "arn:aws:iam::" + OWNER + ":user/" + userName;

        setTopicPolicy(OWNER, topicArn, denyAllBut(topicArn, userArn));
        publish(publisher, topicArn).statusCode(allOf(greaterThanOrEqualTo(400), lessThan(500)));

        setTopicPolicy(OWNER, topicArn, denyAllBut(topicArn, userArn, "arn:aws:iam::" + OWNER + ":root"));
        publish(publisher, topicArn).statusCode(200);

        String boundaryArn = "arn:aws:iam::" + OWNER + ":policy/boundary-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removePolicy(baseUri.getPort(),
                EnforcementFixtures.auth(OWNER, "iam"), boundaryArn));
        iam(OWNER, "CreatePolicy", Map.of("PolicyName", boundaryArn.substring(boundaryArn.lastIndexOf('/') + 1),
                "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"sns:*","Resource":"*"}]}"""));
        iam(OWNER, "PutUserPermissionsBoundary", Map.of("UserName", userName, "PermissionsBoundary", boundaryArn));
        publish(publisher, topicArn).statusCode(allOf(greaterThanOrEqualTo(400), lessThan(500)));
    }

    private static String denyAllBut(String topicArn, String... spared) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sns:Publish","Resource":"%1$s"},
                  {"Effect":"Deny","NotPrincipal":{"AWS":["%2$s"]},"Action":"sns:Publish","Resource":"%1$s"}]}"""
                .formatted(topicArn, String.join("\",\"", spared));
    }

    private Decision sesPublish(String topicArn, String sourceArn, String sourceAccount) {
        return authorizer.authorize("ses", "sns:Publish", topicArn, sourceArn, sourceAccount);
    }

    private static String configurationSet(String account, String name) {
        return "arn:aws:ses:" + REGION + ":" + account + ":configuration-set/" + name;
    }

    private String createTopic(String account, String name) {
        return EnforcementFixtures.createTopic(cleanup, baseUri.getPort(), EnforcementFixtures.auth(account, "sns"), name);
    }

    private void setTopicPolicy(String account, String topicArn, String policy) {
        EnforcementFixtures.setTopicPolicy(baseUri.getPort(), EnforcementFixtures.auth(account, "sns"), topicArn, policy);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Publishes through SNS's JSON protocol, whose body the request filter reads the topic from. A
     * Query-protocol request carries the topic in its form body, which the filter does not read yet.
     */
    private static ValidatableResponse publish(String accessKeyId, String topicArn) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "sns"))
                .header("X-Amz-Target", "SNS_20100331.Publish")
                .contentType("application/x-amz-json-1.0")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Message\":\"hello\"}")
        .when()
                .post("/")
        .then();
    }

    /** An IAM user of {@code account} holding {@code policyDocument} inline; returns its access key ID. */
    private String createUser(String account, String userName, String policyDocument) {
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                EnforcementFixtures.auth(account, "iam"), userName).accessKeyId();
        iam(account, "PutUserPolicy", Map.of("UserName", userName, "PolicyName", "inline",
                "PolicyDocument", policyDocument));
        return accessKeyId;
    }

    private static void iam(String account, String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", EnforcementFixtures.auth(account, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        spec.when().post("/").then().statusCode(200);
    }
}
