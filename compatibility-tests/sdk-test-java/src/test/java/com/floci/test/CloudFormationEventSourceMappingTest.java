package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetEventSourceMappingResponse;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies that CloudFormation AWS::Lambda::EventSourceMapping provisions and
 * deletes an ESM backed by an SQS queue, matching the use-case from issue #593,
 * and that batching, filtering, scaling, retry and destination options set in the
 * template reach the mapping and are cleared when removed on update (issue #4313).
 */
@DisplayName("CloudFormation AWS::Lambda::EventSourceMapping")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFormationEventSourceMappingTest {

    private static final String STACK_NAME = "compat-cfn-esm-stack";
    private static final String FUNC_NAME  = "compat-cfn-esm-func";
    private static final String QUEUE_NAME = "compat-cfn-esm-queue";
    private static final String ROLE       = "arn:aws:iam::000000000000:role/cfn-lambda-role";
    private static final String ACCOUNT    = "000000000000";
    private static final String REGION     = "us-east-1";

    private static final String DDB_STACK_NAME = "compat-cfn-esm-ddb-stack";
    private static final String DDB_FUNC_NAME  = "compat-cfn-esm-ddb-func";
    private static final String DDB_TABLE_NAME = "compat-cfn-esm-ddb-table";
    private static final String DLQ_NAME       = "compat-cfn-esm-ddb-dlq";
    private static final String FILTER_PATTERN = "{\"body\":{\"kind\":[\"order\"]}}";

    private static CloudFormationClient cfn;
    private static LambdaClient lambda;
    private static SqsClient sqs;

    private static String esmUuid;
    private static String ddbEsmUuid;

    @BeforeAll
    static void setup() {
        cfn    = TestFixtures.cloudFormationClient();
        lambda = TestFixtures.lambdaClient();
        sqs    = TestFixtures.sqsClient();
    }

    @AfterAll
    static void cleanup() {
        try {
            if (cfn != null) {
                cfn.deleteStack(DeleteStackRequest.builder().stackName(STACK_NAME).build());
                cfn.deleteStack(DeleteStackRequest.builder().stackName(DDB_STACK_NAME).build());
            }
        } catch (Exception ignored) {}
        if (cfn    != null) cfn.close();
        if (lambda != null) lambda.close();
        if (sqs    != null) sqs.close();
    }

    @Test
    @Order(1)
    @DisplayName("CreateStack with Lambda + SQS + EventSourceMapping reaches CREATE_COMPLETE")
    void createStack_withEventSourceMapping() throws InterruptedException {
        String template = sqsStackTemplate("""
                    "MaximumBatchingWindowInSeconds": 5,
                    "FilterCriteria": { "Filters": [ { "Pattern": "{\\"body\\":{\\"kind\\":[\\"order\\"]}}" } ] },
                    "ScalingConfig": { "MaximumConcurrency": 5 }
                """);

        cfn.createStack(CreateStackRequest.builder()
                .stackName(STACK_NAME)
                .templateBody(template)
                .build());

        String status = waitForTerminal(STACK_NAME, 30);
        assertThat(status).isEqualTo("CREATE_COMPLETE");
    }

    @Test
    @Order(2)
    @DisplayName("ESM resource appears in DescribeStackResources with CREATE_COMPLETE")
    void esmResourceIsComplete() {
        List<StackResource> resources = cfn.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(STACK_NAME).build()
        ).stackResources();

        StackResource esmResource = resources.stream()
                .filter(r -> "AWS::Lambda::EventSourceMapping".equals(r.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No EventSourceMapping resource found"));

        assertThat(esmResource.resourceStatusAsString()).isEqualTo("CREATE_COMPLETE");
        assertThat(esmResource.physicalResourceId()).isNotBlank();

        esmUuid = esmResource.physicalResourceId();
    }

    @Test
    @Order(3)
    @DisplayName("ListEventSourceMappings returns ESM linked to the Lambda function")
    void esmAppearsInLambdaList() {
        ListEventSourceMappingsResponse resp = lambda.listEventSourceMappings(
                r -> r.functionName(FUNC_NAME));

        assertThat(resp.eventSourceMappings()).isNotEmpty();

        boolean found = resp.eventSourceMappings().stream()
                .anyMatch(e -> e.functionArn().contains(FUNC_NAME)
                        && e.eventSourceArn().contains(QUEUE_NAME));
        assertThat(found).as("ESM for queue %s not found in listing", QUEUE_NAME).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("GetEventSourceMapping by UUID matches the created ESM")
    void getEventSourceMappingByUuid() {
        assertThat(esmUuid).as("ESM UUID must have been captured in earlier test").isNotNull();

        GetEventSourceMappingResponse esm = lambda.getEventSourceMapping(r -> r.uuid(esmUuid));
        assertThat(esm.uuid()).isEqualTo(esmUuid);
        assertThat(esm.functionArn()).contains(FUNC_NAME);
        assertThat(esm.eventSourceArn()).contains(QUEUE_NAME);
        assertThat(esm.batchSize()).isEqualTo(5);
        assertThat(esm.state()).isIn("Enabled", "Enabling");
        assertThat(esm.maximumBatchingWindowInSeconds()).isEqualTo(5);
        assertThat(esm.filterCriteria()).isNotNull();
        assertThat(esm.filterCriteria().filters()).singleElement()
                .satisfies(f -> assertThat(f.pattern()).isEqualTo(FILTER_PATTERN));
        assertThat(esm.scalingConfig()).isNotNull();
        assertThat(esm.scalingConfig().maximumConcurrency()).isEqualTo(5);
    }

    @Test
    @Order(5)
    @DisplayName("UpdateStack changes the batching window and clears removed FilterCriteria and ScalingConfig")
    void updateStack_changesWindowAndClearsRemovedOptions() throws InterruptedException {
        assertThat(esmUuid).as("ESM UUID must have been captured in earlier test").isNotNull();

        cfn.updateStack(UpdateStackRequest.builder()
                .stackName(STACK_NAME)
                .templateBody(sqsStackTemplate("""
                    "MaximumBatchingWindowInSeconds": 20
                """))
                .build());
        assertThat(waitForTerminal(STACK_NAME, 30)).isEqualTo("UPDATE_COMPLETE");

        GetEventSourceMappingResponse esm = lambda.getEventSourceMapping(r -> r.uuid(esmUuid));
        assertThat(esm.uuid()).isEqualTo(esmUuid);
        assertThat(esm.maximumBatchingWindowInSeconds()).isEqualTo(20);
        assertThat(esm.filterCriteria()).isNull();
        assertThat(esm.scalingConfig() == null || esm.scalingConfig().maximumConcurrency() == null).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("DynamoDB stream ESM carries retry, record age, bisect and DestinationConfig from the template")
    void createDynamoDbStreamStack_withRetryAndDestinationOptions() throws InterruptedException {
        cfn.createStack(CreateStackRequest.builder()
                .stackName(DDB_STACK_NAME)
                .templateBody(ddbStackTemplate("""
                    "MaximumRetryAttempts": 2,
                    "MaximumRecordAgeInSeconds": 3600,
                    "BisectBatchOnFunctionError": true,
                    "DestinationConfig": { "OnFailure": { "Destination": { "Fn::GetAtt": ["MyDlq", "Arn"] } } }
                """))
                .build());
        assertThat(waitForTerminal(DDB_STACK_NAME, 30)).isEqualTo("CREATE_COMPLETE");

        ddbEsmUuid = cfn.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(DDB_STACK_NAME).build()
        ).stackResources().stream()
                .filter(r -> "AWS::Lambda::EventSourceMapping".equals(r.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No EventSourceMapping resource found"))
                .physicalResourceId();

        GetEventSourceMappingResponse esm = lambda.getEventSourceMapping(r -> r.uuid(ddbEsmUuid));
        assertThat(esm.eventSourceArn()).contains(":dynamodb:").contains(DDB_TABLE_NAME);
        assertThat(esm.maximumRetryAttempts()).isEqualTo(2);
        assertThat(esm.maximumRecordAgeInSeconds()).isEqualTo(3600);
        assertThat(esm.bisectBatchOnFunctionError()).isTrue();
        assertThat(esm.destinationConfig()).isNotNull();
        assertThat(esm.destinationConfig().onFailure().destination())
                .isEqualTo("arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + DLQ_NAME);
    }

    @Test
    @Order(7)
    @DisplayName("UpdateStack clears retry, record age, bisect and DestinationConfig removed from the template")
    void updateDynamoDbStreamStack_clearsRemovedOptions() throws InterruptedException {
        assertThat(ddbEsmUuid).as("DynamoDB ESM UUID must have been captured in earlier test").isNotNull();

        cfn.updateStack(UpdateStackRequest.builder()
                .stackName(DDB_STACK_NAME)
                .templateBody(ddbStackTemplate("""
                    "MaximumRetryAttempts": 5
                """))
                .build());
        assertThat(waitForTerminal(DDB_STACK_NAME, 30)).isEqualTo("UPDATE_COMPLETE");

        GetEventSourceMappingResponse esm = lambda.getEventSourceMapping(r -> r.uuid(ddbEsmUuid));
        assertThat(esm.uuid()).isEqualTo(ddbEsmUuid);
        assertThat(esm.maximumRetryAttempts()).isEqualTo(5);
        assertThat(esm.maximumRecordAgeInSeconds()).isNull();
        assertThat(esm.bisectBatchOnFunctionError()).isNull();
        assertThat(esm.destinationConfig() == null || esm.destinationConfig().onFailure() == null).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("DeleteStack removes the ESM")
    void deleteStack_removesEsm() throws InterruptedException {
        assertThat(esmUuid).as("ESM UUID must have been captured in earlier test").isNotNull();

        cfn.deleteStack(DeleteStackRequest.builder().stackName(STACK_NAME).build());
        waitForDeleted(STACK_NAME, 30);

        assertThatThrownBy(() -> lambda.getEventSourceMapping(r -> r.uuid(esmUuid)))
                .isInstanceOf(software.amazon.awssdk.services.lambda.model.ResourceNotFoundException.class);
    }

    private static String sqsStackTemplate(String esmOptions) {
        return """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "%s"
                  }
                },
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "%s",
                    "Code": {
                      "ZipFile": "exports.handler = async (e) => ({ statusCode: 200 });"
                    }
                  }
                },
                "MyESM": {
                  "Type": "AWS::Lambda::EventSourceMapping",
                  "Properties": {
                    "FunctionName": "%s",
                    "EventSourceArn": { "Fn::GetAtt": ["MyQueue", "Arn"] },
                    "Enabled": true,
                    "BatchSize": 5,
            %s
                  }
                }
              }
            }
            """.formatted(QUEUE_NAME, FUNC_NAME, ROLE, FUNC_NAME, esmOptions);
    }

    private static String ddbStackTemplate(String esmOptions) {
        return """
            {
              "Resources": {
                "MyTable": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "%s",
                    "BillingMode": "PAY_PER_REQUEST",
                    "AttributeDefinitions": [ { "AttributeName": "pk", "AttributeType": "S" } ],
                    "KeySchema": [ { "AttributeName": "pk", "KeyType": "HASH" } ],
                    "StreamSpecification": { "StreamViewType": "NEW_AND_OLD_IMAGES" }
                  }
                },
                "MyDlq": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "%s"
                  }
                },
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "%s",
                    "Code": {
                      "ZipFile": "exports.handler = async (e) => ({ statusCode: 200 });"
                    }
                  }
                },
                "MyESM": {
                  "Type": "AWS::Lambda::EventSourceMapping",
                  "Properties": {
                    "FunctionName": { "Ref": "MyFunction" },
                    "EventSourceArn": { "Fn::GetAtt": ["MyTable", "StreamArn"] },
                    "StartingPosition": "TRIM_HORIZON",
            %s
                  }
                }
              }
            }
            """.formatted(DDB_TABLE_NAME, DLQ_NAME, DDB_FUNC_NAME, ROLE, esmOptions);
    }

    private String waitForTerminal(String stackName, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cfn.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build()
            ).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Stack " + stackName + " did not reach terminal state within " + maxSeconds + "s");
    }

    private void waitForDeleted(String stackName, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cfn.describeStacks(
                        DescribeStacksRequest.builder().stackName(stackName).build()
                ).stacks();
                if (stacks.isEmpty() || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
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
        throw new AssertionError("Stack " + stackName + " was not deleted within " + maxSeconds + "s");
    }
}
