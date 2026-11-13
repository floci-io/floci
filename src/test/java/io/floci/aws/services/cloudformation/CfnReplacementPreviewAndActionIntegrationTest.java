package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.replacement;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a change set previews for a resource ({@code Replacement}) and what the update then does
 * to its physical id agree, in the cases where the create-only value is not plain template text:
 * a dynamic reference rewritten as the literal it resolves to, a value known only during the
 * update, a value read from a resource replaced in the same update, and a stack that holds no
 * record of what its dynamic references resolved to.
 */
@QuarkusTest
class CfnReplacementPreviewAndActionIntegrationTest {

    private static final String REGION = "us-east-1";

    /** One update: its templates, what the change set says for {@code subject}, and whether it is replaced. */
    private record Row(String label, String before, String after, String subject, String preview,
                       boolean replaced, Consumer<String> afterCreate) {
    }

    @Inject
    CloudFormationService cloudFormationService;

    private static String json(String template) {
        return template.replace('\'', '"');
    }

    private static String profile(String locationUri) {
        return json("{'Resources': {'App': {'Type': 'AWS::AppConfig::Application',"
                + " 'Properties': {'Name': 'preview-app'}},"
                + "'Subject': {'Type': 'AWS::AppConfig::ConfigurationProfile', 'Properties': {"
                + "'ApplicationId': {'Ref': 'App'}, 'Name': 'settings', 'LocationUri': " + locationUri
                + ", 'Type': 'AWS.Freeform', 'Description': 'd'}}}}");
    }

    private static String profileWithDescription(String locationUri, String description) {
        return profile(locationUri).replace("\"Description\": \"d\"", "\"Description\": \"" + description + "\"");
    }

    private static String vpcAndSubnet(String cidrPrefix) {
        return "'Vpc': {'Type': 'AWS::EC2::VPC', 'Properties': {'CidrBlock': '" + cidrPrefix + ".0.0/16'}},"
                + "'Subnet': {'Type': 'AWS::EC2::Subnet', 'Properties': {'VpcId': {'Ref': 'Vpc'},"
                + " 'CidrBlock': '" + cidrPrefix + ".0.0/24'}},";
    }

    private static String subnetGroup(String logicalId, String name) {
        return "'" + logicalId + "': {'Type': 'AWS::ElastiCache::SubnetGroup', 'Properties': {"
                + "'CacheSubnetGroupName': '" + name + "', 'Description': 'example',"
                + " 'SubnetIds': [{'Ref': 'Subnet'}]}},";
    }

    private static String tags(String value) {
        return "'Tags': [{'Key': 'owner', 'Value': '" + value + "'}]";
    }

    /** Group names are {@code <prefix>-1} and {@code <prefix>-2}; the cluster picks one by the template's latest version. */
    private static String versionedCluster(String prefix, String instanceType, String tag) {
        return json("{'Resources': {" + vpcAndSubnet("10.81") + subnetGroup("Group1", prefix + "-1")
                + subnetGroup("Group2", prefix + "-2")
                + "'Template': {'Type': 'AWS::EC2::LaunchTemplate', 'Properties': {'LaunchTemplateName': '" + prefix
                + "', 'LaunchTemplateData': {'InstanceType': '" + instanceType + "'}}},"
                + "'Cluster': {'Type': 'AWS::ElastiCache::CacheCluster', 'DependsOn': ['Group1', 'Group2'],"
                + " 'Properties': {'Engine': 'redis', 'CacheNodeType': 'cache.t3.micro', 'NumCacheNodes': 1,"
                + " 'CacheSubnetGroupName': {'Fn::Join': ['', ['" + prefix + "-',"
                + " {'Fn::GetAtt': ['Template', 'LatestVersionNumber']}]]}, " + tags(tag) + "}}}}");
    }

    /** A cluster whose engine is {@code engine} (template text) and whose subnet group is the literal {@code group}. */
    private static String clusterInGroup(String engine, String group, String prefix) {
        return json("{'Resources': {" + vpcAndSubnet("10.83") + subnetGroup("Group1", prefix + "-1")
                + subnetGroup("Group2", prefix + "-2")
                + "'Cluster': {'Type': 'AWS::ElastiCache::CacheCluster', 'DependsOn': ['Group1', 'Group2'],"
                + " 'Properties': {'Engine': " + engine + ", 'CacheNodeType': 'cache.t3.micro', 'NumCacheNodes': 1,"
                + " 'CacheSubnetGroupName': '" + group + "'}}}}");
    }

    private static String clusterReadingGroup(String groupName, String tag) {
        return json("{'Resources': {" + vpcAndSubnet("10.82") + subnetGroup("R0", groupName)
                + "'R1': {'Type': 'AWS::ElastiCache::CacheCluster', 'Properties': {'Engine': 'redis',"
                + " 'CacheNodeType': 'cache.t3.micro', 'NumCacheNodes': 1,"
                + " 'CacheSubnetGroupName': {'Ref': 'R0'}, " + tags(tag) + "}}}}");
    }

    @Test
    void aDynamicReferenceRewrittenAsALiteralReplacesOnlyWhenTheValueDiffers() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String parameter = "/preview/" + suffix + "/location";
        String reference = "'{{resolve:ssm:" + parameter + "}}'";
        List<Row> rows = List.of(
                new Row("reference to the literal it resolves to", profile(json(reference)), profile("'hosted'"),
                        "Subject", "False", false, null),
                new Row("reference to another literal", profile(json(reference)), profile("'s3://b/k'"),
                        "Subject", "True", true, null));
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            ssm.putParameter(request -> request.name(parameter).value("hosted")
                    .type(ParameterType.STRING).overwrite(true));
            assertAll(rows.stream().map(row -> () -> update(row, "a-" + rows.indexOf(row) + "-" + suffix)));
        }
    }

    @Test
    void aValueKnownOnlyDuringTheUpdateIsConditionalAndADependentIsReplacedWithItsDependency() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String prefix = "lt" + suffix;
        String parameter = "/preview/" + suffix + "/unrecorded";
        String reference = "'{{resolve:ssm:" + parameter + "}}'";
        String engineParameter = "/preview/" + suffix + "/engine";
        String engineReference = "'{{resolve:ssm:" + engineParameter + "}}'";
        List<Row> rows = List.of(
                new Row("launch template changed in place", versionedCluster(prefix, "t3.micro", "1"),
                        versionedCluster(prefix, "t3.small", "1"), "Cluster", "Conditional", true, null),
                new Row("launch template unchanged, tags only", versionedCluster(prefix + "x", "t3.micro", "1"),
                        versionedCluster(prefix + "x", "t3.micro", "2"), "Cluster", "False", false, null),
                new Row("group replaced, cluster tags changed", clusterReadingGroup("sa-" + suffix, "1"),
                        clusterReadingGroup("sb-" + suffix, "2"), "R1", "True", true, null),
                new Row("no recorded digests, only a description changes",
                        profileWithDescription(json(reference), "d"), profileWithDescription(json(reference), "e"),
                        "Subject", "False", false, this::forgetDigests),
                new Row("no record for one create-only property does not hide another one that changed",
                        clusterInGroup(json(engineReference), "e" + suffix + "-1", "e" + suffix),
                        clusterInGroup(json(engineReference), "e" + suffix + "-2", "e" + suffix),
                        "Cluster", "True", true, this::forgetDigests),
                new Row("a digest of an unresolvable reference, only a description changes",
                        profileWithDescription(json(reference), "d"), profileWithDescription(json(reference), "e"),
                        "Subject", "False", false, this::storeUnresolvableDigests),
                new Row("a literal that starts with the unrecorded marker changes to another literal",
                        profile("'unrecorded one'"), profile("'unrecorded two'"), "Subject", "True", true, null));
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            ssm.putParameter(request -> request.name(parameter).value("hosted")
                    .type(ParameterType.STRING).overwrite(true));
            ssm.putParameter(request -> request.name(engineParameter).value("redis")
                    .type(ParameterType.STRING).overwrite(true));
            Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for the cache data plane");
            assertAll(rows.stream().map(row -> () -> update(row, "b-" + rows.indexOf(row) + "-" + suffix)));
        }
    }

    @Test
    void theDigestSeamReallyEmptiesTheStackRecord() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "seam-" + suffix;
        String parameter = "/preview/" + suffix + "/seam";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            ssm.putParameter(request -> request.name(parameter).value("hosted")
                    .type(ParameterType.STRING).overwrite(true));
            client.createStack(request -> request.stackName(stack)
                    .templateBody(profile(json("'{{resolve:ssm:" + parameter + "}}'"))));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            assertFalse(digests(stack).isEmpty(), "a stack created with a dynamic reference records its digest");
            forgetDigests(stack);
            assertTrue(digests(stack).isEmpty(), "the record is gone when read back");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private Map<String, String> digests(String stack) {
        return cloudFormationService.describeStacks(stack, REGION).getFirst().dynamicReferenceDigestsSnapshot();
    }

    private void forgetDigests(String stack) {
        cloudFormationService.describeStacks(stack, REGION).getFirst().replaceDynamicReferenceDigests(Map.of());
    }

    /** What an older stack may hold: a digest that stands for a reference that did not resolve. */
    private void storeUnresolvableDigests(String stack) {
        Stack current = cloudFormationService.describeStacks(stack, REGION).getFirst();
        Map<String, String> unresolvable = new LinkedHashMap<>();
        current.dynamicReferenceDigestsSnapshot().keySet()
                .forEach(key -> unresolvable.put(key, "unresolvable " + UUID.randomUUID()));
        current.replaceDynamicReferenceDigests(unresolvable);
    }

    private static void update(Row row, String stack) throws Exception {
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            client.createStack(request -> request.stackName(stack).templateBody(row.before()));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack, row.subject());
            if (row.afterCreate() != null) {
                row.afterCreate().accept(stack);
            }

            client.createChangeSet(request -> request.stackName(stack).changeSetName("move")
                    .changeSetType(ChangeSetType.UPDATE).templateBody(row.after()));
            assertEquals(List.of(row.preview()), replacement(client, stack, "move", row.subject()), row.label());

            client.executeChangeSet(request -> request.stackName(stack).changeSetName("move"));
            CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");
            String after = physicalId(client, stack, row.subject());
            if (row.replaced()) {
                assertNotEquals(before, after, row.label() + ": the update replaces the resource");
            } else {
                assertEquals(before, after, row.label() + ": the update keeps the resource");
            }
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }
}
