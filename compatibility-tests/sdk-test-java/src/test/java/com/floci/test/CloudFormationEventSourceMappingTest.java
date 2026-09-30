package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetEventSourceMappingResponse;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies that CloudFormation AWS::Lambda::EventSourceMapping provisions and
 * deletes an ESM backed by an SQS queue, matching the use-case from issue #593,
 * that batching, filtering, scaling, retry and destination options set in the
 * template reach the mapping and are cleared when removed on update (issue #4313),
 * and that the SQS poller applies the stack's filter, batching window and
 * partial batch responses when it delivers messages.
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
    private static final int WINDOW_SECONDS = 5;

    // Logs each delivered batch as "<body id>@<receive count>" and reports a message flagged "fail"
    // as a batch item failure on its first delivery.
    private static final String SQS_HANDLER = """
            exports.handler = async (event) => {
              const records = event.Records.map((r) => ({ messageId: r.messageId,
                count: r.attributes.ApproximateReceiveCount, body: JSON.parse(r.body) }));
              console.log('cfn-esm-batch ids=' + records.map((r) => r.body.id + '@' + r.count).join(','));
              return { batchItemFailures: records.filter((r) => r.body.fail && r.count === '1')
                .map((r) => ({ itemIdentifier: r.messageId })) };
            };
            """.replace('\n', ' ');
    private static final Pattern BATCH_LOG = Pattern.compile("cfn-esm-batch ids=(\\S*)");

    private static CloudFormationClient cfn;
    private static LambdaClient lambda;
    private static SqsClient sqs;
    private static CloudWatchLogsClient logs;

    private static String esmUuid;
    private static String ddbEsmUuid;

    @BeforeAll
    static void setup() {
        cfn    = TestFixtures.cloudFormationClient();
        lambda = TestFixtures.lambdaClient();
        sqs    = TestFixtures.sqsClient();
        logs   = TestFixtures.cloudWatchLogsClient();
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
        if (logs   != null) logs.close();
    }

    @Test
    @Order(1)
    @DisplayName("CreateStack with Lambda + SQS + EventSourceMapping reaches CREATE_COMPLETE")
    void createStack_withEventSourceMapping() throws InterruptedException {
        String template = sqsStackTemplate("""
                    "MaximumBatchingWindowInSeconds": %d,
                    "FilterCriteria": { "Filters": [ { "Pattern": "{\\"body\\":{\\"kind\\":[\\"order\\"]}}" } ] },
                    "ScalingConfig": { "MaximumConcurrency": 5 }
                """.formatted(WINDOW_SECONDS));

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
        assertThat(esm.maximumBatchingWindowInSeconds()).isEqualTo(WINDOW_SECONDS);
        assertThat(esm.filterCriteria()).isNotNull();
        assertThat(esm.filterCriteria().filters()).singleElement()
                .satisfies(f -> assertThat(f.pattern()).isEqualTo(FILTER_PATTERN));
        assertThat(esm.scalingConfig()).isNotNull();
        assertThat(esm.scalingConfig().maximumConcurrency()).isEqualTo(5);
    }

    @Test
    @Order(5)
    @DisplayName("SQS delivery applies the stack's filter, batching window and partial batch response")
    void sqsDelivery_appliesFilterWindowAndPartialBatchResponse() throws InterruptedException {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Lambda REQUEST_RESPONSE dispatch unavailable in this environment");
        String queueUrl = sqs.getQueueUrl(r -> r.queueName(QUEUE_NAME)).queueUrl();
        long since = System.currentTimeMillis();

        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody("{\"id\":\"ok\",\"kind\":\"order\"}"));
        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody("{\"id\":\"skip\",\"kind\":\"other\"}"));
        // Once the poller holds the first two, only the window can batch the third with them.
        waitForZero(queueUrl, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody("{\"id\":\"fail\",\"kind\":\"order\",\"fail\":true}"));

        waitForBatches(since, 2);
        waitForZero(queueUrl, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);

        List<List<String>> batches = readBatches(since);
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).containsExactlyInAnyOrder("ok@1", "fail@1");
        assertThat(batches.get(1)).containsExactly("fail@2");
    }

    @Test
    @Order(6)
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
    @Order(7)
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
    @Order(8)
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
    @Order(9)
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
                    "QueueName": "%s",
                    "VisibilityTimeout": 1
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
                      "ZipFile": "%s"
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
                    "FunctionResponseTypes": ["ReportBatchItemFailures"],
            %s
                  }
                }
              }
            }
            """.formatted(QUEUE_NAME, FUNC_NAME, ROLE, SQS_HANDLER, FUNC_NAME, esmOptions);
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

    private static List<List<String>> readBatches(long since) {
        try {
            return logs.filterLogEvents(r -> r.logGroupName("/aws/lambda/" + FUNC_NAME).startTime(since))
                    .events().stream()
                    .sorted(Comparator.comparing(FilteredLogEvent::timestamp))
                    .map(e -> BATCH_LOG.matcher(String.valueOf(e.message())))
                    .filter(Matcher::find)
                    .map(m -> List.of(m.group(1).split(",")))
                    .toList();
        } catch (ResourceNotFoundException e) {
            return List.of();
        }
    }

    private static void waitForBatches(long since, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (readBatches(since).size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Expected " + count + " delivered batches, got " + readBatches(since));
            }
            Thread.sleep(500);
        }
    }

    private static void waitForZero(String queueUrl, QueueAttributeName... counts) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNames(
                    counts)).attributes();
            if (attrs.values().stream().allMatch("0"::equals)) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Queue still holds messages: " + attrs);
            }
            Thread.sleep(200);
        }
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
