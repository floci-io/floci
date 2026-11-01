package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebRoles;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Under IAM enforcement a request needs lambda:PassNetworkConnector on each network connector it passes. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PassedNetworkConnectorsIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String CONNECTOR =
            "arn:aws:lambda:us-east-1:000000000000:network-connector:nc-0123456789abcdef0";
    private static final String MANAGED = "arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:";
    private static final String DENY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Deny","Action":"lambda:PassNetworkConnector","Resource":"*"}]}""";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    IamService iamService;

    @Test
    void aRevisionNeedsPassNetworkConnectorOnTheVersionItNames() {
        int port = baseUri.getPort();
        String name = "connector-pass-" + Long.toString(System.nanoTime(), 36);
        String denyArn = "arn:aws:iam::" + ACCOUNT + ":policy/" + name;
        cleanup.register(() -> EnforcementFixtures.removePolicy(port, auth(ACCOUNT, "iam"), denyArn));
        LambdaWebRoles.trustLambda(iamService, ROLE);
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/", name,
                policy("lambda:CreateWebFunctionRevision", ""));
        Session session = EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
        String denied = "User: arn:aws:sts::" + ACCOUNT + ":assumed-role/" + name + "/"
                + EnforcementFixtures.SESSION_NAME + " is not authorized to perform:"
                + " lambda:PassNetworkConnector on resource: " + CONNECTOR + ":1 ";

        revision(session, CONNECTOR + ":1").statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException")
                .body("Message", equalTo(denied
                        + "because no identity-based policy allows the lambda:PassNetworkConnector action"));

        iamService.putRolePolicy(name, "p", policy("lambda:CreateWebFunctionRevision", allow(CONNECTOR + ":1")));
        revision(session, CONNECTOR + ":1").statusCode(404)
                .body("message", equalTo("The function arn:aws:lambda:us-east-1:" + ACCOUNT
                        + ":web-function/missing does not exist."));
        revision(session, CONNECTOR + ":2").statusCode(403);

        iamService.createPolicy(name, "/", null, DENY, null);
        iamService.attachRolePolicy(name, denyArn);
        revision(session, CONNECTOR + ":1").statusCode(403)
                .body("Message", equalTo(denied + "with an explicit deny in an identity-based policy: " + denyArn));
    }

    @Test
    void aUserAndItsSessionTokenNameTheManagedPolicyThatDenies() {
        int port = baseUri.getPort();
        String name = "connector-pass-" + Long.toString(System.nanoTime(), 36);
        String denyArn = "arn:aws:iam::" + ACCOUNT + ":policy/" + name;
        cleanup.register(() -> EnforcementFixtures.removePolicy(port, auth(ACCOUNT, "iam"), denyArn));
        LambdaWebRoles.trustLambda(iamService, ROLE);
        UserCredentials user = EnforcementFixtures.createUser(cleanup, port, auth(ACCOUNT, "iam"), name);
        EnforcementFixtures.putUserPolicy(port, auth(ACCOUNT, "iam"), name, "p",
                policy("lambda:CreateWebFunctionRevision", allow("*")));
        iamService.createPolicy(name, "/", null, DENY, null);
        iamService.attachUserPolicy(name, denyArn);
        Session token = sessionToken(user.accessKeyId());
        String denied = "User: arn:aws:iam::" + ACCOUNT + ":user/" + name + " is not authorized to perform:"
                + " lambda:PassNetworkConnector on resource: " + CONNECTOR + ":1"
                + " with an explicit deny in an identity-based policy: " + denyArn;

        revision(new Session(user.accessKeyId(), null), CONNECTOR + ":1").statusCode(403)
                .body("Message", equalTo(denied));
        revision(token, CONNECTOR + ":1").statusCode(403).body("Message", equalTo(denied));
    }

    @Test
    void aMicrovmPassesTheManagedConnectorsWhenItNamesNone() {
        int port = baseUri.getPort();
        String name = "connector-pass-" + Long.toString(System.nanoTime(), 36);
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/", name,
                policy("lambda:RunMicrovm", ""));
        Session session = EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
        String denied = "User: arn:aws:sts::" + ACCOUNT + ":assumed-role/" + name + "/"
                + EnforcementFixtures.SESSION_NAME + " is not authorized to perform:"
                + " lambda:PassNetworkConnector on resource: ";
        String noAllow = " because no identity-based policy allows the lambda:PassNetworkConnector action";

        microvm(session, "").statusCode(403).body("Message", equalTo(denied + MANAGED + "INTERNET_EGRESS" + noAllow));
        microvm(session, ", \"egressNetworkConnectors\": []").statusCode(403)
                .body("Message", equalTo(denied + MANAGED + "INTERNET_EGRESS" + noAllow));

        iamService.putRolePolicy(name, "p", policy("lambda:RunMicrovm",
                allow(MANAGED + "INTERNET_EGRESS") + allow(CONNECTOR + ":1")));
        microvm(session, "").statusCode(403).body("Message", equalTo(denied + MANAGED + "HTTP_INGRESS" + noAllow));
        microvm(session, ", \"ingressNetworkConnectors\": [\"" + CONNECTOR + ":2\"]").statusCode(403)
                .body("Message", equalTo(denied + CONNECTOR + ":2" + noAllow));
        microvm(session, ", \"egressNetworkConnectors\": [\"" + CONNECTOR + ":1\", \"" + CONNECTOR + ":3\"],"
                + " \"ingressNetworkConnectors\": [\"" + CONNECTOR + ":2\"]").statusCode(403)
                .body("Message", equalTo(denied + CONNECTOR + ":3" + noAllow));

        iamService.putRolePolicy(name, "p", policy("lambda:RunMicrovm", allow("*")));
        microvm(session, "").statusCode(404);
    }

    private static Session sessionToken(String accessKeyId) {
        XmlPath credentials = given().header("Authorization", auth(accessKeyId, "sts"))
                .formParam("Action", "GetSessionToken")
                .when().post("/")
                .then().statusCode(200).extract().xmlPath();
        return new Session(
                credentials.getString("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId"),
                credentials.getString("GetSessionTokenResponse.GetSessionTokenResult.Credentials.SessionToken"));
    }

    private static ValidatableResponse revision(Session session, String connectorArn) {
        return signed(session).contentType("application/json")
                .body("""
                        {"buildConfig": {"codeConfig": {"s3Object": {"bucket": "web-code", "key": "app.zip"}},
                                         "runtimeConfig": {"runtime": "nodejs24.x"}},
                         "serviceConfig": {"executionRoleArn": "%s", "egressNetworkConnectorArn": "%s"}}
                        """.formatted(ROLE, connectorArn))
                .when().post("/2025-03-07/web-functions/missing/revisions")
                .then();
    }

    private static ValidatableResponse microvm(Session session, String connectors) {
        return signed(session).contentType("application/json")
                .body("{\"imageIdentifier\": \"arn:aws:lambda:us-east-1:" + ACCOUNT + ":microvm-image:missing\""
                        + connectors + "}")
                .when().post("/2025-09-09/microvms")
                .then();
    }

    private static RequestSpecification signed(Session session) {
        RequestSpecification request = given().header("Authorization", auth(session.accessKeyId(), "lambda"));
        if (session.sessionToken() != null) {
            request.header("X-Amz-Security-Token", session.sessionToken());
        }
        return request;
    }

    private static String allow(String resource) {
        return ",{\"Effect\":\"Allow\",\"Action\":\"lambda:PassNetworkConnector\",\"Resource\":\"" + resource + "\"}";
    }

    private static String policy(String action, String more) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"%s","Resource":"*"},
                  {"Effect":"Allow","Action":"iam:PassRole","Resource":"*"}%s]}""".formatted(action, more);
    }
}
