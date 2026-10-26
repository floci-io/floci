package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Holds {@link ResourceArnBuilder}'s declared-unresolved resource types to the reason each one
 * gives. Every entry claims its type belongs only to actions the handler does not dispatch, and
 * that is a claim about the handler rather than about the builder, so nothing inside the builder
 * can check it.
 *
 * <p>It matters because the failure is silent and fail-open. Dispatching such an action without
 * teaching the resolver its type leaves the action evaluated against {@code *}, so a policy
 * statement naming the resource never applies, which is the gap this builder exists to close.
 *
 * <p>The map is empty since {@code GetRoleTemplateVersion} was dispatched, so the first test
 * holds nothing today and the second holds what emptied it: the type that used to be declared is
 * resolved from the ARN the request carries. That one is the regression worth a test, since an
 * empty map asserts nothing and dropping the parameter would put the action back on {@code *}.
 */
@QuarkusTest
class UnresolvedResourceTypesIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261010/us-east-1/iam/aws4_request";

    @Test
    void everyTypeDeclaredUnresolvedIsCarriedOnlyByActionsNobodyDispatches() {
        for (Map.Entry<String, String> declared : ResourceArnBuilder.UNRESOLVED_TYPES.entrySet()) {
            Set<String> actions = actionsCarrying(declared.getKey());
            assertFalse(actions.isEmpty(), declared.getKey()
                    + " is declared unresolved but AWS no longer gives it to any action, so the "
                    + "entry is dead and should go");
            for (String action : actions) {
                // The credential scope resolves the service, so this reaches the IAM handler
                // whether or not the action is routable by name, and an action the handler does
                // not dispatch falls to its default branch.
                Response response = given().header("Authorization", IAM_AUTH)
                        .formParam("Action", action)
                    .when().post("/");
                // The status is read before the body: a dispatched action may answer with no
                // parseable body at all, and the reason for failing is the same either way.
                assertEquals(400, response.statusCode(), reason(declared, action));
                assertEquals("UnsupportedOperation",
                        response.xmlPath().getString("ErrorResponse.Error.Code"),
                        reason(declared, action));
            }
        }
    }

    /**
     * {@code role-template} was the last declared-unresolved type, and dispatching
     * {@code GetRoleTemplateVersion} is what took it out: the action carries the template's ARN,
     * so the type resolves like any other the request names. Held over the wire as well as in the
     * table, because the action being dispatched is half of the claim.
     */
    @Test
    void theTypeThatWasDeclaredUnresolvedIsResolvedFromTheArnTheRequestCarries() {
        assertEquals("TemplateArn",
                ResourceArnBuilder.IAM_ARN_PARAMETERS.get("GetRoleTemplateVersion"),
                "GetRoleTemplateVersion no longer resolves its template from the ARN it carries, "
                        + "so role-template is back to * unless it is declared unresolved again");
        assertEquals(List.of("role-template"),
                IamActionResources.typesOf("GetRoleTemplateVersion"),
                "AWS has changed which resource type GetRoleTemplateVersion is authorized "
                        + "against, so the parameter above may name the wrong thing");

        Response response = given().header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRoleTemplateVersion")
                .formParam("TemplateArn", "arn:aws:iam::aws:role-template/ec2.amazonaws.com/X:1")
            .when().post("/");
        assertEquals(404, response.statusCode(),
                "GetRoleTemplateVersion is not dispatched any more, so role-template belongs in "
                        + "UNRESOLVED_TYPES again rather than being resolved here");
        assertEquals("NoSuchEntity", response.xmlPath().getString("ErrorResponse.Error.Code"));
    }

    private static String reason(Map.Entry<String, String> declared, String action) {
        return action + " is dispatched now, so '" + declared.getKey() + "' is no longer a type "
                + "only undispatched actions carry (" + declared.getValue() + "). Resolve it in "
                + "ResourceArnBuilder rather than leaving it declared, or the action is evaluated "
                + "against * and a policy naming the resource never applies";
    }

    private static Set<String> actionsCarrying(String resourceType) {
        Set<String> actions = new TreeSet<>();
        for (Map.Entry<String, List<String>> entry : IamActionResources.all().entrySet()) {
            if (entry.getValue().contains(resourceType)) {
                actions.add(entry.getKey());
            }
        }
        return actions;
    }
}
