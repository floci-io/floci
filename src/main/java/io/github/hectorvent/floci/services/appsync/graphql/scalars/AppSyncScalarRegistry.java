package io.github.hectorvent.floci.services.appsync.graphql.scalars;

import graphql.schema.GraphQLScalarType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class AppSyncScalarRegistry {
    private final List<GraphQLScalarType> scalars;

    @Inject
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
    }

    public List<GraphQLScalarType> allScalars() {
        return scalars;
    }

    public Optional<GraphQLScalarType> getScalar(String name) {
        return scalars.stream().filter(s -> s.getName().equals(name)).findFirst();
    }

    public Map<String, GraphQLScalarType> scalarMap() {
        Map<String, GraphQLScalarType> map = new java.util.HashMap<>();
        for (GraphQLScalarType s : scalars) {
            map.put(s.getName(), s);
        }
        return map;
    }
}
