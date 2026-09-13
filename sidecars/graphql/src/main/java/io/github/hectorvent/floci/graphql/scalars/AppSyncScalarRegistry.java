package io.github.hectorvent.floci.graphql.scalars;

import graphql.schema.GraphQLScalarType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** No CDI here: this sidecar has no DI container, the server just calls {@code new}. */
public class AppSyncScalarRegistry {
    private final List<GraphQLScalarType> scalars;
    private final Map<String, GraphQLScalarType> scalarMap;

    public AppSyncScalarRegistry() {
        this.scalars = List.of(
            AppSyncScalars.AWSJSON,
            AppSyncScalars.AWS_DATE_TIME,
            AppSyncScalars.AWS_DATE,
            AppSyncScalars.AWS_TIME,
            AppSyncScalars.AWS_TIMESTAMP,
            AppSyncScalars.AWS_EMAIL,
            AppSyncScalars.AWS_URL,
            AppSyncScalars.AWS_PHONE,
            AppSyncScalars.AWS_IP_ADDRESS,
            AppSyncScalars.AWS_BOOLEAN,
            AppSyncScalars.AWS_LONG,
            AppSyncScalars.AWS_INTEGER,
            AppSyncScalars.AWS_SHORT,
            AppSyncScalars.AWS_FLOAT,
            AppSyncScalars.AWS_BIG_DECIMAL,
            AppSyncScalars.AWS_BIG_INT,
            AppSyncScalars.AWS_BYTE
        );
        this.scalarMap = scalars.stream()
            .collect(Collectors.toUnmodifiableMap(GraphQLScalarType::getName, s -> s));
    }

    public List<GraphQLScalarType> allScalars() {
        return scalars;
    }

    public Optional<GraphQLScalarType> getScalar(String name) {
        return Optional.ofNullable(scalarMap.get(name));
    }

    public Map<String, GraphQLScalarType> scalarMap() {
        return scalarMap;
    }
}
