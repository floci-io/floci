package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.rdsdata.RdsDataService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Statement normalisation and the result wrapper. The wrapper is the contract with
 * {@code toJsonObject()}: the call every relational JS resolver's response handler ends with only
 * finds rows under {@code sqlStatementResults}, with column metadata beside them.
 */
class RelationalDataSourceInvokerTest {

    private final RdsDataService rdsData = mock(RdsDataService.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RelationalDataSourceInvoker invoker =
            new RelationalDataSourceInvoker(rdsData, regionResolver, mapper);

    private DataSource dataSource() {
        DataSource ds = new DataSource();
        ds.setName("accountDB");
        ds.setType(DataSourceType.RELATIONAL_DATABASE);
        ds.setRelationalDatabaseConfig(Map.of(
                "relationalDatabaseSourceType", "RDS_HTTP_ENDPOINT",
                "rdsHttpEndpointConfig", Map.of(
                        "awsRegion", "eu-west-1",
                        "dbClusterIdentifier", "ls-db-cluster",
                        "databaseName", "accountdb",
                        "awsSecretStoreArn", "arn:aws:secretsmanager:eu-west-1:000000000000:secret:db")));
        return ds;
    }

    private void answersOneRow() {
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        ObjectNode response = mapper.createObjectNode();
        response.put("numberOfRecordsUpdated", 0);
        response.set("columnMetadata", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("label", "id").put("typeName", "int4")));
        response.set("records", mapper.createArrayNode()
                .add(mapper.createArrayNode().add(mapper.createObjectNode().put("longValue", 7))));
        when(rdsData.executeStatement(any(), anyString())).thenReturn(response);
    }

    private JsonNode captureRequest() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(rdsData).executeStatement(captor.capture(), anyString());
        return captor.getValue();
    }

    @Test
    void theHelpersStatementAndPositionalParametersBecomeNamedDataApiParameters() {
        answersOneRow();

        invoker.invoke(dataSource(), Map.of(
                "statement", "SELECT * FROM \"messages\" WHERE \"org_no\" = :param1 LIMIT :param2",
                "parameters", List.of("556677", 10)), "eu-west-1");

        JsonNode sent = captureRequest();
        assertEquals("accountdb", sent.path("database").asText());
        // The Data API needs a cluster ARN; the data source records only the identifier.
        assertEquals("arn:aws:rds:eu-west-1:000000000000:cluster:ls-db-cluster",
                sent.path("resourceArn").asText());
        assertTrue(sent.path("includeResultMetadata").asBoolean(),
                "without metadata toJsonObject cannot name a column");
        assertEquals("param1", sent.path("parameters").get(0).path("name").asText());
        assertEquals("556677", sent.path("parameters").get(0).path("value").path("stringValue").asText());
        // An integer must not arrive as a string, or a numeric comparison in SQL fails.
        assertEquals("param2", sent.path("parameters").get(1).path("name").asText());
        assertEquals(10, sent.path("parameters").get(1).path("value").path("longValue").asLong());
    }

    @Test
    void theResultIsWrappedTheWayToJsonObjectReadsIt() {
        answersOneRow();

        Object result = invoker.invoke(dataSource(),
                Map.of("statement", "SELECT id FROM messages"), "eu-west-1");

        Map<?, ?> wrapper = (Map<?, ?>) result;
        List<?> statements = (List<?>) wrapper.get("sqlStatementResults");
        assertEquals(1, statements.size());
        Map<?, ?> first = (Map<?, ?>) statements.get(0);
        assertTrue(first.containsKey("records"));
        assertTrue(first.containsKey("columnMetadata"));
        assertEquals(0, ((Number) first.get("numberOfRecordsUpdated")).intValue());
    }

    @Test
    void theClassicStatementsAndVariableMapShapeIsAccepted() {
        answersOneRow();

        invoker.invoke(dataSource(), Map.of(
                "statements", List.of("SELECT id FROM messages WHERE org_no = :orgNo"),
                "variableMap", Map.of(":orgNo", "556677")), "eu-west-1");

        JsonNode sent = captureRequest();
        // AppSync's own variableMap names its placeholders with a colon; the Data API does not.
        assertEquals("orgNo", sent.path("parameters").get(0).path("name").asText());
        assertEquals("556677", sent.path("parameters").get(0).path("value").path("stringValue").asText());
    }

    @Test
    void declaredTypeHintsTravelWithTheirParameters() {
        answersOneRow();

        // The shape a resolver's date filter produces: one variable map and one hint map, keyed by
        // the placeholder as written in the statement.
        invoker.invoke(dataSource(), Map.of(
                "statements", List.of("SELECT * FROM message_log WHERE received_at >= :v0 AND owneraccount = :v1"),
                "variableMap", Map.of(":v0", "2026-09-01 00:00:00", ":v1", "5566778899"),
                "variableTypeHintMap", Map.of(":v0", "TIMESTAMP")), "eu-west-1");

        JsonNode parameters = captureRequest().path("parameters");
        Map<String, JsonNode> byName = new java.util.LinkedHashMap<>();
        parameters.forEach(parameter -> byName.put(parameter.path("name").asText(), parameter));
        // Without the hint the value binds as text and PostgreSQL refuses the comparison outright:
        // "operator does not exist: timestamp with time zone >= character varying".
        assertEquals("TIMESTAMP", byName.get("v0").path("typeHint").asText());
        assertEquals("2026-09-01 00:00:00", byName.get("v0").path("value").path("stringValue").asText());
        // A parameter with no declared hint carries none, so it binds as its own JSON type.
        assertFalse(byName.get("v1").has("typeHint"));
    }

    @Test
    void aTypeHintOnAPositionalParameterIsHonoured() {
        answersOneRow();

        invoker.invoke(dataSource(), Map.of(
                "statement", "SELECT * FROM t WHERE d = :param1",
                "parameters", List.of("2026-09-01"),
                "variableTypeHintMap", Map.of(":param1", "DATE")), "eu-west-1");

        assertEquals("DATE", captureRequest().path("parameters").get(0).path("typeHint").asText());
    }

    @Test
    void aBareSqlStringIsAccepted() {
        answersOneRow();

        invoker.invoke(dataSource(), "SELECT 1", "eu-west-1");

        assertEquals("SELECT 1", captureRequest().path("sql").asText());
        assertFalse(captureRequest().has("parameters"));
    }

    @Test
    void everyStatementInAListRunsAndAnswersItsOwnResult() {
        answersOneRow();

        Object result = invoker.invoke(dataSource(), List.of(
                Map.of("statement", "SELECT 1"),
                Map.of("statement", "SELECT 2")), "eu-west-1");

        Map<?, ?> wrapper = (Map<?, ?>) result;
        assertEquals(2, ((List<?>) wrapper.get("sqlStatementResults")).size());
    }

    @Test
    void nullAndStructuredParametersAreTyped() {
        answersOneRow();

        invoker.invoke(dataSource(), Map.of("statement", "INSERT INTO t VALUES (:param1, :param2)",
                "parameters", List.of(Map.of("a", 1), true)), "eu-west-1");

        JsonNode parameters = captureRequest().path("parameters");
        // A structured parameter binds as its JSON text, which is what a json/jsonb column takes.
        assertEquals("{\"a\":1}", parameters.get(0).path("value").path("stringValue").asText());
        assertTrue(parameters.get(1).path("value").path("booleanValue").asBoolean());
    }

    @Test
    void aClusterIdentifierThatIsAlreadyAnArnIsUsedAsIs() {
        answersOneRow();
        DataSource ds = dataSource();
        ds.setRelationalDatabaseConfig(Map.of("rdsHttpEndpointConfig", Map.of(
                "dbClusterIdentifier", "arn:aws:rds:eu-west-1:000000000000:cluster:given",
                "databaseName", "accountdb",
                "awsSecretStoreArn", "arn:secret")));

        invoker.invoke(ds, "SELECT 1", "eu-west-1");

        assertEquals("arn:aws:rds:eu-west-1:000000000000:cluster:given",
                captureRequest().path("resourceArn").asText());
    }

    @Test
    void aResolverThatProducedNoStatementFailsClearly() {
        AwsException e = assertThrows(AwsException.class,
                () -> invoker.invoke(dataSource(), Map.of(), "eu-west-1"));

        assertTrue(e.getMessage().contains("no SQL statement"), e.getMessage());
    }

    @Test
    void aDataSourceWithoutAnEndpointConfigFailsClearly() {
        DataSource ds = dataSource();
        ds.setRelationalDatabaseConfig(Map.of());

        AwsException e = assertThrows(AwsException.class,
                () -> invoker.invoke(ds, "SELECT 1", "eu-west-1"));

        assertTrue(e.getMessage().contains("rdsHttpEndpointConfig"), e.getMessage());
    }
}
