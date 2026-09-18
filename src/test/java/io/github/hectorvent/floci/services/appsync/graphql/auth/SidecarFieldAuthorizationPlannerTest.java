package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.DenyField;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedDirective;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedField;
import io.github.hectorvent.floci.services.appsync.model.AdditionalAuthenticationProvider;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Same scenarios {@code AuthorizationDataFetcherTest} used to exercise against a live in-process
 * {@code DataFetcher} wrapper, now against {@link SidecarFieldAuthorizationPlanner}'s decision
 * logic directly: a hand-built {@link PlanResult} stands in for what {@code
 * GraphqlSidecarClient#plan} would have returned (no live sidecar needed for this), and the
 * assertion is whether {@code planDenyFields} redacts the field or not (issue #2917). The actual
 * "null the field out" behavior these decisions drive is now the GraphQL sidecar's own generic
 * {@code denyFields} handling, not something this class needs to re-verify.
 */
class SidecarFieldAuthorizationPlannerTest {

    private IamAuthValidator iamAuthValidator;
    private SidecarFieldAuthorizationPlanner planner;

    @BeforeEach
    void setUp() {
        iamAuthValidator = mock(IamAuthValidator.class);
        when(iamAuthValidator.isFieldDenied(anyString(), anyString())).thenReturn(false);
        planner = new SidecarFieldAuthorizationPlanner(iamAuthValidator);
    }

    @Test
    void iamCallerOnApiKeyFieldIsDenied() {
        PlanResult plan = plan(field("Query", "hello", List.of(directive("aws_api_key")), List.of()));
        GraphqlApi api = api(AuthenticationType.AWS_IAM);
        withAdditional(api, AuthenticationType.API_KEY);

        List<DenyField> denied = planner.planDenyFields(plan, iamContext(api));

        assertEquals(1, denied.size());
        assertEquals("Query", denied.get(0).typeName());
        assertEquals("hello", denied.get(0).fieldName());
        assertEquals(AppSyncAuth.FIELD_UNAUTHORIZED_TYPE, denied.get(0).errorType());
        assertEquals("Not Authorized to access hello on type Query", denied.get(0).message());
    }

    @Test
    void unmarkedFieldRequiresDefaultMode() {
        PlanResult plan = plan(field("Query", "hello", List.of(), List.of()));
        GraphqlApi api = api(AuthenticationType.API_KEY);
        withAdditional(api, AuthenticationType.AWS_IAM);

        assertDenied(planner.planDenyFields(plan, iamContext(api)));
    }

    @Test
    void orDirectivesAllowApiKey() {
        PlanResult plan = plan(field("Query", "hello", List.of(directive("aws_api_key"), directive("aws_iam")), List.of()));
        GraphqlApi api = api(AuthenticationType.API_KEY);
        withAdditional(api, AuthenticationType.AWS_IAM);

        assertAllowed(planner.planDenyFields(plan, apiKeyContext(api)));
    }

    @Test
    void fieldDirectivesOverrideType() {
        PlanResult plan = plan(field("Query", "hello", List.of(directive("aws_iam")), List.of(directive("aws_api_key"))));
        GraphqlApi api = api(AuthenticationType.API_KEY);
        withAdditional(api, AuthenticationType.AWS_IAM);

        assertDenied(planner.planDenyFields(plan, apiKeyContext(api)));
        assertAllowed(planner.planDenyFields(plan, iamContext(api)));
    }

    @Test
    void typeLevelGrantsFieldWithNoOwnDirectives() {
        PlanResult plan = plan(field("Query", "hello", List.of(), List.of(directive("aws_iam"))));
        GraphqlApi api = api(AuthenticationType.AWS_IAM);

        assertAllowed(planner.planDenyFields(plan, iamContext(api)));
    }

    @Test
    void awsAuthIgnoredWhenAdditionalModesExist() {
        PlanResult plan = plan(field("Query", "secret",
                List.of(directive("aws_auth", Map.of("cognito_groups", List.of("admin")))), List.of()));
        GraphqlApi api = api(AuthenticationType.AMAZON_COGNITO_USER_POOLS);
        withAdditional(api, AuthenticationType.API_KEY);
        Map<String, Object> identity = IdentityBuilder.cognito(Map.of("sub", "s", "iss", "https://iss"), List.of(), "ALLOW");
        AppSyncAuthContext ctx = new AppSyncAuthContext(identity, AppSyncAuth.AUTH_TYPE_COGNITO,
                AuthenticationType.AMAZON_COGNITO_USER_POOLS, Set.of(), api, null, "us-east-1", "000000000000");

        assertAllowed(planner.planDenyFields(plan, ctx));
    }

    @Test
    void awsAuthEnforcedWhenCognitoSole() {
        PlanResult plan = plan(field("Query", "secret",
                List.of(directive("aws_auth", Map.of("cognito_groups", List.of("admin")))), List.of()));
        GraphqlApi api = api(AuthenticationType.AMAZON_COGNITO_USER_POOLS);
        Map<String, Object> identity = IdentityBuilder.cognito(
                Map.of("sub", "s", "iss", "https://iss", "cognito:groups", List.of("users")), List.of(), "ALLOW");
        AppSyncAuthContext ctx = new AppSyncAuthContext(identity, AppSyncAuth.AUTH_TYPE_COGNITO,
                AuthenticationType.AMAZON_COGNITO_USER_POOLS, Set.of(), api, null, "us-east-1", "000000000000");

        assertDenied(planner.planDenyFields(plan, ctx));
    }

    @Test
    void deniedFieldsShortFormDenies() {
        PlanResult plan = plan(field("Query", "hello", List.of(), List.of()));
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        AppSyncAuthContext ctx = new AppSyncAuthContext(IdentityBuilder.lambda(Map.of()), AppSyncAuth.AUTH_TYPE_LAMBDA,
                AuthenticationType.AWS_LAMBDA, Set.of("Query.hello"), api, null, "us-east-1", "000000000000");

        assertDenied(planner.planDenyFields(plan, ctx));
    }

    @Test
    void deniedFieldsArnForOtherApiDoesNotDeny() {
        PlanResult plan = plan(field("Query", "hello", List.of(), List.of()));
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        String otherArn = IamAuthValidator.fieldArn("us-east-1", "000000000000", "other-api", "Query", "hello");
        AppSyncAuthContext ctx = new AppSyncAuthContext(IdentityBuilder.lambda(Map.of()), AppSyncAuth.AUTH_TYPE_LAMBDA,
                AuthenticationType.AWS_LAMBDA, Set.of(otherArn), api, null, "us-east-1", "000000000000");

        assertAllowed(planner.planDenyFields(plan, ctx));
    }

    @Test
    void deniedFieldsArnForThisApiDenies() {
        PlanResult plan = plan(field("Query", "hello", List.of(), List.of()));
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        String arn = IamAuthValidator.fieldArn("us-east-1", "000000000000", "api-1", "Query", "hello");
        AppSyncAuthContext ctx = new AppSyncAuthContext(IdentityBuilder.lambda(Map.of()), AppSyncAuth.AUTH_TYPE_LAMBDA,
                AuthenticationType.AWS_LAMBDA, Set.of(arn), api, null, "us-east-1", "000000000000");

        assertDenied(planner.planDenyFields(plan, ctx));
    }

    @Test
    void noAuthContextAllowsEverything() {
        PlanResult plan = plan(field("Query", "hello", List.of(), List.of()));
        assertTrue(planner.planDenyFields(plan, null).isEmpty());
    }

    private static PlanResult plan(PlannedField field) {
        return new PlanResult("QUERY", List.of(field));
    }

    private static void assertDenied(List<DenyField> denied) {
        assertEquals(1, denied.size());
    }

    private static void assertAllowed(List<DenyField> denied) {
        assertTrue(denied.isEmpty());
    }

    private static PlannedField field(String typeName, String fieldName, List<PlannedDirective> directives,
                                      List<PlannedDirective> typeDirectives) {
        return new PlannedField(typeName, fieldName, directives, typeDirectives);
    }

    private static PlannedDirective directive(String name) {
        return new PlannedDirective(name, Map.of());
    }

    private static PlannedDirective directive(String name, Map<String, Object> args) {
        return new PlannedDirective(name, args);
    }

    private static GraphqlApi api(AuthenticationType type) {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        api.setAuthenticationType(type);
        return api;
    }

    private static void withAdditional(GraphqlApi api, AuthenticationType type) {
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(type);
        api.setAdditionalAuthenticationProviders(List.of(extra));
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
}
