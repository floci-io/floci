package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CodeArtifactIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void domainAndRepositoryLifecycle() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}]}")
                .post("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(200)
                .body("domain.name", equalTo("lifecycle-domain"))
                .body("domain.repositoryCount", equalTo(0))
                .body("domain.arn", notNullValue());

        given().header("Authorization", AUTH).get("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(200).body("domain.status", equalTo("Active"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"description\":\"a repo\"}")
                .post("/v1/repository?domain=lifecycle-domain&repository=lifecycle-repo")
                .then().statusCode(200)
                .body("repository.name", equalTo("lifecycle-repo"))
                .body("repository.domainName", equalTo("lifecycle-domain"))
                .body("repository.upstreams", emptyIterable());

        given().header("Authorization", AUTH)
                .get("/v1/repository/endpoint?domain=lifecycle-domain&repository=lifecycle-repo&format=npm")
                .then().statusCode(200)
                .body("repositoryEndpoint", equalTo("http://localhost:4566/codeartifact/npm/lifecycle-domain/lifecycle-repo/"));

        given().header("Authorization", AUTH)
                .delete("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(409).body("__type", equalTo("ConflictException"));

        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=lifecycle-domain&repository=lifecycle-repo")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .delete("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(200);
    }

    @Test
    void tagResourceListTagsAndUntagResourceRoundTrip() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=tag-domain").then().statusCode(200);

        String arn = "arn:aws:codeartifact:us-east-1:000000000000:domain/tag-domain";

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}")
                .post("/v1/tag?resourceArn=" + arn)
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .post("/v1/tags?resourceArn=" + arn)
                .then().statusCode(200)
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("team"))
                .body("tags[0].value", equalTo("data"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"tagKeys\":[\"team\"]}")
                .post("/v1/untag?resourceArn=" + arn)
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .post("/v1/tags?resourceArn=" + arn)
                .then().statusCode(200).body("tags", hasSize(0));
    }

    @Test
    void listDomainsReturnsSummaryShapeNotFullDescription() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=summary-domain").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domains")
                .then().statusCode(200)
                .body("domains.find { it.name == 'summary-domain' }.owner", notNullValue())
                .body("domains.find { it.name == 'summary-domain' }.repositoryCount", equalTo(null));
    }

    @Test
    void deleteRepositoryPermissionsPolicyUsesPluralPath() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=policy-domain").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/repository?domain=policy-domain&repository=policy-repo")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"policyDocument\":\"{}\"}")
                .put("/v1/repository/permissions/policy?domain=policy-domain&repository=policy-repo")
                .then().statusCode(200).body("policy.document", equalTo("{}"));

        given().header("Authorization", AUTH)
                .delete("/v1/repository/permissions/policies?domain=policy-domain&repository=policy-repo")
                .then().statusCode(200);
    }

    @Test
    void createRepositoryUnderMissingDomainReturnsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/repository?domain=no-such-domain&repository=valid-repo")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void invalidDomainNameReturnsValidationError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=NOT-VALID")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }
}
