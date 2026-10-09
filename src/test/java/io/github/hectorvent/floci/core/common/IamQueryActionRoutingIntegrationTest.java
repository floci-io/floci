package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Every IAM action the Query handler dispatches answers in the IAM namespace when the request
 * arrives without a usable credential scope.
 *
 * <p>Without an {@code Authorization} header the controller infers the service from the action name
 * alone, and that chain ends in {@code return "sqs"}. A name missing from the IAM set therefore
 * answers {@code UnsupportedOperation} in the SQS namespace, which looks like a plausible error
 * rather than a routing failure, and {@code IamEnforcementFilter} resolves the policy namespace
 * through the same code, so the same operations would be authorized as {@code sqs:<Action>} and a
 * policy denying {@code iam:*} would miss them.
 *
 * <p>One action per family that was unrouted, plus a control that was already routed.
 */
@QuarkusTest
class IamQueryActionRoutingIntegrationTest {

    private static final String IAM_NAMESPACE = "iam.amazonaws.com/doc/2010-05-08";
    private static final String SQS_NAMESPACE = "sqs.amazonaws.com/doc";

    private static void assertRoutesToIam(String action) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
        .when().post("/").then()
                .body(containsString(IAM_NAMESPACE))
                .body(not(containsString(SQS_NAMESPACE)));
    }

    @Test
    void accountAliasActionsRouteToIam() {
        for (String action : List.of("CreateAccountAlias", "DeleteAccountAlias",
                "ListAccountAliases")) {
            assertRoutesToIam(action);
        }
    }

    @Test
    void accountPasswordPolicyActionsRouteToIam() {
        for (String action : List.of("GetAccountPasswordPolicy", "UpdateAccountPasswordPolicy",
                "DeleteAccountPasswordPolicy")) {
            assertRoutesToIam(action);
        }
    }

    @Test
    void permissionsBoundaryActionsRouteToIam() {
        for (String action : List.of("PutUserPermissionsBoundary", "DeleteUserPermissionsBoundary",
                "PutRolePermissionsBoundary", "DeleteRolePermissionsBoundary")) {
            assertRoutesToIam(action);
        }
    }

    @Test
    void openIdConnectProviderActionsRouteToIam() {
        for (String action : List.of("CreateOpenIDConnectProvider", "GetOpenIDConnectProvider",
                "ListOpenIDConnectProviders", "DeleteOpenIDConnectProvider",
                "AddClientIDToOpenIDConnectProvider", "RemoveClientIDFromOpenIDConnectProvider",
                "UpdateOpenIDConnectProviderThumbprint", "TagOpenIDConnectProvider",
                "UntagOpenIDConnectProvider", "ListOpenIDConnectProviderTags")) {
            assertRoutesToIam(action);
        }
    }

    @Test
    void organizationsFeatureActionsRouteToIam() {
        for (String action : List.of("ListOrganizationsFeatures",
                "EnableOrganizationsRootCredentialsManagement",
                "DisableOrganizationsRootCredentialsManagement",
                "EnableOrganizationsRootSessions", "DisableOrganizationsRootSessions")) {
            assertRoutesToIam(action);
        }
    }

    @Test
    void theRemainingSinglesRouteToIam() {
        for (String action : List.of("GetAccessKeyLastUsed", "UpdateGroup",
                "UpdateAssumeRolePolicy")) {
            assertRoutesToIam(action);
        }
    }

    /**
     * Reaching IAM is only half of it: an operation whose required parameters are absent has to
     * answer {@code ValidationError}, not a 500.
     *
     * <p>These five took their model-required parameters through the nullable accessor, so a
     * missing one reached the service as {@code null} and surfaced as {@code InternalFailure} from
     * a null map key. That was reachable before this change too, through an IAM credential scope;
     * routing them by action name alone just adds a second way in.
     */
    @Test
    void anAbsentRequiredParameterIsAValidationErrorRatherThanA500() {
        for (String action : List.of("UpdateAssumeRolePolicy", "PutUserPermissionsBoundary",
                "PutRolePermissionsBoundary", "DeleteRolePermissionsBoundary",
                "DeleteUserPermissionsBoundary")) {
            assertValidationError(action, Map.of());
        }
    }

    /**
     * Three of those five take a second required parameter, and omitting everything only ever
     * exercises the first check: {@code requireParam} on the name throws before the one on the
     * policy document or the boundary ARN is reached. These cases supply the name, so the second
     * check is the one under test.
     *
     * <p>Both checks run while the arguments to the service call are evaluated, which is why a name
     * that does not exist still answers {@code ValidationError} rather than {@code NoSuchEntity}.
     */
    @Test
    void anAbsentSecondRequiredParameterIsAlsoAValidationError() {
        assertValidationError("UpdateAssumeRolePolicy",
                Map.of("RoleName", "role-that-need-not-exist"));
        assertValidationError("PutUserPermissionsBoundary",
                Map.of("UserName", "user-that-need-not-exist"));
        assertValidationError("PutRolePermissionsBoundary",
                Map.of("RoleName", "role-that-need-not-exist"));
    }

    private static void assertValidationError(String action, Map<String, String> presentParams) {
        Map<String, String> form = new LinkedHashMap<>(presentParams);
        form.put("Action", action);
        form.put("Version", "2010-05-08");
        given().contentType("application/x-www-form-urlencoded")
                .formParams(form)
        .when().post("/").then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(not(containsString("NoSuchEntity")))
                .body(not(containsString("InternalFailure")));
    }

    /** The control: an action that was already routed, so a broken assertion shows up here too. */
    @Test
    void anAlreadyRoutedActionStillRoutesToIam() {
        assertRoutesToIam("ListUsers");
    }
}
