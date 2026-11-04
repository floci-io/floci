package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EventBridgeAppSyncRoleResolutionIntegrationTest {

    private static final String ACCOUNT = "725018800006";
    private static final String REGION = "us-east-1";
    private static final String RULE = "arn:aws:events:" + REGION + ":" + ACCOUNT + ":rule/publish";

    @Inject
    AppSyncTargetInvoker invoker;
    @Inject
    IamService iam;
    @Inject
    EmulatorConfig config;

    @Test
    void aNonexistentRoleIsRejectedEvenWithIamEnforcementDisabled() {
        assertFalse(config.services().iam().enforcementEnabled());
        assertDenied("arn:aws:iam::" + ACCOUNT + ":role/missing-" + UUID.randomUUID());
    }

    @Test
    void aRoleThatDoesNotTrustEventBridgeIsRejectedWithIamEnforcementDisabled() {
        assertFalse(config.services().iam().enforcementEnabled());
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            String name = "appsync-untrusted-" + UUID.randomUUID();
            String roleArn = iam.createRole(name, "/", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                    "Principal":{"Service":"lambda.amazonaws.com"}}]}""", null, 3600, Map.of()).getArn();
            try {
                assertDenied(roleArn);
            } finally {
                iam.deleteRole(name);
            }
        });
    }

    private void assertDenied(String roleArn) {
        Target target = new Target("appsync", "arn:aws:appsync:" + REGION + ":" + ACCOUNT
                + ":endpoints/graphql-api/example", null, null);
        target.setRoleArn(roleArn);
        target.setAppSyncParameters(new Target.AppSyncParameters("mutation { publish }"));
        AwsException error = assertThrows(AwsException.class,
                () -> invoker.invoke(target, "{}", RULE, ACCOUNT));
        assertEquals("AccessDeniedException", error.getErrorCode());
        assertEquals(403, error.getHttpStatus());
    }
}
