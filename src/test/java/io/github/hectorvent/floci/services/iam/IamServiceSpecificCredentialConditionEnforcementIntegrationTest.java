package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The IAM User Guide's example for {@code iam:ServiceSpecificCredentialServiceName} and
 * {@code iam:ServiceSpecificCredentialAgeDays}: a user may create Amazon Bedrock API keys that expire
 * within 90 days, and nothing else. The keys come from the request's {@code ServiceName} and
 * {@code CredentialAgeDays} members.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamServiceSpecificCredentialConditionEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"iam:CreateServiceSpecificCredential","Resource":"*","Condition":{
                "StringEquals":{"iam:ServiceSpecificCredentialServiceName":"bedrock.amazonaws.com"},
                "NumericLessThanEquals":{"iam:ServiceSpecificCredentialAgeDays":"90"}}}]}""";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aUserMayCreateOnlyTheServiceAndAgeThePolicyNames() {
        String userName = "bedrock-keys-" + UUID.randomUUID().toString().substring(0, 8);
        UserCredentials user = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"),
                userName);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), userName, "BedrockKeys", POLICY);

        create(user, userName, "codecommit.amazonaws.com", null).then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("iam:CreateServiceSpecificCredential"));
        create(user, userName, "bedrock.amazonaws.com", "365").then().statusCode(403);
        create(user, userName, "bedrock.amazonaws.com", null).then().statusCode(403);

        String credentialId = create(user, userName, "bedrock.amazonaws.com", "90").then()
                .statusCode(200)
                .body(containsString("<ServiceName>bedrock.amazonaws.com</ServiceName>"))
                .extract().xmlPath().getString("CreateServiceSpecificCredentialResponse"
                        + ".CreateServiceSpecificCredentialResult.ServiceSpecificCredential"
                        + ".ServiceSpecificCredentialId");
        cleanup.register(() -> iam(ADMIN, "DeleteServiceSpecificCredential").formParam("UserName", userName)
                .formParam("ServiceSpecificCredentialId", credentialId).post("/"));
    }

    private static Response create(UserCredentials user, String userName, String serviceName, String ageDays) {
        RequestSpecification request = iam(user.accessKeyId(), "CreateServiceSpecificCredential")
                .formParam("UserName", userName).formParam("ServiceName", serviceName);
        if (ageDays != null) {
            request.formParam("CredentialAgeDays", ageDays);
        }
        return request.post("/");
    }

    private static RequestSpecification iam(String accessKeyId, String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(accessKeyId, "iam"))
                .formParam("Action", action);
    }
}
