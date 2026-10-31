package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridge.model.RedshiftDataParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
 * With IAM enforcement on, an EventBridge Redshift target runs only when its role trusts
 * events.amazonaws.com and may call the Data API and authenticate to the cluster.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EventBridgeRedshiftTargetRoleEnforcementIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER_ARN = "arn:aws:redshift:us-east-1:" + ACCOUNT + ":cluster:warehouse";
    private static final String EVENTS_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String LAMBDA_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String DATA_API_AND_CREDENTIALS = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"redshift-data:ExecuteStatement","Resource":"%s"},
              {"Effect":"Allow","Action":"redshift:GetClusterCredentials","Resource":"*"}]}""".formatted(CLUSTER_ARN);

    @Inject
    EventBridgeInvoker invoker;

    @Inject
    TargetDispatcher dispatcher;

    @Inject
    IamService iamService;

    @InjectMock
    RedshiftDataService redshiftDataService;

    @BeforeEach
    void setUp() {
        clearInvocations(redshiftDataService);
        when(redshiftDataService.executeStatement(any(), any(), any()))
                .thenReturn(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void roleThatTrustsEventBridgeAndHoldsThePermissionsRunsTheStatement() {
        String roleArn = role("events-redshift-allowed", EVENTS_TRUST, DATA_API_AND_CREDENTIALS);

        invoker.invokeTarget(target(roleArn), "{\"id\":\"event-1\"}", "us-east-1");

        verify(redshiftDataService).executeStatement(any(JsonNode.class), eq("us-east-1"), eq(roleArn));
    }

    @Test
    void roleThatDoesNotTrustEventBridgeIsRefused() {
        String roleArn = role("events-redshift-untrusted", LAMBDA_TRUST, DATA_API_AND_CREDENTIALS);

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target(roleArn), "{\"id\":\"event-1\"}", "us-east-1"));

        assertEquals("AccessDeniedException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("does not trust events.amazonaws.com"), failure.getMessage());
        verify(redshiftDataService, never()).executeStatement(any(), any(), any());
    }

    @Test
    void roleWithoutTheCredentialsPermissionIsRefused() {
        String roleArn = role("events-redshift-no-credentials", EVENTS_TRUST, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"redshift-data:ExecuteStatement","Resource":"*"}]}""");

        AwsException failure = assertThrows(AwsException.class,
                () -> invoker.invokeTarget(target(roleArn), "{\"id\":\"event-1\"}", "us-east-1"));

        assertEquals(403, failure.getHttpStatus());
        assertTrue(failure.getMessage().contains("redshift:GetClusterCredentials"), failure.getMessage());
        verify(redshiftDataService, never()).executeStatement(any(), any(), any());
    }

    @Test
    void trustConditionOnTheRuleArnIsMatchedForADeliveryThroughTheDispatcher() {
        String ruleArn = "arn:aws:events:us-east-1:" + ACCOUNT + ":rule/refresh";
        String roleArn = role("events-redshift-source-arn", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}""".formatted(ruleArn),
                DATA_API_AND_CREDENTIALS);
        Target target = target(roleArn);

        dispatcher.dispatch(ruleArn, target, "{\"id\":\"event-1\"}", "us-east-1", () -> List.of(target));
        verify(redshiftDataService).executeStatement(any(JsonNode.class), eq("us-east-1"), eq(roleArn));

        clearInvocations(redshiftDataService);
        dispatcher.dispatch("arn:aws:events:us-east-1:" + ACCOUNT + ":rule/other", target,
                "{\"id\":\"event-2\"}", "us-east-1", () -> List.of(target));
        verify(redshiftDataService, never()).executeStatement(any(), any(), any());
    }

    private String role(String name, String trust, String policy) {
        String arn = iamService.createRole(name, "/", trust, null, 3600, Map.of()).getArn();
        iamService.putRolePolicy(name, "redshift", policy);
        return arn;
    }

    private static Target target(String roleArn) {
        RedshiftDataParameters parameters = new RedshiftDataParameters();
        parameters.setDatabase("dev");
        parameters.setDbUser("admin");
        parameters.setSql("select 1");
        Target target = new Target();
        target.setId("redshift");
        target.setArn(CLUSTER_ARN);
        target.setRoleArn(roleArn);
        target.setRedshiftDataParameters(parameters);
        return target;
    }
}
