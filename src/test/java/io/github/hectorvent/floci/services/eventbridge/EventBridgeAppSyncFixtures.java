package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

final class EventBridgeAppSyncFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventBridgeAppSyncFixtures() {}

    static GraphqlApi createApi(AppSyncService appSync, SchemaRegistry schemas,
                                String account, String region, String schema) {
        return RequestScopes.callAs(account, region, () -> {
            GraphqlApi api = appSync.createGraphqlApi(Map.of("name", "target-" + UUID.randomUUID(),
                    "authenticationType", "AWS_IAM"), region);
            schemas.register(api.getApiId(), schema);
            return api;
        });
    }

    static void stubMutation(GraphqlSidecarClient sidecar, AppSyncResolverExecutor resolvers, PlannedField... fields) {
        when(sidecar.plan(any(), any(), any(), any()))
                .thenReturn(new PlanResult("MUTATION", List.of(fields)));
        when(resolvers.findResolver(any(), any(), any())).thenReturn(new Resolver());
    }

    static Target target(GraphqlApi api, String account, String region, String roleArn, String operation) {
        Target target = new Target("appsync", "arn:aws:appsync:" + region + ":" + account
                + ":endpoints/graphql-api/" + api.getApiId(), null, null);
        target.setRoleArn(roleArn);
        target.setAppSyncParameters(new Target.AppSyncParameters(operation));
        return target;
    }

    static String policy(String effect, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect
                + "\",\"Action\":\"appsync:GraphQL\",\"Resource\":\"" + resource + "\"}]}";
    }

    static String trust(String service, String sourceArn, String account) {
        ObjectNode statement = MAPPER.createObjectNode().put("Effect", "Allow").put("Action", "sts:AssumeRole");
        statement.putObject("Principal").put("Service", service + ".amazonaws.com");
        statement.putObject("Condition").putObject("StringEquals").put("aws:SourceAccount", account);
        if (sourceArn != null) {
            ((ObjectNode) statement.path("Condition")).putObject("ArnEquals").put("aws:SourceArn", sourceArn);
        }
        ObjectNode document = MAPPER.createObjectNode().put("Version", "2012-10-17");
        document.putArray("Statement").add(statement);
        return document.toString();
    }
}
