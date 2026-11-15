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
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.PublishLayerVersionResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.services.lambda.LambdaLayerFixtures.publishLayerVersion;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaDeleteLayerArnIamIntegrationTest {

    private static final String ACCOUNT = "741681410023";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,us-east-1,false", "us-east-1,us-east-1,true",
            "cn-north-1,cn-north-1,true", "us-gov-west-1,us-gov-west-1,true", "us-west-2,us-east-1,true"})
    void deletionChecksTheVersionNamedByTheSelector(String layerRegion, String requestRegion, boolean arnSelector)
            throws Exception {
        String name = "layer-delete-iam-" + UUID.randomUUID();
        try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.of(layerRegion), endpoint)) {
            PublishLayerVersionResponse published = publishLayerVersion(
                    owner, name, ACCOUNT, Region.of(layerRegion), endpoint, cleanup);
            String user = "layer-deleter-" + UUID.randomUUID();
            String authorization = auth(ACCOUNT, requestRegion, "iam");
            UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
            String selector = arnSelector ? published.layerArn() : name;
            String resource = published.layerVersionArn();
            try (LambdaClient caller = AwsTestClients.create(LambdaClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), Region.of(requestRegion), endpoint)) {
                policy(authorization, user, resource + "-other", null);
                denied(caller, selector, published.version());
                policy(authorization, user, "*", resource);
                denied(caller, selector, published.version());
                assertEquals(resource, owner.getLayerVersion(request -> request
                        .layerName(name).versionNumber(published.version())).layerVersionArn());

                policy(authorization, user, resource, null);
                assertEquals(204, caller.deleteLayerVersion(request -> request.layerName(selector)
                        .versionNumber(published.version())).sdkHttpResponse().statusCode());
                assertThrows(ResourceNotFoundException.class, () -> owner.getLayerVersion(request -> request
                        .layerName(name).versionNumber(published.version())));
            }
        }
    }

    private void policy(String authorization, String user, String allowed, String denied) {
        String statements = """
                {
                  "Effect": "Allow",
                  "Action": "lambda:DeleteLayerVersion",
                  "Resource": "%s"
                }
                """.formatted(allowed);
        if (denied != null) {
            statements += "," + """
                    {
                      "Effect": "Deny",
                      "Action": "lambda:DeleteLayerVersion",
                      "Resource": "%s"
                    }
                    """.formatted(denied);
        }
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "layer-delete",
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [%s]
                }
                """.formatted(statements));
    }

    private static void denied(LambdaClient caller, String selector, long version) {
        LambdaException denied = assertThrows(LambdaException.class, () -> caller.deleteLayerVersion(request ->
                request.layerName(selector).versionNumber(version)));
        assertEquals(403, denied.statusCode());
    }
}
