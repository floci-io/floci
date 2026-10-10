package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** The legacy Elasticsearch Service API ({@code 2015-01-01}) over the shared OpenSearch store. */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElasticsearchIntegrationTest {

    private static final String DOMAIN_NAME = "legacy-es-domain";
    private static final String AUTH_HEADER = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";

    private static String arn;

    @Test
    @Order(1)
    void createElasticsearchDomain() {
        arn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"" + DOMAIN_NAME + "\",\"ElasticsearchVersion\":\"7.10\","
                    + "\"ElasticsearchClusterConfig\":{\"InstanceType\":\"t3.small.elasticsearch\",\"InstanceCount\":2},"
                    + "\"EBSOptions\":{\"EBSEnabled\":true,\"VolumeType\":\"gp2\",\"VolumeSize\":20}}")
        .when()
            .post("/2015-01-01/es/domain")
        .then()
            .statusCode(200)
            .body("DomainStatus.DomainName", equalTo(DOMAIN_NAME))
            .body("DomainStatus.ElasticsearchVersion", equalTo("7.10"))
            .body("DomainStatus.ElasticsearchClusterConfig.InstanceType", equalTo("t3.small.elasticsearch"))
            .body("DomainStatus.ElasticsearchClusterConfig.InstanceCount", equalTo(2))
            .body("DomainStatus", not(hasKey("EngineVersion")))
            .body("DomainStatus", not(hasKey("ClusterConfig")))
            .body("DomainStatus.ARN", containsString(":domain/" + DOMAIN_NAME))
            .extract().path("DomainStatus.ARN");
    }

    @Test
    @Order(2)
    void storedAsElasticsearchEngineVersion() {
        // Read back through the OpenSearch API: the legacy version must be stored prefixed.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.EngineVersion", equalTo("Elasticsearch_7.10"))
            .body("DomainStatus.ClusterConfig.InstanceCount", equalTo(2));
    }

    @Test
    @Order(3)
    void describeElasticsearchDomain() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2015-01-01/es/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.ElasticsearchVersion", equalTo("7.10"))
            .body("DomainStatus.ElasticsearchClusterConfig.InstanceType", equalTo("t3.small.elasticsearch"))
            .body("DomainStatus.EBSOptions.VolumeSize", equalTo(20));
    }

    @Test
    @Order(3)
    void describeElasticsearchDomains() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainNames\":[\"" + DOMAIN_NAME + "\"]}")
        .when()
            .post("/2015-01-01/es/domain-info")
        .then()
            .statusCode(200)
            .body("DomainStatusList", hasSize(1))
            .body("DomainStatusList[0].ElasticsearchVersion", equalTo("7.10"))
            .body("DomainStatusList[0].ElasticsearchClusterConfig.InstanceCount", equalTo(2));
    }

    @Test
    @Order(3)
    void listDomainNames() {
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("engineType", "Elasticsearch")
        .when()
            .get("/2015-01-01/domain")
        .then()
            .statusCode(200)
            .body("DomainNames.DomainName", hasItem(DOMAIN_NAME))
            .body("DomainNames.find { it.DomainName == '" + DOMAIN_NAME + "' }.EngineType",
                    equalTo("Elasticsearch"));
    }

    @Test
    @Order(4)
    void describeElasticsearchDomainConfig() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2015-01-01/es/domain/" + DOMAIN_NAME + "/config")
        .then()
            .statusCode(200)
            .body("DomainConfig.ElasticsearchVersion.Options", equalTo("7.10"))
            .body("DomainConfig.ElasticsearchVersion.Status.State", equalTo("Active"))
            .body("DomainConfig.ElasticsearchClusterConfig.Options.InstanceCount", equalTo(2))
            .body("DomainConfig", not(hasKey("EngineVersion")))
            .body("DomainConfig", not(hasKey("ClusterConfig")));
    }

    @Test
    @Order(5)
    void updateElasticsearchDomainConfig() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ElasticsearchClusterConfig\":{\"InstanceType\":\"m5.large.elasticsearch\",\"InstanceCount\":3}}")
        .when()
            .post("/2015-01-01/es/domain/" + DOMAIN_NAME + "/config")
        .then()
            .statusCode(200)
            .body("DomainConfig.ElasticsearchClusterConfig.Options.InstanceCount", equalTo(3));

        // The update must reach the store, not just the response.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2015-01-01/es/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.ElasticsearchClusterConfig.InstanceType", equalTo("m5.large.elasticsearch"))
            .body("DomainStatus.ElasticsearchClusterConfig.InstanceCount", equalTo(3))
            .body("DomainStatus.ElasticsearchVersion", equalTo("7.10"));
    }

    @Test
    @Order(6)
    void addListRemoveTags() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + arn + "\",\"TagList\":[{\"Key\":\"env\",\"Value\":\"test\"},{\"Key\":\"owner\",\"Value\":\"team\"}]}")
        .when()
            .post("/2015-01-01/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("arn", arn)
        .when()
            .get("/2015-01-01/tags/")
        .then()
            .statusCode(200)
            .body("TagList.Key", containsInAnyOrder("env", "owner"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ARN\":\"" + arn + "\",\"TagKeys\":[\"owner\"]}")
        .when()
            .post("/2015-01-01/tags-removal")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("arn", arn)
        .when()
            .get("/2015-01-01/tags/")
        .then()
            .statusCode(200)
            .body("TagList.Key", contains("env"));
    }

    @Test
    @Order(7)
    void createWithoutVersionIsElasticsearch() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"legacy-es-default\"}")
        .when()
            .post("/2015-01-01/es/domain")
        .then()
            .statusCode(200)
            .body("DomainStatus.ElasticsearchVersion", equalTo("7.10"));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/2015-01-01/es/domain/legacy-es-default")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void deleteElasticsearchDomain() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/2015-01-01/es/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(200)
            .body("DomainStatus.Deleted", equalTo(true))
            .body("DomainStatus.ElasticsearchVersion", equalTo("7.10"));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2015-01-01/es/domain/" + DOMAIN_NAME)
        .then()
            .statusCode(409);
    }
}
