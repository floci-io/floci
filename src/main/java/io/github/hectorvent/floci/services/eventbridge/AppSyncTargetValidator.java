package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/** Checks an endpoint target's document before any targets in the request are stored. */
@ApplicationScoped
public class AppSyncTargetValidator {

    // Planning parses the document without executing it. A placeholder schema deliberately
    // avoids requiring an existing API or checking its fields during PutTargets.
    private static final String SDL = "type Query { _eventBridge: Boolean } type Mutation { _eventBridge: Boolean }";
    private final GraphqlSidecarClient sidecar;

    @Inject
    public AppSyncTargetValidator(GraphqlSidecarClient sidecar) {
        this.sidecar = sidecar;
    }

    public void validate(Target target) {
        if (!AwsArnUtils.isArnFor(target.getArn(), "appsync")
                || !AwsArnUtils.parse(target.getArn()).resource().startsWith("endpoints/graphql-api/")) {
            return;
        }
        String operation = target.getAppSyncParameters() == null
                ? null : target.getAppSyncParameters().graphQLOperation();
        if (operation == null) {
            throw invalidOperation();
        }
        try {
            // No operationName: the sidecar rejects documents with multiple operations.
            if (!"MUTATION".equals(sidecar.plan(SDL, Map.of(), operation, null).operationType())) {
                throw invalidOperation();
            }
        } catch (AppSyncTransportException e) {
            if (e.getHttpStatus() == 400) {
                throw invalidOperation();
            }
            throw new AwsException("InternalFailure", "Unable to validate the AppSync target operation.", 500);
        }
    }

    private static AwsException invalidOperation() {
        return new AwsException("ValidationException",
                "AppSyncParameters.GraphQLOperation must contain exactly one mutation operation.", 400);
    }
}
