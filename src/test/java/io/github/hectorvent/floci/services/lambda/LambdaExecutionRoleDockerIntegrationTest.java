package io.github.hectorvent.floci.services.lambda;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(LambdaExecutionRoleDockerIntegrationTest.IamEnforcementProfile.class)
class LambdaExecutionRoleDockerIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String ACCOUNT_ID = "000000000000";

    @Inject
    DockerClient dockerClient;

    @Inject
    IamService iamService;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Lambda execution-role integration tests");
    }

    @Test
    void runtimeUsesExecutionRoleCredentialsForSdkCalls() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionName = "execution-role-" + suffix;
        String roleName = "LambdaDockerRole" + suffix;
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

        try {
            given()
                    .contentType("application/json")
                    .body("""
                        {
                          "FunctionName": "%1$s",
                          "Runtime": "nodejs20.x",
                          "Role": "%2$s",
                          "Handler": "index.handler",
                          "Timeout": 60,
                          "Code": {"ZipFile": "%3$s"},
                          "Environment": {"Variables": {
                            "AWS_ACCESS_KEY_ID": "test",
                            "AWS_SECRET_ACCESS_KEY": "test",
                            "AWS_SESSION_TOKEN": "test"
                          }}
                        }
                        """.formatted(functionName, roleArn, handlerZipBase64()))
            .when()
                    .post(BASE_PATH + "/functions")
            .then()
                    .statusCode(201);

            Response response = given()
                    .contentType("application/json")
                    .body("{}")
            .when()
                    .post(BASE_PATH + "/functions/" + functionName + "/invocations");

            response.then()
                    .statusCode(200)
                    .body("accessKeyId", startsWith("ASIA"))
                    .body("signedAccessKeyId", startsWith("ASIA"))
                    .body("account", equalTo(ACCOUNT_ID))
                    .body("arn", startsWith(
                            "arn:aws:sts::" + ACCOUNT_ID + ":assumed-role/" + roleName + "/"))
                    .body("s3Denied", equalTo(true));

            String accessKeyId = response.jsonPath().getString("accessKeyId");
            assertNull(iamService.resolveCallerContext(accessKeyId),
                    "ephemeral container stop should unregister its execution-role session");
        } finally {
            given().when().delete(BASE_PATH + "/functions/" + functionName);
            iamService.deleteRolePolicy(roleName, "RuntimePolicy");
            iamService.deleteRole(roleName);
        }
    }

    private static String handlerZipBase64() throws Exception {
        String handler = """
                const { STSClient, GetCallerIdentityCommand } = require('@aws-sdk/client-sts');
                const { S3Client, ListBucketsCommand } = require('@aws-sdk/client-s3');

                exports.handler = async () => {
                  const options = {
                    endpoint: process.env.AWS_ENDPOINT_URL,
                    region: process.env.AWS_REGION
                  };
                  let signedAccessKeyId = null;
                  const sts = new STSClient(options);
                  sts.middlewareStack.add(
                    (next) => async (args) => {
                      const authorization = args.request.headers.authorization || '';
                      signedAccessKeyId = authorization.match(/Credential=([^/]+)/)?.[1] || null;
                      return next(args);
                    },
                    {step: 'finalizeRequest', name: 'captureCredential'}
                  );
                  const identity = await sts.send(new GetCallerIdentityCommand({}));
                  let s3Denied = false;
                  try {
                    await new S3Client({...options, forcePathStyle: true}).send(new ListBucketsCommand({}));
                  } catch (error) {
                    s3Denied = error?.$metadata?.httpStatusCode === 403;
                  }
                  return {
                    accessKeyId: process.env.AWS_ACCESS_KEY_ID,
                    signedAccessKeyId,
                    account: identity.Account,
                    arn: identity.Arn,
                    s3Denied
                  };
                };
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(handler.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.services.lambda.ephemeral", "true",
                    "quarkus.http.test-port", "4587",
                    "floci.port", "4587",
                    "floci.base-url", "http://localhost:4587");
        }
    }
}
