package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * Every list these operations read is read off the indices the request numbers its members with,
 * not off a run from 1 that stops at the first gap. The Query protocol numbers a list's members,
 * and the constraints ahead of each operation read every index the request names, so a request
 * numbering 1 and 3 was held to both members and then given one. The bare {@code List.N} spelling
 * the constraints read is read here too.
 */
@QuarkusTest
class IamQueryListIndicesIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261103/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String TAGS = "ListUserTagsResponse.ListUserTagsResult.Tags.member.Key";

    /** 40 characters, the only length `thumbprintType` accepts. */
    private static final String THUMBPRINT = "9e99a48a9960b14926bb7f3b02e22da2b0ab7280";
    private static final String OTHER_THUMBPRINT = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";

    private static final String ALLOW_GET_OBJECT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    /**
     * What these tests create outlives this class otherwise: the next class to list users or
     * providers unscoped reads what this one left.
     */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String createUser(String prefix) {
        String name = unique(prefix);
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser")
            .formParam("UserName", name).when().post("/").then().statusCode(200));
        return name;
    }

    private static RequestSpecification listTags(String user) {
        return iam("ListUserTags").formParam("UserName", user);
    }

    /**
     * A request numbering tags 1 and 3 carries two tags: the constraints hold it to both, so
     * storing the first alone left the caller a tag short of what it was answered for.
     */
    @Test
    void tagsTheRequestNumbersWithAGapAreAllStored() {
        String user = createUser("gap-tags");

        iam("TagUser").formParam("UserName", user)
            .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
            .formParam("Tags.member.3.Key", "owner").formParam("Tags.member.3.Value", "ops")
        .when().post("/").then().statusCode(200);

        listTags(user).when().post("/").then().statusCode(200)
            .body(TAGS, hasItems("team", "owner"));
    }

    /**
     * A tag numbered with a value and no key never reaches the operation: {@code Key} is required, so the
     * constraints ahead of it answer under the index the request spelled, and nothing is attached. The skip
     * inside the reader sits behind that gate, for a member the model requires nothing of.
     */
    @Test
    void aTagNumberedWithAValueAndNoKeyIsRefusedBeforeTheOperation() {
        String user = createUser("no-key-tags");

        iam("TagUser").formParam("UserName", user)
            .formParam("Tags.member.1.Value", "blue")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("Value at 'tags.1.member.key' failed to satisfy constraint: "
                + "Member must not be null"));

        listTags(user).when().post("/").then().statusCode(200)
            .body(not(containsString("blue")));
    }

    /** The duplicate check reads every tag the request carries, so a repeat past a gap is one. */
    @Test
    void aDuplicateTagKeyPastAGapIsStillRefused() {
        String user = createUser("gap-dup-tags");

        iam("TagUser").formParam("UserName", user)
            .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
            .formParam("Tags.member.3.Key", "Team").formParam("Tags.member.3.Value", "payments")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("InvalidInput"))
            .body(MESSAGE, equalTo("Duplicate tag keys found. Please note that Tag keys are case insensitive."));

        listTags(user).when().post("/").then().statusCode(200)
            .body(not(containsString("platform")));
    }

    /** The bare spelling of a tag, which the constraints read and the operation now reads with them. */
    @Test
    void theBareSpellingOfATagIsRead() {
        String user = createUser("bare-tags");

        iam("TagUser").formParam("UserName", user)
            .formParam("Tags.1.Key", "bare").formParam("Tags.1.Value", "read")
        .when().post("/").then().statusCode(200);

        listTags(user).when().post("/").then().statusCode(200)
            .body(TAGS, equalTo("bare"));
    }

    /** The keys an untag request numbers, where dropping the second left its tag behind. */
    @Test
    void tagKeysTheRequestNumbersWithAGapAreAllRemoved() {
        String user = createUser("gap-untag");

        iam("TagUser").formParam("UserName", user)
            .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
            .formParam("Tags.member.2.Key", "owner").formParam("Tags.member.2.Value", "ops")
        .when().post("/").then().statusCode(200);

        iam("UntagUser").formParam("UserName", user)
            .formParam("TagKeys.member.1", "team")
            .formParam("TagKeys.member.3", "owner")
        .when().post("/").then().statusCode(200);

        listTags(user).when().post("/").then().statusCode(200)
            .body(not(containsString("team")))
            .body(not(containsString("owner")));
    }

    /**
     * A key past a gap is held to `tagKeyType`, where it used to be dropped unchecked and the
     * request went on to remove the keys ahead of it.
     */
    @Test
    void aTagKeyPastAGapIsHeldToItsConstraints() {
        String user = createUser("gap-untag-bad");

        iam("TagUser").formParam("UserName", user)
            .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
        .when().post("/").then().statusCode(200);

        iam("UntagUser").formParam("UserName", user)
            .formParam("TagKeys.member.1", "team")
            .formParam("TagKeys.member.3", "cost#center")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("Value at 'tagKeys' failed to satisfy constraint"));

        listTags(user).when().post("/").then().statusCode(200)
            .body(TAGS, equalTo("team"));
    }

    /** `tagKeyListType` binds the keys as sent, so a fifty-first key numbered past a gap is one. */
    @Test
    void theTagKeyCountIsTakenOverTheIndicesTheRequestNumbers() {
        String user = createUser("gap-untag-count");

        RequestSpecification request = iam("UntagUser").formParam("UserName", user);
        for (int i = 1; i <= 50; i++) {
            request.formParam("TagKeys.member." + i, "key" + i);
        }
        request.formParam("TagKeys.member.52", "key52")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("Value at 'tagKeys' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 50"));
    }

    /**
     * A provider's thumbprints and client IDs, the generic spelling numbered with a gap and the
     * bare one beside it: both lists reach the store with every member the request carried.
     */
    @Test
    void aProvidersThumbprintsAndClientIdsAreReadOffTheirIndices() {
        String arn = iam("CreateOpenIDConnectProvider")
            .formParam("Url", "https://oidc.query-indices.example.com/" + unique("provider"))
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .formParam("ThumbprintList.member.3", OTHER_THUMBPRINT)
            .formParam("ClientIDList.2", "sts.amazonaws.com")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString(
                    "CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
        cleanup.register(() -> iam("DeleteOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", arn).when().post("/").then().statusCode(200));

        iam("GetOpenIDConnectProvider").formParam("OpenIDConnectProviderArn", arn)
        .when().post("/").then().statusCode(200)
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ThumbprintList.member",
                    hasItems(THUMBPRINT, OTHER_THUMBPRINT))
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ClientIDList.member",
                    equalTo("sts.amazonaws.com"));
    }

    /**
     * A simulation evaluates every action the request numbers, and reads its documents off the
     * bare spelling too. A list whose first member is numbered 2 is a list with a member, where
     * reading from 1 answered it as the list that never arrived.
     */
    @Test
    void everyActionTheRequestNumbersIsSimulated() {
        iam("SimulateCustomPolicy")
            .formParam("PolicyInputList.1", ALLOW_GET_OBJECT)
            .formParam("ActionNames.member.2", "s3:GetObject")
            .formParam("ActionNames.member.4", "ec2:RunInstances")
            .formParam("ResourceArns.member.1", "*")
        .when().post("/").then().statusCode(200)
            .body("SimulateCustomPolicyResponse.SimulateCustomPolicyResult.EvaluationResults"
                            + ".member.find { it.EvalActionName == 's3:GetObject' }.EvalDecision",
                    equalTo("allowed"))
            .body("SimulateCustomPolicyResponse.SimulateCustomPolicyResult.EvaluationResults"
                            + ".member.find { it.EvalActionName == 'ec2:RunInstances' }.EvalDecision",
                    equalTo("implicitDeny"));
    }

    /**
     * A context entry numbered past a gap, with its own values numbered past gaps of their own.
     * Dropping the entry dropped its key and every value with it, and a set operator over a key
     * the request did supply then decided on a context it never saw.
     */
    @Test
    void aContextEntryPastAGapIsReadWithEveryValueItCarries() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"ForAnyValue:StringEquals":{"dynamodb:LeadingKeys":["USER_alice"]}}}
                ]}""";

        iam("SimulateCustomPolicy")
            .formParam("PolicyInputList.member.1", policy)
            .formParam("ActionNames.member.1", "dynamodb:GetItem")
            .formParam("ResourceArns.member.1", "*")
            .formParam("ContextEntries.member.3.ContextKeyName", "dynamodb:LeadingKeys")
            .formParam("ContextEntries.member.3.ContextKeyValues.member.2", "USER_bob")
            .formParam("ContextEntries.member.3.ContextKeyValues.member.4", "USER_alice")
        .when().post("/").then().statusCode(200)
            .body("SimulateCustomPolicyResponse.SimulateCustomPolicyResult.EvaluationResults"
                            + ".member.EvalDecision", equalTo("allowed"));
    }
}
