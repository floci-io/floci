package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AddPermission} and {@code RemovePermission}, which edit a topic's policy. The expected
 * documents are the ones AWS returned in LocalStack's recordings of the same calls against a live
 * account ({@code test_topic_add_permission}, {@code test_topic_add_multiple_permissions},
 * {@code test_topic_remove_permission}, {@code test_add_permission_errors}), and the default
 * policy is the one AWS returns for a new topic ({@code test_create_topic_with_attributes}).
 */
@QuarkusTest
class SnsTopicPermissionIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String GRANTEE = "111122223333";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aNewTopicCarriesTheDefaultPolicyAwsReturns() {
        String topicArn = createTopic();

        JsonPath policy = policyOf(topicArn);
        assertEquals("2008-10-17", policy.getString("Version"));
        assertEquals("__default_policy_ID", policy.getString("Id"));
        assertEquals(1, policy.getList("Statement").size());
        assertEquals("__default_statement_ID", policy.getString("Statement[0].Sid"));
        assertEquals("*", policy.getString("Statement[0].Principal.AWS"));
        assertEquals(List.of("SNS:GetTopicAttributes", "SNS:SetTopicAttributes", "SNS:AddPermission",
                        "SNS:RemovePermission", "SNS:DeleteTopic", "SNS:Subscribe",
                        "SNS:ListSubscriptionsByTopic", "SNS:Publish"),
                policy.getList("Statement[0].Action"));
        assertEquals(topicArn, policy.getString("Statement[0].Resource"));
        assertEquals(ACCOUNT_ID, policy.getString("Statement[0].Condition.StringEquals.'AWS:SourceOwner'"));
    }

    @Test
    void addPermissionAppendsALabelledStatementToTheDefaultPolicy() {
        String topicArn = createTopic();

        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "publish-grant",
                "AWSAccountId.member.1", GRANTEE, "ActionName.member.1", "Publish"))
                .statusCode(200);

        JsonPath policy = policyOf(topicArn);
        assertEquals(2, policy.getList("Statement").size());
        assertEquals("__default_statement_ID", policy.getString("Statement[0].Sid"));
        assertEquals("publish-grant", policy.getString("Statement[1].Sid"));
        assertEquals("Allow", policy.getString("Statement[1].Effect"));
        // One account and one action are written as strings, not lists of one.
        assertEquals("arn:aws:iam::" + GRANTEE + ":root", policy.getString("Statement[1].Principal.AWS"));
        assertEquals("SNS:Publish", policy.getString("Statement[1].Action"));
        assertEquals(topicArn, policy.getString("Statement[1].Resource"));
    }

    @Test
    void severalAccountsAndActionsAreWrittenAsLists() {
        String topicArn = createTopic();

        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "two-grants",
                "AWSAccountId.member.1", GRANTEE, "AWSAccountId.member.2", ACCOUNT_ID,
                "ActionName.member.1", "Publish", "ActionName.member.2", "Subscribe"))
                .statusCode(200);

        JsonPath policy = policyOf(topicArn);
        assertEquals(List.of("arn:aws:iam::" + GRANTEE + ":root", "arn:aws:iam::" + ACCOUNT_ID + ":root"),
                policy.getList("Statement[1].Principal.AWS"));
        assertEquals(List.of("SNS:Publish", "SNS:Subscribe"), policy.getList("Statement[1].Action"));
    }

    @Test
    void aLabelAlreadyInThePolicyIsRefused() {
        String topicArn = createTopic();
        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "taken",
                "AWSAccountId.member.1", GRANTEE, "ActionName.member.1", "Publish"))
                .statusCode(200);

        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "taken",
                "AWSAccountId.member.1", GRANTEE, "ActionName.member.1", "AddPermission"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"))
                .body("ErrorResponse.Error.Message", equalTo("Invalid parameter: Statement already exists"));
    }

    @Test
    void anActionSnsDoesNotHaveIsRefused() {
        String topicArn = createTopic();

        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "bogus",
                "AWSAccountId.member.1", GRANTEE, "ActionName.member.1", "InvalidAction"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"))
                .body("ErrorResponse.Error.Message",
                        equalTo("Invalid parameter: Policy statement action out of service scope!"));
        assertEquals(1, policyOf(topicArn).getList("Statement").size());
    }

    @Test
    void anAccountIdThatIsNotTwelveDigitsIsRefusedOnBothProtocols() {
        String topicArn = createTopic();

        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "bad-account",
                "AWSAccountId.member.1", GRANTEE, "AWSAccountId.member.2", "abc:def",
                "ActionName.member.1", "Publish"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"));
        json("AddPermission", """
                {"TopicArn": "%s", "Label": "bad-account", "AWSAccountId": ["abc:def"], "ActionName": ["Publish"]}
                """.formatted(topicArn))
                .statusCode(400)
                .body("__type", equalTo("InvalidParameter"));

        assertEquals(1, policyOf(topicArn).getList("Statement").size());
    }

    @Test
    void bothCallsAnswerNotFoundForAMissingTopic() {
        String missing = createTopic() + "-not-existing";

        query("AddPermission", Map.of("TopicArn", missing, "Label", "any",
                "AWSAccountId.member.1", GRANTEE, "ActionName.member.1", "Publish"))
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NotFound"))
                .body("ErrorResponse.Error.Message", equalTo("Topic does not exist"));
        query("RemovePermission", Map.of("TopicArn", missing, "Label", "any"))
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NotFound"))
                .body("ErrorResponse.Error.Message", equalTo("Topic does not exist"));
    }

    @Test
    void removePermissionTakesTheStatementBackOut() {
        String topicArn = createTopic();
        query("AddPermission", Map.of("TopicArn", topicArn, "Label", "temporary",
                "AWSAccountId.member.1", GRANTEE, "ActionName.member.1", "Publish"))
                .statusCode(200);

        query("RemovePermission", Map.of("TopicArn", topicArn, "Label", "temporary")).statusCode(200);

        JsonPath policy = policyOf(topicArn);
        assertEquals(1, policy.getList("Statement").size());
        assertEquals("__default_statement_ID", policy.getString("Statement[0].Sid"));
    }

    @Test
    void removingALabelThePolicyDoesNotHoldLeavesItAsItIs() {
        String topicArn = createTopic();
        String before = rawPolicyOf(topicArn);

        query("RemovePermission", Map.of("TopicArn", topicArn, "Label", "never-added")).statusCode(200);

        assertEquals(before, rawPolicyOf(topicArn));
    }

    @Test
    void theJsonProtocolEditsTheSamePolicy() {
        String topicArn = createTopic();

        json("AddPermission", """
                {"TopicArn": "%s", "Label": "json-grant", "AWSAccountId": ["%s"], "ActionName": ["Publish"]}
                """.formatted(topicArn, GRANTEE))
                .statusCode(200);
        assertEquals("json-grant", policyOf(topicArn).getString("Statement[1].Sid"));

        json("RemovePermission", """
                {"TopicArn": "%s", "Label": "json-grant"}
                """.formatted(topicArn))
                .statusCode(200);
        assertEquals(1, policyOf(topicArn).getList("Statement").size());
    }

    private String createTopic() {
        String topicArn = query("CreateTopic", Map.of("Name", "permission-" + UUID.randomUUID()))
                .statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> query("DeleteTopic", Map.of("TopicArn", topicArn)).statusCode(200));
        return topicArn;
    }

    private static JsonPath policyOf(String topicArn) {
        return JsonPath.from(rawPolicyOf(topicArn));
    }

    private static String rawPolicyOf(String topicArn) {
        return query("GetTopicAttributes", Map.of("TopicArn", topicArn))
                .statusCode(200)
                .extract().xmlPath()
                .getString("GetTopicAttributesResponse.GetTopicAttributesResult.Attributes.entry"
                        + ".find { it.key == 'Policy' }.value");
    }

    private static ValidatableResponse query(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-03-31");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse json(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "SNS_20100331." + action)
                .body(body)
                .when().post("/").then();
    }
}
