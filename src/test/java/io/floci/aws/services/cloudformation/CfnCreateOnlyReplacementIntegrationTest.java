package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * For an AppConfig profile and an ElastiCache cluster, one create-only and
 * one other change each: the change set's {@code Replacement} is the one AWS documents for that
 * property ("Update requires"), and an executed update changes the physical id exactly when the
 * change set said {@code True}, so a preview and the action never differ.
 */
@QuarkusTest
class CfnCreateOnlyReplacementIntegrationTest {

    private static final String APP = """
            "App": {"Type": "AWS::AppConfig::Application", "Properties": {"Name": "replacement-orders"}}""";

    private record Row(String label, String extraResources, String type, String before, String after,
                       String replacement, boolean needsDocker) {

        @Override
        public String toString() {
            return label;
        }

        String template(String properties) {
            String extra = extraResources.isEmpty() ? "" : extraResources + ",";
            return "{\"Resources\": {" + extra + "\"Subject\": {\"Type\": \"" + type
                    + "\", \"Properties\": {" + properties + "}}}}";
        }
    }

    private static final String PROFILE = "\"ApplicationId\": {\"Ref\": \"App\"}, \"Name\": \"settings\", ";
    private static final String CLUSTER = "\"Engine\": \"redis\", \"NumCacheNodes\": 1, ";

    static Stream<Row> rows() {
        return Stream.of(
                new Row("AppConfig profile Type", APP, "AWS::AppConfig::ConfigurationProfile",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.AppConfig.FeatureFlags\"",
                        "False", false),
                new Row("AppConfig profile LocationUri", APP, "AWS::AppConfig::ConfigurationProfile",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"",
                        PROFILE + "\"LocationUri\": \"ssm-parameter://replacement-settings\","
                                + " \"Type\": \"AWS.Freeform\"",
                        "True", false),
                new Row("ElastiCache cluster Port", "", "AWS::ElastiCache::CacheCluster",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"Port\": 6379",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"Port\": 6380",
                        "True", true),
                new Row("ElastiCache cluster CacheNodeType", "", "AWS::ElastiCache::CacheCluster",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\"",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.small\"",
                        "False", true));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void aChangeSetReportsReplacementExactlyWhenTheUpdateChangesThePhysicalId(Row row) throws Exception {
        if (row.needsDocker()) {
            Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for the cache data plane");
        }
        String stack = "repl-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            client.createStack(request -> request.stackName(stack).templateBody(row.template(row.before())));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack);

            client.createChangeSet(request -> request.stackName(stack).changeSetName("change")
                    .changeSetType(ChangeSetType.UPDATE).templateBody(row.template(row.after())));
            List<String> reported = client.describeChangeSet(request -> request.stackName(stack)
                    .changeSetName("change")).changes().stream()
                    .filter(change -> "Subject".equals(change.resourceChange().logicalResourceId()))
                    .map(change -> change.resourceChange().replacementAsString()).toList();
            assertEquals(List.of(row.replacement()), reported, row.label());

            client.executeChangeSet(request -> request.stackName(stack).changeSetName("change"));
            CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");
            String after = physicalId(client, stack);

            assertEquals("True".equals(row.replacement()), !before.equals(after),
                    row.label() + ": physical id " + before + " then " + after);
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String physicalId(CloudFormationClient client, String stack) {
        return client.describeStackResource(request -> request.stackName(stack).logicalResourceId("Subject"))
                .stackResourceDetail().physicalResourceId();
    }
}
