package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.ProvisionedConcurrencyConfigNotFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaResourceTagIamIntegrationTest {

    private static final String ACCOUNT = "741681410031";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aResourceTagConditionReadsTheTagsOfTheFunctionItsVersionsAndAnEventSourceMapping() throws Exception {
        Region region = Region.US_EAST_1;
        String tagged = "tag-iam-" + UUID.randomUUID();
        String untagged = "tag-iam-" + UUID.randomUUID();
        try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, region, endpoint)) {
            String taggedArn = LambdaFunctionUrlFixtures.createFunction(owner, tagged, ACCOUNT, region, endpoint, cleanup);
            LambdaFunctionUrlFixtures.createFunction(owner, untagged, ACCOUNT, region, endpoint, cleanup);
            owner.tagResource(request -> request.resource(taggedArn).tags(Map.of("team", "data")));
            String taggedMapping = mapping(owner, tagged, Map.of("team", "data"));
            String untaggedMapping = mapping(owner, tagged, Map.of("team", "web"));
            String authorization = auth(ACCOUNT, region.id(), "iam");
            String user = "tag-reader-" + UUID.randomUUID();
            UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "team-data",
                    """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Action": ["lambda:GetFunction", "lambda:GetProvisionedConcurrencyConfig",
                                   "lambda:GetEventSourceMapping"],
                        "Resource": "*",
                        "Condition": {"StringEquals": {"aws:ResourceTag/team": "data"}}
                      }]
                    }
                    """);
            try (LambdaClient reader = AwsTestClients.create(LambdaClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), region, endpoint)) {
                assertEquals(taggedArn, reader.getFunction(request -> request.functionName(tagged))
                        .configuration().functionArn());
                denied(() -> reader.getFunction(request -> request.functionName(untagged)));

                assertThrows(ProvisionedConcurrencyConfigNotFoundException.class, () ->
                        reader.getProvisionedConcurrencyConfig(request -> request.functionName(tagged).qualifier("1")));
                denied(() -> reader.getProvisionedConcurrencyConfig(request ->
                        request.functionName(untagged).qualifier("1")));

                assertEquals(taggedMapping, reader.getEventSourceMapping(request -> request.uuid(taggedMapping))
                        .uuid());
                denied(() -> reader.getEventSourceMapping(request -> request.uuid(untaggedMapping)));
            }
        }
    }

    /** A disabled self-managed Kafka mapping of the function, so nothing polls the endpoint it names. */
    private String mapping(LambdaClient owner, String function, Map<String, String> tags) {
        String uuid = owner.createEventSourceMapping(request -> request.functionName(function)
                .topics("orders").enabled(false).tags(tags)
                .selfManagedEventSource(source -> source.endpointsWithStrings(
                        Map.of("KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092"))))).uuid();
        cleanup.register(() -> {
            try (LambdaClient client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1,
                    endpoint)) {
                client.deleteEventSourceMapping(request -> request.uuid(uuid));
            }
        });
        return uuid;
    }

    private static void denied(Runnable call) {
        LambdaException denied = assertThrows(LambdaException.class, call::run);
        assertEquals(403, denied.statusCode());
    }
}
