package io.github.hectorvent.floci.services.appsync.graphql.auth;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;

import java.util.List;
import java.util.Map;

public class AppSyncFieldUnauthorizedException extends RuntimeException implements GraphQLError {

    private final List<Object> path;

    public AppSyncFieldUnauthorizedException(List<Object> path) {
        super(AppSyncAuth.UNAUTHORIZED_MESSAGE);
        this.path = path == null ? List.of() : List.copyOf(path);
    }

    @Override
    public List<Object> getPath() {
        return path;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorClassification getErrorType() {
        return new ErrorClassification() {
            @Override
            public String toString() {
                return AppSyncAuth.UNAUTHORIZED_TYPE;
            }
        };
    }

    @Override
    public Map<String, Object> getExtensions() {
        return null;
    }

    public static AppSyncFieldUnauthorizedException from(DataFetchingEnvironment environment) {
        if (environment == null
                || environment.getExecutionStepInfo() == null
                || environment.getExecutionStepInfo().getPath() == null) {
            return new AppSyncFieldUnauthorizedException(List.of());
        }
        return new AppSyncFieldUnauthorizedException(environment.getExecutionStepInfo().getPath().toList());
    }
}
