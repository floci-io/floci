package io.github.hectorvent.floci.services.appsync.graphql.auth;

import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncErrorFormatter;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncSchemaParser;
import io.github.hectorvent.floci.services.appsync.graphql.QueryExecutor;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import io.github.hectorvent.floci.services.appsync.model.AdditionalAuthenticationProvider;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationDataFetcherTest {

    private AppSyncSchemaParser parser;
    private AuthFieldWrapper wrapper;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() {
        parser = new AppSyncSchemaParser(new AppSyncScalarRegistry());
        wrapper = new AuthFieldWrapper(null);
        executor = new QueryExecutor(new AppSyncErrorFormatter());
    }

    @Test
    void iamCallerOnApiKeyFieldReturnsUnauthorizedError() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String @aws_api_key }"));
        GraphqlApi api = api(AuthenticationType.AWS_IAM);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.API_KEY);
        api.setAdditionalAuthenticationProviders(List.of(extra));

        Map<String, Object> response = execute(schema, "{ hello }", iamContext(api));

        assertEquals(null, dataHello(response));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) response.get("errors")).get(0);
        assertEquals("Unauthorized", error.get("errorType"));
        assertEquals("Not Authorized to access hello on type Query", error.get("message"));
        assertEquals(List.of("hello"), error.get("path"));
        assertNull(error.get("errorInfo"));
    }

    @Test
    void unmarkedFieldRequiresDefaultMode() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String }"));
        GraphqlApi api = api(AuthenticationType.API_KEY);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.AWS_IAM);
        api.setAdditionalAuthenticationProviders(List.of(extra));

        Map<String, Object> response = execute(schema, "{ hello }", iamContext(api));
        assertEquals("Unauthorized", firstErrorType(response));
    }

    @Test
    void orDirectivesAllowApiKey() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String @aws_api_key @aws_iam }"));
        GraphqlApi api = api(AuthenticationType.API_KEY);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.AWS_IAM);
        api.setAdditionalAuthenticationProviders(List.of(extra));

        Map<String, Object> response = execute(schema, "{ hello }", apiKeyContext(api));
        assertNull(response.get("errors"));
        assertTrue(((Map<?, ?>) response.get("data")).containsKey("hello"));
    }

    @Test
    void fieldOverridesType() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("""
                type Query @aws_api_key {
                  hello: String @aws_iam
                }
                """));
        GraphqlApi api = api(AuthenticationType.API_KEY);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.AWS_IAM);
        api.setAdditionalAuthenticationProviders(List.of(extra));

        assertEquals("Unauthorized", firstErrorType(execute(schema, "{ hello }", apiKeyContext(api))));
        assertNull(execute(schema, "{ hello }", iamContext(api)).get("errors"));
    }

    @Test
    void typeLevelGrantsField() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("""
                type Query @aws_iam {
                  hello: String
                }
                """));
        GraphqlApi api = api(AuthenticationType.AWS_IAM);
        Map<String, Object> response = execute(schema, "{ hello }", iamContext(api));
        assertNull(response.get("errors"));
    }

    @Test
    void awsAuthIgnoredWhenAdditionalModesExist() {
        GraphQLSchema schema = wrapper.wrap(parser.parse(
                "type Query { secret: String @aws_auth(cognito_groups: [\"admin\"]) }"));
        GraphqlApi api = api(AuthenticationType.AMAZON_COGNITO_USER_POOLS);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.API_KEY);
        api.setAdditionalAuthenticationProviders(List.of(extra));
        Map<String, Object> identity = IdentityBuilder.cognito(
                Map.of("sub", "s", "iss", "https://iss"), List.of(), "ALLOW");
        AppSyncAuthContext ctx = new AppSyncAuthContext(
                identity, AppSyncAuth.AUTH_TYPE_COGNITO, AuthenticationType.AMAZON_COGNITO_USER_POOLS,
                Set.of(), api, null, "us-east-1", "000000000000");
        Map<String, Object> response = execute(schema, "{ secret }", ctx);
        assertNull(response.get("errors"));
    }

    @Test
    void awsAuthEnforcedWhenCognitoSole() {
        GraphQLSchema schema = wrapper.wrap(parser.parse(
                "type Query { secret: String @aws_auth(cognito_groups: [\"admin\"]) }"));
        GraphqlApi api = api(AuthenticationType.AMAZON_COGNITO_USER_POOLS);
        Map<String, Object> identity = IdentityBuilder.cognito(
                Map.of("sub", "s", "iss", "https://iss", "cognito:groups", List.of("users")), List.of(), "ALLOW");
        AppSyncAuthContext ctx = new AppSyncAuthContext(
                identity, AppSyncAuth.AUTH_TYPE_COGNITO, AuthenticationType.AMAZON_COGNITO_USER_POOLS,
                Set.of(), api, null, "us-east-1", "000000000000");
        assertEquals("Unauthorized", firstErrorType(execute(schema, "{ secret }", ctx)));
    }

    @Test
    void deniedFieldsShortForm() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String }"));
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        AppSyncAuthContext ctx = new AppSyncAuthContext(
                IdentityBuilder.lambda(Map.of()), AppSyncAuth.AUTH_TYPE_LAMBDA, AuthenticationType.AWS_LAMBDA,
                Set.of("Query.hello"), api, null, "us-east-1", "000000000000");
        Map<String, Object> response = execute(schema, "{ hello }", ctx);
        assertNull(dataHello(response));
        assertEquals(List.of("hello"), firstErrorPath(response));
    }

    @Test
    void deniedFieldsArnOtherApiDoesNotDeny() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String }"));
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        String otherArn = IamAuthValidator.fieldArn("us-east-1", "000000000000", "other-api", "Query", "hello");
        AppSyncAuthContext ctx = new AppSyncAuthContext(
                IdentityBuilder.lambda(Map.of()), AppSyncAuth.AUTH_TYPE_LAMBDA, AuthenticationType.AWS_LAMBDA,
                Set.of(otherArn), api, null, "us-east-1", "000000000000");
        Map<String, Object> response = execute(schema, "{ hello }", ctx);
        assertNull(response.get("errors"));
    }

    @Test
    void deniedFieldsArnThisApiDenies() {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String }"));
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        String arn = IamAuthValidator.fieldArn("us-east-1", "000000000000", "api-1", "Query", "hello");
        AppSyncAuthContext ctx = new AppSyncAuthContext(
                IdentityBuilder.lambda(Map.of()), AppSyncAuth.AUTH_TYPE_LAMBDA, AuthenticationType.AWS_LAMBDA,
                Set.of(arn), api, null, "us-east-1", "000000000000");
        assertEquals("Unauthorized", firstErrorType(execute(schema, "{ hello }", ctx)));
    }

    @Test
    void wrapperSurvivesInnerDelegateSwap() throws Exception {
        GraphQLSchema schema = wrapper.wrap(parser.parse("type Query { hello: String @aws_iam }"));
        GraphQLObjectType query = schema.getQueryType();
        GraphQLFieldDefinition field = query.getFieldDefinition("hello");
        DataFetcher<?> fetcher = schema.getCodeRegistry().getDataFetcher(
                FieldCoordinates.coordinates("Query", "hello"), field);
        assertInstanceOf(AuthorizationDataFetcher.class, fetcher);
        AuthorizationDataFetcher wrapperFetcher = (AuthorizationDataFetcher) fetcher;
        AtomicBoolean innerRan = new AtomicBoolean(false);
        wrapperFetcher.setDelegate(env -> {
            innerRan.set(true);
            return "secret";
        });

        GraphqlApi api = api(AuthenticationType.API_KEY);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.AWS_IAM);
        api.setAdditionalAuthenticationProviders(List.of(extra));
        Map<String, Object> response = execute(schema, "{ hello }", apiKeyContext(api));
        assertEquals("Unauthorized", firstErrorType(response));
        assertTrue(!innerRan.get());
    }

    @Test
    void registerWrapsUserFields() {
        SchemaRegistry registry = new SchemaRegistry(parser, wrapper);
        registry.register("api-1", "type Query { hello: String @aws_iam }");
        GraphQLSchema schema = registry.getSchema("api-1").orElseThrow();
        GraphQLFieldDefinition field = schema.getQueryType().getFieldDefinition("hello");
        DataFetcher<?> fetcher = schema.getCodeRegistry().getDataFetcher(
                FieldCoordinates.coordinates("Query", "hello"), field);
        assertInstanceOf(AuthorizationDataFetcher.class, fetcher);
    }

    private Map<String, Object> execute(GraphQLSchema schema, String query, AppSyncAuthContext ctx) {
        Map<Object, Object> graphqlContext = new HashMap<>();
        graphqlContext.put(AppSyncAuthContext.KEY, ctx);
        if (ctx.identity() != null) {
            graphqlContext.put("identity", ctx.identity());
        }
        graphqlContext.put("authType", ctx.authType());
        graphqlContext.put("deniedFields", ctx.deniedFieldsList());
        GraphQL graphQL = SchemaRegistry.buildGraphQL(schema);
        return executor.execute(graphQL, query, null, null, graphqlContext);
    }

    private static GraphqlApi api(AuthenticationType type) {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        api.setAuthenticationType(type);
        return api;
    }

    private static AppSyncAuthContext iamContext(GraphqlApi api) {
        return new AppSyncAuthContext(
                IdentityBuilder.iam("000000000000", "test", "test", "arn:aws:iam::000000000000:root", List.of()),
                AppSyncAuth.AUTH_TYPE_IAM, AuthenticationType.AWS_IAM, Set.of(), api, "test",
                "us-east-1", "000000000000");
    }

    private static AppSyncAuthContext apiKeyContext(GraphqlApi api) {
        return new AppSyncAuthContext(
                null, AppSyncAuth.AUTH_TYPE_API_KEY, AuthenticationType.API_KEY, Set.of(), api, null,
                "us-east-1", "000000000000");
    }

    private static Object dataHello(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> map)) {
            return null;
        }
        return map.get("hello");
    }

    @SuppressWarnings("unchecked")
    private static String firstErrorType(Map<String, Object> response) {
        return (String) ((List<Map<String, Object>>) response.get("errors")).get(0).get("errorType");
    }

    @SuppressWarnings("unchecked")
    private static Object firstErrorPath(Map<String, Object> response) {
        return ((List<Map<String, Object>>) response.get("errors")).get(0).get("path");
    }
}
