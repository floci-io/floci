package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DeleteParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("CloudFormation RDS credential dynamic references")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFormationRdsDynamicReferenceTest {

    private static final String USERNAME = "viewer";
    private static final String PASSWORD = "compat-secret-123";
    private static final String DATABASE = "app";
    private static final List<String> STACK_NAMES = new ArrayList<>();

    private static CloudFormationClient cloudFormation;
    private static RdsClient rds;
    private static SsmClient ssm;
    private static String secureParameterName;
    private static String plainParameterName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        rds = TestFixtures.rdsClient();
        ssm = TestFixtures.ssmClient();

        String suffix = TestFixtures.uniqueName("cfn-rds-ref");
        secureParameterName = "/" + suffix + "/secure";
        plainParameterName = "/" + suffix + "/plain";

        ssm.putParameter(PutParameterRequest.builder()
                .name(secureParameterName)
                .type(ParameterType.SECURE_STRING)
                .value(PASSWORD)
                .build());
        ssm.putParameter(PutParameterRequest.builder()
                .name(plainParameterName)
                .type(ParameterType.STRING)
                .value(PASSWORD)
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            for (String stackName : STACK_NAMES) {
                try {
                    cloudFormation.deleteStack(DeleteStackRequest.builder()
                            .stackName(stackName)
                            .build());
                } catch (Exception e) {
                    System.err.printf("Unable to delete compatibility stack %s: %s%n",
                            stackName, e.getMessage());
                }
            }
            cloudFormation.close();
        }
        if (ssm != null) {
            deleteParameter(secureParameterName);
            deleteParameter(plainParameterName);
            ssm.close();
        }
        if (rds != null) {
            rds.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("ssm-secure resolves into an RDS master password")
    void resolvesSecureSsmMasterPassword() throws Exception {
        assumeTrue(!Boolean.parseBoolean(System.getenv()
                        .getOrDefault("FLOCI_SERVICES_RDS_MOCK", "false")),
                "Container-backed RDS is required for the credential compatibility test");
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Docker dispatch is unavailable for the RDS container compatibility test");

        String stackName = TestFixtures.uniqueName("cfn-rds-secure");
        String instanceId = TestFixtures.uniqueName("cfn-rds-secure");
        STACK_NAMES.add(stackName);

        String template = """
                {
                  "Resources": {
                    "Database": {
                      "Type": "AWS::RDS::DBInstance",
                      "Properties": {
                        "DBInstanceIdentifier": "%s",
                        "DBInstanceClass": "db.t3.micro",
                        "AllocatedStorage": 20,
                        "Engine": "postgres",
                        "DBName": "%s",
                        "MasterUsername": "%s",
                        "MasterUserPassword": "{{resolve:ssm-secure:%s}}"
                      }
                    }
                  }
                }
                """.formatted(instanceId, DATABASE, USERNAME, secureParameterName);

        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template)
                .build());

        assertThat(waitForTerminal(stackName, Duration.ofSeconds(90)))
                .isEqualTo(StackStatus.CREATE_COMPLETE);

        int port = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                        .dbInstanceIdentifier(instanceId)
                        .build())
                .dbInstances()
                .get(0)
                .endpoint()
                .port();
        try (Connection connection = awaitPostgresConnection(port)) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    @Test
    @Order(2)
    @DisplayName("ssm-secure is rejected for RDS MasterUsername")
    void rejectsSecureSsmMasterUsername() throws InterruptedException {
        String stackName = TestFixtures.uniqueName("cfn-rds-secure-user");
        String instanceId = TestFixtures.uniqueName("cfn-rds-secure-user");
        STACK_NAMES.add(stackName);

        String template = """
                {
                  "Resources": {
                    "Database": {
                      "Type": "AWS::RDS::DBInstance",
                      "Properties": {
                        "DBInstanceIdentifier": "%s",
                        "DBInstanceClass": "db.t3.micro",
                        "AllocatedStorage": 20,
                        "Engine": "postgres",
                        "MasterUsername": "{{resolve:ssm-secure:%s}}",
                        "MasterUserPassword": "%s"
                      }
                    }
                  }
                }
                """.formatted(instanceId, secureParameterName, PASSWORD);

        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template)
                .build());

        assertThat(waitForTerminal(stackName, Duration.ofSeconds(30)))
                .isEqualTo(StackStatus.ROLLBACK_COMPLETE);
    }

    @Test
    @Order(3)
    @DisplayName("ssm-secure requires a SecureString parameter")
    void rejectsPlaintextParameterForSecureReference() throws InterruptedException {
        String stackName = TestFixtures.uniqueName("cfn-rds-wrong-type");
        String instanceId = TestFixtures.uniqueName("cfn-rds-wrong-type");
        STACK_NAMES.add(stackName);

        String template = """
                {
                  "Resources": {
                    "Database": {
                      "Type": "AWS::RDS::DBInstance",
                      "Properties": {
                        "DBInstanceIdentifier": "%s",
                        "DBInstanceClass": "db.t3.micro",
                        "AllocatedStorage": 20,
                        "Engine": "postgres",
                        "MasterUsername": "%s",
                        "MasterUserPassword": "{{resolve:ssm-secure:%s}}"
                      }
                    }
                  }
                }
                """.formatted(instanceId, USERNAME, plainParameterName);

        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template)
                .build());

        assertThat(waitForTerminal(stackName, Duration.ofSeconds(30)))
                .isEqualTo(StackStatus.ROLLBACK_COMPLETE);
    }

    private static StackStatus waitForTerminal(String stackName, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Stack> stacks = cloudFormation.describeStacks(DescribeStacksRequest.builder()
                            .stackName(stackName)
                            .build())
                    .stacks();
            if (!stacks.isEmpty() && !stacks.get(0).stackStatusAsString().endsWith("_IN_PROGRESS")) {
                return stacks.get(0).stackStatus();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Stack " + stackName + " did not reach a terminal state within " + timeout);
    }

    private static Connection awaitPostgresConnection(int port) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Properties properties = new Properties();
                properties.setProperty("user", USERNAME);
                properties.setProperty("password", PASSWORD);
                properties.setProperty("sslmode", "disable");
                properties.setProperty("connectTimeout", "5");
                return DriverManager.getConnection(
                        "jdbc:postgresql://" + TestFixtures.proxyHost() + ":" + port + "/" + DATABASE,
                        properties);
            } catch (SQLException e) {
                lastFailure = e;
                Thread.sleep(1000);
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new SQLException("Timed out waiting for the RDS proxy");
    }

    private static void deleteParameter(String parameterName) {
        if (parameterName == null) {
            return;
        }
        try {
            ssm.deleteParameter(DeleteParameterRequest.builder()
                    .name(parameterName)
                    .build());
        } catch (Exception e) {
            System.err.printf("Unable to delete compatibility parameter %s: %s%n",
                    parameterName, e.getMessage());
        }
    }
}
