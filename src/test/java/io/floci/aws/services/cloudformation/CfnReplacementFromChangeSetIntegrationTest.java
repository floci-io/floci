package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An update replaces a resource exactly when its change set said {@code Replacement: True}, also
 * when the create-only property is unchanged in the template and differs only because it reads a
 * resource replaced in the same update.
 */
@QuarkusTest
class CfnReplacementFromChangeSetIntegrationTest {

    private static final String TEMPLATE = """
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

    @TestHTTPResource
    URI endpoint;

    @Test
    void aClusterReadingAReplacedSubnetGroupIsReplacedAsThePreviewSaid() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for the cache data plane");
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "repl-sng-" + suffix;
        String first = "sng-a-" + suffix;
        String second = "sng-b-" + suffix;
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             ElastiCacheClient cache = ElastiCacheClient.builder().endpointOverride(endpoint)
                     .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create("test", "test"))).build()) {
            client.createStack(request -> request.stackName(stack).templateBody(TEMPLATE)
                    .parameters(Parameter.builder().parameterKey("GroupName").parameterValue(first).build()));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Cluster");

            client.createChangeSet(request -> request.stackName(stack).changeSetName("rename")
                    .changeSetType(ChangeSetType.UPDATE).templateBody(TEMPLATE)
                    .parameters(Parameter.builder().parameterKey("GroupName").parameterValue(second).build()));
            List<String> preview = client.describeChangeSet(request -> request.stackName(stack)
                    .changeSetName("rename")).changes().stream()
                    .filter(change -> "Cluster".equals(change.resourceChange().logicalResourceId()))
                    .map(change -> change.resourceChange().replacementAsString()).toList();
            assertEquals(List.of("True"), preview);

            client.executeChangeSet(request -> request.stackName(stack).changeSetName("rename"));
            CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");

            String after = physicalId(client, stack, "Cluster");
            assertNotEquals(before, after, "the cluster is replaced, as the preview said");
            assertEquals(second, cache.describeCacheClusters(request -> request.cacheClusterId(after))
                    .cacheClusters().get(0).cacheSubnetGroupName());
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String physicalId(CloudFormationClient client, String stack, String logicalId) {
        return client.describeStackResource(request -> request.stackName(stack).logicalResourceId(logicalId))
                .stackResourceDetail().physicalResourceId();
    }
}
