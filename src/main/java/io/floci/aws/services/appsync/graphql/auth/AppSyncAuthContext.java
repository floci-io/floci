package io.floci.aws.services.appsync.graphql.auth;

import io.floci.aws.services.appsync.model.AuthenticationType;
import io.floci.aws.services.appsync.model.GraphqlApi;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record AppSyncAuthContext(
        Map<String, Object> identity,
        String authType,
        AuthenticationType authenticationType,
        Set<String> deniedFields,
        GraphqlApi graphqlApi,
        String accessKeyId,
        String region,
        String accountId
) {
    public static final String KEY = "appsyncAuthContext";

    public AppSyncAuthContext {
        deniedFields = deniedFields == null ? Set.of() : Set.copyOf(deniedFields);
    }

    public List<String> deniedFieldsList() {
        return List.copyOf(deniedFields);
    }
}
