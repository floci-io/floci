package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Executes real {@code APPSYNC_JS} resolvers end to end: a GraphQL query over HTTP, through the
 * schema's data fetchers, the pipeline, the Node sidecar and back.
 *
 * <p>Everything here runs the resolver code as written, the {@code @aws-appsync/utils} imports
 * included, so it is the test that says whether an AppSync API deployed into Floci answers
 * queries rather than nulls. A {@code NONE} data source keeps the assertions about the pipeline
 * itself; the data-source adapters have their own tests.
 *
 * <p>Needs Docker for the sidecar, and skips without it.
 */
@QuarkusTest
class AppSyncJsResolverDockerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    private static final String SCHEMA = """
            type Message { id: ID! name: String }
            type Query {
              getMessages(orgNo: String!): [Message]
              ping: String
              failing: String
              warned: String
              asyncResolver: String
              importsNode: String
              loops: String
              keywordsInText: String
            }
            """;

    /** The before step's only job is to stash what the functions need, as a pipeline usually does. */
    private static final String PIPELINE_RESOLVER = """
            export function request(ctx) {
              ctx.stash.orgNo = ctx.args.orgNo;
              return {};
            }
            export function response(ctx) {
              return ctx.prev.result;
            }
            """;

    private static final String PIPELINE_FUNCTION = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              if (!ctx.stash.orgNo) {
                util.error("the before step did not stash orgNo", "BadRequest");
              }
              return { payload: [
                { id: "1", name: "Acme " + ctx.stash.orgNo },
                { id: "2", name: "Globex" }
              ] };
            }
            export function response(ctx) {
              return ctx.result;
            }
            """;

    private static final String UNIT_RESOLVER = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              return { payload: { value: "pong", at: util.time.nowISO8601() } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    /** Appends an error and still returns data: AppSync reports both. */
    private static final String WARNING_RESOLVER = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              return { payload: { value: "partial" } };
            }
            export function response(ctx) {
              util.appendError("one row was dropped", "Partial");
              return ctx.result.value;
            }
            """;

    private static final String FAILING_RESOLVER = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              util.error("orgNo is required", "BadRequest", { field: "orgNo" });
            }
            export function response(ctx) {
              return ctx.result;
            }
            """;

    /** Valid on Node, rejected by AWS: the runtime has no async support. */
    private static final String ASYNC_RESOLVER = """
            export async function request(ctx) {
              return { payload: { value: "nope" } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    /** APPSYNC_JS resolvers have no filesystem or network access. */
    private static final String NODE_IMPORT_RESOLVER = """
            import fs from "node:fs";
            export function request(ctx) {
              return { payload: { value: fs.readdirSync("/").length } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    private static final String WHILE_RESOLVER = """
            export function request(ctx) {
              let i = 0;
              while (i < 3) { i = i + 1; }
              return { payload: { value: String(i) } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    /** The same keywords, but only inside a string, a comment and a template: still valid. */
    private static final String KEYWORDS_IN_TEXT_RESOLVER = """
            export function request(ctx) {
              // class while try throw async await this
              const whileActive = "try catch class while";
              return { payload: { value: `${whileActive} ok` } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    private String apiId;
    private String apiKey;

    @BeforeAll
    static void configure() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is required for the AppSync JS resolver sidecar");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            // Bounded: an unresponsive daemon would otherwise hang the probe, and with it CI,
            // instead of the suite simply skipping.
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void deployApi() {
        apiId = createApi("js-" + UUID.randomUUID().toString().substring(0, 8));
        apiKey = createApiKey(apiId);
        startSchema(apiId, SCHEMA);
        awaitSchemaSuccess(apiId);
        createNoneDataSource(apiId, "local");

        String functionId = createFunction(apiId, "Query_getMessages_0", "local", PIPELINE_FUNCTION);
        createPipelineResolver(apiId, "Query", "getMessages", PIPELINE_RESOLVER, functionId);
        createUnitResolver(apiId, "Query", "ping", "local", UNIT_RESOLVER);
        createUnitResolver(apiId, "Query", "failing", "local", FAILING_RESOLVER);
        createUnitResolver(apiId, "Query", "warned", "local", WARNING_RESOLVER);
        createUnitResolver(apiId, "Query", "asyncResolver", "local", ASYNC_RESOLVER);
        createUnitResolver(apiId, "Query", "importsNode", "local", NODE_IMPORT_RESOLVER);
        createUnitResolver(apiId, "Query", "loops", "local", WHILE_RESOLVER);
        createUnitResolver(apiId, "Query", "keywordsInText", "local", KEYWORDS_IN_TEXT_RESOLVER);
    }

    @Test
    void aPipelineResolverAnswersWithItsFunctionsData() {
        query("{ getMessages(orgNo: \\\"556677\\\") { id name } }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.getMessages", hasSize(2))
            // The before step stashed orgNo and the function read it: the value proves the whole
            // chain ran, not just that something returned.
            .body("data.getMessages.name", contains("Acme 556677", "Globex"))
            .body("data.getMessages.id", contains("1", "2"));
    }

    @Test
    void aUnitResolverRunsRequestAndResponseAroundItsDataSource() {
        query("{ ping }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.ping", equalTo("pong"));
    }

    @Test
    void utilErrorBecomesAGraphqlErrorCarryingItsErrorType() {
        query("{ failing }")
            .statusCode(200)
            .body("data.failing", nullValue())
            .body("errors", hasSize(1))
            .body("errors[0].message", equalTo("orgNo is required"))
            // AppSync reports these on the error itself, not under extensions, and clients switch
            // on errorType.
            .body("errors[0].errorType", equalTo("BadRequest"))
            .body("errors[0].data.field", equalTo("orgNo"));
    }

    @Test
    void utilAppendErrorReturnsTheErrorBesideTheDataWithItsType() {
        query("{ warned }")
            .statusCode(200)
            // appendError is the one that stops nothing: the field keeps its value.
            .body("data.warned", equalTo("partial"))
            .body("errors", hasSize(1))
            .body("errors[0].message", equalTo("one row was dropped"))
            // The shim and the Java bridge have to agree on the member carrying the type, or it is
            // dropped on the way out and every appended error arrives untyped.
            .body("errors[0].errorType", equalTo("Partial"));
    }

    // ── The APPSYNC_JS subset is enforced, not just documented ───────────────

    @Test
    void anAsyncResolverIsRefusedRatherThanRunOnNode() {
        query("{ asyncResolver }")
            .statusCode(200)
            .body("data.asyncResolver", nullValue())
            // Node would run this happily. AWS rejects it, so accepting it locally would green-light
            // code that cannot deploy, which is the one thing an emulator must not do.
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("async"));
    }

    @Test
    void aResolverImportingANodeBuiltinIsRefused() {
        query("{ importsNode }")
            .statusCode(200)
            .body("data.importsNode", nullValue())
            // APPSYNC_JS has no filesystem or network access at all.
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("node:fs"));
    }

    @Test
    void aWhileLoopIsRefused() {
        query("{ loops }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("while"));
    }

    @Test
    void theSameKeywordsInStringsAndCommentsAreNotRefused() {
        // The check would be worse than useless if it rejected valid resolvers, so the scan blanks
        // comments, strings and template text before looking for constructs.
        query("{ keywordsInText }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.keywordsInText", equalTo("try catch class while ok"));
    }

    @Test
    void aFieldWithNoResolverStillResolvesFromItsParent() {
        // Message.id and Message.name have no resolvers of their own: they come off the objects the
        // pipeline returned, which is the default fetcher the resolver fetcher defers to.
        query("{ getMessages(orgNo: \\\"1\\\") { name } }")
            .statusCode(200)
            .body("data.getMessages[0].name", equalTo("Acme 1"))
            .body("data.getMessages[0]", notNullValue());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private io.restassured.response.ValidatableResponse query(String graphql) {
        return given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"" + graphql + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then();
    }

    private static String createApi(String name) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"name\": \"%s\", \"authenticationType\": \"API_KEY\"}".formatted(name))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");
    }

    private static String createApiKey(String apiId) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .extract().path("apiKey.id");
    }

    private static void startSchema(String apiId, String definition) {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"definition\": \"" + escape(definition) + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200);
    }

    private static void awaitSchemaSuccess(String apiId) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() ->
                "SUCCESS".equals(given().header("Authorization", AUTH)
                    .when().get("/v1/apis/" + apiId + "/schemacreation")
                    .then().statusCode(200).extract().path("status")));
    }

    private static void createNoneDataSource(String apiId, String name) {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"name\": \"%s\", \"type\": \"NONE\"}".formatted(name))
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200);
    }

    private static String createFunction(String apiId, String name, String dataSourceName, String code) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "%s", "dataSourceName": "%s", "functionVersion": "2018-05-29",
                 "runtime": {"name": "APPSYNC_JS", "runtimeVersion": "1.0.0"},
                 "code": "%s"}
                """.formatted(name, dataSourceName, escape(code)))
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .extract().path("functionConfiguration.functionId");
    }

    private static void createPipelineResolver(String apiId, String typeName, String fieldName,
                                               String code, String functionId) {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"typeName": "%s", "fieldName": "%s", "kind": "PIPELINE",
                 "runtime": {"name": "APPSYNC_JS", "runtimeVersion": "1.0.0"},
                 "pipelineConfig": {"functions": ["%s"]},
                 "code": "%s"}
                """.formatted(typeName, fieldName, functionId, escape(code)))
        .when()
            .post("/v1/apis/" + apiId + "/types/" + typeName + "/resolvers")
        .then()
            .statusCode(200);
    }

    private static void createUnitResolver(String apiId, String typeName, String fieldName,
                                           String dataSourceName, String code) {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"typeName": "%s", "fieldName": "%s", "kind": "UNIT", "dataSourceName": "%s",
                 "runtime": {"name": "APPSYNC_JS", "runtimeVersion": "1.0.0"},
                 "code": "%s"}
                """.formatted(typeName, fieldName, dataSourceName, escape(code)))
        .when()
            .post("/v1/apis/" + apiId + "/types/" + typeName + "/resolvers")
        .then()
            .statusCode(200);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
