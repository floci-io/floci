package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedField;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverExecutor;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
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
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            api = appSync.createGraphqlApi(Map.of("name", "iam-toggle-" + UUID.randomUUID(),
                    "authenticationType", "AWS_IAM"), REGION);
            schemas.register(api.getApiId(), "type Query { unused: String } type Mutation { publish: String }");
        });
        when(sidecar.plan(any(), any(), any(), any())).thenReturn(new PlanResult("MUTATION",
                List.of(new PlannedField("Mutation", "publish", List.of(), List.of()))));
        when(resolvers.findResolver(any(), any(), any())).thenReturn(new Resolver());
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
        Target target = new Target("appsync", "arn:aws:appsync:" + REGION + ":" + ACCOUNT
                + ":endpoints/graphql-api/" + api.getApiId(), null, null);
        target.setRoleArn(roleArn);
        target.setAppSyncParameters(new Target.AppSyncParameters("mutation { publish }"));
        invoker.invoke(target, "{}", RULE, ACCOUNT);
        verify(sidecar).execute(any(), any(), any(), any(), any(), any(), any());
    }
}
