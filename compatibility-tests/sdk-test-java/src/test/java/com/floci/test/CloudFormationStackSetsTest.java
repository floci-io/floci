package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies CloudFormation StackSets through the real AWS SDK: a stack set created in the
 * administration account provisions instances into target accounts, the management responses
 * unmarshal cleanly, and the provisioned resources land in each target account's namespace.
 */
@DisplayName("CloudFormation StackSets cross-account provisioning")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFormationStackSetsTest {

    private static final String ACCOUNT_B = "222222222222";
    private static final String ACCOUNT_C = "333333333333";
    private static final String REGION = "us-east-1";

    private static CloudFormationClient cfn;
    private static String stackSetName;
    private static String queueName;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        stackSetName = TestFixtures.uniqueName("compat-stackset");
        queueName = TestFixtures.uniqueName("compat-ss-queue");
    }

    @AfterAll
    static void teardown() {
        try {
            cfn.deleteStackInstances(r -> r.stackSetName(stackSetName)
                    .accounts(ACCOUNT_B, ACCOUNT_C).regions(REGION).retainStacks(false));
        } catch (Exception e) {
            System.err.println("teardown: deleteStackInstances for " + stackSetName + " failed: " + e.getMessage());
        }
        try {
            cfn.deleteStackSet(r -> r.stackSetName(stackSetName));
        } catch (Exception e) {
            System.err.println("teardown: deleteStackSet for " + stackSetName + " failed: " + e.getMessage());
        }
        cfn.close();
    }

    private static SqsClient sqsFor(String account) {
        return SqsClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static String queueTemplate(String queue) {
        return "{\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\","
                + "\"Properties\":{\"QueueName\":\"" + queue + "\"}}}}";
    }

    @Test
    @Order(1)
    void createStackSet() {
        CreateStackSetResponse resp = cfn.createStackSet(r -> r
                .stackSetName(stackSetName)
                .templateBody(queueTemplate(queueName)));
        assertThat(resp.stackSetId()).isNotBlank();
    }

    @Test
    @Order(2)
    void describeStackSetReturnsDefinition() {
        DescribeStackSetResponse resp = cfn.describeStackSet(r -> r.stackSetName(stackSetName));
        StackSet ss = resp.stackSet();
        assertThat(ss.stackSetName()).isEqualTo(stackSetName);
        assertThat(ss.status()).isEqualTo(StackSetStatus.ACTIVE);
        assertThat(ss.templateBody()).contains(queueName);
    }

    @Test
    @Order(3)
    void createStackInstancesProvisionsIntoTargetAccounts() {
        CreateStackInstancesResponse resp = cfn.createStackInstances(r -> r
                .stackSetName(stackSetName)
                .accounts(ACCOUNT_B, ACCOUNT_C)
                .regions(REGION));
        assertThat(resp.operationId()).isNotBlank();

        // The queue materializes in each target account's namespace...
        try (SqsClient b = sqsFor(ACCOUNT_B); SqsClient c = sqsFor(ACCOUNT_C)) {
            assertThat(b.getQueueUrl(g -> g.queueName(queueName)).queueUrl())
                    .contains("/" + ACCOUNT_B + "/" + queueName);
            assertThat(c.getQueueUrl(g -> g.queueName(queueName)).queueUrl())
                    .contains("/" + ACCOUNT_C + "/" + queueName);
        }
        // ...and not in the administration account.
        try (SqsClient admin = TestFixtures.sqsClient()) {
            assertThatThrownBy(() -> admin.getQueueUrl(g -> g.queueName(queueName)))
                    .isInstanceOf(QueueDoesNotExistException.class);
        }
    }

    @Test
    @Order(4)
    void listStackInstancesReportsTargets() {
        List<StackInstanceSummary> summaries = cfn.listStackInstances(r -> r.stackSetName(stackSetName))
                .summaries();
        assertThat(summaries).hasSize(2);
        assertThat(summaries).extracting(StackInstanceSummary::account)
                .containsExactlyInAnyOrder(ACCOUNT_B, ACCOUNT_C);
        assertThat(summaries).allSatisfy(s -> assertThat(s.region()).isEqualTo(REGION));

        // DescribeStackInstance (singular) — the operation DPS's state machine actually calls.
        StackInstance inst = cfn.describeStackInstance(r -> r.stackSetName(stackSetName)
                .stackInstanceAccount(ACCOUNT_B).stackInstanceRegion(REGION)).stackInstance();
        assertThat(inst.account()).isEqualTo(ACCOUNT_B);
        assertThat(inst.region()).isEqualTo(REGION);
        assertThat(inst.stackId()).isNotBlank();
    }

    @Test
    @Order(5)
    void listStackSetOperationsRecordsCreate() {
        List<StackSetOperationSummary> ops = cfn.listStackSetOperations(r -> r.stackSetName(stackSetName))
                .summaries();
        assertThat(ops).isNotEmpty();
        assertThat(ops).extracting(StackSetOperationSummary::action)
                .contains(StackSetOperationAction.CREATE);

        String opId = ops.stream().filter(o -> o.action() == StackSetOperationAction.CREATE)
                .findFirst().orElseThrow().operationId();
        StackSetOperation op = cfn.describeStackSetOperation(r -> r.stackSetName(stackSetName)
                .operationId(opId)).stackSetOperation();
        assertThat(op.operationId()).isEqualTo(opId);
        assertThat(op.action()).isEqualTo(StackSetOperationAction.CREATE);
        assertThat(op.status()).isEqualTo(StackSetOperationStatus.SUCCEEDED);
    }

    @Test
    @Order(6)
    void deleteStackInstancesRemovesResources() {
        cfn.deleteStackInstances(r -> r.stackSetName(stackSetName)
                .accounts(ACCOUNT_B, ACCOUNT_C).regions(REGION).retainStacks(false));

        assertThat(cfn.listStackInstances(r -> r.stackSetName(stackSetName)).summaries()).isEmpty();

        try (SqsClient b = sqsFor(ACCOUNT_B)) {
            assertThatThrownBy(() -> b.getQueueUrl(g -> g.queueName(queueName)))
                    .isInstanceOf(QueueDoesNotExistException.class);
        }
    }

    @Test
    @Order(7)
    void deleteStackSetSucceedsWhenEmpty() {
        cfn.deleteStackSet(r -> r.stackSetName(stackSetName));
        assertThatThrownBy(() -> cfn.describeStackSet(r -> r.stackSetName(stackSetName)))
                .isInstanceOf(StackSetNotFoundException.class);
    }
}
