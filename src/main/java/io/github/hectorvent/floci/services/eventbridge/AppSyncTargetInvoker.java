package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncExecutionController;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuth;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthContext;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthMiddleware;
import io.github.hectorvent.floci.services.appsync.graphql.auth.IamAuthValidator;
import io.github.hectorvent.floci.services.appsync.graphql.auth.IdentityBuilder;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Delivers to an AppSync API through its execution engine, never through a caller-supplied URL. */
@ApplicationScoped
public class AppSyncTargetInvoker {

    private static final String EVENTS = ServicePrincipals.of("events");
    private final AppSyncService appSync;
    private final AppSyncExecutionController execution;
    private final ServiceRoleAuthorizer authorizer;
    private final ServiceRegistry registry;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AppSyncTargetInvoker(AppSyncService appSync, AppSyncExecutionController execution,
                               ServiceRoleAuthorizer authorizer, ServiceRegistry registry,
                               RegionResolver regionResolver, ObjectMapper mapper) {
        this.appSync = appSync;
        this.execution = execution;
        this.authorizer = authorizer;
        this.registry = registry;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    public void invoke(Target target, String payload, String ruleArn, String accountId) {
        if (!registry.isServiceEnabled("appsync")) {
            throw new AwsException("ServiceNotAvailableException", "Service appsync is not enabled.", 400);
        }
        AwsArnUtils.Arn endpoint = endpoint(target.getArn());
        if (!endpoint.accountId().equals(accountId)) {
            throw new AwsException("AccessDeniedException", "Cross-account AppSync targets are not supported.", 403);
        }
        if (ruleArn != null) {
            if (!AwsArnUtils.isArn(ruleArn)) {
                throw new AwsException("AccessDeniedException", "Invalid AppSync target source rule.", 403);
            }
            AwsArnUtils.Arn source = AwsArnUtils.parse(ruleArn);
            if (!endpoint.partition().equals(source.partition()) || !accountId.equals(source.accountId())) {
                throw new AwsException("AccessDeniedException", "The AppSync target must share the rule's account and partition.", 403);
            }
        }
        Target.AppSyncParameters parameters = target.getAppSyncParameters();
        String operation = parameters == null ? null : parameters.graphQLOperation();
        if (operation == null || operation.isBlank() || operation.codePointCount(0, operation.length()) > 1048576) {
            throw new AwsException("ValidationException", "An AppSync target requires GraphQLOperation of length 1-1048576.", 400);
        }
        Map<String, Object> variables = variables(payload);
        RequestScopes.runAs(accountId, endpoint.region(), () -> {
            String apiId = endpoint.resource().substring("endpoints/graphql-api/".length());
            GraphqlApi api = appSync.getGraphqlApi(apiId);
            String expected = new AwsArnUtils.Arn(endpoint.partition(), "appsync", endpoint.region(), accountId,
                    "apis/" + apiId).toString();
            if (!expected.equals(api.getArn())) {
                throw new AwsException("NotFoundException", "GraphQL API not found in the target region.", 404);
            }
            boolean iamConfigured = AuthMiddleware.configuredModes(api).contains(AuthenticationType.AWS_IAM);
            if (!iamConfigured || !"GLOBAL".equals(api.getVisibility())) {
                throw new AwsException("AccessDeniedException", "AppSync targets require a public API with AWS_IAM authorization.", 403);
            }
            authorizer.refusal(target.getRoleArn(), accountId, EVENTS, ruleArn, List.of()).ifPresent(reason -> {
                throw new AwsException("AccessDeniedException", reason, 403);
            });
            String roleName = target.getRoleArn() == null ? null
                    : target.getRoleArn().substring(target.getRoleArn().lastIndexOf('/') + 1);
            String callerArn = roleName == null ? null : AwsArnUtils.Arn.global(endpoint.partition(), "sts", accountId,
                    "assumed-role/" + roleName + "/EventBridge").toString();
            AppSyncAuthContext auth = new AppSyncAuthContext(
                    IdentityBuilder.iam(accountId, null, roleName, callerArn, List.of()),
                    AppSyncAuth.AUTH_TYPE_IAM, AuthenticationType.AWS_IAM, Set.of(), api, null, endpoint.region(), accountId);
            try {
                Map<String, Object> result = execution.executeServiceMutation(apiId, operation, variables, auth,
                        field -> authorizer.refusal(
                        target.getRoleArn(), accountId, EVENTS, ruleArn, List.of(new Permission("appsync:GraphQL",
                                IamAuthValidator.fieldArn(endpoint.region(), accountId, apiId,
                                        field.typeName(), field.fieldName())))).isEmpty());
                // GraphQL request errors omit data; execution results include it even when null
                // (spec section 7.1.3). Never retry a returned execution result after partial mutations.
                if (!result.containsKey("data")) {
                    if (result.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
                        throw new AwsException("ValidationException", "AppSync rejected the mutation before execution.", 400);
                    }
                    throw new AwsException("InternalFailure", "Invalid AppSync execution response.", 502);
                }
            } catch (AppSyncTransportException e) {
                throw new AwsException(e.getErrorType(), e.getMessage(), e.getHttpStatus());
            }
        });
    }

    private AwsArnUtils.Arn endpoint(String value) {
        if (!AwsArnUtils.isArn(value)) {
            throw new AwsException("ValidationException", "Invalid AppSync GraphQL endpoint ARN.", 400);
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        if (!"appsync".equals(arn.service()) || !arn.accountId().matches("[0-9]{12}")
                || !RegionResolver.isKnownRegion(arn.region()) || !regionResolver.partitionForRegion(arn.region()).equals(arn.partition())
                || !arn.resource().matches("endpoints/graphql-api/[A-Za-z0-9_-]+")) {
            throw new AwsException("ValidationException", "Invalid AppSync GraphQL endpoint ARN.", 400);
        }
        return arn;
    }

    private Map<String, Object> variables(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new AwsException("ValidationException", "AppSync target input must be a JSON object of variables.", 400);
            }
            return mapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", "AppSync target input must be valid JSON.", 400);
        }
    }
}
