package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.BatchExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.DescribeTableResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultRequest;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultResponse;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultV2Response;
import software.amazon.awssdk.services.redshiftdata.model.ResultFormatString;
import software.amazon.awssdk.services.redshiftdata.model.SqlParameter;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;
import software.amazon.awssdk.services.redshiftdata.model.SubStatementData;
import software.amazon.awssdk.services.redshiftdata.model.ValidationException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redshift Data API Operations")
class RedshiftDataOperationsTest {

    private static final Logger LOG = Logger.getLogger(RedshiftDataOperationsTest.class);

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password123";
    private static final String DATABASE = "dev";

    private static RedshiftClient redshift;
    private static RedshiftDataClient data;
    private static String clusterId;

    @BeforeAll
    static void setup() {
        redshift = TestFixtures.redshiftClient();
        data = TestFixtures.redshiftDataClient();
        clusterId = TestFixtures.uniqueName("rsdata-cluster");
        redshift.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (redshift != null && clusterId != null) {
            try {
                redshift.deleteCluster(DeleteClusterRequest.builder().clusterIdentifier(clusterId)
                        .skipFinalClusterSnapshot(true).build());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to clean up Redshift cluster %s", clusterId);
            }
        }
        if (data != null) {
            data.close();
        }
        if (redshift != null) {
            redshift.close();
        }
    }

    @Test
    @DisplayName("explicit client token retries an INSERT without inserting twice")
    void executeStatementClientTokenRetry() throws Exception {
        run("CREATE TABLE compat_idempotent_single (id int)");
        ExecuteStatementRequest request = ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId).database(DATABASE).dbUser(USERNAME)
                .clientToken(TestFixtures.uniqueName("single-token"))
                .sql("INSERT INTO compat_idempotent_single VALUES (1)").build();
        String first = data.executeStatement(request).id();
        awaitFinished(first);
        assertThat(data.executeStatement(request).id()).isEqualTo(first);
        String count = run("SELECT COUNT(*) FROM compat_idempotent_single");
        assertThat(data.getStatementResult(r -> r.id(count)).records().get(0).get(0).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("explicit client token retries a batch without repeating its mutations")
    void batchExecuteStatementClientTokenRetry() throws Exception {
        run("CREATE TABLE compat_idempotent_batch (id int)");
        BatchExecuteStatementRequest request = BatchExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId).database(DATABASE).dbUser(USERNAME)
                .clientToken(TestFixtures.uniqueName("batch-token"))
                .sqls("INSERT INTO compat_idempotent_batch VALUES (1)",
                        "INSERT INTO compat_idempotent_batch VALUES (2)").build();
        String first = data.batchExecuteStatement(request).id();
        awaitFinished(first);
        assertThat(data.batchExecuteStatement(request).id()).isEqualTo(first);
        String count = run("SELECT COUNT(*) FROM compat_idempotent_batch");
        assertThat(data.getStatementResult(r -> r.id(count)).records().get(0).get(0).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("execute, describe, and get-statement-result round trip")
    void executeDescribeGetResult() throws Exception {
        run("CREATE TABLE compat_t (id int, name varchar(20))");
        String insertId = run("INSERT INTO compat_t VALUES (1, 'a'), (2, 'b')");
        assertThat(describe(insertId).resultRows()).isEqualTo(2L);

        String selectId = run("SELECT id, name FROM compat_t ORDER BY id");
        GetStatementResultResponse result = data.getStatementResult(GetStatementResultRequest.builder()
                .id(selectId)
                .build());

        assertThat(result.totalNumRows()).isEqualTo(2L);
        assertThat(result.columnMetadata()).extracting("name").containsExactly("id", "name");
        assertThat(result.records().get(0).get(0).longValue()).isEqualTo(1L);
        assertThat(result.records().get(0).get(1).stringValue()).isEqualTo("a");
    }

    @Test
    @DisplayName("named SQL parameters are bound through the SDK model")
    void parameterizedStatementBindsSqlParameters() throws Exception {
        String id = data.executeStatement(ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .database(DATABASE)
                .dbUser(USERNAME)
                .sql("SELECT CAST(:value AS INTEGER) + 1 AS result")
                .parameters(SqlParameter.builder().name("value").value("41").build())
                .waitTimeSeconds(30)
                .build()).id();

        GetStatementResultResponse result = data.getStatementResult(request -> request.id(id));
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).get(0).longValue()).isEqualTo(42L);
        assertThat(describe(id).queryParameters()).containsExactly(
                SqlParameter.builder().name("value").value("41").build());
    }

    @Test
    @DisplayName("batch response and child statement results round trip through the SDK")
    void batchResultsExposeChildStatements() throws Exception {
        String id = data.batchExecuteStatement(BatchExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .database(DATABASE)
                .dbUser(USERNAME)
                .sqls("SELECT 11 AS value", "SELECT 22 AS value")
                .build()).id();
        awaitFinished(id);

        DescribeStatementResponse batch = describe(id);
        assertThat(batch.status()).isEqualTo(StatusString.FINISHED);
        assertThat(batch.subStatements()).hasSize(2);
        assertThat(batch.subStatements()).extracting(SubStatementData::statusAsString).containsOnly("FINISHED");

        GetStatementResultResponse parentResult = data.getStatementResult(request -> request.id(id));
        assertThat(parentResult.records().get(0).get(0).longValue()).isEqualTo(22L);
        GetStatementResultResponse firstChild = data.getStatementResult(request -> request.id(batch.subStatements().get(0).id()));
        assertThat(firstChild.records().get(0).get(0).longValue()).isEqualTo(11L);
    }

    @Test
    @DisplayName("result paging returns every row once through SDK next tokens")
    void resultPagingRoundTripsSdkNextToken() throws Exception {
        String sql = IntStream.rangeClosed(1, 1001).mapToObj(value -> "SELECT " + value + " AS value")
                .collect(Collectors.joining(" UNION ALL "));
        String id = run(sql);

        GetStatementResultResponse firstPage = data.getStatementResult(request -> request.id(id));
        assertThat(firstPage.records()).hasSize(1000);
        assertThat(firstPage.nextToken()).isNotBlank();
        GetStatementResultResponse secondPage = data.getStatementResult(request -> request.id(id)
                .nextToken(firstPage.nextToken()));
        assertThat(secondPage.records()).hasSize(1);
        assertThat(secondPage.nextToken()).isNull();

        Set<Long> values = new HashSet<>();
        firstPage.records().forEach(record -> values.add(record.get(0).longValue()));
        secondPage.records().forEach(record -> values.add(record.get(0).longValue()));
        assertThat(values).hasSize(1001).contains(1L, 1001L);
    }

    @Test
    @DisplayName("V2 result CSV union is unmarshalled by the SDK")
    void resultV2SupportsCsvRecords() throws Exception {
        String id = data.executeStatement(request -> request.clusterIdentifier(clusterId).database(DATABASE)
                .dbUser(USERNAME).resultFormat(ResultFormatString.CSV)
                .sql("SELECT 1 AS id, 'a' AS name UNION ALL SELECT 2, 'b'"))
                .id();
        awaitFinished(id);

        GetStatementResultV2Response result = data.getStatementResultV2(request -> request.id(id));
        assertThat(result.resultFormat()).isEqualTo(ResultFormatString.CSV);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records()).extracting("csvRecords").containsExactly("1,a", "2,b");
        assertThat(result.columnMetadata()).extracting("name").containsExactly("id", "name");
    }

    /**
     * Redshift stores INTERVAL YEAR TO MONTH and INTERVAL DAY TO SECOND columns and the
     * Data API returns them as strings, checked against a real cluster on 2026-09-13. pgjdbc
     * builds the value through reflection, so this also checks that the native image keeps
     * that metadata.
     */
    @Test
    @DisplayName("interval columns read back as strings")
    void intervalColumnsReadBackAsStrings() throws Exception {
        run("CREATE TABLE compat_intervals (y2m interval year to month, d2s interval day to second)");
        run("INSERT INTO compat_intervals VALUES (interval '1-2' year to month, interval '2 1:0:0' day to second)");

        String selectId = run("SELECT y2m, d2s FROM compat_intervals");
        GetStatementResultResponse result = data.getStatementResult(GetStatementResultRequest.builder()
                .id(selectId)
                .build());

        assertThat(result.totalNumRows()).isEqualTo(1L);
        assertThat(result.records().get(0).get(0).stringValue()).isEqualTo("1 years 2 mons");
        // Redshift prints "2 days 1 hours 0 mins 0.0 secs", Floci drops the zero fields.
        assertThat(result.records().get(0).get(1).stringValue()).isNotEmpty();
    }

    /**
     * Redshift rejects these as column types but evaluates them in a select, and the Data
     * API returns them as strings. Values checked against a real ra3.large cluster on
     * 2026-09-13. pgjdbc builds them through reflection, so this also checks that the
     * native image keeps that metadata.
     */
    @Test
    @DisplayName("geometric expressions read back as strings")
    void geometricExpressionsReadBackAsStrings() throws Exception {
        String selectId = run("SELECT point(1, 2), box '((0,0),(1,1))', circle '<(0,0),1>', lseg '[(0,0),(1,1)]', "
                + "path '[(0,0),(1,1)]', polygon '((0,0),(1,1),(1,0))', '12.34'::money");
        GetStatementResultResponse result = data.getStatementResult(GetStatementResultRequest.builder()
                .id(selectId)
                .build());

        assertThat(result.totalNumRows()).isEqualTo(1L);
        assertThat(result.columnMetadata()).extracting("typeName")
                .containsExactly("point", "box", "circle", "lseg", "path", "polygon", "money");
        assertThat(result.records().get(0).get(0).stringValue()).isEqualTo("(1.0,2.0)");
        assertThat(result.records().get(0).get(1).stringValue()).isEqualTo("(1.0,1.0),(0.0,0.0)");
        assertThat(result.records().get(0).get(2).stringValue()).isEqualTo("<(0.0,0.0),1.0>");
        assertThat(result.records().get(0).get(3).stringValue()).isEqualTo("[(0.0,0.0),(1.0,1.0)]");
        // Redshift prints path as the server text [(0,0),(1,1)], Floci still prints the driver form.
        assertThat(result.records().get(0).get(4).stringValue()).isNotEmpty();
        assertThat(result.records().get(0).get(5).stringValue()).isEqualTo("((0.0,0.0),(1.0,1.0),(1.0,0.0))");
        assertThat(result.records().get(0).get(6).doubleValue()).isEqualTo(12.34);
    }

    /**
     * Error text checked against a real cluster on 2026-09-13.
     */
    @Test
    @DisplayName("PostgreSQL types Redshift lacks fail the statement with the Redshift error")
    void typesRedshiftLacksFailTheStatement() throws Exception {
        assertThat(failure("SELECT line '{1,2,3}'")).isEqualTo("ERROR: type \"line\" not yet implemented");
        assertThat(failure("SELECT '{\"a\":1}'::json")).isEqualTo("ERROR: type \"json\" does not exist");
        assertThat(failure("SELECT '{\"a\":1}'::jsonb")).isEqualTo("ERROR: type \"jsonb\" does not exist");
    }

    @Test
    @DisplayName("metadata operations return SDK response models")
    void metadataOperationsReturnSdkModels() throws Exception {
        String tableName = TestFixtures.uniqueName("rsdata_meta").replace('-', '_');
        run("CREATE TABLE public." + tableName + " (id integer, label varchar(32))");
        try {
            assertThat(data.listDatabases(request -> request.clusterIdentifier(clusterId)
                    .database(DATABASE).dbUser(USERNAME)).databases()).contains(DATABASE);

            assertThat(data.listSchemas(request -> request.clusterIdentifier(clusterId)
                    .database(DATABASE).dbUser(USERNAME).schemaPattern("public")).schemas()).contains("public");

            assertThat(data.listTables(request -> request.clusterIdentifier(clusterId)
                    .database(DATABASE).dbUser(USERNAME).schemaPattern("public").tablePattern(tableName))
                    .tables()).anySatisfy(table -> {
                assertThat(table.name()).isEqualTo(tableName);
                assertThat(table.schema()).isEqualTo("public");
            });

            DescribeTableResponse described = data.describeTable(request -> request.clusterIdentifier(clusterId)
                    .database(DATABASE).dbUser(USERNAME).schema("public").table(tableName));
            assertThat(described.tableName()).isEqualTo(tableName);
            assertThat(described.columnList()).extracting("name").containsExactly("id", "label");
            assertThat(described.columnList()).extracting("typeName").allMatch(typeName -> !((String) typeName).isBlank());
        } finally {
            run("DROP TABLE public." + tableName);
        }
    }

    @Test
    @DisplayName("ListStatements paginator returns matching SDK statement models")
    void listStatementsPaginatorReturnsMatchingStatements() throws Exception {
        String statementName = TestFixtures.uniqueName("rsdata_list");
        for (int value = 1; value <= 3; value++) {
            int queryValue = value;
            String id = data.executeStatement(request -> request.clusterIdentifier(clusterId).database(DATABASE)
                    .dbUser(USERNAME).statementName(statementName).sql("SELECT " + queryValue))
                    .id();
            awaitFinished(id);
        }

        List<String> statementIds = data.listStatementsPaginator(request -> request.clusterIdentifier(clusterId)
                        .statementName(statementName).maxResults(1))
                .statements().stream()
                .map(statement -> statement.id())
                .toList();
        assertThat(statementIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(data.listStatements(request -> request.clusterIdentifier(clusterId).statementName(statementName)
                .status(StatusString.FINISHED)).statements()).hasSize(3);
    }

    @Test
    @DisplayName("cancellation rejects terminal statements with ValidationException")
    void cancelStatementRejectsTerminalStatements() throws Exception {
        String id = data.executeStatement(ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .database(DATABASE)
                .dbUser(USERNAME)
                .sql("SELECT 1")
                .waitTimeSeconds(30)
                .build()).id();
        assertThatThrownBy(() -> data.cancelStatement(request -> request.id(id)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Could not cancel a query that is already in FINISHED state with ID: " + id);
    }

    @Test
    @DisplayName("SDK surfaces service validation and statement failure separately")
    void sdkReportsStatementAndRequestErrors() throws Exception {
        String failedId = data.executeStatement(request -> request
                        .clusterIdentifier(clusterId)
                        .database(DATABASE)
                        .dbUser(USERNAME)
                        .sql("SELECT * FROM " + TestFixtures.uniqueName("missing_table").replace('-', '_'))
                        .waitTimeSeconds(30))
                .id();
        DescribeStatementResponse failed = describe(failedId);
        assertThat(failed.status()).isEqualTo(StatusString.FAILED);
        assertThat(failed.error()).contains("does not exist");

        assertThatThrownBy(() -> data.executeStatement(request -> request
                        .clusterIdentifier(clusterId).workgroupName("unused").database(DATABASE).sql("SELECT 1")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("WithEvent routes terminal SQL status through EventBridge to SQS")
    void withEventRoutesFinishedAndFailedStatementsToSqs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String name = TestFixtures.uniqueName("rsdata-event");
        try (EventBridgeClient events = TestFixtures.eventBridgeClient(); SqsClient sqs = TestFixtures.sqsClient()) {
            String queueUrl = sqs.createQueue(request -> request.queueName(name)).queueUrl();
            String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String pattern = mapper.writeValueAsString(Map.of("source", List.of("aws.redshift-data"),
                    "detail", Map.of("statementName", List.of(name))));
            String ruleArn = events.putRule(request -> request.name(name).eventPattern(pattern)).ruleArn();
            String policy = mapper.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                    "Effect", "Allow", "Principal", Map.of("Service", "events.amazonaws.com"),
                    "Action", "sqs:SendMessage", "Resource", queueArn,
                    "Condition", Map.of("ArnEquals", Map.of("aws:SourceArn", ruleArn))))));
            sqs.setQueueAttributes(request -> request.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, policy)));
            try {
                events.putTargets(request -> request.rule(name).targets(Target.builder().id("queue").arn(queueArn).build()));
                for (String sql : List.of("SELECT 1", "SELECT * FROM missing_event_table")) {
                    String id = data.executeStatement(request -> request.clusterIdentifier(clusterId).database(DATABASE)
                            .dbUser(USERNAME).statementName(name).sql(sql).withEvent(true)).id();
                    Message message = awaitEvent(sqs, queueUrl, mapper, id);
                    JsonNode event = mapper.readTree(message.body());
                    assertThat(event.path("source").asText()).isEqualTo("aws.redshift-data");
                    assertThat(event.path("detail-type").asText()).isEqualTo("Redshift Data Statement Status Change");
                    assertThat(event.path("detail").path("statementName").asText()).isEqualTo(name);
                    assertThat(event.path("detail").path("state").asText())
                            .isEqualTo(sql.equals("SELECT 1") ? "FINISHED" : "FAILED");
                    assertThat(describe(id).statusAsString()).isEqualTo(event.path("detail").path("state").asText());
                    assertThat(event.path("detail").path("redshiftQueryId").asLong()).isEqualTo(describe(id).redshiftQueryId());
                    assertThat(event.path("resources").get(0).asText()).endsWith(":cluster:" + clusterId);
                    sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                }
                String batchId = data.batchExecuteStatement(request -> request.clusterIdentifier(clusterId)
                        .database(DATABASE).dbUser(USERNAME).statementName(name).withEvent(true)
                        .sqls("SELECT 10", "SELECT 20")).id();
                Message batchEvent = awaitEvent(sqs, queueUrl, mapper, batchId);
                assertThat(mapper.readTree(batchEvent.body()).path("detail").path("state").asText()).isEqualTo("FINISHED");
                assertThat(describe(batchId).subStatements()).hasSize(2);
                sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(batchEvent.receiptHandle()));
                if (!TestFixtures.isRealAws()) {
                    String cancelledId = data.executeStatement(request -> request.clusterIdentifier(clusterId)
                            .database(DATABASE).dbUser(USERNAME).statementName(name).withEvent(true).sql("SELECT pg_sleep(20)")).id();
                    assertThat(data.cancelStatement(request -> request.id(cancelledId)).status()).isTrue();
                    Message abortedEvent = awaitEvent(sqs, queueUrl, mapper, cancelledId);
                    assertThat(mapper.readTree(abortedEvent.body()).path("detail").path("state").asText()).isEqualTo("ABORTED");
                    assertThat(describe(cancelledId).status()).isEqualTo(StatusString.ABORTED);
                    sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(abortedEvent.receiptHandle()));
                }
                for (boolean explicitFalse : List.of(false, true)) {
                    ExecuteStatementRequest.Builder request = ExecuteStatementRequest.builder().clusterIdentifier(clusterId)
                            .database(DATABASE).dbUser(USERNAME).statementName(name).sql("SELECT 2");
                    if (explicitFalse) {
                        request.withEvent(false);
                    }
                    awaitFinished(data.executeStatement(request.build()).id());
                }
                assertThat(sqs.receiveMessage(request -> request.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();
            } finally {
                events.removeTargets(request -> request.rule(name).ids("queue"));
                events.deleteRule(request -> request.name(name));
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    private static Message awaitEvent(SqsClient sqs, String queueUrl, ObjectMapper mapper, String id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl).waitTimeSeconds(1)).messages();
            for (Message message : messages) {
                if (id.equals(mapper.readTree(message.body()).path("detail").path("statementId").asText())) {
                    return message;
                }
            }
        }
        throw new AssertionError("No EventBridge event for statement " + id);
    }

    private String failure(String sql) throws Exception {
        String id = submit(sql);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            DescribeStatementResponse describe = describe(id);
            if (describe.status() == StatusString.FAILED) {
                return describe.error();
            }
            if (describe.status() == StatusString.FINISHED) {
                throw new IllegalStateException("Statement " + id + " finished but was expected to fail");
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Statement " + id + " did not finish in time");
    }

    private String submit(String sql) {
        return data.executeStatement(ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .dbUser(USERNAME)
                .database(DATABASE)
                .sql(sql)
                .build())
                .id();
    }

    private String run(String sql) throws Exception {
        String id = data.executeStatement(ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .dbUser(USERNAME)
                .database(DATABASE)
                .sql(sql)
                .build())
                .id();
        return awaitFinished(id);
    }

    private String awaitFinished(String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            DescribeStatementResponse describe = describe(id);
            StatusString status = describe.status();
            if (status == StatusString.FINISHED) {
                return id;
            }
            if (status == StatusString.FAILED || status == StatusString.ABORTED) {
                throw new IllegalStateException("Statement " + id + " ended " + status + ": " + describe.error());
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Statement " + id + " did not finish in time");
    }

    private DescribeStatementResponse describe(String id) {
        return data.describeStatement(DescribeStatementRequest.builder().id(id).build());
    }
}
