package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenSearchIntegrationTest {

    private static final String DOMAIN_NAME = "test-domain";
    private static final String AUTH_HEADER = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";

    // ── Domain CRUD ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createDomain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200)
            .body("DomainStatus.DomainName", equalTo(DOMAIN_NAME))
            .body("DomainStatus.EngineVersion", equalTo("OpenSearch_2.11"))
            .body("DomainStatus.Processing", equalTo(false))
            .body("DomainStatus.Deleted", equalTo(false))
            .body("DomainStatus.ARN", containsString("arn:aws:es:"))
            .body("DomainStatus.ARN", containsString(DOMAIN_NAME));
    }

    @Test
    @Order(2)
    void createDuplicateDomainFails() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(2)
    void createDomainRejectsUnknownEngineVersion() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"bogus-version-domain\",\"EngineVersion\":\"OpenSearch_99.0\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(3)
    void describeDomain() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.DomainName", equalTo(DOMAIN_NAME))
            .body("DomainStatus.EngineVersion", equalTo("OpenSearch_2.11"))
            .body("DomainStatus.ClusterConfig.InstanceType", equalTo("m5.large.search"))
            .body("DomainStatus.ClusterConfig.InstanceCount", equalTo(1))
            .body("DomainStatus.EBSOptions.EBSEnabled", equalTo(true));
    }

    @Test
    @Order(4)
    void describeDomains() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainNames\":[\"" + DOMAIN_NAME + "\"]}")
        .when()
            .post("/2021-01-01/opensearch/domain-info")
        .then()
            .statusCode(200)
            .body("DomainStatusList", hasSize(1))
            .body("DomainStatusList[0].DomainName", equalTo(DOMAIN_NAME));
    }

    @Test
    @Order(5)
    void listDomainNames() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/domain")
        .then()
            .statusCode(200)
            .body("DomainNames", hasSize(greaterThanOrEqualTo(1)))
            .body("DomainNames.DomainName", hasItem(DOMAIN_NAME));
    }

    @Test
    @Order(6)
    void listDomainNamesFilteredByEngineType() {
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("engineType", "OpenSearch")
        .when()
            .get("/2021-01-01/domain")
        .then()
            .statusCode(200)
            .body("DomainNames.DomainName", hasItem(DOMAIN_NAME));
    }

    @Test
    @Order(7)
    void describeDomainConfig() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/config")
        .then()
            .statusCode(200)
            .body("DomainConfig.ClusterConfig.Options.InstanceType", equalTo("m5.large.search"))
            .body("DomainConfig.ClusterConfig.Status.State", equalTo("Active"))
            .body("DomainConfig.EBSOptions.Options.EBSEnabled", equalTo(true))
            .body("DomainConfig.EngineVersion.Options", equalTo("OpenSearch_2.11"));
    }

    @Test
    @Order(8)
    void updateDomainConfig() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ClusterConfig\":{\"InstanceCount\":3}}")
        .when()
            .post("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/config")
        .then()
            .statusCode(200)
            .body("DomainConfig.ClusterConfig.Options.InstanceCount", equalTo(3));
    }

    @Test
    @Order(9)
    void describeNonExistentDomain() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/nonexistent-domain")
        .then()
            .statusCode(409);
    }

    // ── Tags ─────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void addTags() {
        String arn = "arn:aws:es:us-east-1:000000000000:domain/" + DOMAIN_NAME;
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + arn + "\",\"TagList\":[{\"Key\":\"env\",\"Value\":\"test\"},{\"Key\":\"owner\",\"Value\":\"team\"}]}")
        .when()
            .post("/2021-01-01/tags")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void listTags() {
        String arn = "arn:aws:es:us-east-1:000000000000:domain/" + DOMAIN_NAME;
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("arn", arn)
        .when()
            .get("/2021-01-01/tags/")
        .then()
            .statusCode(200)
            .body("TagList.Key", hasItem("env"))
            .body("TagList.Key", hasItem("owner"));
    }

    @Test
    @Order(12)
    void removeTags() {
        String arn = "arn:aws:es:us-east-1:000000000000:domain/" + DOMAIN_NAME;
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + arn + "\",\"TagKeys\":[\"owner\"]}")
        .when()
            .post("/2021-01-01/tags-removal")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("arn", arn)
        .when()
            .get("/2021-01-01/tags/")
        .then()
            .statusCode(200)
            .body("TagList.Key", not(hasItem("owner")))
            .body("TagList.Key", hasItem("env"));
    }

    // ── Versions & Instance Types ─────────────────────────────────────────────

    @Test
    @Order(13)
    void listVersions() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/versions")
        .then()
            .statusCode(200)
            .body("Versions", not(empty()))
            .body("Versions", hasItem("OpenSearch_3.6"))
            .body("Versions", hasItem("OpenSearch_2.19"))
            .body("Versions", hasItem("OpenSearch_2.11"))
            .body("Versions", hasItem("Elasticsearch_7.10"));
    }

    @Test
    @Order(14)
    void getCompatibleVersions() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/compatibleVersions")
        .then()
            .statusCode(200)
            .body("CompatibleVersions", not(empty()))
            .body("CompatibleVersions.SourceVersion", hasItem("OpenSearch_2.19"));
    }

    @Test
    @Order(14)
    void getCompatibleVersionsForDomain() {
        // domainName-scoped query should only return the source/target row for
        // the named domain, not the full matrix.
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("domainName", DOMAIN_NAME)
        .when()
            .get("/2021-01-01/opensearch/compatibleVersions")
        .then()
            .statusCode(200)
            .body("CompatibleVersions", hasSize(1))
            .body("CompatibleVersions[0].SourceVersion", equalTo("OpenSearch_2.11"))
            .body("CompatibleVersions[0].TargetVersions", hasItem("OpenSearch_2.13"));
    }

    @Test
    @Order(15)
    void listInstanceTypeDetails() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/instanceTypeDetails/OpenSearch_2.11")
        .then()
            .statusCode(200)
            .body("InstanceTypeDetails", not(empty()));
    }

    @Test
    @Order(16)
    void describeInstanceTypeLimits() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/instanceTypeLimits/OpenSearch_2.11/m5.large.search")
        .then()
            .statusCode(200)
            .body("LimitsByRole", notNullValue());
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    @Test
    @Order(17)
    void describeDomainChangeProgress() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/progress")
        .then()
            .statusCode(200)
            .body("ChangeProgressStatus", notNullValue());
    }

    @Test
    @Order(18)
    void describeDomainAutoTunes() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/autoTunes")
        .then()
            .statusCode(200)
            .body("AutoTunes", empty());
    }

    @Test
    @Order(19)
    void describeDryRunProgress() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/dryRun")
        .then()
            .statusCode(200)
            .body("DryRunProgressStatus", notNullValue());
    }

    @Test
    @Order(20)
    void describeDomainHealth() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/health")
        .then()
            .statusCode(200)
            .body("ClusterHealth", equalTo("Green"));
    }

    @Test
    @Order(21)
    void getUpgradeHistory() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/upgradeDomain/" + DOMAIN_NAME + "/history")
        .then()
            .statusCode(200)
            .body("UpgradeHistories", empty());
    }

    @Test
    @Order(22)
    void getUpgradeStatus() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/upgradeDomain/" + DOMAIN_NAME + "/status")
        .then()
            .statusCode(200)
            .body("UpgradeStep", equalTo("UPGRADE"))
            .body("StepStatus", equalTo("SUCCEEDED"));
    }

    @Test
    @Order(23)
    void upgradeDomain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\",\"TargetVersion\":\"OpenSearch_2.13\"}")
        .when()
            .post("/2021-01-01/opensearch/upgradeDomain")
        .then()
            .statusCode(200)
            .body("DomainName", equalTo(DOMAIN_NAME))
            .body("TargetVersion", equalTo("OpenSearch_2.13"));
    }

    @Test
    @Order(23)
    void upgradeDomainRejectsUnsupportedTarget() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\",\"TargetVersion\":\"OpenSearch_99.0\"}")
        .when()
            .post("/2021-01-01/opensearch/upgradeDomain")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(24)
    void cancelDomainConfigChange() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/2021-01-01/opensearch/domain/" + DOMAIN_NAME + "/config/cancel")
        .then()
            .statusCode(200)
            .body("CancelledChangeIds", empty());
    }

    @Test
    @Order(25)
    void startServiceSoftwareUpdate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/serviceSoftwareUpdate/start")
        .then()
            .statusCode(200)
            .body("ServiceSoftwareOptions.UpdateStatus", equalTo("COMPLETED"));
    }

    @Test
    @Order(26)
    void cancelServiceSoftwareUpdate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\"}")
        .when()
            .post("/2021-01-01/opensearch/serviceSoftwareUpdate/cancel")
        .then()
            .statusCode(200)
            .body("ServiceSoftwareOptions.UpdateStatus", equalTo("COMPLETED"));
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void deleteDomain() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/2021-01-01/opensearch/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.DomainName", equalTo(DOMAIN_NAME))
            .body("DomainStatus.Deleted", equalTo(true));
    }

    @Test
    @Order(31)
    void deleteNonExistentDomain() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/2021-01-01/opensearch/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(409);
    }
}
