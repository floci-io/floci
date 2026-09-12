package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test provisioning AWS::Redshift::Cluster,
 * AWS::Redshift::ClusterParameterGroup, AWS::Redshift::ClusterSubnetGroup,
 * and AWS::Redshift::ClusterSecurityGroup through CloudFormation stacks.
 */
@QuarkusTest
class RedshiftClusterCfnIntegrationTest {

    private static final Logger LOG = Logger.getLogger(RedshiftClusterCfnIntegrationTest.class);

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String REDSHIFT_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/redshift/aws4_request";

    private final List<String> stacksToCleanup = new ArrayList<>();

    @BeforeAll
    static void configure() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for this integration test");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void cleanUp() {
        for (String stack : stacksToCleanup) {
            try {
                cloudFormation(stack, "DeleteStack", null);
                awaitStackDeleted(stack);
            } catch (Exception e) {
                LOG.warnv("Failed to clean up stack {0}: {1}", stack, e.getMessage());
            }
        }
        stacksToCleanup.clear();
    }

    @Test
    void createStackProvisionsRealClusterWithEndpointOutputs() throws Exception {
        String stackName = "rs-cfn-it-" + System.currentTimeMillis();
        String clusterId = stackName + "-wh";
        stacksToCleanup.add(stackName);

        String template = """
            {
              "Resources": {
                "Warehouse": {
                  "Type": "AWS::Redshift::Cluster",
                  "Properties": {
                    "ClusterIdentifier": "%s",
                    "NodeType": "ra3.large",
                    "MasterUsername": "admin",
                    "MasterUserPassword": "Secret12345",
                    "ClusterType": "single-node",
                    "DBName": "dev"
                  }
                }
              },
              "Outputs": {
                "Addr": {"Value": {"Fn::GetAtt": ["Warehouse", "Endpoint.Address"]}},
                "Port": {"Value": {"Fn::GetAtt": ["Warehouse", "Endpoint.Port"]}},
                "Ns":   {"Value": {"Fn::GetAtt": ["Warehouse", "ClusterNamespaceArn"]}}
              }
            }""".formatted(clusterId);

        cloudFormation(stackName, "CreateStack", template);

        String stacks = describeStacks(stackName, "CREATE_COMPLETE");
        String addr = outputValue(stacks, "Addr");
        String portStr = outputValue(stacks, "Port");
        String ns = outputValue(stacks, "Ns");

        assertNotNull(addr, "Endpoint.Address output must not be null");
        assertFalse(addr.isBlank(), "Endpoint.Address output must not be blank");
        assertNotNull(portStr, "Endpoint.Port output must not be null");
        int port = Integer.parseInt(portStr);
        assertTrue(port > 0, "Endpoint.Port must be a positive integer: " + port);
        assertNotNull(ns, "ClusterNamespaceArn output must not be null");
        assertTrue(ns.startsWith("arn:aws:redshift:"),
                "ClusterNamespaceArn output must start with arn:aws:redshift:, got: " + ns);

        // Fall back to 127.0.0.1 when hostname is host.docker.internal to avoid CI resolution issues
        String connectHost = "host.docker.internal".equalsIgnoreCase(addr) ? "127.0.0.1" : addr;
        try (Connection conn = waitForConnection(connectHost, port, "admin", "Secret12345");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }

        cloudFormation(stackName, "DeleteStack", null);
        awaitStackDeleted(stackName);
        stacksToCleanup.remove(stackName);

        assertClusterNotFound(clusterId);
    }

    @Test
    void createStackProvisionsParameterAndSubnetGroups() throws Exception {
        String stackName = "rs-groups-it-" + System.currentTimeMillis();
        String pgName = stackName + "-pg";
        String sgName = stackName + "-sg";
        stacksToCleanup.add(stackName);

        String template = """
            {
              "Resources": {
                "ParamGroup": {
                  "Type": "AWS::Redshift::ClusterParameterGroup",
                  "Properties": {
                    "ParameterGroupName": "%s",
                    "ParameterGroupFamily": "redshift-1.0",
                    "Description": "Test parameter group for CFN integration"
                  }
                },
                "SubnetGroup": {
                  "Type": "AWS::Redshift::ClusterSubnetGroup",
                  "Properties": {
                    "ClusterSubnetGroupName": "%s",
                    "Description": "Test subnet group for CFN integration",
                    "SubnetIds": ["subnet-12345678", "subnet-87654321"]
                  }
                },
                "SecurityGroup": {
                  "Type": "AWS::Redshift::ClusterSecurityGroup",
                  "Properties": {
                    "Description": "Test security group for CFN integration"
                  }
                }
              },
              "Outputs": {
                "PgRef": {"Value": {"Ref": "ParamGroup"}},
                "SgRef": {"Value": {"Ref": "SubnetGroup"}},
                "SgName": {"Value": {"Fn::GetAtt": ["SubnetGroup", "ClusterSubnetGroupName"]}},
                "SecurityGroupRef": {"Value": {"Ref": "SecurityGroup"}},
                "SecurityGroupId": {"Value": {"Fn::GetAtt": ["SecurityGroup", "Id"]}}
              }
            }""".formatted(pgName, sgName);

        cloudFormation(stackName, "CreateStack", template);

        String stacks = describeStacks(stackName, "CREATE_COMPLETE");
        assertEquals(pgName, outputValue(stacks, "PgRef"));
        assertEquals(sgName, outputValue(stacks, "SgRef"));
        assertEquals(sgName, outputValue(stacks, "SgName"));
        assertEquals(outputValue(stacks, "SecurityGroupRef"), outputValue(stacks, "SecurityGroupId"));

        assertParameterGroupExists(pgName, "redshift-1.0");
        assertSubnetGroupExists(sgName);

        cloudFormation(stackName, "DeleteStack", null);
        awaitStackDeleted(stackName);
        stacksToCleanup.remove(stackName);

        assertParameterGroupNotFound(pgName);
        assertSubnetGroupNotFound(sgName);
    }

    private static Connection waitForConnection(String host, int port, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/dev", username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/dev", username, password);
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
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("Stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("Stack " + stack + " was not deleted within the timeout");
    }

    private static void assertClusterNotFound(String clusterId) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", REDSHIFT_AUTH)
            .formParam("Action", "DescribeClusters")
            .formParam("ClusterIdentifier", clusterId)
        .when().post("/").then()
            .statusCode(404)
            .body(containsString("<Code>ClusterNotFound</Code>"));
    }

    private static void assertParameterGroupExists(String pgName, String expectedFamily) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", REDSHIFT_AUTH)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", pgName)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ParameterGroupName>" + pgName + "</ParameterGroupName>"))
            .body(containsString("<ParameterGroupFamily>" + expectedFamily + "</ParameterGroupFamily>"));
    }

    private static void assertParameterGroupNotFound(String pgName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", REDSHIFT_AUTH)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", pgName)
        .when().post("/").then()
            .statusCode(404)
            .body(containsString("<Code>ClusterParameterGroupNotFound</Code>"));
    }

    private static void assertSubnetGroupExists(String sgName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", REDSHIFT_AUTH)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", sgName)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ClusterSubnetGroupName>" + sgName + "</ClusterSubnetGroupName>"));
    }

    private static void assertSubnetGroupNotFound(String sgName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", REDSHIFT_AUTH)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", sgName)
        .when().post("/").then()
            .statusCode(404)
            .body(containsString("<Code>ClusterSubnetGroupNotFound</Code>"));
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
