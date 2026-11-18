package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.createUpdateChangeSet;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.executeAndAwaitUpdate;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.subjectStack;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that CloudFormation provisions EKS resources for real (into EksService) rather
 * than stubbing them. EKS runs in mock mode under test (floci.services.eks.mock=true), so no k3s
 * container starts — the test stays Docker-free.
 */
@QuarkusTest
class CloudFormationEksIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void createStackProvisionsEksClusterAndNodegroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterName = "cfn-eks-" + suffix;
        String nodegroupName = "ng-" + suffix;
        String stackName = "cfn-eks-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Cluster": {
                      "Type": "AWS::EKS::Cluster",
                      "Properties": {
                        "Name": "%s",
                        "Version": "1.29",
                        "RoleArn": "arn:aws:iam::000000000000:role/eks-cluster-role"
                      }
                    },
                    "Nodes": {
                      "Type": "AWS::EKS::Nodegroup",
                      "Properties": {
                        "ClusterName": {"Ref": "Cluster"},
                        "NodegroupName": "%s",
                        "NodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
                        "Subnets": ["subnet-aaaa1111", "subnet-bbbb2222"]
                      }
                    }
                  },
                  "Outputs": {
                    "ClusterRef": {"Value": {"Ref": "Cluster"}},
                    "ClusterArn": {"Value": {"Fn::GetAtt": ["Cluster", "Arn"]}}
                  }
                }
                """.formatted(clusterName, nodegroupName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(clusterName))
            // Fn::GetAtt Cluster.Arn resolves to a real EKS arn, not the literal "Cluster.Arn".
            .body(containsString("arn:aws:eks:"));

        // The cluster really exists in EKS (provisioned, not stubbed).
        given()
        .when()
            .get("/clusters/" + clusterName)
        .then()
            .statusCode(200)
            .body(containsString(clusterName));

        // The nodegroup was created under it (ClusterName resolved from Ref(Cluster)).
        given()
        .when()
            .get("/clusters/" + clusterName + "/node-groups/" + nodegroupName)
        .then()
            .statusCode(200)
            .body(containsString(nodegroupName));
    }

    @Test
    void anUnnamedClusterIsUpdatedInPlaceAndReplacedOnlyWhenItsRoleChanges() {
        String stack = "cfn-eks-upd-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, cluster("role-a", "one"), "CREATE_COMPLETE");
            String first = physicalId(client, stack, "Subject");
            int count = given().get("/clusters").jsonPath().getList("clusters").size();

            createUpdateChangeSet(client, stack, "tags", cluster("role-a", "two"));
            executeAndAwaitUpdate(client, stack, "tags");
            assertEquals(first, physicalId(client, stack, "Subject"));
            assertEquals(count, given().get("/clusters").jsonPath().getList("clusters").size());
            assertEquals("two", given().get("/clusters/" + first).jsonPath().getString("cluster.tags.team"));

            createUpdateChangeSet(client, stack, "role", cluster("role-b", "two"));
            executeAndAwaitUpdate(client, stack, "role");
            assertNotEquals(first, physicalId(client, stack, "Subject"));
            given().get("/clusters/" + first).then().statusCode(404);
            assertEquals(count, given().get("/clusters").jsonPath().getList("clusters").size());
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void anUnnamedNodegroupKeepsItsArnAndReconcilesStackTagsWhenOnlyTagsChange() {
        String stack = "cfn-eksng-upd-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, nodegroup("one", "{\"Key\": \"old\", \"Value\": \"x\"}"),
                    "CREATE_COMPLETE");
            String base = "/clusters/" + physicalId(client, stack, "Cluster") + "/node-groups";
            List<String> names = given().get(base).jsonPath().getList("nodegroups");
            assertEquals(1, names.size());
            String arn = given().get(base + "/" + names.getFirst()).jsonPath().getString("nodegroup.nodegroupArn");
            given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("tags", Map.of("ext", "1")))
                    .post("/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8)).then().statusCode(200);

            createUpdateChangeSet(client, stack, "tags", nodegroup("two", ""));
            executeAndAwaitUpdate(client, stack, "tags");

            assertEquals(names, given().get(base).jsonPath().getList("nodegroups"));
            JsonPath after = given().get(base + "/" + names.getFirst()).jsonPath();
            assertEquals(arn, after.getString("nodegroup.nodegroupArn"));
            assertEquals("two", after.getString("nodegroup.tags.team"));
            assertEquals("1", after.getString("nodegroup.tags.ext"));
            assertNull(after.getString("nodegroup.tags.old"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aDroppedTagIsGoneFromTheOriginalNodegroupAfterAReplacementRolledBack() {
        String stack = "cfn-eksng-rb-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, rollbackStack("a", "env", "dev", false), "CREATE_COMPLETE");
            String base = "/clusters/" + physicalId(client, stack, "Cluster") + "/node-groups";
            List<String> names = given().get(base).jsonPath().getList("nodegroups");

            CfnStackWaits.updateStack(client, stack, rollbackStack("b", "team", "a", true),
                    "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(names, given().get(base).jsonPath().getList("nodegroups"));

            CfnStackWaits.updateStack(client, stack, rollbackStack("a", null, null, false), "UPDATE_COMPLETE");
            assertEquals(names, given().get(base).jsonPath().getList("nodegroups"));
            assertNull(given().get(base + "/" + names.getFirst()).jsonPath().getString("nodegroup.tags.env"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String rollbackStack(String roleSuffix, String tagKey, String tagValue, boolean laterFails) {
        String tags = tagKey == null ? "" : ", \"Tags\": [{\"Key\": \"" + tagKey
                + "\", \"Value\": \"" + tagValue + "\"}]";
        String cluster = "\"Cluster\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {\"RoleArn\":"
                + " \"arn:aws:iam::000000000000:role/eks-cluster-role\"" + tags + "}}";
        String template = subjectStack(cluster, "", "AWS::EKS::Nodegroup", "\"ClusterName\": {\"Ref\": \"Cluster\"},"
                + " \"NodeRole\": \"arn:aws:iam::000000000000:role/eks-node-role-" + roleSuffix + "\","
                + " \"Subnets\": [\"subnet-aaaa1111\"]" + tags);
        return laterFails
                ? template.substring(0, template.length() - 2) + CfnStackWaits.failingResourceAfter("Subject") + "}}"
                : template;
    }

    private static String nodegroup(String team, String extraTag) {
        String cluster = "\"Cluster\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {\"RoleArn\":"
                + " \"arn:aws:iam::000000000000:role/eks-cluster-role\"}}";
        return subjectStack(cluster, "", "AWS::EKS::Nodegroup", "\"ClusterName\": {\"Ref\": \"Cluster\"},"
                + " \"NodeRole\": \"arn:aws:iam::000000000000:role/eks-node-role\","
                + " \"Subnets\": [\"subnet-aaaa1111\"], \"Tags\": [{\"Key\": \"team\", \"Value\": \""
                + team + "\"}" + (extraTag.isEmpty() ? "" : ", " + extraTag) + "]");
    }

    @Test
    void aClusterIsCreatedWithItsEncryptionAndNetworkSettings() {
        String stack = "cfn-eks-set-" + Long.toString(System.nanoTime(), 36);
        String key = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";
        String listed = "\"EncryptionConfig\": [{\"Provider\": {\"KeyArn\": \"" + key
                + "\"}, \"Resources\": [\"secrets\"]}], " + NETWORK;
        String bare = "\"EncryptionConfig\": [{\"Provider\": {\"KeyArn\": \"" + key + "\"}}]";
        String template = "{\"Resources\": {"
                + "\"Subject\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {" + ROLE + listed + "}},"
                + "\"Bare\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {" + ROLE + bare + "}}}}";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, template, "CREATE_COMPLETE");
            JsonPath cluster = given().get("/clusters/" + physicalId(client, stack, "Subject")).jsonPath();
            assertEquals(key, cluster.getString("cluster.encryptionConfig[0].provider.keyArn"));
            assertEquals(List.of("secrets"), cluster.getList("cluster.encryptionConfig[0].resources"));
            assertEquals("172.20.0.0/16", cluster.getString("cluster.kubernetesNetworkConfig.serviceIpv4Cidr"));
            assertEquals(List.of("secrets"), given().get("/clusters/" + physicalId(client, stack, "Bare"))
                    .jsonPath().getList("cluster.encryptionConfig[0].resources"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aBlankEncryptionConfigFailsTheClusterInsteadOfCreatingItUnencrypted() {
        for (String blank : List.of("\"EncryptionConfig\": \"\"",
                "\"Logging\": {\"ClusterLogging\": {\"EnabledTypes\": [{}]}}",
                "\"Logging\": {\"ClusterLogging\": {\"EnabledTypes\": [{\"Type\": \"\"}]}}")) {
            String stack = "cfn-eks-blank-" + Long.toString(System.nanoTime(), 36);
            try (CloudFormationClient client = AwsTestClients.create(
                    CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
                CfnStackWaits.createStack(client, stack, subjectStack("", "", "AWS::EKS::Cluster",
                        ROLE + blank), "ROLLBACK_COMPLETE");
            } finally {
                CfnStackWaits.deleteStack(stack);
            }
        }
    }

    /** EKS Auto Mode stays out of AWS::EKS::Cluster for now, so a template enabling it still creates its cluster. */
    @Test
    void aClusterEnablingAutoModeIsCreatedWithoutIt() {
        String stack = "cfn-eks-auto-" + Long.toString(System.nanoTime(), 36);
        String autoMode = "\"ComputeConfig\": {\"Enabled\": true, \"NodePools\": [\"general-purpose\", \"system\"],"
                + " \"NodeRoleArn\": \"arn:aws:iam::000000000000:role/eks-auto-node\"},"
                + " \"StorageConfig\": {\"BlockStorage\": {\"Enabled\": true}},"
                + " \"KubernetesNetworkConfig\": {\"ElasticLoadBalancing\": {\"Enabled\": true}}";
        // The Gateway API settings, which Floci doesn't model, don't fail the conversion either.
        String gatewayApi = "\"KubernetesNetworkConfig\": {\"ElasticLoadBalancing\": {\"Enabled\": false,"
                + " \"GatewayApi\": {\"Crds\": {\"Managed\": true}}}}";
        String template = "{\"Resources\": {"
                + "\"Subject\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {" + ROLE + autoMode + "}},"
                + "\"Gateway\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {" + ROLE + gatewayApi + "}}}}";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, template, "CREATE_COMPLETE");
            for (String resource : List.of("Subject", "Gateway")) {
                JsonPath cluster = given().get("/clusters/" + physicalId(client, stack, resource)).jsonPath();
                assertNull(cluster.get("cluster.computeConfig"), resource);
                assertNull(cluster.get("cluster.storageConfig"), resource);
                assertNull(cluster.get("cluster.kubernetesNetworkConfig.elasticLoadBalancing"), resource);
            }
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void changingTheServiceCidrReplacesTheClusterWithTheNewOne() {
        String stack = "cfn-eks-cidr-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, subjectStack("", "", "AWS::EKS::Cluster", ROLE + NETWORK),
                    "CREATE_COMPLETE");
            String first = physicalId(client, stack, "Subject");
            createUpdateChangeSet(client, stack, "cidr", subjectStack("", "", "AWS::EKS::Cluster",
                    ROLE + NETWORK.replace("172.20.0.0/16", "192.168.128.0/20")));
            executeAndAwaitUpdate(client, stack, "cidr");
            String second = physicalId(client, stack, "Subject");
            assertNotEquals(first, second);
            assertEquals("192.168.128.0/20", given().get("/clusters/" + second).jsonPath()
                    .getString("cluster.kubernetesNetworkConfig.serviceIpv4Cidr"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aClusterHonoursItsVpcLoggingAndAccessSettings() {
        String stack = "cfn-eks-cfg-" + Long.toString(System.nanoTime(), 36);
        String template = "{\"Resources\": {"
                + "\"Vpc\": {\"Type\": \"AWS::EC2::VPC\", \"Properties\": {\"CidrBlock\": \"10.7.0.0/16\"}},"
                + subnet("SubA", "10.7.1.0/24", "us-east-1a") + subnet("SubB", "10.7.2.0/24", "us-east-1b")
                + "\"Subject\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {" + ROLE
                + vpcConfig("{\"Ref\": \"SubA\"}, {\"Ref\": \"SubB\"}") + ", " + SETTINGS + "}}}}";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, template, "CREATE_COMPLETE");
            JsonPath cluster = given().get("/clusters/" + physicalId(client, stack, "Subject")).jsonPath();
            assertEquals(false, cluster.getBoolean("cluster.resourcesVpcConfig.endpointPublicAccess"));
            assertEquals(true, cluster.getBoolean("cluster.resourcesVpcConfig.endpointPrivateAccess"));
            assertEquals(Stream.of(physicalId(client, stack, "SubA"), physicalId(client, stack, "SubB")).sorted().toList(),
                    cluster.getList("cluster.resourcesVpcConfig.subnetIds").stream().sorted().toList());
            assertEquals(List.of("api", "audit"), cluster.getList(
                    "cluster.logging.clusterLogging.find { it.enabled == true }.types").stream().sorted().toList());
            assertEquals("API", cluster.getString("cluster.accessConfig.authenticationMode"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aClustersConfigIsRestoredWhenAnUpdateRollsBackAndChangedInPlaceWhenItSucceeds() {
        String stack = "cfn-eks-cfgupd-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, configStack(false, false), "CREATE_COMPLETE");
            String id = physicalId(client, stack, "Subject");

            CfnStackWaits.updateStack(client, stack, configStack(true, true), "UPDATE_ROLLBACK_COMPLETE");
            JsonPath cluster = given().get("/clusters/" + physicalId(client, stack, "Subject")).jsonPath();
            assertEquals(true, cluster.getBoolean("cluster.resourcesVpcConfig.endpointPublicAccess"));
            assertEquals("API_AND_CONFIG_MAP", cluster.getString("cluster.accessConfig.authenticationMode"));
            assertEquals(List.of(), cluster.getList("cluster.logging.clusterLogging.findAll { it.enabled == true }"));

            CfnStackWaits.updateStack(client, stack, configStack(true, false), "UPDATE_COMPLETE");
            assertEquals(id, physicalId(client, stack, "Subject"));
            cluster = given().get("/clusters/" + id).jsonPath();
            assertEquals(false, cluster.getBoolean("cluster.resourcesVpcConfig.endpointPublicAccess"));
            assertEquals(true, cluster.getBoolean("cluster.resourcesVpcConfig.endpointPrivateAccess"));
            assertEquals(List.of("api"),
                    cluster.getList("cluster.logging.clusterLogging.find { it.enabled == true }.types"));
            assertEquals("API", cluster.getString("cluster.accessConfig.authenticationMode"));
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aClusterNamingAMissingSubnetFailsTheStack() {
        String stack = "cfn-eks-nosub-" + Long.toString(System.nanoTime(), 36);
        String template = "{\"Resources\": {\"Subject\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {"
                + ROLE + vpcConfig("\"subnet-0000000000000000f\"") + "}}}}";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            client.createStack(request -> request.stackName(stack).templateBody(template));
            String status = CfnStackWaits.awaitTerminal(stack).status();
            assertTrue(List.of("CREATE_FAILED", "ROLLBACK_COMPLETE").contains(status), status);
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static final String ROLE = "\"RoleArn\": \"arn:aws:iam::000000000000:role/eks-set\", ";
    private static final String NETWORK = "\"KubernetesNetworkConfig\": {\"IpFamily\": \"ipv4\","
            + " \"ServiceIpv4Cidr\": \"172.20.0.0/16\"}";

    private static final String SETTINGS = "\"Logging\": {\"ClusterLogging\": {\"EnabledTypes\": ["
            + "{\"Type\": \"api\"}, {\"Type\": \"audit\"}]}}, \"AccessConfig\": {\"AuthenticationMode\": \"API\"}";

    private static String subnet(String id, String cidr, String zone) {
        return "\"" + id + "\": {\"Type\": \"AWS::EC2::Subnet\", \"Properties\": {\"VpcId\": {\"Ref\": \"Vpc\"},"
                + " \"CidrBlock\": \"" + cidr + "\", \"AvailabilityZone\": \"" + zone + "\"}},";
    }

    private static String vpcConfig(String subnetIds) {
        return "\"ResourcesVpcConfig\": {\"SubnetIds\": [" + subnetIds + "], \"EndpointPublicAccess\": false,"
                + " \"EndpointPrivateAccess\": true}";
    }

    /** The cluster of the config test: its original settings, or the updated ones; a failing resource follows it when asked. */
    private static String configStack(boolean updated, boolean fails) {
        String settings = updated
                ? "\"EndpointPublicAccess\": false, \"EndpointPrivateAccess\": true}, \"Logging\": {\"ClusterLogging\": "
                        + "{\"EnabledTypes\": [{\"Type\": \"api\"}]}}, \"AccessConfig\": {\"AuthenticationMode\": \"API\"}"
                : "\"EndpointPublicAccess\": true}, \"AccessConfig\": {\"AuthenticationMode\": \"API_AND_CONFIG_MAP\"}";
        return "{\"Resources\": {"
                + "\"Vpc\": {\"Type\": \"AWS::EC2::VPC\", \"Properties\": {\"CidrBlock\": \"10.7.0.0/16\"}},"
                + subnet("SubA", "10.7.1.0/24", "us-east-1a") + subnet("SubB", "10.7.2.0/24", "us-east-1b")
                + "\"Subject\": {\"Type\": \"AWS::EKS::Cluster\", \"Properties\": {" + ROLE
                + "\"ResourcesVpcConfig\": {\"SubnetIds\": [{\"Ref\": \"SubA\"}, {\"Ref\": \"SubB\"}], " + settings
                + "}}" + (fails ? CfnStackWaits.failingResourceAfter("Subject") : "") + "}}";
    }

    private static String cluster(String role, String team) {
        return subjectStack("", "", "AWS::EKS::Cluster", "\"RoleArn\": \"arn:aws:iam::000000000000:role/" + role
                + "\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"" + team + "\"}]");
    }
}
