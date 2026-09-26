package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tag validation shared by every IAM tagging action. {@code tagListType} and {@code tagKeyListType}
 * are {@code max: 50} on the members as sent, so a 51-member list is rejected even when a repeated
 * key would collapse it to 50. Keys and values carry their own length and character constraints,
 * and each resource holds at most 50 tags however many requests they arrive in.
 */
@QuarkusTest
class IamTagValidationIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String ACCOUNT = "000000000000";
    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String POLICY_DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
    private static final String THUMBPRINT = "9e99a48a9960b14926bb7f3b02e22da2b0ab7280";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static RequestSpecification withTags(RequestSpecification request, int distinctKeys, boolean repeatFirstKey) {
        for (int i = 1; i <= distinctKeys; i++) {
            request.formParam("Tags.member." + i + ".Key", "key" + i)
                   .formParam("Tags.member." + i + ".Value", "value" + i);
        }
        if (repeatFirstKey) {
            int last = distinctKeys + 1;
            request.formParam("Tags.member." + last + ".Key", "key1")
                   .formParam("Tags.member." + last + ".Value", "again");
        }
        return request;
    }

    private static void assertRejected(RequestSpecification request) {
        request.when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body(containsString("Member must have length less than or equal to 50"));
    }

    private static void assertNoSuchEntity(RequestSpecification request) {
        request.when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    @Test
    void createUserWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-user");
        assertRejected(withTags(iam("CreateUser").formParam("UserName", name), 50, true));
        assertNoSuchEntity(iam("GetUser").formParam("UserName", name));
    }

    @Test
    void tagUserWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-user");
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
        assertRejected(withTags(iam("TagUser").formParam("UserName", name), 50, true));
    }

    @Test
    void tagUserBeyondFiftyDistinctKeysIsRejected() {
        String name = unique("tag-max-user");
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
        assertRejected(withTags(iam("TagUser").formParam("UserName", name), 60, false));
    }

    @Test
    void tagUserWithExactlyFiftyMembersIsAccepted() {
        String name = unique("tag-max-user");
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
        withTags(iam("TagUser").formParam("UserName", name), 50, false)
            .when().post("/").then().statusCode(200);
    }

    @Test
    void createRoleWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-role");
        assertRejected(withTags(iam("CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY), 50, true));
        assertNoSuchEntity(iam("GetRole").formParam("RoleName", name));
    }

    @Test
    void tagRoleWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-role");
        iam("CreateRole").formParam("RoleName", name).formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        assertRejected(withTags(iam("TagRole").formParam("RoleName", name), 50, true));
    }

    @Test
    void createPolicyWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-policy");
        assertRejected(withTags(iam("CreatePolicy").formParam("PolicyName", name)
                .formParam("PolicyDocument", POLICY_DOCUMENT), 50, true));
        assertNoSuchEntity(iam("GetPolicy").formParam("PolicyArn", "arn:aws:iam::" + ACCOUNT + ":policy/" + name));
    }

    @Test
    void tagPolicyWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-policy");
        String arn = iam("CreatePolicy").formParam("PolicyName", name).formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        assertRejected(withTags(iam("TagPolicy").formParam("PolicyArn", arn), 50, true));
    }

    @Test
    void createInstanceProfileWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-profile");
        assertRejected(withTags(iam("CreateInstanceProfile").formParam("InstanceProfileName", name), 50, true));
        assertNoSuchEntity(iam("GetInstanceProfile").formParam("InstanceProfileName", name));
    }

    @Test
    void tagInstanceProfileWithRepeatedKeyPastFiftyMembersIsRejected() {
        String name = unique("tag-max-profile");
        iam("CreateInstanceProfile").formParam("InstanceProfileName", name)
            .when().post("/").then().statusCode(200);
        assertRejected(withTags(iam("TagInstanceProfile").formParam("InstanceProfileName", name), 50, true));
    }

    @Test
    void createOpenIDConnectProviderWithRepeatedKeyPastFiftyMembersIsRejected() {
        String path = unique("tag-max-create");
        assertRejected(withTags(iam("CreateOpenIDConnectProvider")
                .formParam("Url", "https://oidc.tag-max.example.com/" + path)
                .formParam("ThumbprintList.member.1", THUMBPRINT), 50, true));
        assertNoSuchEntity(iam("GetOpenIDConnectProvider").formParam("OpenIDConnectProviderArn",
                "arn:aws:iam::" + ACCOUNT + ":oidc-provider/oidc.tag-max.example.com/" + path));
    }

    @Test
    void tagOpenIDConnectProviderWithRepeatedKeyPastFiftyMembersIsRejected() {
        String arn = iam("CreateOpenIDConnectProvider")
            .formParam("Url", "https://oidc.tag-max.example.com/" + unique("tag-max-tag"))
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString(
                    "CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
        assertRejected(withTags(iam("TagOpenIDConnectProvider").formParam("OpenIDConnectProviderArn", arn), 50, true));
    }

    private static void createUser(String name) {
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
    }

    private static RequestSpecification tagUser(String name, int from, int to) {
        RequestSpecification request = iam("TagUser").formParam("UserName", name);
        for (int i = from; i <= to; i++) {
            int member = i - from + 1;
            request.formParam("Tags.member." + member + ".Key", "key" + i)
                   .formParam("Tags.member." + member + ".Value", "value" + i);
        }
        return request;
    }

    private static RequestSpecification withTagKeys(RequestSpecification request, int count) {
        for (int i = 1; i <= count; i++) {
            request.formParam("TagKeys.member." + i, "key" + i);
        }
        return request;
    }

    private static void assertQuotaExceeded(RequestSpecification request, String quota) {
        request.when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("LimitExceeded"))
            .body("ErrorResponse.Error.Message", equalTo("Cannot exceed quota for " + quota + ": 50"));
    }

    private static RequestSpecification singleTag(RequestSpecification request, String key, String value) {
        return request.formParam("Tags.member.1.Key", key).formParam("Tags.member.1.Value", value);
    }

    private static void assertInvalidTag(RequestSpecification request, String at, String constraint) {
        request.when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message",
                    containsString("at '" + at + "' failed to satisfy constraint: " + constraint));
    }

    @Test
    void tagUserPastFiftyAcrossRequestsIsRejectedWhole() {
        String name = unique("tag-quota-user");
        createUser(name);
        tagUser(name, 1, 30).when().post("/").then().statusCode(200);
        assertQuotaExceeded(tagUser(name, 31, 60), "TagsPerUser");

        iam("ListUserTags").formParam("UserName", name).when().post("/").then()
            .statusCode(200)
            .body("ListUserTagsResponse.ListUserTagsResult.Tags.member.size()", equalTo(30));
    }

    @Test
    void retaggingExistingKeysAtFiftyStaysWithinQuota() {
        String name = unique("tag-quota-user");
        createUser(name);
        tagUser(name, 1, 50).when().post("/").then().statusCode(200);
        tagUser(name, 1, 50).when().post("/").then().statusCode(200);
    }

    @Test
    void tagRolePastFiftyAcrossRequestsIsRejected() {
        String name = unique("tag-quota-role");
        iam("CreateRole").formParam("RoleName", name).formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        withTags(iam("TagRole").formParam("RoleName", name), 30, false).when().post("/").then().statusCode(200);
        RequestSpecification second = iam("TagRole").formParam("RoleName", name);
        for (int i = 1; i <= 30; i++) {
            second.formParam("Tags.member." + i + ".Key", "other" + i).formParam("Tags.member." + i + ".Value", "v");
        }
        assertQuotaExceeded(second, "TagsPerRole");
    }

    @Test
    void tagPolicyPastFiftyAcrossRequestsIsRejected() {
        String arn = iam("CreatePolicy").formParam("PolicyName", unique("tag-quota-policy"))
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        withTags(iam("TagPolicy").formParam("PolicyArn", arn), 30, false).when().post("/").then().statusCode(200);
        RequestSpecification second = iam("TagPolicy").formParam("PolicyArn", arn);
        for (int i = 1; i <= 30; i++) {
            second.formParam("Tags.member." + i + ".Key", "other" + i).formParam("Tags.member." + i + ".Value", "v");
        }
        assertQuotaExceeded(second, "TagsPerPolicy");
    }

    @Test
    void tagOpenIDConnectProviderPastFiftyAcrossRequestsIsRejected() {
        String arn = iam("CreateOpenIDConnectProvider")
            .formParam("Url", "https://oidc.tag-max.example.com/" + unique("tag-quota"))
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString(
                    "CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
        withTags(iam("TagOpenIDConnectProvider").formParam("OpenIDConnectProviderArn", arn), 30, false)
            .when().post("/").then().statusCode(200);
        RequestSpecification second = iam("TagOpenIDConnectProvider").formParam("OpenIDConnectProviderArn", arn);
        for (int i = 1; i <= 30; i++) {
            second.formParam("Tags.member." + i + ".Key", "other" + i).formParam("Tags.member." + i + ".Value", "v");
        }
        assertQuotaExceeded(second, "TagsPerOpenIdConnectProvider");
    }

    @Test
    void untagBeyondFiftyKeysIsRejectedOnEveryUntagAction() {
        String user = unique("untag-max-user");
        createUser(user);
        assertRejected(withTagKeys(iam("UntagUser").formParam("UserName", user), 51));

        String role = unique("untag-max-role");
        iam("CreateRole").formParam("RoleName", role).formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        assertRejected(withTagKeys(iam("UntagRole").formParam("RoleName", role), 51));

        String policyArn = iam("CreatePolicy").formParam("PolicyName", unique("untag-max-policy"))
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        assertRejected(withTagKeys(iam("UntagPolicy").formParam("PolicyArn", policyArn), 51));

        String oidcArn = iam("CreateOpenIDConnectProvider")
            .formParam("Url", "https://oidc.tag-max.example.com/" + unique("untag-max"))
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString(
                    "CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
        assertRejected(withTagKeys(iam("UntagOpenIDConnectProvider").formParam("OpenIDConnectProviderArn", oidcArn), 51));

        withTagKeys(iam("UntagUser").formParam("UserName", user), 50).when().post("/").then().statusCode(200);
    }

    @Test
    void tagKeyLongerThan128IsRejected() {
        String name = unique("tag-key-long");
        createUser(name);
        assertInvalidTag(singleTag(iam("TagUser").formParam("UserName", name), "k".repeat(129), "v"),
                "tags.1.member.key", "Member must have length less than or equal to 128");
        singleTag(iam("TagUser").formParam("UserName", name), "k".repeat(128), "v")
            .when().post("/").then().statusCode(200);
    }

    /**
     * AWS measures string length in Unicode scalar values, so a letter outside the Basic
     * Multilingual Plane counts once even though Java stores it as two UTF-16 units.
     */
    @Test
    void tagLengthLimitsCountSupplementaryCharactersOnce() {
        String name = unique("tag-supplementary");
        createUser(name);
        String scriptA = new String(Character.toChars(0x1D49C));
        singleTag(iam("TagUser").formParam("UserName", name)
                .contentType("application/x-www-form-urlencoded; charset=UTF-8"),
                scriptA.repeat(128), scriptA.repeat(256))
            .when().post("/").then().statusCode(200);
        assertInvalidTag(singleTag(iam("TagUser").formParam("UserName", name)
                .contentType("application/x-www-form-urlencoded; charset=UTF-8"), scriptA.repeat(129), "v"),
                "tags.1.member.key", "Member must have length less than or equal to 128");
    }

    @Test
    void emptyTagKeyIsRejected() {
        String name = unique("tag-key-empty");
        createUser(name);
        assertInvalidTag(singleTag(iam("TagUser").formParam("UserName", name), "", "v"),
                "tags.1.member.key", "Member must have length greater than or equal to 1");
    }

    @Test
    void tagValueLongerThan256IsRejected() {
        String name = unique("tag-value-long");
        createUser(name);
        assertInvalidTag(singleTag(iam("TagUser").formParam("UserName", name), "k", "v".repeat(257)),
                "tags.1.member.value", "Member must have length less than or equal to 256");
        singleTag(iam("TagUser").formParam("UserName", name), "k", "v".repeat(256))
            .when().post("/").then().statusCode(200);
    }

    @Test
    void tagCharactersOutsideThePatternAreRejectedAtTheirMemberIndex() {
        String name = unique("tag-chars");
        createUser(name);
        RequestSpecification badKey = iam("TagUser").formParam("UserName", name)
            .formParam("Tags.member.1.Key", "ok").formParam("Tags.member.1.Value", "ok")
            .formParam("Tags.member.2.Key", "cost#center").formParam("Tags.member.2.Value", "ok");
        assertInvalidTag(badKey, "tags.2.member.key", "Member must satisfy regular expression pattern");
        assertInvalidTag(singleTag(iam("TagUser").formParam("UserName", name), "team", "a,b"),
                "tags.1.member.value", "Member must satisfy regular expression pattern");
    }

    @Test
    void tagWithLettersSpacesAndAllowedSymbolsIsAccepted() {
        String name = unique("tag-chars-ok");
        createUser(name);
        singleTag(iam("TagUser").formParam("UserName", name)
                .contentType("application/x-www-form-urlencoded; charset=UTF-8"),
                "Cost Center_é.:/=+-@", "Ünïcode 42 _.:/=+-@")
            .when().post("/").then().statusCode(200);
        singleTag(iam("TagUser").formParam("UserName", name), "phoneNumber", "")
            .when().post("/").then().statusCode(200);
    }

    @Test
    void invalidTagOnCreateLeavesNoResourceBehind() {
        String name = unique("tag-invalid-create");
        assertInvalidTag(singleTag(iam("CreateUser").formParam("UserName", name), "bad#key", "v"),
                "tags.1.member.key", "Member must satisfy regular expression pattern");
        assertNoSuchEntity(iam("GetUser").formParam("UserName", name));
    }
}
