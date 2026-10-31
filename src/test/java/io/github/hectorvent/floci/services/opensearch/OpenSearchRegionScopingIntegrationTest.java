package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * OpenSearch domains are regional: a name is unique inside one account and region, and
 * describe, update, delete and list resolve only in the region the request is signed for.
 */
@QuarkusTest
class OpenSearchRegionScopingIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";
    private static final String OTHER_REGION_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-1/es/aws4_request";
    private static final String SHARED_NAME = "shared-regional";
    private static final String EAST_ONLY_NAME = "east-regional";
    private static final String WEST_ONLY_NAME = "west-regional";

    @AfterEach
    void cleanup() {
        for (String name : new String[]{SHARED_NAME, EAST_ONLY_NAME, WEST_ONLY_NAME}) {
            given().header("Authorization", AUTH_HEADER)
                    .when().delete("/2021-01-01/opensearch/domain/" + name);
            given().header("Authorization", OTHER_REGION_AUTH_HEADER)
                    .when().delete("/2021-01-01/opensearch/domain/" + name);
        }
    }

    @Test
    void theSameNameCoexistsInTwoRegionsAndEachArnCarriesItsRegion() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200)
            .body("DomainStatus.ARN", equalTo("arn:aws:es:us-east-1:000000000000:domain/" + SHARED_NAME));

        given()
            .contentType("application/json")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200)
            .body("DomainStatus.ARN", equalTo("arn:aws:es:eu-west-1:000000000000:domain/" + SHARED_NAME));
    }

    @Test
    void createConflictsOnlyWithinTheSameRegion() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(409);

        given()
            .contentType("application/json")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);
    }

    @Test
    void describeAndDescribeConfigSeeOnlyTheRequestRegionsDomains() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + EAST_ONLY_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + EAST_ONLY_NAME)
        .then()
            .statusCode(409);

        given()
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + EAST_ONLY_NAME + "/config")
        .then()
            .statusCode(409);
    }

    @Test
    void listDomainNamesListsOnlyTheRequestRegionsDomains() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + EAST_ONLY_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
            .body("{\"DomainName\":\"" + WEST_ONLY_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/domain")
        .then()
            .statusCode(200)
            .body("DomainNames.DomainName", hasItem(EAST_ONLY_NAME))
            .body("DomainNames.DomainName", not(hasItem(WEST_ONLY_NAME)));

        given()
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .get("/2021-01-01/domain")
        .then()
            .statusCode(200)
            .body("DomainNames.DomainName", hasItem(WEST_ONLY_NAME))
            .body("DomainNames.DomainName", not(hasItem(EAST_ONLY_NAME)));
    }

    @Test
    void updateInOneRegionLeavesTheOtherRegionUntouched() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ClusterConfig\":{\"InstanceCount\":5}}")
        .when()
            .post("/2021-01-01/opensearch/domain/" + SHARED_NAME + "/config")
        .then()
            .statusCode(200)
            .body("DomainConfig.ClusterConfig.Options.InstanceCount", equalTo(5));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + SHARED_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.ClusterConfig.InstanceCount", equalTo(5));

        given()
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + SHARED_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.ClusterConfig.InstanceCount", equalTo(1));
    }

    @Test
    void deleteInOneRegionLeavesTheOtherRegionIntact() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/2021-01-01/opensearch/domain/" + SHARED_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.Deleted", equalTo(true));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + SHARED_NAME)
        .then()
            .statusCode(409);

        given()
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + SHARED_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.ARN", equalTo("arn:aws:es:eu-west-1:000000000000:domain/" + SHARED_NAME));
    }

    @Test
    void upgradeDomainResolvesOnlyInTheRequestRegion() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
            .body("{\"DomainName\":\"" + SHARED_NAME + "\",\"TargetVersion\":\"OpenSearch_2.13\"}")
        .when()
            .post("/2021-01-01/opensearch/upgradeDomain")
        .then()
            .statusCode(409);
    }

    @Test
    void tagOperationsRejectAForeignAccountArnAsNotFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + EAST_ONLY_NAME + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200);

        String foreignArn = "arn:aws:es:us-east-1:387263792837:domain/" + EAST_ONLY_NAME;

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + foreignArn + "\",\"TagList\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
            .post("/2021-01-01/tags")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("arn", foreignArn)
        .when()
            .get("/2021-01-01/tags/")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + foreignArn + "\",\"TagKeys\":[\"env\"]}")
        .when()
            .post("/2021-01-01/tags-removal")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceNotFoundException"));

        String localArn = "arn:aws:es:us-east-1:000000000000:domain/" + EAST_ONLY_NAME;
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + localArn + "\",\"TagList\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
            .post("/2021-01-01/tags")
        .then()
            .statusCode(200);
    }
}
