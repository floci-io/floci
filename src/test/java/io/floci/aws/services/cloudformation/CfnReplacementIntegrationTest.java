package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.PROFILE_TYPE;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.appConfigApplication;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.createUpdateChangeSet;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.executeAndAwaitUpdate;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.json;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.profileProperties;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.profileStack;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.replacement;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.subjectStack;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.subnetGroup;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.vpcAndSubnet;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An update replaces a resource exactly when its change set said {@code Replacement: True}, and the
 * change set's {@code Replacement} is the one AWS documents for the changed property ("Update
 * requires"). Covered: AppConfig profiles and ElastiCache clusters changed in a create-only and in
 * another property; a create-only value that is not plain template text (a dynamic reference
 * rewritten as the literal it resolves to, a value known only during the update, a value read from
 * a resource replaced in the same update, an attribute another resource moves while the update
 * runs); and a stack that holds no record of what its dynamic references resolved to.
 */
@QuarkusTest
class CfnReplacementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String APP_REF = "{\"Ref\": \"App\"}";
    private static final String CLUSTER = "\"Engine\": \"redis\", \"NumCacheNodes\": 1, ";

    /** One update: its templates, what the change set says for {@code subject}, and whether it is replaced. */
    private record Row(String label, String before, String after, String subject, String preview,
                       boolean replaced, Consumer<String> afterCreate, boolean needsDocker,
                       String clusterSubnetGroupAfter) {

        Row(String label, String before, String after, String subject, String preview, boolean replaced,
            Consumer<String> afterCreate) {
            this(label, before, after, subject, preview, replaced, afterCreate, false, null);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Inject
    CloudFormationService cloudFormationService;

    /** A create-only row: {@code @V@} in the parameters becomes a before the update and b after it. */
    private static Row createOnlyRow(String label, String extraResources, String parameters, String type,
                                     String before, String after, String preview, boolean needsDocker,
                                     String clusterSubnetGroupAfter) {
        return new Row(label, subjectStack(extraResources, parameters, type, before).replace("@V@", "a"),
                subjectStack(extraResources, parameters, type, after).replace("@V@", "b"), "Subject", preview,
                "True".equals(preview), null, needsDocker, clusterSubnetGroupAfter);
    }

    private static final String PROFILE = profileProperties(APP_REF, "");

    /** The cluster names a subnet group whose own name comes from a parameter that changes in the update. */
    private static final Row SUBNET_GROUP_REPLACED = createOnlyRow("ElastiCache cluster in a replaced subnet group",
            json(vpcAndSubnet("10.73")
                    + "'SubnetGroup': {'Type': 'AWS::ElastiCache::SubnetGroup',"
                    + " 'Properties': {'CacheSubnetGroupName': {'Ref': 'Sg'}, 'Description': 'example',"
                    + " 'SubnetIds': [{'Ref': 'Subnet'}]}}"),
            "\"Sg\": {\"Type\": \"String\", \"Default\": \"repl-sng-@V@\"}",
            "AWS::ElastiCache::CacheCluster",
            CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"CacheSubnetGroupName\": {\"Ref\": \"SubnetGroup\"}",
            CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"CacheSubnetGroupName\": {\"Ref\": \"SubnetGroup\"}",
            "True", true, "repl-sng-b");

    static Stream<Row> rows() {
        String app = appConfigApplication("replacement-orders");
        return Stream.of(
                createOnlyRow("AppConfig profile Type", app, "", PROFILE_TYPE,
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"",
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.AppConfig.FeatureFlags\"",
                        "False", false, null),
                createOnlyRow("AppConfig profile LocationUri", app, "", PROFILE_TYPE,
                        PROFILE + "\"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"",
                        PROFILE + "\"LocationUri\": \"ssm-parameter://replacement-settings\","
                                + " \"Type\": \"AWS.Freeform\"",
                        "True", false, null),
                createOnlyRow("ElastiCache cluster Port", "", "", "AWS::ElastiCache::CacheCluster",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"Port\": 6379",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\", \"Port\": 6380",
                        "True", true, null),
                createOnlyRow("ElastiCache cluster CacheNodeType", "", "", "AWS::ElastiCache::CacheCluster",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.micro\"",
                        CLUSTER + "\"CacheNodeType\": \"cache.t3.small\"",
                        "False", true, null),
                SUBNET_GROUP_REPLACED,
                new Row("deployed parameter renamed, same value",
                        locationStack(stringParameter("NameA", HOSTED), "", REF_A, "d"),
                        locationStack(stringParameter("NameB", HOSTED), "", REF_B, "e"),
                        "Subject", "False", false, null),
                new Row("deployed parameter value moved into a mapping",
                        locationStack(stringParameter("NameA", HOSTED), "", REF_A, "d"),
                        locationStack("", "\"M\": {\"k\": {\"v\": \"" + HOSTED + "\"}}",
                                "{\"Fn::FindInMap\": [\"M\", \"k\", \"v\"]}", "e"),
                        "Subject", "False", false, null),
                new Row("deployed parameter renamed, another value",
                        locationStack(stringParameter("NameA", HOSTED), "", REF_A, "d"),
                        locationStack(stringParameter("NameB", "ssm-parameter://deployed-other"), "", REF_B, "e"),
                        "Subject", "True", true, null));
    }

    private static final String HOSTED = "hosted";
    private static final String REF_A = "{\"Ref\": \"NameA\"}";
    private static final String REF_B = "{\"Ref\": \"NameB\"}";

    @ParameterizedTest
    @MethodSource("rows")
    void aChangeSetReportsReplacementExactlyWhenTheUpdateChangesThePhysicalId(Row row) throws Exception {
        if (row.needsDocker()) {
            Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for the cache data plane");
        }
        String stack = "repl-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, row.before(), "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Subject");

            createUpdateChangeSet(client, stack, "change", row.after());
            assertEquals(List.of(row.preview()), replacement(client, stack, "change", "Subject"), row.label());

            executeAndAwaitUpdate(client, stack, "change");
            String after = physicalId(client, stack, "Subject");

            assertEquals(row.replaced(), !before.equals(after),
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

    @Test
    void aCreateOnlyValueFromADynamicReferenceThatChangesReplacesTheResource() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "repl-dynamic-" + suffix;
        String parameter = "/repl/location-" + suffix;
        String profile = "\"LocationUri\": \"{{resolve:ssm:" + parameter + "}}\", \"Type\": \"AWS.Freeform\"";
        String before = profileStack("replacement-orders", APP_REF, profile);
        String afterUpdate = profileStack("replacement-orders", APP_REF, profile + ", \"Description\": \"edited\"");
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
                SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            ssm.putParameter(request -> request.name(parameter).type("String").value("ssm-parameter://orders-a"));
            CfnStackWaits.createStack(cfn, stack, before, "CREATE_COMPLETE");
            String profileBefore = physicalId(cfn, stack, "Subject");

            ssm.putParameter(request -> request.name(parameter).type("String").value("ssm-parameter://orders-b")
                    .overwrite(true));
            createUpdateChangeSet(cfn, stack, "change", afterUpdate);
            assertEquals(List.of("True"), replacement(cfn, stack, "change", "Subject"));

            executeAndAwaitUpdate(cfn, stack, "change");
            String profileAfter = physicalId(cfn, stack, "Subject");

            assertNotEquals(profileBefore, profileAfter, "the changed SSM value replaces the profile");
            String application = physicalId(cfn, stack, "App");
            given().contentType(ContentType.JSON)
                    .get("/applications/" + application + "/configurationprofiles/" + profileAfter)
                    .then().statusCode(200).body("LocationUri", equalTo("ssm-parameter://orders-b"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String resolvedProfile(String locationUri, String description) {
        return profileStack("resolved-app", APP_REF, "\"LocationUri\": " + locationUri
                + ", \"Type\": \"AWS.Freeform\", \"Description\": \"" + description + "\"");
    }

    @Test
    void aDynamicReferenceWhoseStoredValueMovedReplacesTheProfile() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "resolved-ssm-" + suffix;
        String parameter = "/resolved/" + suffix + "/location";
        String reference = "\"{{resolve:ssm:" + parameter + "}}\"";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            ssm.putParameter(request -> request.name(parameter).value("ssm-parameter://one")
                    .type(ParameterType.STRING).overwrite(true));
            CfnStackWaits.createStack(client, stack, resolvedProfile(reference, "first"), "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Subject");

            ssm.putParameter(request -> request.name(parameter).value("ssm-parameter://two")
                    .type(ParameterType.STRING).overwrite(true));
            createUpdateChangeSet(client, stack, "move", resolvedProfile(reference, "second"));
            assertEquals(List.of("True"), replacement(client, stack, "move", "Subject"));

            executeAndAwaitUpdate(client, stack, "move");
            assertNotEquals(before, physicalId(client, stack, "Subject"), "the profile is replaced, as the preview said");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aRefWrittenAsAJoinOfTheSameValueIsNotAChange() throws Exception {
        String stack = "resolved-join-" + Long.toString(System.nanoTime(), 36);
        String asRef = "{\"Ref\": \"App\"}";
        String asJoin = "{\"Fn::Join\": [\"\", [{\"Ref\": \"App\"}]]}";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, applicationTemplate(asRef, "first"), "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Subject");

            createUpdateChangeSet(client, stack, "rewrite", applicationTemplate(asJoin, "second"));
            assertEquals(List.of("False"), replacement(client, stack, "rewrite", "Subject"));

            executeAndAwaitUpdate(client, stack, "rewrite");
            assertEquals(before, physicalId(client, stack, "Subject"), "the same ApplicationId keeps the profile");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String applicationTemplate(String applicationId, String description) {
        return profileStack("resolved-join-app", applicationId,
                "\"LocationUri\": \"hosted\", \"Description\": \"" + description + "\"");
    }

    private static String launchTemplateFeedingProfile(String instanceType) {
        return subjectStack("\"Template\": {\"Type\": \"AWS::EC2::LaunchTemplate\", \"Properties\": {"
                        + "\"LaunchTemplateName\": \"at-execution\", \"LaunchTemplateData\": {\"InstanceType\": \""
                        + instanceType + "\"}}}," + appConfigApplication("at-exec"),
                "", PROFILE_TYPE, profileProperties(APP_REF, "\"LocationUri\":"
                        + " {\"Fn::Sub\": \"ssm-parameter://version-${Template.LatestVersionNumber}\"}"));
    }

    @Test
    void aCreateOnlyPropertyReadingAnAttributeThatMovedReplacesTheResource() throws Exception {
        String stack = "at-exec-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, launchTemplateFeedingProfile("t3.micro"), "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Subject");

            CfnStackWaits.updateStack(client, stack, launchTemplateFeedingProfile("t3.small"), "UPDATE_COMPLETE");

            assertNotEquals(before, physicalId(client, stack, "Subject"),
                    "the profile's LocationUri resolves to another version, so it is replaced");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static final String GROUP_NAME_TEMPLATE = """
            {"Parameters": {"GroupName": {"Type": "String"}},
             "Resources": {
              "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.74.0.0/16"}},
              "Subnet": {"Type": "AWS::EC2::Subnet",
                         "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.74.0.0/24"}},
              "SubnetGroup": {"Type": "AWS::ElastiCache::SubnetGroup",
                              "Properties": {"CacheSubnetGroupName": {"Ref": "GroupName"}, "Description": "example",
                                             "SubnetIds": [{"Ref": "Subnet"}]}},
              "Cluster": {"Type": "AWS::ElastiCache::CacheCluster",
                          "Properties": {"Engine": "redis", "CacheNodeType": "cache.t3.micro", "NumCacheNodes": 1,
                                         "CacheSubnetGroupName": {"Ref": "SubnetGroup"}}}
             }}
            """;

    @Test
    void aClusterReadingAReplacedSubnetGroupIsReplacedAsThePreviewSaid() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for the cache data plane");
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "repl-sng-" + suffix;
        String first = "sng-a-" + suffix;
        String second = "sng-b-" + suffix;
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             ElastiCacheClient cache = AwsTestClients.create(ElastiCacheClient.builder(), "test", Region.US_EAST_1)) {
            client.createStack(request -> request.stackName(stack).templateBody(GROUP_NAME_TEMPLATE)
                    .parameters(Parameter.builder().parameterKey("GroupName").parameterValue(first).build()));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Cluster");

            createUpdateChangeSet(client, stack, "rename", GROUP_NAME_TEMPLATE,
                    Parameter.builder().parameterKey("GroupName").parameterValue(second).build());
            assertEquals(List.of("True"), replacement(client, stack, "rename", "Cluster"));

            executeAndAwaitUpdate(client, stack, "rename");

            String after = physicalId(client, stack, "Cluster");
            assertNotEquals(before, after, "the cluster is replaced, as the preview said");
            assertEquals(second, cache.describeCacheClusters(request -> request.cacheClusterId(after))
                    .cacheClusters().get(0).cacheSubnetGroupName());
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String previewProfile(String locationUri, String description) {
        return profileStack("preview-app", APP_REF, json("\"LocationUri\": " + locationUri
                + ", \"Type\": \"AWS.Freeform\", \"Description\": \"" + description + "\""));
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
                new Row("reference to the literal it resolves to", previewProfile(reference, "d"),
                        previewProfile("'hosted'", "d"), "Subject", "False", false, null),
                new Row("reference to another literal", previewProfile(reference, "d"),
                        previewProfile("'s3://b/k'", "d"), "Subject", "True", true, null));
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
                        previewProfile(reference, "d"), previewProfile(reference, "e"),
                        "Subject", "False", false, this::forgetDigests),
                new Row("no record for one create-only property does not hide another one that changed",
                        clusterInGroup(json(engineReference), "e" + suffix + "-1", "e" + suffix),
                        clusterInGroup(json(engineReference), "e" + suffix + "-2", "e" + suffix),
                        "Cluster", "True", true, this::forgetDigests),
                new Row("a digest of an unresolvable reference, only a description changes",
                        previewProfile(reference, "d"), previewProfile(reference, "e"),
                        "Subject", "False", false, this::storeUnresolvableDigests),
                new Row("a literal that starts with the unrecorded marker changes to another literal",
                        previewProfile("'unrecorded one'", "d"), previewProfile("'unrecorded two'", "d"),
                        "Subject", "True", true, null));
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
            CfnStackWaits.createStack(client, stack,
                    previewProfile("'{{resolve:ssm:" + parameter + "}}'", "d"), "CREATE_COMPLETE");
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

    /** A profile whose create-only LocationUri is {@code locationUri}, read from {@code parameters} and {@code mappings}. */
    private static String locationStack(String parameters, String mappings, String locationUri, String description) {
        String template = subjectStack(appConfigApplication("deployed-app"), parameters, PROFILE_TYPE,
                profileProperties(APP_REF, "\"LocationUri\": " + locationUri + ", \"Description\": \""
                        + description + "\""));
        return mappings.isEmpty() ? template : "{\"Mappings\": {" + mappings + "}, " + template.substring(1);
    }

    private static String stringParameter(String name, String value) {
        return "\"" + name + "\": {\"Type\": \"String\", \"Default\": \"" + value + "\"}";
    }

    private static void update(Row row, String stack) throws Exception {
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, row.before(), "CREATE_COMPLETE");
            String before = physicalId(client, stack, row.subject());
            if (row.afterCreate() != null) {
                row.afterCreate().accept(stack);
            }

            createUpdateChangeSet(client, stack, "move", row.after());
            assertEquals(List.of(row.preview()), replacement(client, stack, "move", row.subject()), row.label());

            executeAndAwaitUpdate(client, stack, "move");
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
