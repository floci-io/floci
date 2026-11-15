package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionUrlAuthType;
import software.amazon.awssdk.services.lambda.model.LambdaException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaFunctionUrlListIamIntegrationTest {

    private static final String ACCOUNT = "741681410025";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"name", "arn", "partial"})
    void listingRequiresPermissionOnTheNamedFunction(String form) throws Exception {
        Region region = Region.US_EAST_1;
        String name = "url-list-iam-" + UUID.randomUUID();
        try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, region, endpoint)) {
            String arn = LambdaFunctionUrlFixtures.createFunction(owner, name, ACCOUNT, region, endpoint, cleanup);
            owner.createFunctionUrlConfig(request -> request.functionName(name).authType(FunctionUrlAuthType.AWS_IAM));
            String selector = switch (form) {
                case "arn" -> arn;
                case "partial" -> ACCOUNT + ":function:" + name;
                default -> name;
            };
            String authorization = auth(ACCOUNT, region.id(), "iam");
            String user = "url-list-reader-" + UUID.randomUUID();
            UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
            try (LambdaClient reader = AwsTestClients.create(LambdaClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), region, endpoint)) {
                policy(authorization, user, "Allow", arn + "-other");
                denied(reader, selector);
                policy(authorization, user, "Deny", arn);
                denied(reader, selector);
                policy(authorization, user, "Allow", arn);
                assertEquals(List.of(arn), reader.listFunctionUrlConfigs(request -> request.functionName(selector))
                        .functionUrlConfigs().stream().map(config -> config.functionArn()).toList());
            }
        }
    }

    private void policy(String authorization, String user, String effect, String resource) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "url-list",
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "lambda:ListFunctionUrlConfigs",
                      "Resource": "%s"
                    },
                    {
                      "Effect": "%s",
                      "Action": "lambda:ListFunctionUrlConfigs",
                      "Resource": "%s"
                    }
                  ]
                }
                """.formatted("Deny".equals(effect) ? "*" : resource, effect, resource));
    }

    private static void denied(LambdaClient client, String name) {
        LambdaException error = assertThrows(LambdaException.class, () -> client.listFunctionUrlConfigs(request ->
                request.functionName(name)));
        assertEquals(403, error.statusCode());
    }
}
