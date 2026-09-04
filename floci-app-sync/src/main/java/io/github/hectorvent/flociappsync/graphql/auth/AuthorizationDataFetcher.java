package io.github.hectorvent.flociappsync.graphql.auth;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapted from Floci's {@code services.appsync.graphql.auth.AuthorizationDataFetcher}:
 * takes {@link IamFieldAuthorizer} instead of {@code IamAuthValidator}, and
 * {@link GraphqlApiAuthConfig} instead of the full {@code GraphqlApi} model — see those
 * classes for why. Logic is otherwise unchanged.
 */
public class AuthorizationDataFetcher implements DataFetcher<Object> {

    private DataFetcher<?> delegate;
    private final String typeName;
    private final String fieldName;
    private final IamFieldAuthorizer iamFieldAuthorizer;

    public AuthorizationDataFetcher(DataFetcher<?> delegate, String typeName, String fieldName,
                                    IamFieldAuthorizer iamFieldAuthorizer) {
        this.delegate = delegate;
        this.typeName = typeName;
        this.fieldName = fieldName;
        this.iamFieldAuthorizer = iamFieldAuthorizer;
    }

    public void setDelegate(DataFetcher<?> delegate) {
        this.delegate = delegate;
    }

    public DataFetcher<?> getDelegate() {
        return delegate;
    }

    @Override
    public Object get(DataFetchingEnvironment environment) throws Exception {
        if (!authorize(environment)) {
            return DataFetcherResult.newResult()
                    .data(null)
                    .error(AppSyncFieldUnauthorizedException.from(environment, fieldName, typeName))
                    .build();
        }
        return delegate == null ? null : delegate.get(environment);
    }

    boolean authorize(DataFetchingEnvironment environment) {
        AppSyncAuthContext auth = environment.getGraphQlContext().get(AppSyncAuthContext.KEY);
        if (auth == null || auth.graphqlApi() == null) {
            return true;
        }
        if (isDeniedField(auth)) {
            return false;
        }
        if (!modeAllowed(environment, auth)) {
            return false;
        }
        if (auth.authenticationType() == AuthenticationType.AWS_IAM && iamFieldAuthorizer != null) {
            String fieldArn = IamFieldAuthorizer.fieldArn(
                    auth.region(), auth.accountId(), auth.graphqlApi().apiId(), typeName, fieldName);
            if (iamFieldAuthorizer.isFieldDenied(auth.callerContext(), auth.accessKeyId(), fieldArn)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDeniedField(AppSyncAuthContext auth) {
        Set<String> denied = auth.deniedFields();
        if (denied.isEmpty()) {
            return false;
        }
        String shortForm = typeName + "." + fieldName;
        if (denied.contains(shortForm)) {
            return true;
        }
        String thisArn = IamFieldAuthorizer.fieldArn(
                auth.region(), auth.accountId(), auth.graphqlApi().apiId(), typeName, fieldName);
        for (String entry : denied) {
            if (thisArn.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private boolean modeAllowed(DataFetchingEnvironment environment, AppSyncAuthContext auth) {
        GraphqlApiAuthConfig api = auth.graphqlApi();
        List<AuthRequirement> requirements = effectiveRequirements(environment, api);
        if (requirements.isEmpty()) {
            return auth.authenticationType() == api.authenticationType();
        }
        for (AuthRequirement requirement : requirements) {
            if (requirement.mode() == auth.authenticationType() && groupsAllowed(requirement, auth)) {
                return true;
            }
        }
        return false;
    }

    private List<AuthRequirement> effectiveRequirements(DataFetchingEnvironment environment, GraphqlApiAuthConfig api) {
        GraphQLFieldDefinition field = environment.getFieldDefinition();
        GraphQLObjectType parent = parentObjectType(environment);
        List<AuthRequirement> fieldReqs = requirementsFrom(field == null ? List.of() : field.getAppliedDirectives(), api);
        if (!fieldReqs.isEmpty()) {
            return fieldReqs;
        }
        return requirementsFrom(parent == null ? List.of() : parent.getAppliedDirectives(), api);
    }

    private static GraphQLObjectType parentObjectType(DataFetchingEnvironment environment) {
        Object parentType = environment.getParentType();
        if (parentType instanceof GraphQLObjectType objectType) {
            return objectType;
        }
        return null;
    }

    static List<AuthRequirement> requirementsFrom(List<GraphQLAppliedDirective> directives, GraphqlApiAuthConfig api) {
        List<AuthRequirement> requirements = new ArrayList<>();
        boolean ignoreAwsAuth = api.hasAdditionalModes();
        for (GraphQLAppliedDirective directive : directives) {
            String name = directive.getName();
            switch (name) {
                case "aws_api_key" -> requirements.add(new AuthRequirement(AuthenticationType.API_KEY, null));
                case "aws_iam" -> requirements.add(new AuthRequirement(AuthenticationType.AWS_IAM, null));
                case "aws_oidc" -> requirements.add(new AuthRequirement(AuthenticationType.OPENID_CONNECT, null));
                case "aws_lambda" -> requirements.add(new AuthRequirement(AuthenticationType.AWS_LAMBDA, null));
                case "aws_cognito_user_pools" -> requirements.add(
                        new AuthRequirement(AuthenticationType.AMAZON_COGNITO_USER_POOLS, groupsArg(directive)));
                case "aws_auth" -> {
                    if (!ignoreAwsAuth && api.authenticationType() == AuthenticationType.AMAZON_COGNITO_USER_POOLS) {
                        requirements.add(new AuthRequirement(
                                AuthenticationType.AMAZON_COGNITO_USER_POOLS, groupsArg(directive)));
                    }
                }
                default -> {
                }
            }
        }
        return requirements;
    }

    @SuppressWarnings("unchecked")
    static List<String> groupsArg(GraphQLAppliedDirective directive) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument("cognito_groups");
        if (arg == null) {
            return List.of();
        }
        Object value = arg.getValue();
        if (value instanceof List<?> list) {
            List<String> groups = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    groups.add(String.valueOf(item));
                }
            }
            return groups;
        }
        return List.of();
    }

    static boolean groupsAllowed(AuthRequirement requirement, AppSyncAuthContext auth) {
        if (requirement.groups() == null || requirement.groups().isEmpty()) {
            return true;
        }
        if (auth.authenticationType() != AuthenticationType.AMAZON_COGNITO_USER_POOLS) {
            return true;
        }
        Object groups = auth.identity() == null ? null : auth.identity().get("groups");
        if (!(groups instanceof List<?> callerGroups)) {
            return false;
        }
        for (String required : requirement.groups()) {
            for (Object caller : callerGroups) {
                if (required.equals(String.valueOf(caller))) {
                    return true;
                }
            }
        }
        return false;
    }

    record AuthRequirement(AuthenticationType mode, List<String> groups) {
    }
}
