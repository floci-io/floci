package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.model.RedshiftDataParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class EventBridgeRedshiftDataTargetTest {

    private static final String CLUSTER_ARN = "arn:aws:redshift:us-east-1:123456789012:cluster:warehouse";
    private static final String WORKGROUP_ARN =
            "arn:aws:redshift-serverless:us-west-2:123456789012:workgroup/warehouse";
    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/EventBridgeRedshiftRole";
    private static final String EVENT = "{\"id\":\"event-1\"}";

    private RedshiftDataService redshiftDataService;
    private ServiceRegistry serviceRegistry;
    private EventBridgeInvoker invoker;

    @BeforeEach
    void setUp() {
        redshiftDataService = mock(RedshiftDataService.class);
        serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.isServiceEnabled("redshift-data")).thenReturn(true);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        invoker = new EventBridgeInvoker(mock(LambdaService.class), mock(SqsService.class), mock(SnsService.class),
                redshiftDataService, serviceRegistry, new ObjectMapper(), config);
    }

    @Test
    void disabledDataApiRejectsSingleAndBatchDispatch() {
        when(serviceRegistry.isServiceEnabled("redshift-data")).thenReturn(false);
        for (boolean batch : List.of(false, true)) {
            Target target = target(CLUSTER_ARN);
            RedshiftDataParameters parameters = parameters("dev");
            if (batch) {
                parameters.setSqls(List.of("select 1"));
            } else {
                parameters.setSql("select 1");
            }
            target.setRedshiftDataParameters(parameters);
            AwsException failure = assertThrows(AwsException.class,
                    () -> invoker.invokeTarget(target, EVENT, "us-east-1"));
            assertEquals("ServiceNotAvailableException", failure.getErrorCode());
        }
        verifyNoInteractions(redshiftDataService);
    }

    @Test
    void resolvesParametersFromOriginalEventDespiteInputOverride() {
        Target target = target(CLUSTER_ARN);
        target.setInput("{\"detail\":{\"database\":\"wrong\"}}");
        RedshiftDataParameters parameters = parameters("$.detail.database");
        parameters.setDbUser("$.detail.user");
        parameters.setSecretManagerArn("$.detail.secret");
        parameters.setStatementName("$.detail.name");
        parameters.setSql("$.detail.queries[0]");
        target.setRedshiftDataParameters(parameters);
        String event = """
                {"detail":{"database":"dev","user":"admin","secret":"secret-arn",
                  "name":"event-statement","queries":["select 42"]}}
                """;

        invoker.invokeTarget(target, event, "us-east-1");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(redshiftDataService).executeStatement(request.capture(), eq("us-east-1"), eq(ROLE_ARN));
        assertEquals("dev", request.getValue().path("Database").asText());
        assertEquals("admin", request.getValue().path("DbUser").asText());
        assertEquals("secret-arn", request.getValue().path("SecretArn").asText());
        assertEquals("event-statement", request.getValue().path("StatementName").asText());
        assertEquals("select 42", request.getValue().path("Sql").asText());
        assertEquals("$.detail.database", parameters.getDatabase());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesSqlPathContainingSlash(boolean batch) {
        Target target = target(CLUSTER_ARN);
        RedshiftDataParameters parameters = parameters("dev");
        if (batch) {
            parameters.setSqls(List.of("$.detail.sql/query"));
        } else {
            parameters.setSql("$.detail.sql/query");
        }
        target.setRedshiftDataParameters(parameters);

        invoker.invokeTarget(target, "{\"detail\":{\"sql/query\":\"select 1\"}}", "us-east-1");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        if (batch) {
            verify(redshiftDataService).batchExecuteStatement(request.capture(), eq("us-east-1"), eq(ROLE_ARN));
            assertEquals("select 1", request.getValue().path("Sqls").get(0).asText());
        } else {
            verify(redshiftDataService).executeStatement(request.capture(), eq("us-east-1"), eq(ROLE_ARN));
            assertEquals("select 1", request.getValue().path("Sql").asText());
        }
    }

    @Test
    void resolvesBatchSqlPathsAndPreservesStaticSql() {
        Target target = target(WORKGROUP_ARN);
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSqls(List.of("$.detail.queries[0]", "$.detail.queries[1]", "select '$.detail.literal'"));
        target.setRedshiftDataParameters(parameters);

        invoker.invokeTarget(target, "{\"detail\":{\"queries\":[\"select 42\",\"select 43\"]}}", "us-west-2");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(redshiftDataService).batchExecuteStatement(request.capture(), eq("us-west-2"), eq(ROLE_ARN));
        assertEquals("select 42", request.getValue().path("Sqls").get(0).asText());
        assertEquals("select 43", request.getValue().path("Sqls").get(1).asText());
        assertEquals("select '$.detail.literal'", request.getValue().path("Sqls").get(2).asText());
    }

    @Test
    void invokesExecuteStatementForClusterTarget() throws Exception {
        Target target = target(CLUSTER_ARN);
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setDbUser("event_user");
        parameters.setSql("select 1");
        parameters.setWithEvent(true);
        target.setRedshiftDataParameters(parameters);

        invoker.invokeTarget(target, EVENT, "eu-west-1");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(redshiftDataService).executeStatement(request.capture(), eq("us-east-1"), eq(ROLE_ARN));
        assertEquals("warehouse", request.getValue().path("ClusterIdentifier").asText());
        assertEquals("dev", request.getValue().path("Database").asText());
        assertEquals("event_user", request.getValue().path("DbUser").asText());
        assertEquals("select 1", request.getValue().path("Sql").asText());
        assertEquals("event-rule", request.getValue().path("StatementName").asText());
        assertEquals(true, request.getValue().path("WithEvent").asBoolean());
    }

    @Test
    void invokesBatchExecuteStatementForServerlessWorkgroupTarget() throws Exception {
        Target target = target(WORKGROUP_ARN);
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSecretManagerArn("arn:aws:secretsmanager:us-west-2:123456789012:secret:redshift");
        parameters.setSqls(List.of("select 1", "select 2"));
        target.setRedshiftDataParameters(parameters);

        invoker.invokeTarget(target, EVENT, "us-east-1");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(redshiftDataService).batchExecuteStatement(request.capture(), eq("us-west-2"), eq(ROLE_ARN));
        assertEquals(WORKGROUP_ARN, request.getValue().path("WorkgroupName").asText());
        assertEquals("dev", request.getValue().path("Database").asText());
        assertEquals(parameters.getSecretManagerArn(), request.getValue().path("SecretArn").asText());
        assertEquals(2, request.getValue().path("Sqls").size());
    }

    @Test
    void readsRedshiftDataParametersUsingAwsMemberNames() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RedshiftDataParameters parameters = objectMapper.convertValue(
                objectMapper.readTree("{\"Database\":\"dev\",\"SecretManagerArn\":\"secret\",\"Sql\":\"select 1\"}"),
                RedshiftDataParameters.class);

        assertEquals("dev", parameters.getDatabase());
        assertEquals("secret", parameters.getSecretManagerArn());
        assertEquals("select 1", parameters.getSql());
        JsonNode response = objectMapper.valueToTree(parameters);
        assertEquals("dev", response.path("Database").asText());
        assertEquals("secret", response.path("SecretManagerArn").asText());
    }

    @Test
    void doesNotThrowWhenServiceRegistryIsNull() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        EventBridgeInvoker bareInvoker = new EventBridgeInvoker(mock(LambdaService.class), mock(SqsService.class),
                mock(SnsService.class), redshiftDataService, null, new ObjectMapper(), config);
        Target target = target(CLUSTER_ARN);
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");
        target.setRedshiftDataParameters(parameters);

        bareInvoker.invokeTarget(target, EVENT, "us-east-1");
        verify(redshiftDataService).executeStatement(any(), eq("us-east-1"), eq(ROLE_ARN));
    }

    @Test
    void runsInRulesAccountRatherThanTargetArnAccount() {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("999999999999");
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        EventBridgeInvoker scopedInvoker = new EventBridgeInvoker(
                mock(LambdaService.class), mock(SqsService.class), mock(SnsService.class),
                null, null, null, null, null, null,
                redshiftDataService, serviceRegistry, resolver, new ObjectMapper(), config);

        Target target = target(CLUSTER_ARN);
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");
        target.setRedshiftDataParameters(parameters);

        try (MockedStatic<RequestScopes> scopes =
                     mockStatic(RequestScopes.class, withSettings().mockMaker(MockMakers.INLINE))) {
            scopes.when(() -> RequestScopes.callAs(eq("999999999999"), eq("us-east-1"), any(Supplier.class)))
                    .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(2).get());

            scopedInvoker.invokeTarget(target, EVENT, "us-east-1");

            scopes.verify(() -> RequestScopes.callAs(eq("999999999999"), eq("us-east-1"), any(Supplier.class)));
        }
    }

    @Test
    void missingPathThrowsValidationException() {
        Target target = target(CLUSTER_ARN);
        RedshiftDataParameters parameters = parameters("$.detail.nonexistent");
        parameters.setSql("select 1");
        target.setRedshiftDataParameters(parameters);

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target, "{\"detail\":{}}", "us-east-1"));
        assertEquals("ValidationException", failure.getErrorCode());
    }

    @Test
    void nullPathThrowsValidationException() {
        Target target = target(CLUSTER_ARN);
        RedshiftDataParameters parameters = parameters("$.detail.nullDatabase");
        parameters.setSql("select 1");
        target.setRedshiftDataParameters(parameters);

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target, "{\"detail\":{\"nullDatabase\":null}}", "us-east-1"));
        assertEquals("ValidationException", failure.getErrorCode());
    }

    private static Target target(String arn) {
        Target target = new Target("target-1", arn, null, null);
        target.setRoleArn(ROLE_ARN);
        return target;
    }

    private static RedshiftDataParameters parameters(String database) {
        RedshiftDataParameters parameters = new RedshiftDataParameters();
        parameters.setDatabase(database);
        parameters.setStatementName("event-rule");
        return parameters;
    }
}
