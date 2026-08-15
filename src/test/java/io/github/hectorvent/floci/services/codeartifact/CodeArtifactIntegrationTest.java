package io.github.hectorvent.floci.services.codeartifact;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactIntegrationTest {

    private static final String DOMAIN = "floci-integration";
    private static final String REPOSITORY = "floci-repo";
    private static final String UPSTREAM_REPOSITORY = "floci-upstream";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private static String domainArn;
    private static String repositoryArn;
    private static String domainPolicyRevision;

    @Test
    @Order(1)
    void createDomain() {
        domainArn = given()
            .contentType("application/json")
            .body("""
                {"tags": [{"key": "team", "value": "devtools"}]}
                """)
        .when()
            .post("/v1/domain?domain=" + DOMAIN)
        .then()
            .statusCode(200)
            .body("domain.name", equalTo(DOMAIN))
            .body("domain.owner", equalTo("000000000000"))
            .body("domain.arn", containsString(":codeartifact:"))
            .body("domain.arn", endsWith(":domain/" + DOMAIN))
            .body("domain.status", equalTo("Active"))
            .body("domain.createdTime", notNullValue())
            .body("domain.encryptionKey", containsString(":kms:"))
            .body("domain.repositoryCount", equalTo(0))
            .body("domain.assetSizeBytes", equalTo(0))
            .body("domain.s3BucketArn", containsString("arn:aws:s3:::assets-"))
            .extract().path("domain.arn");
    }

    @Test
    @Order(2)
    void describeDomainIsActiveOnFirstRead() {
        given()
        .when()
            .get("/v1/domain?domain=" + DOMAIN + "&domain-owner=000000000000")
        .then()
            .statusCode(200)
            .body("domain.name", equalTo(DOMAIN))
            .body("domain.status", equalTo("Active"))
            .body("domain.arn", equalTo(domainArn));
    }

    @Test
    @Order(3)
    void describeMissingDomainReturnsResourceNotFound() {
        given()
        .when()
            .get("/v1/domain?domain=no-such-domain")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void createDomainRejectsInvalidName() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/domain?domain=Invalid_Domain")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(5)
    void listDomains() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/domains")
        .then()
            .statusCode(200)
            .body("domains.name", hasItem(DOMAIN))
            .body("domains.status", hasItem("Active"));
    }

    @Test
    @Order(6)
    void domainPermissionsPolicyRoundTrip() {
        domainPolicyRevision = given()
            .contentType("application/json")
            .body("""
                {"domain": "%s", "policyDocument": %s}
                """.formatted(DOMAIN, quote(POLICY)))
        .when()
            .put("/v1/domain/permissions/policy")
        .then()
            .statusCode(200)
            .body("policy.resourceArn", equalTo(domainArn))
            .body("policy.document", equalTo(POLICY))
            .body("policy.revision", notNullValue())
            .extract().path("policy.revision");

        given()
        .when()
            .get("/v1/domain/permissions/policy?domain=" + DOMAIN)
        .then()
            .statusCode(200)
            .body("policy.document", equalTo(POLICY))
            .body("policy.revision", equalTo(domainPolicyRevision));
    }

    @Test
    @Order(7)
    void putDomainPolicyWithStaleRevisionConflicts() {
        given()
            .contentType("application/json")
            .body("""
                {"domain": "%s", "policyRevision": "stale", "policyDocument": %s}
                """.formatted(DOMAIN, quote(POLICY)))
        .when()
            .put("/v1/domain/permissions/policy")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(8)
    void deleteDomainPermissionsPolicy() {
        given()
        .when()
            .delete("/v1/domain/permissions/policy?domain=" + DOMAIN
                    + "&policy-revision=" + domainPolicyRevision)
        .then()
            .statusCode(200)
            .body("policy.document", equalTo(POLICY));

        given()
        .when()
            .get("/v1/domain/permissions/policy?domain=" + DOMAIN)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(9)
    void createRepository() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/repository?domain=" + DOMAIN + "&repository=" + UPSTREAM_REPOSITORY)
        .then()
            .statusCode(200)
            .body("repository.name", equalTo(UPSTREAM_REPOSITORY));

        repositoryArn = given()
            .contentType("application/json")
            .body("""
                {"description": "integration repository",
                 "upstreams": [{"repositoryName": "%s"}],
                 "tags": [{"key": "team", "value": "devtools"}]}
                """.formatted(UPSTREAM_REPOSITORY))
        .when()
            .post("/v1/repository?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("repository.name", equalTo(REPOSITORY))
            .body("repository.domainName", equalTo(DOMAIN))
            .body("repository.domainOwner", equalTo("000000000000"))
            .body("repository.administratorAccount", equalTo("000000000000"))
            .body("repository.arn", endsWith(":repository/" + DOMAIN + "/" + REPOSITORY))
            .body("repository.description", equalTo("integration repository"))
            .body("repository.upstreams[0].repositoryName", equalTo(UPSTREAM_REPOSITORY))
            .body("repository.externalConnections", emptyIterable())
            .body("repository.createdTime", notNullValue())
            .extract().path("repository.arn");
    }

    @Test
    @Order(10)
    void describeRepository() {
        given()
        .when()
            .get("/v1/repository?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("repository.name", equalTo(REPOSITORY))
            .body("repository.arn", equalTo(repositoryArn))
            .body("repository.upstreams[0].repositoryName", equalTo(UPSTREAM_REPOSITORY));
    }

    @Test
    @Order(11)
    void describeMissingRepositoryReturnsResourceNotFound() {
        given()
        .when()
            .get("/v1/repository?domain=" + DOMAIN + "&repository=no-such-repo")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void updateRepository() {
        given()
            .contentType("application/json")
            .body("{\"description\": \"updated description\", \"upstreams\": []}")
        .when()
            .put("/v1/repository?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("repository.description", equalTo("updated description"))
            .body("repository.upstreams", emptyIterable());
    }

    @Test
    @Order(13)
    void listRepositories() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/repositories")
        .then()
            .statusCode(200)
            .body("repositories.name", hasItem(REPOSITORY));

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/domain/repositories?domain=" + DOMAIN)
        .then()
            .statusCode(200)
            .body("repositories.name", hasItem(REPOSITORY))
            .body("repositories.domainName", hasItem(DOMAIN));
    }

    @Test
    @Order(14)
    void repositoryPermissionsPolicyRoundTrip() {
        given()
            .contentType("application/json")
            .body("{\"policyDocument\": " + quote(POLICY) + "}")
        .when()
            .put("/v1/repository/permissions/policy?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("policy.resourceArn", equalTo(repositoryArn))
            .body("policy.document", equalTo(POLICY));

        given()
        .when()
            .get("/v1/repository/permissions/policy?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("policy.document", equalTo(POLICY));

        given()
        .when()
            .delete("/v1/repository/permissions/policies?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("policy.document", equalTo(POLICY));

        given()
        .when()
            .get("/v1/repository/permissions/policy?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(15)
    void externalConnectionRoundTrip() {
        given()
        .when()
            .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPOSITORY
                    + "&external-connection=public:npmjs")
        .then()
            .statusCode(200)
            .body("repository.externalConnections[0].externalConnectionName", equalTo("public:npmjs"))
            .body("repository.externalConnections[0].packageFormat", equalTo("npm"))
            .body("repository.externalConnections[0].status", equalTo("Available"));

        given()
        .when()
            .get("/v1/repository?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("repository.externalConnections[0].packageFormat", equalTo("npm"));

        given()
        .when()
            .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPOSITORY
                    + "&external-connection=public:pypi")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));

        given()
        .when()
            .delete("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPOSITORY
                    + "&external-connection=public:npmjs")
        .then()
            .statusCode(200)
            .body("repository.externalConnections", emptyIterable());
    }

    @Test
    @Order(16)
    void getRepositoryEndpoint() {
        given()
        .when()
            .get("/v1/repository/endpoint?domain=" + DOMAIN + "&repository=" + REPOSITORY + "&format=npm")
        .then()
            .statusCode(200)
            .body("repositoryEndpoint", endsWith("/npm/" + REPOSITORY + "/"));

        given()
        .when()
            .get("/v1/repository/endpoint?domain=" + DOMAIN + "&repository=" + REPOSITORY + "&format=cocoapods")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(17)
    void tagRoundTrip() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/tags?resourceArn=" + repositoryArn)
        .then()
            .statusCode(200)
            .body("tags.key", hasItem("team"))
            .body("tags.value", hasItem("devtools"));

        given()
            .contentType("application/json")
            .body("{\"tags\": [{\"key\": \"env\", \"value\": \"test\"}]}")
        .when()
            .post("/v1/tag?resourceArn=" + repositoryArn)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/tags?resourceArn=" + repositoryArn)
        .then()
            .statusCode(200)
            .body("tags.key", hasItem("env"));

        given()
            .contentType("application/json")
            .body("{\"tagKeys\": [\"env\"]}")
        .when()
            .post("/v1/untag?resourceArn=" + repositoryArn)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/tags?resourceArn=" + repositoryArn)
        .then()
            .statusCode(200)
            .body("tags.key", hasItem("team"))
            .body("tags.findAll { it.key == 'env' }", emptyIterable());
    }

    @Test
    @Order(18)
    void tagDomainByArn() {
        given()
            .contentType("application/json")
            .body("{\"tags\": [{\"key\": \"tier\", \"value\": \"shared\"}]}")
        .when()
            .post("/v1/tag?resourceArn=" + domainArn)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/tags?resourceArn=" + domainArn)
        .then()
            .statusCode(200)
            .body("tags.key", hasItem("tier"));
    }

    @Test
    @Order(19)
    void deleteDomainWithRepositoriesConflicts() {
        given()
        .when()
            .delete("/v1/domain?domain=" + DOMAIN)
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(20)
    void deleteRepositoriesThenDomain() {
        given()
        .when()
            .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(200)
            .body("repository.name", equalTo(REPOSITORY));

        given()
        .when()
            .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + UPSTREAM_REPOSITORY)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/v1/repository?domain=" + DOMAIN + "&repository=" + REPOSITORY)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .delete("/v1/domain?domain=" + DOMAIN)
        .then()
            .statusCode(200)
            .body("domain.name", equalTo(DOMAIN));

        given()
        .when()
            .get("/v1/domain?domain=" + DOMAIN)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
