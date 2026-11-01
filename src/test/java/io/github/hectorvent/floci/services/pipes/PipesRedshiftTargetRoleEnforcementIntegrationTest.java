package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * With IAM enforcement on, a pipe's Redshift target runs only when the pipe's role trusts
 * pipes.amazonaws.com and may call the Data API and authenticate to the cluster.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PipesRedshiftTargetRoleEnforcementIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER_ARN = "arn:aws:redshift:us-east-1:" + ACCOUNT + ":cluster:warehouse";
    private static final String PIPES_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"pipes.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String EVENTS_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String DATA_API_AND_CREDENTIALS = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"redshift-data:BatchExecuteStatement","Resource":"%s"},
              {"Effect":"Allow","Action":"redshift:GetClusterCredentials","Resource":"*"}]}""".formatted(CLUSTER_ARN);

    @Inject
    PipesTargetInvoker invoker;

    @Inject
    IamService iamService;

    @InjectMock
    RedshiftDataService redshiftDataService;

    @BeforeEach
    void setUp() {
        clearInvocations(redshiftDataService);
        when(redshiftDataService.batchExecuteStatement(any(), any(), any()))
                .thenReturn(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void roleThatTrustsPipesAndHoldsThePermissionsRunsTheBatch() throws Exception {
        String roleArn = role("pipes-redshift-allowed", PIPES_TRUST, DATA_API_AND_CREDENTIALS);

        invoker.invoke(pipe("allowed", roleArn), "{\"messageId\":\"m1\"}", "us-east-1");

        verify(redshiftDataService).batchExecuteStatement(any(JsonNode.class), eq("us-east-1"), eq(roleArn));
    }

    @Test
    void roleThatDoesNotTrustPipesIsRefused() throws Exception {
        String roleArn = role("pipes-redshift-untrusted", EVENTS_TRUST, DATA_API_AND_CREDENTIALS);

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invoke(pipe("untrusted", roleArn), "{\"messageId\":\"m1\"}", "us-east-1"));

        assertEquals("AccessDeniedException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("does not trust pipes.amazonaws.com"), failure.getMessage());
        verify(redshiftDataService, never()).batchExecuteStatement(any(), any(), any());
    }

    @Test
    void roleWithoutTheBatchPermissionIsRefused() throws Exception {
        String roleArn = role("pipes-redshift-no-batch", PIPES_TRUST, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"redshift-data:ExecuteStatement","Resource":"*"},
                  {"Effect":"Allow","Action":"redshift:GetClusterCredentials","Resource":"*"}]}""");

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invoke(pipe("no-batch", roleArn), "{\"messageId\":\"m1\"}", "us-east-1"));

        assertEquals(403, failure.getHttpStatus());
        assertTrue(failure.getMessage().contains("redshift-data:BatchExecuteStatement"), failure.getMessage());
        verify(redshiftDataService, never()).batchExecuteStatement(any(), any(), any());
    }

    @Test
    void trustConditionOnThePipeArnIsMatched() throws Exception {
        Pipe matching = pipe("source-arn", null);
        String roleArn = role("pipes-redshift-source-arn", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"pipes.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}""".formatted(matching.getArn()),
                DATA_API_AND_CREDENTIALS);
        matching.setRoleArn(roleArn);

        invoker.invoke(matching, "{\"messageId\":\"m1\"}", "us-east-1");
        verify(redshiftDataService).batchExecuteStatement(any(JsonNode.class), eq("us-east-1"), eq(roleArn));

        clearInvocations(redshiftDataService);
        Pipe other = pipe("other-pipe", roleArn);
        assertThrows(AwsException.class, () -> invoker.invoke(other, "{\"messageId\":\"m2\"}", "us-east-1"));
        verify(redshiftDataService, never()).batchExecuteStatement(any(), any(), any());
    }

    private String role(String name, String trust, String policy) {
        String arn = iamService.createRole(name, "/", trust, null, 3600, Map.of()).getArn();
        iamService.putRolePolicy(name, "redshift", policy);
        return arn;
    }

    private static Pipe pipe(String name, String roleArn) throws Exception {
        Pipe pipe = new Pipe();
        pipe.setName(name);
        pipe.setArn("arn:aws:pipes:us-east-1:" + ACCOUNT + ":pipe/" + name);
        pipe.setAccountId(ACCOUNT);
        pipe.setSource("arn:aws:sqs:us-east-1:" + ACCOUNT + ":source");
        pipe.setTarget(CLUSTER_ARN);
        pipe.setRoleArn(roleArn);
        pipe.setDesiredState(DesiredState.RUNNING);
        pipe.setTargetParameters(new ObjectMapper().readTree("""
                {"RedshiftDataParameters":{"Database":"dev","DbUser":"admin","Sqls":["select 1"]}}"""));
        return pipe;
    }
}
