package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.model.RedshiftDataParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventBridgeRedshiftTargetAuthorizationTest {
    private static final String ACCOUNT = "123456789012";
    private static final String CLUSTER_ARN = "arn:aws:redshift:us-east-1:" + ACCOUNT + ":cluster:warehouse";
    private static final String WORKGROUP_ARN =
            "arn:aws:redshift-serverless:us-west-2:" + ACCOUNT + ":workgroup/warehouse";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/EventBridgeRedshiftRole";
    private static final String SECRET_ARN = "arn:aws:secretsmanager:us-east-1:" + ACCOUNT + ":secret:db-AbCdEf";
    private static final String EVENT = "{\"id\":\"event-1\"}";

    private RedshiftDataService redshiftDataService;
    private EventBridgeRedshiftTargetAuthorizer authorizer;
    private EventBridgeInvoker invoker;

    @BeforeEach
    void setUp() {
        redshiftDataService = mock(RedshiftDataService.class);
        authorizer = mock(EventBridgeRedshiftTargetAuthorizer.class);
        when(authorizer.refusal(any(), any(), any(), anyList())).thenReturn(Optional.empty());
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.isServiceEnabled("redshift-data")).thenReturn(true);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn(ACCOUNT);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        invoker = new EventBridgeInvoker(mock(LambdaService.class), mock(SqsService.class), mock(SnsService.class),
                null, null, null, null, null, null, redshiftDataService, authorizer, serviceRegistry, resolver,
                new ObjectMapper(), config);
    }

    @Test
    void clusterTargetWithADbUserNeedsTheDataApiActionAndTemporaryCredentials() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setDbUser("admin");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(List.of(
                new Permission("redshift-data:ExecuteStatement", CLUSTER_ARN),
                new Permission("redshift:GetClusterCredentials",
                        "arn:aws:redshift:us-east-1:" + ACCOUNT + ":dbuser:warehouse/admin"),
                new Permission("redshift:GetClusterCredentials",
                        "arn:aws:redshift:us-east-1:" + ACCOUNT + ":dbname:warehouse/dev")), permissions());
        verify(redshiftDataService).executeStatement(any(JsonNode.class), eq("us-east-1"), eq(ROLE_ARN));
    }

    @Test
    void secretReplacesTheCredentialsPermission() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setDbUser("admin");
        parameters.setSecretManagerArn(SECRET_ARN);
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(List.of(
                new Permission("redshift-data:ExecuteStatement", CLUSTER_ARN),
                new Permission("secretsmanager:GetSecretValue", SECRET_ARN)), permissions());
    }

    @Test
    void clusterTargetNamingAnotherAccountIsCheckedAgainstTheClusterThatRuns() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");

        invoker.invokeTarget(target("arn:aws:redshift:us-east-1:999999999999:cluster:warehouse", parameters),
                EVENT, "us-east-1");

        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", CLUSTER_ARN)), permissions());
    }

    @Test
    void blankSecretIsIgnoredAndTheDbUserCredentialsAreChecked() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setDbUser("admin");
        parameters.setSecretManagerArn("");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(3, permissions().size());
        assertEquals("redshift:GetClusterCredentials", permissions().get(1).action());
    }

    @Test
    void blankSecretOnAServerlessTargetStillNeedsTheWorkgroupCredentials() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSecretManagerArn(" ");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(WORKGROUP_ARN, parameters), EVENT, "us-west-2");

        assertEquals(new Permission("redshift-serverless:GetCredentials", WORKGROUP_ARN), permissions().get(1));
    }

    @Test
    void blankDbUserAddsNoCredentialsPermission() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setDbUser("");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", CLUSTER_ARN)), permissions());
    }

    @Test
    void secretNamedWithoutAnArnAddsNoPermissionBecauseTheDataApiRejectsIt() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSecretManagerArn("db");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", CLUSTER_ARN)), permissions());
    }

    @Test
    void serverlessTargetWithASecretNeedsTheSecretAndNotTheWorkgroupCredentials() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSecretManagerArn(SECRET_ARN);
        parameters.setSql("select 1");

        invoker.invokeTarget(target(WORKGROUP_ARN, parameters), EVENT, "us-west-2");

        assertEquals(List.of(
                new Permission("redshift-data:ExecuteStatement", WORKGROUP_ARN),
                new Permission("secretsmanager:GetSecretValue", SECRET_ARN)), permissions());
    }

    @Test
    void batchNeedsTheBatchAction() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSqls(List.of("select 1", "select 2"));

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(new Permission("redshift-data:BatchExecuteStatement", CLUSTER_ARN), permissions().getFirst());
        verify(redshiftDataService).batchExecuteStatement(any(JsonNode.class), eq("us-east-1"), eq(ROLE_ARN));
    }

    @Test
    void serverlessTargetNeedsTheWorkgroupCredentials() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(WORKGROUP_ARN, parameters), EVENT, "us-west-2");

        assertEquals(List.of(
                new Permission("redshift-data:ExecuteStatement", WORKGROUP_ARN),
                new Permission("redshift-serverless:GetCredentials", WORKGROUP_ARN)), permissions());
    }

    @Test
    void clusterTargetWithoutADbUserOrSecretNeedsOnlyTheDataApiAction() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", CLUSTER_ARN)), permissions());
    }

    @Test
    void refusedRoleIsAccessDeniedAndNothingIsSubmitted() {
        when(authorizer.refusal(any(), any(), any(), anyList())).thenReturn(Optional.of("not authorized"));
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1"));

        assertEquals("AccessDeniedException", failure.getErrorCode());
        assertEquals(403, failure.getHttpStatus());
        assertEquals("not authorized", failure.getMessage());
        verifyNoInteractions(redshiftDataService);
    }

    @Test
    void refusedRoleIsReportedBeforeABadPathInTheSql() {
        when(authorizer.refusal(any(), any(), any(), anyList())).thenReturn(Optional.of("not authorized"));
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("$.detail.missing");

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1"));

        assertEquals("AccessDeniedException", failure.getErrorCode());
        verifyNoInteractions(redshiftDataService);
    }

    @Test
    void allowedRoleStillGetsTheValidationErrorForABadPathInTheSql() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("$.detail.missing");

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1"));

        assertEquals("ValidationException", failure.getErrorCode());
        verifyNoInteractions(redshiftDataService);
    }

    @Test
    void roleRuleAccountAndMatchedRuleAreHandedToTheAuthorizer() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");
        String ruleArn = "arn:aws:events:us-east-1:" + ACCOUNT + ":rule/refresh";

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1", ruleArn);

        verify(authorizer).refusal(eq(ROLE_ARN), eq(ACCOUNT), eq(ruleArn), anyList());
    }

    @Test
    void deliveryWithoutAMatchedRuleHandsOverNone() {
        RedshiftDataParameters parameters = parameters("dev");
        parameters.setSql("select 1");

        invoker.invokeTarget(target(CLUSTER_ARN, parameters), EVENT, "us-east-1");

        verify(authorizer).refusal(eq(ROLE_ARN), eq(ACCOUNT), isNull(), anyList());
    }

    private List<Permission> permissions() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Permission>> captor = ArgumentCaptor.forClass(List.class);
        verify(authorizer).refusal(eq(ROLE_ARN), eq(ACCOUNT), any(), captor.capture());
        return captor.getValue();
    }

    private static RedshiftDataParameters parameters(String database) {
        RedshiftDataParameters parameters = new RedshiftDataParameters();
        parameters.setDatabase(database);
        return parameters;
    }

    private static Target target(String arn, RedshiftDataParameters parameters) {
        Target target = new Target();
        target.setId("redshift");
        target.setArn(arn);
        target.setRoleArn(ROLE_ARN);
        target.setRedshiftDataParameters(parameters);
        return target;
    }
}
