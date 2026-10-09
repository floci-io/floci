package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.CacheSubnetGroupNotFoundException;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Provisions the ElastiCache resource types through CloudFormation and checks them against the
 * ElastiCache API. Output values are asserted, not only the stack status: an unmapped type is
 * stubbed as CREATE_COMPLETE, and its Fn::GetAtt resolves to the literal "LogicalId.Attr".
 */
@QuarkusTest
class ElastiCacheCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String METADATA_STACK = "elasticache-cfn-metadata-it";
    private static final String DATA_STACK = "elasticache-cfn-data-it";

    private static final String METADATA_TEMPLATE = """
        {
          "Resources": {
            "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.71.0.0/16"}},
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.71.0.0/24"}},
            "SubnetGroup": {"Type": "AWS::ElastiCache::SubnetGroup",
                            "Properties": {"CacheSubnetGroupName": "cfn-it-sng", "Description": "example",
                                           "SubnetIds": [{"Ref": "Subnet"}]}}
          },
          "Outputs": {
            "SubnetGroupRef": {"Value": {"Ref": "SubnetGroup"}}
          }
        }
        """;

    private static final String DATA_TEMPLATE = """
        {
          "Resources": {
            "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.72.0.0/16"}},
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.72.0.0/24"}},
            "SubnetGroup": {"Type": "AWS::ElastiCache::SubnetGroup",
                            "Properties": {"Description": "example", "SubnetIds": [{"Ref": "Subnet"}]}},
            "Cluster": {"Type": "AWS::ElastiCache::CacheCluster",
                        "Properties": {"ClusterName": "cfn-it-cluster", "Engine": "redis",
                                       "CacheNodeType": "cache.t3.micro", "NumCacheNodes": 1,
                                       "CacheSubnetGroupName": {"Ref": "SubnetGroup"}}}
          },
          "Outputs": {
            "ClusterRef": {"Value": {"Ref": "Cluster"}},
            "ClusterAddress": {"Value": {"Fn::GetAtt": ["Cluster", "RedisEndpoint.Address"]}},
            "ClusterPort": {"Value": {"Fn::GetAtt": ["Cluster", "RedisEndpoint.Port"]}}
          }
        }
        """;

    private static final String UPDATE_TEMPLATE = """
        {
          "Resources": {
            "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.73.0.0/16"}},
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.73.0.0/24"}},
            "SubnetGroup": {"Type": "AWS::ElastiCache::SubnetGroup",
                            "Properties": {"CacheSubnetGroupName": "@@SNG@@", "Description": "example",
                                           "SubnetIds": [{"Ref": "Subnet"}]}}
          },
          "Outputs": {
            "SubnetGroupRef": {"Value": {"Ref": "SubnetGroup"}}
          }
        }
        """;

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private ElastiCacheClient client() {
        return ElastiCacheClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void subnetGroupIsCreatedAndRemovedWithTheStack() {
        cloudFormation(METADATA_STACK, "CreateStack", METADATA_TEMPLATE);
        String stacks = describeStacks(METADATA_STACK, "CREATE_COMPLETE");

        assertEquals("cfn-it-sng", outputValue(stacks, "SubnetGroupRef"));

        try (ElastiCacheClient client = client()) {
            assertEquals(1, client.describeCacheSubnetGroups(r -> r.cacheSubnetGroupName("cfn-it-sng"))
                    .cacheSubnetGroups().size());

            cloudFormation(METADATA_STACK, "DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(METADATA_STACK);

            assertThrows(CacheSubnetGroupNotFoundException.class,
                    () -> client.describeCacheSubnetGroups(r -> r.cacheSubnetGroupName("cfn-it-sng")));
        }
    }

    @Test
    void renamedSubnetGroupIsReplaced() {
        String stack = "elasticache-cfn-update-it";
        cloudFormation(stack, "CreateStack", UPDATE_TEMPLATE.replace("@@SNG@@", "cfn-it-upd-sng-a"));
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", UPDATE_TEMPLATE.replace("@@SNG@@", "cfn-it-upd-sng-b"));
        String stacks = describeStacks(stack, "UPDATE_COMPLETE");

        assertEquals("cfn-it-upd-sng-b", outputValue(stacks, "SubnetGroupRef"));
        try (ElastiCacheClient client = client()) {
            assertThrows(CacheSubnetGroupNotFoundException.class,
                    () -> client.describeCacheSubnetGroups(r -> r.cacheSubnetGroupName("cfn-it-upd-sng-a")));
            assertEquals(1, client.describeCacheSubnetGroups(r -> r.cacheSubnetGroupName("cfn-it-upd-sng-b"))
                    .cacheSubnetGroups().size());
        }

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void cacheClusterExposesEndpoints() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for the cache data plane");
        cloudFormation(DATA_STACK, "CreateStack", DATA_TEMPLATE);
        String stacks = describeStacks(DATA_STACK, "CREATE_COMPLETE");

        assertEquals("cfn-it-cluster", outputValue(stacks, "ClusterRef"));
        assertFalse(outputValue(stacks, "ClusterAddress").contains("Cluster.RedisEndpoint"),
                "an unset attribute resolves to the literal LogicalId.Attr");

        try (ElastiCacheClient client = client()) {
            CacheCluster cluster = client.describeCacheClusters(r -> r.cacheClusterId("cfn-it-cluster").showCacheNodeInfo(true))
                    .cacheClusters().getFirst();
            assertEquals(outputValue(stacks, "ClusterPort"),
                    String.valueOf(cluster.cacheNodes().getFirst().endpoint().port()));
        }

        cloudFormation(DATA_STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(DATA_STACK);
    }

    private static boolean dockerAvailable() throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("docker", "info").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException expected) {
            // Docker CLI is optional on development machines.
            return false;
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
