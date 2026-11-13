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
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;

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

    private record Row(String label, String extraResources, String parameters, String type, String before,
                       String after, String replacement, boolean needsDocker, String clusterSubnetGroupAfter) {

        @Override
        public String toString() {
            return label;
        }

        /** The template with {@code properties} on the subject; {@code @V@} becomes b once {@code after}. */
        String template(String properties, boolean after) {
            String extra = extraResources.isEmpty() ? "" : extraResources + ",";
            String params = parameters.isEmpty() ? "" : "\"Parameters\": {" + parameters + "}, ";
            return ("{" + params + "\"Resources\": {" + extra + "\"Subject\": {\"Type\": \"" + type
                    + "\", \"Properties\": {" + properties + "}}}}").replace("@V@", after ? "b" : "a");
        }
    }

    private static final String PROFILE = "\"ApplicationId\": {\"Ref\": \"App\"}, \"Name\": \"settings\", ";
    private static final String CLUSTER = "\"Engine\": \"redis\", \"NumCacheNodes\": 1, ";

    /** The cluster names a subnet group whose own name comes from a parameter that changes in the update. */
    private static final Row SUBNET_GROUP_REPLACED = new Row("ElastiCache cluster in a replaced subnet group",
            """
            "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.73.0.0/16"}},
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.73.0.0/24"}},
            "SubnetGroup": {"Type": "AWS::ElastiCache::SubnetGroup",
                            "Properties": {"CacheSubnetGroupName": {"Ref": "Sg"}, "Description": "example",
                                           "SubnetIds": [{"Ref": "Subnet"}]}}""",
            "\"Sg\": {\"Type\": \"String\", \"Default\": \"repl-sng-@V@\"}",
            "AWS::ElastiCache::CacheCluster",
            CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"CacheSubnetGroupName\": {\"Ref\": \"SubnetGroup\"}",
            CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"CacheSubnetGroupName\": {\"Ref\": \"SubnetGroup\"}",
            "True", true, "repl-sng-b");

    static Stream<Row> rows() {
        return Stream.of(
                new Row("AppConfig profile Type", APP, "", "AWS::AppConfig::ConfigurationProfile",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.AppConfig.FeatureFlags\"",
                        "False", false, null),
                new Row("AppConfig profile LocationUri", APP, "", "AWS::AppConfig::ConfigurationProfile",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"",
                        PROFILE + "\"LocationUri\": \"ssm-parameter://replacement-settings\","
                                + " \"Type\": \"AWS.Freeform\"",
                        "True", false, null),
                new Row("ElastiCache cluster Port", "", "", "AWS::ElastiCache::CacheCluster",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"Port\": 6379",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"Port\": 6380",
                        "True", true, null),
                new Row("ElastiCache cluster CacheNodeType", "", "", "AWS::ElastiCache::CacheCluster",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\"",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.small\"",
                        "False", true, null),
                SUBNET_GROUP_REPLACED);
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
            client.createStack(request -> request.stackName(stack).templateBody(row.template(row.before(), false)));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack);

            client.createChangeSet(request -> request.stackName(stack).changeSetName("change")
                    .changeSetType(ChangeSetType.UPDATE).templateBody(row.template(row.after(), true)));
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
            if (row.clusterSubnetGroupAfter() != null) {
                try (ElastiCacheClient cache = AwsTestClients.create(
                        ElastiCacheClient.builder(), "test", Region.US_EAST_1)) {
                    assertEquals(row.clusterSubnetGroupAfter(), cache.describeCacheClusters(request ->
                            request.cacheClusterId(after)).cacheClusters().getFirst().cacheSubnetGroupName());
                }
            }
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String physicalId(CloudFormationClient client, String stack) {
        return client.describeStackResource(request -> request.stackName(stack).logicalResourceId("Subject"))
                .stackResourceDetail().physicalResourceId();
    }
}
