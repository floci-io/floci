package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Under IAM enforcement the endpoint routes carry the authType they name and the one the endpoint has. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaWebAuthTypeConditionKeysIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web-auth-type";
    private static final String BUCKET = "web-code-auth-type";
    private static final String BASE = "/2025-03-07/web-functions";
    private static final String BUILD = """
            {"codeConfig": {"s3Object": {"bucket": "%s", "key": "app.zip"}}, "runtimeConfig": {"runtime": "nodejs24.x"}}
            """.formatted(BUCKET);
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["lambda:*","iam:PassRole"],"Resource":"*"},
              {"Effect":"Deny","Action":["lambda:CreateWebFunction","lambda:CreateWebFunctionEndpoint"],"Resource":"*",
               "Condition":{"StringEquals":{"lambda:Request/WebFunctionAuthType":"ApplicationManaged"}}},
              {"Effect":"Deny","Action":["lambda:GetWebFunctionEndpoint","lambda:DeleteWebFunctionEndpoint"],
               "Resource":"*",
               "Condition":{"StringEquals":{"lambda:Resource/WebFunctionAuthType":"ApplicationManaged"}}},
              {"Effect":"Deny","Action":"lambda:UpdateWebFunctionEndpoint","Resource":"*",
               "Condition":{"Null":{"lambda:Request/WebFunctionAuthType":"true"}}}]}""";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    IamService iamService;

    @Inject
    S3Service s3Service;

    @Test
    void anEndpointRouteIsDeniedByTheAuthTypeItNamesOrHas() {
        int port = baseUri.getPort();
        String name = "auth-type-" + Long.toString(System.nanoTime(), 36);
        LambdaWebRoles.trustLambda(iamService, ROLE);
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, "app.zip", LambdaWebZips.indexJs(""), "application/zip", Map.of());
        cleanup.register(() -> given().header("Authorization", auth(ACCOUNT, "lambda"))
                .when().delete(BASE + "/" + name));
        given().header("Authorization", auth(ACCOUNT, "lambda")).contentType("application/json")
                .body(function(name, "ApplicationManaged"))
                .when().put(BASE)
                .then().statusCode(202);
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/", name, POLICY);
        Session session = EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
        String endpoints = BASE + "/" + name + "/endpoints";

        signed(session).contentType("application/json").body(endpoint("am", "ApplicationManaged"))
                .when().put(endpoints)
                .then().statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException");
        signed(session).contentType("application/json").body(endpoint("iam", "IamAuth"))
                .when().put(endpoints)
                .then().statusCode(202);
        signed(session).contentType("application/json").body(function(name + "-b", "ApplicationManaged"))
                .when().put(BASE)
                .then().statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException");

        signed(session).when().get(endpoints + "/dev")
                .then().statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException");
        signed(session).when().get(endpoints + "/iam").then().statusCode(200);
        signed(session).when().get(BASE + "/arn:bad/endpoints/dev")
                .then().statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException");
        signed(session).when().get(endpoints + "/missing")
                .then().statusCode(404)
                .body("message", equalTo("The endpoint missing does not exist."));

        signed(session).contentType("application/json").body("{\"description\": \"d\"}")
                .when().patch(endpoints + "/iam")
                .then().statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException");
        signed(session).contentType("application/json").body("{\"authType\": \"IamAuth\"}")
                .when().patch(endpoints + "/iam")
                .then().statusCode(202);

        signed(session).when().delete(endpoints + "/dev")
                .then().statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException");
        signed(session).when().delete(endpoints + "/iam").then().statusCode(204);
    }

    private static String function(String name, String authType) {
        return """
                {"functionName": "%s",
                 "revisionConfig": {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s"}},
                 "endpointConfig": %s}
                """.formatted(name, BUILD, ROLE, endpoint("dev", authType));
    }

    private static String endpoint(String name, String authType) {
        return """
                {"endpointName": "%s", "endpointType": "HomeRegion", "authType": "%s",
                 "autoDeploymentMode": "LatestRevision"}""".formatted(name, authType);
    }

    private static RequestSpecification signed(Session session) {
        return given().header("Authorization", auth(session.accessKeyId(), "lambda"))
                .header("X-Amz-Security-Token", session.sessionToken());
    }
}
