package io.github.hectorvent.floci.services.lambdaweb;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/** Under IAM enforcement an IamAuth endpoint evaluates lambda:InvokeWebFunctionEndpoint for the signer. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaWebIamAuthIntegrationTest {

    private static final String BASE = "/2025-03-07";
    private static final String BUCKET = "web-code-iam-auth";
    private static final String ROOT = auth("000000000000", "lambda");
    private static final String IAM_ROOT = auth("000000000000", "iam");
    private static final String APP = """
            const http = require('node:http');
            const [host, port] = process.env.AWS_LAMBDA_HTTP_ENDPOINT.split(':');
            http.createServer((req, res) => res.end('ok')).listen(Number(port), host);
            """;

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @Inject
    LambdaWebEnvironmentManager environments;

    @Inject
    DockerClient dockerClient;

    private String functionName;
    private String functionArn;
    private String domain;

    @BeforeEach
    void createFunction() throws IOException {
        functionName = "iam-auth-" + Long.toString(System.nanoTime(), 36);
        LambdaWebRoles.trustLambda(iamService, "arn:aws:iam::000000000000:role/web");
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, functionName + ".zip", zip(), "application/zip", Map.of());
        int port = baseUri.getPort();
        String name = functionName;
        cleanup.register(() -> {
            environments.stopManagedContainers();
            given().port(port).header("Authorization", ROOT).delete(BASE + "/web-functions/" + name);
        });
        ValidatableResponse created = given().header("Authorization", ROOT).contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {
                           "buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "%s.zip"}},
                                           "runtimeConfig": {"runtime": "nodejs24.x"}},
                           "serviceConfig": {"executionRoleArn": "arn:aws:iam::000000000000:role/web"}},
                         "endpointConfig": {"endpointName": "prod", "endpointType": "HomeRegion",
                                            "authType": "IamAuth", "autoDeploymentMode": "LatestRevision"}}
                        """.formatted(functionName, BUCKET, functionName))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202);
        functionArn = created.extract().path("functionArn");
        domain = created.extract().path("endpoint.domainName");
    }

    @Test
    void aSignerWithoutInvokeOnTheEndpointIsRefused() {
        invoke(user(userName(), null)).statusCode(403);
        invoke(user(userName(), functionArn)).statusCode(403);
    }

    @Test
    @Timeout(180)
    void anIdentityPolicyOrTheFunctionPolicyAllowsTheSigner() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker must be available for the Lambda Web data plane");
        invoke(user(userName(), functionArn + "/endpoint/prod")).statusCode(200);

        String userName = userName();
        UserCredentials granted = user(userName, null);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Principal":{"AWS":"arn:aws:iam::000000000000:user/%s"},
                 "Action":"lambda:InvokeWebFunctionEndpoint","Resource":"%s/endpoint/prod"}]}"""
                .formatted(userName, functionArn);
        given().header("Authorization", ROOT).contentType("application/json").body(Map.of("Policy", policy))
                .when().put(BASE + "/resource-policy/" + functionArn)
                .then().statusCode(200);
        invoke(granted).statusCode(200);
    }

    private static String userName() {
        return "iam-auth-" + Long.toString(System.nanoTime(), 36);
    }

    private UserCredentials user(String userName, String invokeResource) {
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), IAM_ROOT, userName);
        if (invokeResource != null) {
            given().header("Authorization", IAM_ROOT).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "PutUserPolicy").formParam("Version", "2010-05-08")
                    .formParam("UserName", userName).formParam("PolicyName", "invoke")
                    .formParam("PolicyDocument", """
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                             "Action":"lambda:InvokeWebFunctionEndpoint","Resource":"%s"}]}"""
                            .formatted(invokeResource))
                    .when().post("/")
                    .then().statusCode(200);
        }
        return credentials;
    }

    private ValidatableResponse invoke(UserCredentials credentials) {
        LambdaWebRequestSigner.Signed signed = new LambdaWebRequestSigner()
                .credentials(credentials.accessKeyId(), credentials.secretAccessKey(), null)
                .sign("GET", domain, "/");
        return given().header("Host", domain).headers(signed.headers()).when().get("/").then();
    }

    private static byte[] zip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(APP.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
