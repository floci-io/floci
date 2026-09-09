package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AddPermission / GetPolicy / RemovePermission must honour {@code ?Qualifier=} (#2124).
 *
 * <p>AWS keeps a SEPARATE resource policy per qualifier: an alias-scoped statement carries
 * {@code <functionArn>:<alias>} as its Resource, is only visible through
 * {@code GetPolicy?Qualifier=<alias>}, and the same StatementId may exist independently on
 * the unqualified function. Floci previously ignored the qualifier entirely, so an
 * alias-scoped permission could never be read back under its alias — which is what stops
 * Terraform's {@code aws_lambda_permission} with {@code qualifier} from ever converging.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaPermissionQualifierIntegrationTest {

    private static final String BASE    = "/2015-03-31";
    private static final String FN      = "qualifier-perm-fn";
    private static final String FN_ARN  = "arn:aws:lambda:us-east-1:000000000000:function:" + FN;
    private static final String ALIAS   = "LIVE";
    private static final String STMT_ID = "AllowExecutionFromAPIGateway";

    private static final ObjectMapper OM = new ObjectMapper();

    private static String addPermissionBody(String principal) {
        return """
            {
                "StatementId": "%s",
                "Action": "lambda:InvokeFunction",
                "Principal": "%s"
            }
            """.formatted(STMT_ID, principal);
    }

    /** The single Resource string of the one statement in a GetPolicy response. */
    private static String onlyStatementResource(String getPolicyResponse) throws Exception {
        JsonNode statements = OM.readTree(OM.readTree(getPolicyResponse).get("Policy").asText())
                .get("Statement");
        assertEquals(1, statements.size(), "expected exactly one statement in this policy");
        return statements.get(0).get("Resource").asText();
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createFunctionVersionAndAlias() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"
                }
                """.formatted(FN))
        .when()
            .post(BASE + "/functions")
        .then()
            .statusCode(201);

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post(BASE + "/functions/" + FN + "/versions")
        .then()
            .statusCode(201);

        given()
            .contentType("application/json")
            .body("""
                { "Name": "%s", "FunctionVersion": "1" }
                """.formatted(ALIAS))
        .when()
            .post(BASE + "/functions/" + FN + "/aliases")
        .then()
            .statusCode(201);
    }

    // ── AddPermission scopes the statement to the qualifier ───────────────────

    @Test
    @Order(2)
    void addPermission_withQualifier_scopesResourceToTheAlias() throws Exception {
        String response = given()
            .contentType("application/json")
            .body(addPermissionBody("apigateway.amazonaws.com"))
        .when()
            .post(BASE + "/functions/" + FN + "/policy?Qualifier=" + ALIAS)
        .then()
            .statusCode(201)
            .body("Statement", notNullValue())
            .extract().body().asString();

        // The returned statement must name the ALIAS ARN. Before the fix this was the bare
        // function ARN, so the permission was indistinguishable from an unqualified one.
        String resource = OM.readTree(OM.readTree(response).get("Statement").asText())
                .get("Resource").asText();
        assertEquals(FN_ARN + ":" + ALIAS, resource);
    }

    @Test
    @Order(3)
    void getPolicy_withQualifier_returnsTheAliasStatement() throws Exception {
        String response = given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy?Qualifier=" + ALIAS)
        .then()
            .statusCode(200)
            .body("Policy", notNullValue())
            .body("RevisionId", notNullValue())
            .extract().body().asString();

        assertEquals(FN_ARN + ":" + ALIAS, onlyStatementResource(response));
    }

    /**
     * The regression itself: the alias-scoped statement must NOT show up in the function's
     * own policy. Previously GetPolicy returned identical payloads with and without the
     * qualifier, so the provider could never tell the two apart.
     */
    @Test
    @Order(4)
    void getPolicy_withoutQualifier_doesNotSeeTheAliasStatement() {
        given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("The resource you requested does not exist."));
    }

    // ── the qualified and unqualified policies are independent ────────────────

    @Test
    @Order(5)
    void addPermission_sameStatementIdUnqualified_isNotAConflict() {
        given()
            .contentType("application/json")
            .body(addPermissionBody("s3.amazonaws.com"))
        .when()
            .post(BASE + "/functions/" + FN + "/policy")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(6)
    void getPolicy_withoutQualifier_returnsOnlyTheUnqualifiedStatement() throws Exception {
        String response = given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertEquals(FN_ARN, onlyStatementResource(response));
    }

    @Test
    @Order(7)
    void addPermission_duplicateWithinTheSameQualifier_stillConflicts() {
        given()
            .contentType("application/json")
            .body(addPermissionBody("apigateway.amazonaws.com"))
        .when()
            .post(BASE + "/functions/" + FN + "/policy?Qualifier=" + ALIAS)
        .then()
            .statusCode(409);
    }

    // ── RemovePermission is likewise scoped ───────────────────────────────────

    @Test
    @Order(8)
    void removePermission_withoutQualifier_leavesTheAliasStatement() throws Exception {
        given()
        .when()
            .delete(BASE + "/functions/" + FN + "/policy/" + STMT_ID)
        .then()
            .statusCode(204);

        // The unqualified policy is now empty...
        given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy")
        .then()
            .statusCode(404);

        // ...while the alias keeps its own statement.
        String response = given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy?Qualifier=" + ALIAS)
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertEquals(FN_ARN + ":" + ALIAS, onlyStatementResource(response));
    }

    @Test
    @Order(9)
    void removePermission_withUnknownQualifier_returns404() {
        given()
        .when()
            .delete(BASE + "/functions/" + FN + "/policy/" + STMT_ID + "?Qualifier=NOPE")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(10)
    void removePermission_withQualifier_removesTheAliasStatement() {
        given()
        .when()
            .delete(BASE + "/functions/" + FN + "/policy/" + STMT_ID + "?Qualifier=" + ALIAS)
        .then()
            .statusCode(204);

        given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy?Qualifier=" + ALIAS)
        .then()
            .statusCode(404);
    }

    // ── the qualifier may also travel in the function name (arn:...:fn:ALIAS) ──

    @Test
    @Order(11)
    void qualifierEmbeddedInTheFunctionName_isEquivalentToTheQueryParam() throws Exception {
        given()
            .contentType("application/json")
            .body(addPermissionBody("events.amazonaws.com"))
        .when()
            .post(BASE + "/functions/" + FN + ":" + ALIAS + "/policy")
        .then()
            .statusCode(201);

        String response = given()
        .when()
            .get(BASE + "/functions/" + FN + "/policy?Qualifier=" + ALIAS)
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertEquals(FN_ARN + ":" + ALIAS, onlyStatementResource(response));
    }

    @Test
    @Order(12)
    void deleteFunction() {
        given()
        .when()
            .delete(BASE + "/functions/" + FN)
        .then()
            .statusCode(204);
    }
}
