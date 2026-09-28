package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class GeneratedPreSignedUrlAuthEnforcementIntegrationTest {

    private static final S3RequestSigner OWNER = S3RequestSigner.signedAs("test", "test");

    @Inject
    PreSignedUrlGenerator presignGenerator;

    @Inject
    IamService iamService;

    @Inject
    IamPolicyEvaluator policyEvaluator;

    @Test
    void generatedUrlUsesRegisteredSigV4SessionUnderEnforcedAuth() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "generated-presign-auth-" + suffix;
        String key = "exports/report with spaces.csv";
        String content = "recipient,status\nalice@example.com,DELIVERY\n";

        given()
            .filter(OWNER)
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        given()
            .filter(OWNER)
            .body(content)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);

        String url = presignGenerator.generatePresignedUrl(
                "http://localhost:" + RestAssured.port, bucket, key, "GET", 3600);
        URI uri = URI.create(url);
        String credential = queryParam(uri, "X-Amz-Credential");
        String sessionToken = queryParam(uri, "X-Amz-Security-Token");
        String accessKeyId = credential.substring(0, credential.indexOf('/'));

        assertTrue(accessKeyId.matches("ASIA[A-Z0-9]{16}"));
        assertNotNull(sessionToken);
        assertEquals("000000000000", iamService.resolveAccountId(accessKeyId).orElseThrow());
        assertTrue(iamService.findSecretKey(accessKeyId, sessionToken).isPresent());

        CallerContext scopedCaller = iamService.resolveCallerContext(accessKeyId);
        assertNotNull(scopedCaller);
        String objectArn = "arn:aws:s3:::" + bucket + "/" + key;
        assertEquals(IamPolicyEvaluator.SimulationDecision.ALLOWED,
                policyEvaluator.simulatePrincipalPolicy(scopedCaller, "s3:GetObject", objectArn, Map.of()));
        assertEquals(IamPolicyEvaluator.SimulationDecision.IMPLICIT_DENY,
                policyEvaluator.simulatePrincipalPolicy(scopedCaller, "s3:PutObject", objectArn, Map.of()));
        assertEquals(IamPolicyEvaluator.SimulationDecision.IMPLICIT_DENY,
                policyEvaluator.simulatePrincipalPolicy(scopedCaller, "s3:GetObject",
                        "arn:aws:s3:::" + bucket + "/other.csv", Map.of()));
        assertEquals(IamPolicyEvaluator.SimulationDecision.IMPLICIT_DENY,
                policyEvaluator.simulatePrincipalPolicy(scopedCaller, "sqs:SendMessage", "*", Map.of()));

        S3RequestSigner scopedSigner = S3RequestSigner.signedAs(accessKeyId,
                iamService.findSecretKey(accessKeyId, sessionToken).orElseThrow(), sessionToken);
        given().filter(scopedSigner).when().get("/" + bucket + "/other.csv")
                .then().statusCode(403).body("Error.Code", equalTo("AccessDenied"));
        given().filter(scopedSigner).body("changed").when().put("/" + bucket + "/" + key)
                .then().statusCode(403).body("Error.Code", equalTo("AccessDenied"));

        URI wildcardUri = URI.create(presignGenerator.generatePresignedUrl(
                "http://localhost:" + RestAssured.port, bucket, "wild*.csv", "GET", 60));
        String wildcardKey = queryParam(wildcardUri, "X-Amz-Credential").split("/", 2)[0];
        String wildcardToken = queryParam(wildcardUri, "X-Amz-Security-Token");
        S3RequestSigner wildcardSigner = S3RequestSigner.signedAs(wildcardKey,
                iamService.findSecretKey(wildcardKey, wildcardToken).orElseThrow(), wildcardToken);
        given().filter(wildcardSigner).when().get("/" + bucket + "/wild-other.csv")
                .then().statusCode(403).body("Error.Code", equalTo("AccessDenied"));

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(uri.getRawPath() + "?" + uri.getRawQuery())
        .then()
            .statusCode(200)
            .body(equalTo(content));

        String tamperedQuery = uri.getRawQuery().replaceFirst(
                "X-Amz-Signature=[0-9a-f]+", "X-Amz-Signature=" + "0".repeat(64));
        given()
            .urlEncodingEnabled(false)
        .when()
            .get(uri.getRawPath() + "?" + tamperedQuery)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("SignatureDoesNotMatch"));

        given().filter(OWNER).when().delete("/" + bucket + "/" + key).then().statusCode(204);
        given().filter(OWNER).when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void generatedUrlRegistersFreshCredentialsAfterStoredSessionIsRemoved() {
        String bucket = "generated-presign-reset-" + UUID.randomUUID().toString().substring(0, 8);
        String baseUrl = "http://localhost:" + RestAssured.port;
        URI oldUri = URI.create(presignGenerator.generatePresignedUrl(
                baseUrl, bucket, "report.csv", "GET", 3600));
        String oldAccessKeyId = queryParam(oldUri, "X-Amz-Credential").split("/", 2)[0];

        // A state reset removes the persisted IAM session while this generator stays alive.
        iamService.unregisterSession("000000000000", oldAccessKeyId);

        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);
        given().filter(OWNER).body("after reset").when().put("/" + bucket + "/report.csv")
                .then().statusCode(200);

        URI newUri = URI.create(presignGenerator.generatePresignedUrl(
                baseUrl, bucket, "report.csv", "GET", 3600));
        String newAccessKeyId = queryParam(newUri, "X-Amz-Credential").split("/", 2)[0];
        assertNotEquals(oldAccessKeyId, newAccessKeyId);
        assertTrue(iamService.findSecretKey(newAccessKeyId,
                queryParam(newUri, "X-Amz-Security-Token")).isPresent());

        given().urlEncodingEnabled(false).when().get(newUri.getRawPath() + "?" + newUri.getRawQuery())
                .then().statusCode(200).body(equalTo("after reset"));
    }

    @Test
    void generatedPutUrlCanReplaceObjectConditionallyUnderEnforcedIam() {
        String bucket = "generated-presign-conditional-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "object.txt";
        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);
        String eTag = given().filter(OWNER).body("old").when().put("/" + bucket + "/" + key)
                .then().statusCode(200).extract().header("ETag");

        URI uri = URI.create(presignGenerator.generatePresignedUrl(
                "http://localhost:" + RestAssured.port, bucket, key, "PUT", 60));
        String accessKeyId = queryParam(uri, "X-Amz-Credential").split("/", 2)[0];
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        String objectArn = "arn:aws:s3:::" + bucket + "/" + key;
        assertEquals(IamPolicyEvaluator.SimulationDecision.ALLOWED,
                policyEvaluator.simulatePrincipalPolicy(caller, "s3:GetObject", objectArn, Map.of()));
        assertEquals(IamPolicyEvaluator.SimulationDecision.IMPLICIT_DENY,
                policyEvaluator.simulatePrincipalPolicy(caller, "s3:GetObject",
                        "arn:aws:s3:::" + bucket + "/other.txt", Map.of()));

        given().urlEncodingEnabled(false).header("If-Match", eTag).body("new")
                .when().put(uri.getRawPath() + "?" + uri.getRawQuery()).then().statusCode(200);
        given().filter(OWNER).when().get("/" + bucket + "/" + key)
                .then().statusCode(200).body(equalTo("new"));
    }

    @Test
    void generatedPutUrlCannotAddTagsWithoutTaggingPermission() {
        String bucket = "generated-presign-tags-" + UUID.randomUUID().toString().substring(0, 8);
        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);

        URI uri = URI.create(presignGenerator.generatePresignedUrl(
                "http://localhost:" + RestAssured.port, bucket, "object.txt", "PUT", 60));
        String pathAndQuery = uri.getRawPath() + "?" + uri.getRawQuery();

        given().urlEncodingEnabled(false).header("x-amz-tagging", "team=eng").body("tagged")
                .when().put(pathAndQuery)
                .then().statusCode(403).body("Error.Code", equalTo("AccessDenied"));
        given().urlEncodingEnabled(false).body("untagged")
                .when().put(pathAndQuery).then().statusCode(200);
        given().filter(OWNER).when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("untagged"));
    }

    @Test
    void generatedUrlsKeepLeadingSlashKeysDistinctUnderEnforcedIam() throws Exception {
        String bucket = "generated-presign-slash-" + UUID.randomUUID().toString().substring(0, 8);
        String baseUrl = "http://localhost:" + RestAssured.port;
        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);

        URI putUri = URI.create(presignGenerator.generatePresignedUrl(
                baseUrl, bucket, "/object.txt", "PUT", 60));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> put = client.send(HttpRequest.newBuilder(putUri)
                        .PUT(HttpRequest.BodyPublishers.ofString("leading slash")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode(), put.body());

        URI getUri = URI.create(presignGenerator.generatePresignedUrl(
                baseUrl, bucket, "/object.txt", "GET", 60));
        HttpResponse<String> get = client.send(HttpRequest.newBuilder(getUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, get.statusCode(), get.body());
        assertEquals("leading slash", get.body());
        given().filter(OWNER).when().get("/" + bucket + "/object.txt").then().statusCode(404);

        URI alteredUri = URI.create(getUri.toString().replace("/" + bucket + "//", "/" + bucket + "/"));
        HttpResponse<String> altered = client.send(HttpRequest.newBuilder(alteredUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, altered.statusCode(), altered.body());
    }

    @Test
    void proxiedGeneratedUrlUsesTheSignedPathWhenHostMatchesBucket() throws Exception {
        String bucket = "generated-presign-proxy-" + UUID.randomUUID().toString().substring(0, 8);
        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);
        given().filter(OWNER).body("signed object").when().put("/" + bucket + "/object.txt")
                .then().statusCode(200);
        given().filter(OWNER).body("other object").when().put("/" + bucket + "/" + bucket + "/object.txt")
                .then().statusCode(200);

        HttpClient proxyClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("localhost", RestAssured.port)))
                .build();
        String baseUrl = "http://" + bucket;
        URI getUri = URI.create(presignGenerator.generatePresignedUrl(
                baseUrl, bucket, "object.txt", "GET", 60));
        HttpResponse<String> get = proxyClient.send(HttpRequest.newBuilder(getUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, get.statusCode(), get.body());
        assertEquals("signed object", get.body());

        URI putUri = URI.create(presignGenerator.generatePresignedUrl(
                baseUrl, bucket, "object.txt", "PUT", 60));
        HttpResponse<String> put = proxyClient.send(HttpRequest.newBuilder(putUri)
                        .PUT(HttpRequest.BodyPublishers.ofString("updated object")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode(), put.body());
        given().filter(OWNER).when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("updated object"));
        given().filter(OWNER).when().get("/" + bucket + "/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("other object"));
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
