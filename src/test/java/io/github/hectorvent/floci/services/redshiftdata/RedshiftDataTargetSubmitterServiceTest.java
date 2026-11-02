package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedshiftDataTargetSubmitterServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/TargetRole";
    private static final String CLUSTER = "arn:aws:redshift:us-east-1:000000000000:cluster:analytics";
    private static final String WORKGROUP =
            "arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/0123-4567";
    private static final UnaryOperator<String> AS_IS = value -> value;

    private RedshiftDataService dataService;
    private RedshiftDataTargetSubmitter submitter;

    @BeforeEach
    void setUp() {
        dataService = mock(RedshiftDataService.class);
        submitter = new RedshiftDataTargetSubmitter(dataService, new ObjectMapper());
    }

    @Test
    void aSingleStatementRunsAsExecuteStatementOnTheNamedCluster() {
        RedshiftDataTargetSubmitter.Statement statement = new RedshiftDataTargetSubmitter.Statement(
                "dev", "admin", null, "INSERT INTO t VALUES (1)", null, "load", true);

        submitter.submit(CLUSTER, statement, ROLE, ACCOUNT, REGION, AS_IS, permissions -> Optional.empty());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.captor();
        verify(dataService).executeStatement(request.capture(), eq(REGION), eq(ROLE));
        assertEquals("analytics", request.getValue().path("ClusterIdentifier").asText());
        assertEquals("dev", request.getValue().path("Database").asText());
        assertEquals("admin", request.getValue().path("DbUser").asText());
        assertEquals("INSERT INTO t VALUES (1)", request.getValue().path("Sql").asText());
        assertEquals("load", request.getValue().path("StatementName").asText());
        assertTrue(request.getValue().path("WithEvent").asBoolean());
    }

    @Test
    void sqlsRunAsOneBatchOnAServerlessWorkgroup() {
        RedshiftDataTargetSubmitter.Statement statement = new RedshiftDataTargetSubmitter.Statement(
                "dev", null, null, null, List.of("INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)"), null, null);

        submitter.submit(WORKGROUP, statement, ROLE, ACCOUNT, REGION, AS_IS, permissions -> Optional.empty());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.captor();
        verify(dataService).batchExecuteStatement(request.capture(), eq(REGION), eq(ROLE));
        assertEquals(WORKGROUP, request.getValue().path("WorkgroupName").asText());
        assertEquals(2, request.getValue().path("Sqls").size());
        verify(dataService, never()).executeStatement(any(), any(), any());
    }

    @Test
    void aRefusedRoleIsAccessDeniedAndNothingIsSubmitted() {
        RedshiftDataTargetSubmitter.Statement statement = new RedshiftDataTargetSubmitter.Statement(
                "dev", null, null, null, List.of("SELECT 1"), null, null);

        AwsException denied = assertThrows(AwsException.class, () -> submitter.submit(WORKGROUP, statement, ROLE,
                ACCOUNT, REGION, AS_IS, permissions -> Optional.of("not trusted")));

        assertEquals("AccessDeniedException", denied.getErrorCode());
        verify(dataService, never()).batchExecuteStatement(any(), any(), any());
    }

    @Test
    void theRoleIsCheckedBeforeADynamicPathInTheSqlIsResolved() {
        RedshiftDataTargetSubmitter.Statement statement = new RedshiftDataTargetSubmitter.Statement(
                "dev", null, null, "$.missing", null, null, null);
        UnaryOperator<String> failing = value -> {
            if ("$.missing".equals(value)) {
                throw new AwsException("ValidationException", "Cannot resolve", 400);
            }
            return value;
        };

        AwsException denied = assertThrows(AwsException.class, () -> submitter.submit(CLUSTER, statement, ROLE,
                ACCOUNT, REGION, failing, permissions -> Optional.of("not trusted")));

        assertEquals("AccessDeniedException", denied.getErrorCode());
    }

    @Test
    void aTargetThatIsNeitherAClusterNorAWorkgroupIsNotSubmitted() {
        RedshiftDataTargetSubmitter.Statement statement = new RedshiftDataTargetSubmitter.Statement(
                "dev", null, null, "SELECT 1", null, null, null);

        submitter.submit("arn:aws:redshift:us-east-1:000000000000:snapshot:s1", statement, ROLE, ACCOUNT, REGION,
                AS_IS, permissions -> Optional.empty());

        verify(dataService, never()).executeStatement(any(), any(), any());
        verify(dataService, never()).batchExecuteStatement(any(), any(), any());
    }

    @Test
    void permissionsFollowTheCredentialsTheStatementUses() {
        AtomicReference<List<Permission>> asked = new AtomicReference<>();
        RedshiftDataTargetSubmitter.RoleCheck roleCheck = permissions -> {
            asked.set(permissions);
            return Optional.empty();
        };

        submitter.submit(CLUSTER, new RedshiftDataTargetSubmitter.Statement(
                "dev", "admin", null, "SELECT 1", null, null, null), ROLE, ACCOUNT, REGION, AS_IS, roleCheck);
        assertEquals(List.of("redshift-data:ExecuteStatement", "redshift:GetClusterCredentials",
                "redshift:GetClusterCredentials"), asked.get().stream().map(Permission::action).toList());

        String secret = "arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf";
        submitter.submit(CLUSTER, new RedshiftDataTargetSubmitter.Statement(
                "dev", null, secret, null, List.of("SELECT 1"), null, null), ROLE, ACCOUNT, REGION, AS_IS, roleCheck);
        assertEquals(List.of("redshift-data:BatchExecuteStatement", "secretsmanager:GetSecretValue"),
                asked.get().stream().map(Permission::action).toList());

        submitter.submit(WORKGROUP, new RedshiftDataTargetSubmitter.Statement(
                "dev", null, null, "SELECT 1", null, null, null), ROLE, ACCOUNT, REGION, AS_IS, roleCheck);
        assertEquals(List.of("redshift-data:ExecuteStatement", "redshift-serverless:GetCredentials"),
                asked.get().stream().map(Permission::action).toList());
    }
}
