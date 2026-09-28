package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
                .then().statusCode(200).body("domain.name", equalTo("lifecycle-domain"));

        // AWS returns ResourceNotFoundException here although the API reference does not list it on DeleteDomain.
        given().header("Authorization", AUTH)
                .delete("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
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

    @Test
    void publishDescribeAndGetPackageVersionAssetRoundTripExactBytes() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=pkg-domain").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/repository?domain=pkg-domain&repository=pkg-repo").then().statusCode(200);

        byte[] content = "hello codeartifact".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        given().header("Authorization", AUTH).header("x-amz-content-sha256", sha256)
                .contentType("application/octet-stream").body(content)
                .post("/v1/package/version/publish?domain=pkg-domain&repository=pkg-repo&format=generic"
                        + "&package=my-pkg&version=1.0.0&asset=asset.txt")
                .then().statusCode(200)
                .body("status", equalTo("Published"))
                .body("versionRevision", notNullValue())
                .body("asset.name", equalTo("asset.txt"))
                .body("asset.size", equalTo(content.length))
                .body("asset.hashes.'SHA-256'", equalTo(sha256));

        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=pkg-domain&repository=pkg-repo&format=generic"
                        + "&package=my-pkg&version=1.0.0")
                .then().statusCode(200)
                .body("packageVersion.status", equalTo("Published"))
                .body("packageVersion.origin.originType", equalTo("INTERNAL"));

        byte[] downloaded = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=pkg-domain&repository=pkg-repo&format=generic"
                        + "&package=my-pkg&version=1.0.0&asset=asset.txt")
                .then().statusCode(200)
                .header("X-AssetName", equalTo("asset.txt"))
                .extract().asByteArray();
        assertArrayEquals(content, downloaded);

        given().header("Authorization", AUTH).header("x-amz-content-sha256", sha256)
                .contentType("application/octet-stream").body(content)
                .post("/v1/package/version/publish?domain=pkg-domain&repository=pkg-repo&format=generic"
                        + "&package=my-pkg&version=1.0.0&asset=another.txt")
                .then().statusCode(409).body("__type", equalTo("ConflictException"));
    }

    @Test
    void unfinishedPublishKeepsVersionOpenForMoreAssets() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=unfinished-domain").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/repository?domain=unfinished-domain&repository=repo").then().statusCode(200);

        byte[] content = "partial".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        given().header("Authorization", AUTH).header("x-amz-content-sha256", sha256)
                .contentType("application/octet-stream").body(content)
                .post("/v1/package/version/publish?domain=unfinished-domain&repository=repo&format=generic"
                        + "&package=my-pkg&version=1.0.0&asset=a.txt&unfinished=true")
                .then().statusCode(200).body("status", equalTo("Unfinished"));

        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=unfinished-domain&repository=repo&format=generic"
                        + "&package=my-pkg&version=1.0.0")
                .then().statusCode(200).body("packageVersion.status", equalTo("Unfinished"));
    }

    @Test
    void missingRequiredQueryParamsReturnValidationExceptionNotServerError() {
        given().header("Authorization", AUTH).get("/v1/domain")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));

        given().header("Authorization", AUTH).get("/v1/repository")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));

        given().header("Authorization", AUTH)
                .get("/v1/repository/endpoint?repository=r&format=npm")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void getAuthorizationTokenReturnsATokenAndExpiration() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=token-domain")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=token-domain")
                .then().statusCode(200)
                .body("authorizationToken", notNullValue())
                .body("expiration", notNullValue());

        given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=does-not-exist")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=token-domain&duration=899")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    /**
     * Proves the npm route is genuinely wired into the running instance ({@code @Observes
     * Router} registration, the {@code ServiceConfigAccess} enablement check, and the token
     * check), the one thing {@code CodeArtifactNpmDataPlaneTest} cannot: it builds the class by
     * hand rather than through CDI, so it proves nothing about whether the observer actually
     * registers. A missing token is rejected before the named domain or repository is looked up,
     * so this needs neither to exist, and needs no Docker.
     */
    @Test
    void npmEndpointRejectsAMissingTokenWithoutADomainOrRepositoryExisting() {
        given().get("/codeartifact/npm/no-such-domain/no-such-repo/lodash")
                .then().statusCode(401)
                .header("WWW-Authenticate", equalTo("Bearer"));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return SigV4RequestValidator.sha256Hex(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
