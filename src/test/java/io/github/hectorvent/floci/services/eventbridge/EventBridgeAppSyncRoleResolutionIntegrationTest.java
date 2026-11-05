package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedField;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverExecutor;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Inject
    AppSyncService appSync;
    @Inject
    SchemaRegistry schemas;
    @InjectMock
    GraphqlSidecarClient sidecar;
    @InjectMock
    AppSyncResolverExecutor resolvers;

    private GraphqlApi api;

    @BeforeEach
    void createApi() {
        reset(sidecar, resolvers);
        api = EventBridgeAppSyncFixtures.createApi(appSync, schemas, ACCOUNT, REGION,
                "type Query { unused: String } type Mutation { publish: String }");
        EventBridgeAppSyncFixtures.stubMutation(sidecar, resolvers,
                new PlannedField("Mutation", "publish", List.of(), List.of()));
        when(sidecar.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("data", Map.of("publish", "delivered")));
    }

    @AfterEach
    void deleteApi() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> appSync.deleteGraphqlApi(api.getApiId()));
        reset(sidecar, resolvers);
    }

    @Test
    void aNonexistentRoleDoesNotBlockDeliveryWithIamEnforcementDisabled() {
        assertFalse(config.services().iam().enforcementEnabled());
        assertDelivered("arn:aws:iam::" + ACCOUNT + ":role/missing-" + UUID.randomUUID());
    }

    @Test
    void roleTrustDoesNotBlockDeliveryWithIamEnforcementDisabled() {
        assertFalse(config.services().iam().enforcementEnabled());
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            String name = "appsync-untrusted-" + UUID.randomUUID();
            String roleArn = iam.createRole(name, "/", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                    "Principal":{"Service":"lambda.amazonaws.com"}}]}""", null, 3600, Map.of()).getArn();
            try {
                assertDelivered(roleArn);
            } finally {
                iam.deleteRole(name);
            }
        });
    }

    private void assertDelivered(String roleArn) {
        Target target = EventBridgeAppSyncFixtures.target(api, ACCOUNT, REGION, roleArn, "mutation { publish }");
        invoker.invoke(target, "{}", RULE, ACCOUNT);
        verify(sidecar).execute(any(), any(), any(), any(), any(), any(), any());
    }
}
