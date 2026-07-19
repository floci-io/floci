package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(LambdaExecutionRoleIamEnforcementIntegrationTest.IamEnforcementProfile.class)
class LambdaExecutionRoleIamEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    IamService iamService;

    @Inject
    LambdaExecutionRoleCredentials executionRoleCredentials;

    @Test
    void executionRoleDeniesS3ButCanGetCallerIdentity() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "LambdaRuntimeRole" + suffix;
        String roleArn = "arn:aws:iam::" + ACCOUNT_ID + ":role/" + roleName;
        iamService.createRole(roleName, "/", "{}", null, 0, null);
        iamService.putRolePolicy(roleName, "RuntimePolicy", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {"Effect": "Allow", "Action": "logs:*", "Resource": "*"},
                    {"Effect": "Deny", "Action": "s3:*", "Resource": "*"}
                  ]
                }
                """);

        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("runtime-identity-" + suffix);
        function.setAccountId(ACCOUNT_ID);
        function.setRole(roleArn);
        SessionCreds credentials = executionRoleCredentials.forFunction(function).orElseThrow();

        try {
            given()
                    .header("Authorization", auth(credentials.accessKeyId(), "s3"))
            .when()
                    .get("/")
            .then()
                    .statusCode(403)
                    .body(containsString("<Code>AccessDenied</Code>"));

            given()
                    .formParam("Action", "GetCallerIdentity")
                    .header("Authorization", auth(credentials.accessKeyId(), "sts"))
            .when()
                    .post("/")
            .then()
                    .statusCode(200)
                    .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo(ACCOUNT_ID))
                    .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                            equalTo("arn:aws:sts::" + ACCOUNT_ID
                                    + ":assumed-role/" + roleName + "/floci-session"));
        } finally {
            executionRoleCredentials.unregister(ACCOUNT_ID, credentials.accessKeyId());
            iamService.deleteRolePolicy(roleName, "RuntimePolicy");
            iamService.deleteRole(roleName);
        }
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260720/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
