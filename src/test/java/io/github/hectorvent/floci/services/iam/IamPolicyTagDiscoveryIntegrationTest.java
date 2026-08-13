package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * Covers ListPolicyTags, the per-policy tag call a caller has to make once
 * ListPolicies has handed it a set of policy ARNs.
 *
 * <p>ListPolicies itself carries no tags, by AWS's design: "IAM resource-listing
 * operations return a subset of the available attributes for the resource. For
 * example, this operation does not return tags, even though they are an attribute
 * of the returned object. To view all of the information for a customer managed
 * policy, see GetPolicy." Tag-based discovery over managed policies therefore runs
 * as ListPolicies followed by ListPolicyTags (or GetPolicy) per ARN, and this test
 * pins the second half of that sequence.
 *
 * <p>The AWS Terraform provider's SDKv2 tag interceptor works the same way: when a
 * list resource leaves tags unset, {@code ListResourceWithSDKv2Tags.SetTags} falls
 * back to the service's per-identifier tag call, which for a managed policy is
 * ListPolicyTags. It is a paginated call, so the IsTruncated assertion below is
 * what lets an SDK paginator terminate rather than loop.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamPolicyTagDiscoveryIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String MARKED_POLICY = "TagDiscoveryMarkedPolicy";
    private static final String PATHED_POLICY = "TagDiscoveryPathedPolicy";

    private static String markedPolicyArn;
    private static String pathedPolicyArn;

    @Test
    @Order(1)
    void createPoliciesCarryingDiscoveryMarkers() {
        markedPolicyArn = given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", MARKED_POLICY)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .formParam("Tags.member.1.Key", "Owner")
            .formParam("Tags.member.1.Value", "choudoufu")
            .formParam("Tags.member.2.Key", "Marker")
            .formParam("Tags.member.2.Value", "slot-1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        pathedPolicyArn = given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", PATHED_POLICY)
            .formParam("Path", "/discovery/")
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .formParam("Tags.member.1.Key", "Owner")
            .formParam("Tags.member.1.Value", "choudoufu")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    @Test
    @Order(2)
    void listPolicyTagsReturnsTagsSuppliedAtCreation() {
        given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", markedPolicyArn)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags.member.Key",
                    hasItems("Owner", "Marker"))
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags.member.Value",
                    hasItems("choudoufu", "slot-1"))
            // Paginated call: an SDK paginator keeps requesting pages until this is false.
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.IsTruncated", equalTo("false"));
    }

    @Test
    @Order(3)
    void listPolicyTagsResolvesAnArnCarryingAPath() {
        given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", pathedPolicyArn)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags.member.Key", equalTo("Owner"))
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags.member.Value", equalTo("choudoufu"));
    }

    @Test
    @Order(4)
    void listPoliciesThenListPolicyTagsRecoversTheMarkedPolicy() {
        // The discovery sequence end to end: ListPolicies names the ARNs and carries
        // no tags, ListPolicyTags supplies them for the ARN that was found.
        String listPolicies = given()
            .formParam("Action", "ListPolicies")
            .formParam("Scope", "Local")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(listPolicies, containsString(markedPolicyArn));
        assertThat(listPolicies, not(containsString("<Tags>")));

        given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", markedPolicyArn)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags.member.Value",
                    hasItems("slot-1"));
    }

    @Test
    @Order(5)
    void listPolicyTagsReflectsTagPolicyAndUntagPolicy() {
        given()
            .formParam("Action", "TagPolicy")
            .formParam("PolicyArn", markedPolicyArn)
            .formParam("Tags.member.1.Key", "Stage")
            .formParam("Tags.member.1.Value", "live")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", markedPolicyArn)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags.member.Key",
                    hasItems("Owner", "Marker", "Stage"));

        given()
            .formParam("Action", "UntagPolicy")
            .formParam("PolicyArn", markedPolicyArn)
            .formParam("TagKeys.member.1", "Stage")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String afterUntag = given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", markedPolicyArn)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(afterUntag, containsString("Marker"));
        assertThat(afterUntag, not(containsString("Stage")));
    }

    @Test
    @Order(6)
    void listPolicyTagsOnAnAwsManagedPolicyReturnsAnEmptySet() {
        // AWS managed policies are untagged and live in the global catalog rather than
        // the account partition. The call must answer with an empty set, not NoSuchEntity,
        // so a discovery sweep over ListPolicies(Scope=All) does not fail partway through.
        given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/AdministratorAccess")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.IsTruncated", equalTo("false"))
            .body("ListPolicyTagsResponse.ListPolicyTagsResult.Tags", equalTo(""));
    }

    @Test
    @Order(7)
    void listPolicyTagsOnAnUnknownArnIsNoSuchEntity() {
        given()
            .formParam("Action", "ListPolicyTags")
            .formParam("PolicyArn", "arn:aws:iam::000000000000:policy/DoesNotExist")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }
}
