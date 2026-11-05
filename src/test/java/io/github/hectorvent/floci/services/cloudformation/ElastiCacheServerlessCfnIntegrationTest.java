package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.ServerlessCache;
import software.amazon.awssdk.services.elasticache.model.ServerlessCacheNotFoundException;
import software.amazon.awssdk.services.elasticache.model.UserGroupNotFoundException;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions AWS::ElastiCache::ServerlessCache through CloudFormation. Output values are asserted,
 * not only the stack status: an unmapped type is stubbed as CREATE_COMPLETE, and its Fn::GetAtt
 * resolves to the literal "LogicalId.Attr".
 *
 * <p>The cache containers are namespaced by {@link SidecarContainersProfile}, which this class
 * shares with the other classes whose managers start real containers. The profile it used before
 * also mocked RDS and DocumentDB; the stack creates neither, and ElastiCache does not reach them,
 * so the shared profile leaves both at the test default.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class ElastiCacheServerlessCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "elasticache-cfn-serverless-it";

    private static final String TEMPLATE = """
        {
          "Resources": {
            "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.74.0.0/16"}},
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.74.0.0/24"}},
            "DefaultUser": {"Type": "AWS::ElastiCache::User",
                            "Properties": {"UserId": "cfn-it-sl-default", "UserName": "default",
                                           "Engine": "redis", "AccessString": "off -@all",
                                           "NoPasswordRequired": true}},
            "UserGroup": {"Type": "AWS::ElastiCache::UserGroup",
                          "Properties": {"UserGroupId": "cfn-it-sl-users", "Engine": "redis",
                                         "UserIds": [{"Ref": "DefaultUser"}]}},
            "Serverless": {"Type": "AWS::ElastiCache::ServerlessCache",
                           "Properties": {"ServerlessCacheName": "cfn-it-serverless", "Engine": "valkey",
                                          "SubnetIds": [{"Ref": "Subnet"}],
                                          "UserGroupId": {"Ref": "UserGroup"}}}
          },
          "Outputs": {
            "ServerlessRef": {"Value": {"Ref": "Serverless"}},
            "ServerlessArn": {"Value": {"Fn::GetAtt": ["Serverless", "ARN"]}},
            "ServerlessAddress": {"Value": {"Fn::GetAtt": ["Serverless", "Endpoint.Address"]}},
            "ServerlessPort": {"Value": {"Fn::GetAtt": ["Serverless", "Endpoint.Port"]}}
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
    void serverlessCacheExposesEndpointsAndIsRemovedWithTheStack() throws Exception {
        SidecarContainersProfile.requireDocker();
        cloudFormation(STACK, "CreateStack", TEMPLATE);
        String stacks = describeStacks(STACK, "CREATE_COMPLETE");

        assertEquals("cfn-it-serverless", outputValue(stacks, "ServerlessRef"));
        assertFalse(outputValue(stacks, "ServerlessAddress").contains("Serverless.Endpoint"),
                "an unset attribute resolves to the literal LogicalId.Attr");
        assertTrue(outputValue(stacks, "ServerlessArn").endsWith(":serverlesscache:cfn-it-serverless"));

        try (ElastiCacheClient client = client()) {
            ServerlessCache serverless = client.describeServerlessCaches(r -> r.serverlessCacheName("cfn-it-serverless"))
                    .serverlessCaches().getFirst();
            assertNotNull(serverless.endpoint());
            assertEquals(outputValue(stacks, "ServerlessPort"), String.valueOf(serverless.endpoint().port()));
            assertEquals("cfn-it-sl-users", serverless.userGroupId());
        }

        cloudFormation(STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
        try (ElastiCacheClient client = client()) {
            assertThrows(ServerlessCacheNotFoundException.class,
                    () -> client.describeServerlessCaches(r -> r.serverlessCacheName("cfn-it-serverless")));
            assertThrows(UserGroupNotFoundException.class,
                    () -> client.describeUserGroups(r -> r.userGroupId("cfn-it-sl-users")));
        }
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
        CfnStackWaits.awaitStatus(stack, expectedStatus, CFN_AUTH);
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
