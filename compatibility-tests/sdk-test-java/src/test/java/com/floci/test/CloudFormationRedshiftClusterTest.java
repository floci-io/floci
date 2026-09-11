package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end compatibility test provisioning an AWS::Redshift::Cluster
 * via CloudFormationClient and validating connectivity over JDBC.
 */
@DisplayName("CloudFormation AWS::Redshift::Cluster")
class CloudFormationRedshiftClusterTest {

    private static CloudFormationClient cloudFormation;
    private static String stackName;
    private static String clusterId;

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Docker dispatch is unavailable for the Redshift container compatibility test");
        cloudFormation = TestFixtures.cloudFormationClient();
        stackName = TestFixtures.uniqueName("compat-cfn-rs");
        clusterId = stackName + "-wh";
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null && stackName != null) {
            try {
                cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
                waitForDeleted(stackName, 60);
            } catch (Exception e) {
                System.err.println("CloudFormation Redshift cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
    }

    @Test
    void provisionsRedshiftClusterAndConnectsViaJdbc() throws Exception {
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

        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template)
                .build());

        WaiterResponse<DescribeStacksResponse> waiterResponse = cloudFormation.waiter()
                .waitUntilStackCreateComplete(r -> r.stackName(stackName));

        Stack stack = waiterResponse.matched().response()
                .orElseGet(() -> cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(stackName).build()))
                .stacks().get(0);

        Map<String, String> outputs = stack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));

        String addr = outputs.get("Addr");
        String portStr = outputs.get("Port");
        String ns = outputs.get("Ns");

        assertThat(addr).isNotBlank();
        assertThat(portStr).isNotBlank();
        int port = Integer.parseInt(portStr);
        assertThat(port).isPositive();
        assertThat(ns).startsWith("arn:aws:redshift:");

        String connectHost = "host.docker.internal".equalsIgnoreCase(addr)
                ? TestFixtures.proxyHost()
                : addr;

        try (Connection conn = awaitPostgresConnection(connectHost, port, "admin", "Secret12345");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    private static Connection awaitPostgresConnection(String host, int port, String username, String password) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Properties properties = new Properties();
                properties.setProperty("user", username);
                properties.setProperty("password", password);
                properties.setProperty("sslmode", "disable");
                properties.setProperty("connectTimeout", "5");
                return DriverManager.getConnection(
                        "jdbc:postgresql://" + host + ":" + port + "/dev",
                        properties);
            } catch (SQLException e) {
                lastFailure = e;
                Thread.sleep(1000);
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new SQLException("Timed out waiting for Redshift connection at " + host + ":" + port);
    }

    private static void waitForDeleted(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(name).build()).stacks();
                if (stacks.isEmpty()
                        || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
                    return;
                }
            } catch (CloudFormationException e) {
                if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                    return;
                }
                throw e;
            }
            Thread.sleep(500);
        }
    }
}
