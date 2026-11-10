package io.floci.aws.services.dsql;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Aurora DSQL's control plane over its REST-JSON paths, with the answers measured on AWS
 * (eu-west-1, 2026-10-16 and 2026-10-20). Clusters have no database behind them, so they are ACTIVE at once.
 */
@QuarkusTest
class DsqlIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String ROOT = "arn:aws:iam::" + ACCOUNT + ":root";
    private static final String DENY_OUTSIDE_VPC = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
            + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\",\"Condition\":"
            + "{\"StringNotEquals\":{\"aws:SourceVpc\":\"vpc-0123456789abcdef0\"}}}]}";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/eu-west-1/dsql/aws4_request, SignedHeaders=host, Signature=abc";

    @Test
    void aClusterIsCreatedProtectedReadUpdatedAndDeleted() {
        String identifier = dsql().body(Map.of("tags", Map.of("Purpose", "test")))
                .post("/cluster").then().statusCode(200)
                .body("identifier", matchesPattern("[a-z2-7]{26}"))
                .body("arn", matchesPattern("arn:aws:dsql:eu-west-1:" + ACCOUNT + ":cluster/[a-z2-7]{26}"))
                .body("status", equalTo("CREATING"))
                .body("creationTime", instanceOf(Number.class))
                .body("encryptionDetails.encryptionType", equalTo("AWS_OWNED_KMS_KEY"))
                .body("encryptionDetails.encryptionStatus", equalTo("ENABLED"))
                .body("deletionProtectionEnabled", equalTo(true))
                .extract().path("identifier");

        dsql().get("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("tags.Purpose", equalTo("test"))
                .body("endpoint", equalTo(identifier + ".dsql.eu-west-1.localhost.floci.io"));
        dsql().get("/cluster").then().statusCode(200)
                .body("clusters.identifier", hasItem(identifier));

        dsql().delete("/cluster/" + identifier).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Can't delete cluster: Deletion protection is enabled. To proceed,"
                        + " disable deletion protection and try again."))
                .body("reason", equalTo("deletionProtectionEnabled"));

        dsql().body(Map.of("deletionProtectionEnabled", false)).post("/cluster/" + identifier)
                .then().statusCode(200)
                .body("status", equalTo("UPDATING"))
                .body("deletionProtectionEnabled", nullValue());
        dsql().get("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("deletionProtectionEnabled", equalTo(false));

        dsql().delete("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("DELETING"));
        dsql().get("/cluster/" + identifier).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("cluster"));
    }

    @Test
    void identifiersAreCheckedAsDsqlChecksThem() {
        dsql().get("/cluster/abc").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'identifier' failed to satisfy"
                        + " constraint: Member must satisfy regular expression pattern: [a-z0-9]{26}"));
        dsql().get("/cluster/abcdefghijklmnopqrstuvwxyz").then().statusCode(400)
                .body("message", equalTo("Invalid input 'identifier: abcdefghijklmnopqrstuvwxyz'. The cluster"
                        + " identifier format is invalid. Provide a valid cluster identifier and try again."));
        dsql().get("/cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa").then().statusCode(404)
                .body("message", equalTo("The resource with ARN arn:aws:dsql:eu-west-1:" + ACCOUNT
                        + ":cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa doesn't exist. Verify the ARN and try again."))
                .body("resourceId", equalTo("aaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    void aClientTokenMakesCreateIdempotent() {
        String first = dsql().body(Map.of("clientToken", "same-request", "deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        dsql().body(Map.of("clientToken", "same-request", "deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).body("identifier", equalTo(first));
        // Measured (eu-west-1, 2026-10-20): the same token with other parameters is a conflict.
        dsql().body(Map.of("clientToken", "same-request", "deletionProtectionEnabled", true))
                .post("/cluster").then().statusCode(409)
                .body("message", equalTo("Requests using a client token should supply identical request parameters"));
        dsql().delete("/cluster/" + first).then().statusCode(200);
    }

    /**
     * Measured (eu-west-1, 2026-10-20): text is false when it spells false in any case and true
     * otherwise; empty text, a number and an object fail to deserialize.
     */
    @Test
    void deletionProtectionIsReadAsDsqlReadsIt() {
        String yes = dsql().body(Map.of("deletionProtectionEnabled", "yes")).post("/cluster")
                .then().statusCode(200).body("deletionProtectionEnabled", equalTo(true)).extract().path("identifier");
        dsql().body(Map.of("deletionProtectionEnabled", "False")).post("/cluster/" + yes).then().statusCode(200);
        dsql().get("/cluster/" + yes).then().body("deletionProtectionEnabled", equalTo(false));
        dsql().body(Map.of("deletionProtectionEnabled", "TRUE")).post("/cluster/" + yes).then().statusCode(200);
        dsql().get("/cluster/" + yes).then().body("deletionProtectionEnabled", equalTo(true));

        dsql().body(Map.of("deletionProtectionEnabled", 1)).post("/cluster").then().statusCode(400)
                .body("message", equalTo("NUMBER_VALUE cannot be converted to Boolean"));
        dsql().body(Map.of("deletionProtectionEnabled", Map.of())).post("/cluster").then().statusCode(400)
                .body("message", equalTo("Start of structure or map found where not expected"));
        dsql().body(Map.of("deletionProtectionEnabled", "")).post("/cluster/" + yes).then().statusCode(400)
                .body("message", equalTo("Unexpected token received from parser"));

        dsql().body(Map.of("deletionProtectionEnabled", false)).post("/cluster/" + yes).then().statusCode(200);
        dsql().delete("/cluster/" + yes).then().statusCode(200);
    }

    /** Measured (eu-west-1, 2026-10-20): AWS_OWNED_KMS_KEY names AWS's own key, on create and update. */
    @Test
    void theReservedAwsOwnedKeyIsAwsOwned() {
        String identifier = dsql().body(Map.of("deletionProtectionEnabled", false, "kmsEncryptionKey", "AWS_OWNED_KMS_KEY"))
                .post("/cluster").then().statusCode(200)
                .body("encryptionDetails.encryptionType", equalTo("AWS_OWNED_KMS_KEY")).extract().path("identifier");
        dsql().body(Map.of("kmsEncryptionKey", "AWS_OWNED_KMS_KEY")).post("/cluster/" + identifier).then().statusCode(200);
        dsql().get("/cluster/" + identifier).then()
                .body("encryptionDetails.encryptionType", equalTo("AWS_OWNED_KMS_KEY"))
                .body("encryptionDetails.kmsKeyArn", nullValue());
        dsql().delete("/cluster/" + identifier).then().statusCode(200);
    }

    /** Measured (eu-west-1, 2026-10-20): the next page carries on after its first cluster is deleted. */
    @Test
    void aPageCarriesOnAfterItsNextClusterIsGone() {
        String auth = AUTH.replace("eu-west-1", "ap-southeast-4");
        for (int i = 0; i < 3; i++) {
            given().header("Authorization", auth).contentType("application/json")
                    .body(Map.of("deletionProtectionEnabled", false)).post("/cluster")
                    .then().statusCode(200);
        }
        List<String> order = given().header("Authorization", auth).get("/cluster")
                .then().statusCode(200).extract().jsonPath().getList("clusters.identifier", String.class);
        assertEquals(3, order.size());
        String token = given().header("Authorization", auth).queryParam("max-results", 1).get("/cluster")
                .then().statusCode(200).body("clusters[0].identifier", equalTo(order.get(0))).extract().path("nextToken");
        given().header("Authorization", auth).delete("/cluster/" + order.get(1)).then().statusCode(200);
        given().header("Authorization", auth).queryParam("max-results", 1).queryParam("next-token", token).get("/cluster")
                .then().statusCode(200).body("clusters[0].identifier", equalTo(order.get(2)));
        given().header("Authorization", auth).delete("/cluster/" + order.get(0)).then().statusCode(200);
        given().header("Authorization", auth).delete("/cluster/" + order.get(2)).then().statusCode(200);
    }

    /** Measured (eu-west-1, 2026-10-20): another account's cluster ARN is refused, not read in the caller's. */
    @Test
    void anotherAccountsArnIsRefused() {
        String identifier = dsql().body(Map.of("deletionProtectionEnabled", false)).post("/cluster")
                .then().statusCode(200).extract().path("identifier");
        String foreign = "arn:aws:dsql:eu-west-1:999999999999:cluster/" + identifier;
        dsql().urlEncodingEnabled(false).get("/tags/" + URLEncoder.encode(foreign, StandardCharsets.UTF_8))
                .then().statusCode(403)
                .body("message", equalTo("User: " + ROOT + " is not authorized to perform: dsql:ListTagsForResource on"
                        + " resource: " + foreign + " because no resource-based policy allows the"
                        + " dsql:ListTagsForResource action"));
        dsql().delete("/cluster/" + identifier).then().statusCode(200);
    }

    @Test
    void tagsPoliciesAndTheVpcEndpointServiceName() {
        String identifier = dsql().body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        String arn = "arn:aws:dsql:eu-west-1:" + ACCOUNT + ":cluster/" + identifier;
        String tagsPath = "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);

        dsql().body(Map.of("tags", Map.of("team", "data"))).urlEncodingEnabled(false)
                .post(tagsPath).then().statusCode(200);
        dsql().urlEncodingEnabled(false).get(tagsPath).then().statusCode(200)
                .body("tags.team", equalTo("data"));
        dsql().urlEncodingEnabled(false).queryParam("tagKeys", "team")
                .delete(tagsPath).then().statusCode(200);
        dsql().urlEncodingEnabled(false).get(tagsPath).then().statusCode(200)
                .body("tags", not(hasKey("team")));

        dsql().get("/clusters/" + identifier + "/vpc-endpoint-service-name").then().statusCode(200)
                .body("serviceName", matchesPattern("com\\.amazonaws\\.eu-west-1\\.dsql-[a-z2-7]{4}"))
                .body("clusterVpcEndpoint", matchesPattern(identifier + "\\.dsql-[a-z2-7]{4}\\.eu-west-1\\..+"));

        // Measured (eu-west-1, 2026-10-20): a version is when the policy was set, the policy is kept in
        // DSQL's own form, and a delete answers the version it deleted.
        String version = dsql().body(Map.of("policy", DENY_OUTSIDE_VPC))
                .post("/cluster/" + identifier + "/policy").then().statusCode(200)
                .body("policyVersion", matchesPattern("\\d{13}")).extract().path("policyVersion");
        dsql().get("/cluster/" + identifier + "/policy").then().statusCode(200)
                .body("policy", equalTo("""
                        {
                          "Version" : "2012-10-17",
                          "Statement" : [ {
                            "Effect" : "Deny",
                            "Principal" : {
                              "AWS" : "*"
                            },
                            "Action" : "dsql:DbConnect",
                            "Resource" : "*",
                            "Condition" : {
                              "StringNotEquals" : {
                                "aws:SourceVpc" : "vpc-0123456789abcdef0"
                              }
                            }
                          } ]
                        }"""))
                .body("policyVersion", equalTo(version));
        dsql().delete("/cluster/" + identifier + "/policy").then().statusCode(200)
                .body("policyVersion", equalTo(version));
        dsql().get("/cluster/" + identifier + "/policy").then().statusCode(404)
                .body("message", equalTo("Aurora DSQL can't find the policy for the resource " + identifier
                        + ". Verify that the policy exists."))
                .body("resourceType", equalTo("policy"));

        dsql().delete("/cluster/" + identifier).then().statusCode(200);
    }

    /** Measured (eu-west-1, 2026-10-20): the three ways a tag call is refused before it finds a cluster. */
    @Test
    void aTagCallRefusesWhatIsNoClusterOfTheRegion() {
        String malformed = "arn:aws:dsql:eu-west-1:" + ACCOUNT + ":cluster/abc";
        String otherRegion = "arn:aws:dsql:us-east-1:" + ACCOUNT + ":cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa";
        String missing = "arn:aws:dsql:eu-west-1:" + ACCOUNT + ":cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa";

        dsql().urlEncodingEnabled(false).get("/tags/" + URLEncoder.encode(malformed, StandardCharsets.UTF_8))
                .then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Invalid input for 'resourceArn'. The cluster ARN that you specified isn't"
                        + " valid. Provide the cluster ARN in the format"
                        + " 'arn:partition:dsql:region:account-id:cluster/cluster-id' and try again.User: " + ROOT
                        + " is not authorized to perform: dsql:ListTagsForResource on resource: * with an explicit deny"
                        + " in a resource-based policy"));
        dsql().urlEncodingEnabled(false).get("/tags/" + URLEncoder.encode(otherRegion, StandardCharsets.UTF_8))
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid input resource arn: expected region eu-west-1"));
        dsql().urlEncodingEnabled(false).get("/tags/" + URLEncoder.encode(missing, StandardCharsets.UTF_8))
                .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Resource was not found"));
    }

    /** Measured (eu-west-1, 2026-10-20): only the page token's refusal names a reason and the member. */
    @Test
    void listingRefusesWhatItCannotPage() {
        dsql().queryParam("next-token", "garbage").get("/cluster").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Invalid pagination token. Request a new token and try again."))
                .body("reason", equalTo("fieldValidationFailed"))
                .body("fieldList[0].name", equalTo("next-token"))
                .body("fieldList[0].message", equalTo("Invalid pagination token. Request a new token and try again."));
        dsql().queryParam("max-results", 0).get("/cluster").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'maxResults' failed to satisfy"
                        + " constraint: Member must have value greater than or equal to 1"))
                .body("reason", nullValue());
    }

    /** Measured (eu-west-1, 2026-10-20): what PutClusterPolicy refuses, and the lockout it can be told to allow. */
    @Test
    void aPolicyIsRefusedAsDsqlRefusesIt() {
        String identifier = dsql().body(Map.of("deletionProtectionEnabled", false)).post("/cluster")
                .then().statusCode(200).extract().path("identifier");
        String policyPath = "/cluster/" + identifier + "/policy";
        String lockout = DENY_OUTSIDE_VPC.replace("\"dsql:DbConnect\"", "\"*\"");

        dsql().body(Map.of("policy", "")).post(policyPath).then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'policy' failed to satisfy constraint:"
                        + " Member must have length greater than or equal to 1"));
        dsql().body(Map.of("policy", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":\"*\",\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"}]}"))
                .post(policyPath).then().statusCode(400)
                .body("message", equalTo("Aurora DSQL can't apply the policy because it grants public access. Modify"
                        + " the policy to restrict access and try again."));
        dsql().body(Map.of("policy", lockout)).post(policyPath).then().statusCode(400)
                .body("message", equalTo("The new policy would result in the calling principal being unable to"
                        + " further update and delete this cluster's policy. Set the 'bypassPolicyLockoutSafetyCheck'"
                        + " parameter to 'true' to confirm this intent."));
        dsql().body(Map.of("policy", lockout, "bypassPolicyLockoutSafetyCheck", true)).post(policyPath)
                .then().statusCode(200);

        dsql().delete("/cluster/" + identifier).then().statusCode(200);
    }

    /** Measured (eu-west-1, 2026-10-20): a multi-Region cluster waits for its peers, and so does its deletion. */
    @Test
    void aMultiRegionClusterWaitsForItsPeers() {
        String arn = dsql().body(Map.of("deletionProtectionEnabled", false,
                        "multiRegionProperties", Map.of("witnessRegion", "eu-west-2")))
                .post("/cluster").then().statusCode(200)
                .body("status", equalTo("PENDING_SETUP"))
                .extract().path("arn");
        String identifier = arn.substring(arn.lastIndexOf('/') + 1);

        dsql().get("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("PENDING_SETUP"))
                .body("multiRegionProperties.witnessRegion", equalTo("eu-west-2"))
                .body("multiRegionProperties.clusters", equalTo(List.of(arn)));
        dsql().delete("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("PENDING_DELETE"));
    }

    private static RequestSpecification dsql() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }
}
