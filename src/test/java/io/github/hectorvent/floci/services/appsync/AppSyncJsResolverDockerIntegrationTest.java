package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Executes real {@code APPSYNC_JS} resolvers end to end: a GraphQL query over HTTP, out to the
 * GraphQL sidecar, back through Floci's resolver callback, then the pipeline and the Node sidecar.
 *
 * <p>Everything here runs the resolver code as written, the {@code @aws-appsync/utils} imports
 * included, so it is the test that says whether an AppSync API deployed into Floci answers
 * queries rather than nulls. A {@code NONE} data source keeps the assertions about the pipeline
 * itself; the data-source adapters have their own tests.
 *
 * <p>Needs Docker for both sidecars, and skips without it.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AppSyncJsResolverDockerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    private static final String SCHEMA = """
            type Message { id: ID! name: String }
            type VtlPipelineResult { value: String before: String }
            type Query {
              getMessages(orgNo: String!): [Message]
              vtlPipeline(value: String!): VtlPipelineResult
              ping(x: String): String
              echoArg(x: String): String
              failing: String
              warned: String
              asyncResolver: String
              importsNode: String
              loops: String
              withStatement: String
              misplacedAwait: String
              misplacedYield: String
              malformedSyntax: String
              keywordsInText: String
              regexLiteral: String
              regexKeyword: String
              regexClassThenUnsupported: String
              regexDivision: String
              forOfIdentifierDivision: String
              regexAfterCaseBlock(n: Int): String
              regexAfterLabelBlock: String
              commentCr: String
              commentLineSeparator: String
              commentParagraphSeparator: String
              divisionAfterFunction: String
              divisionAfterObject: String
              taggedRawInvalidEscape: String
              taggedRawInvalidEscapeHole: String
              untaggedInvalidEscape: String
              normalizeString: String
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

    /** Reports back what reached ctx.args, so an explicit null is distinguishable from an absent one. */
    private static final String ECHO_ARG_RESOLVER = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              return { payload: { count: Object.keys(ctx.args).length, value: ctx.args.x } };
            }
            export function response(ctx) {
              return "count=" + ctx.result.count + " value=" + util.toJson(ctx.result.value);
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

    private static final String WITH_RESOLVER = """
            export function request(ctx) {
              with (ctx) { return { payload: { value: "nope" } }; }
            }
            """;

    private static final String AWAIT_RESOLVER = """
            export function request(ctx) {
              await 1;
              return { payload: { value: "nope" } };
            }
            """;

    private static final String YIELD_RESOLVER = """
            export function request(ctx) {
              yield 1;
              return { payload: { value: "nope" } };
            }
            """;

    private static final String MALFORMED_RESOLVER = """
            export function request(ctx) {
              // with await yield
              const text = "with";
              const value = ;
              return { payload: { value } };
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

    private static final String REGEX_KEYWORD_RESOLVER = """
            export function request(ctx) {
              return { payload: { value: /while/.test("while") } };
            }
            export function response(ctx) {
              return String(ctx.result.value);
            }
            """;

    private static final String REGEX_LITERAL_RESOLVER = """
            export function request(ctx) {
              return { payload: { value: /foo/.test("foo") } };
            }
            """;

    private static final String REGEX_CLASS_UNSUPPORTED_RESOLVER = """
            export function request(ctx) {
              return { payload: { value: /[//]/.test("/") && this.value } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    private static final String REGEX_DIVISION_RESOLVER = """
            export function request(ctx) {
              const sum = (value) => value;
              return { payload: { value: `${sum(6) / 2}% vs ${sum(8) / 2}%` } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    private static final String FOR_OF_IDENTIFIER_DIVISION_RESOLVER = """
            export function request(ctx) {
              const of = 8;
              for (const value of [of / 2 / 2]) {
                return { payload: { value: String(value) } };
              }
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    private static final String REGEX_AFTER_CASE_BLOCK_RESOLVER = """
            export function request(ctx) {
              switch (ctx.args.n) { case 1: {} /foo/.test("foo"); }
              return { payload: { value: "unreachable" } };
            }
            """;

    private static final String REGEX_AFTER_LABEL_BLOCK_RESOLVER = """
            export function request(ctx) {
              label: {} /foo/.test("foo");
              return { payload: { value: "unreachable" } };
            }
            """;

    static final String COMMENT_CR_RESOLVER = """
            export function request(ctx) {
              // comment\rwhile(false) {}
              return { payload: { value: "unreachable" } };
            }
            """;

    private static final String COMMENT_LINE_SEPARATOR_RESOLVER =
            "export function request(ctx) { // comment" + Character.toString(0x2028)
                + "const pattern = /foo/; return { payload: { value: pattern.test('foo') } }; }";

    private static final String COMMENT_PARAGRAPH_SEPARATOR_RESOLVER =
            "export function request(ctx) { // comment" + Character.toString(0x2029)
                + "return { payload: { value: this } }; }";

    private static final String DIVISION_AFTER_FUNCTION_RESOLVER = """
            export function request(ctx) {
              const value = function() {} / 2;
              while (false) {}
              return { payload: { value } };
            }
            """;

    private static final String DIVISION_AFTER_OBJECT_RESOLVER = """
            export function request(ctx) {
              const value = true ? { x: 1 } / this : 2;
              return { payload: { value } };
            }
            """;

    private static final String TAGGED_RAW_INVALID_ESCAPE_RESOLVER = """
            export function request(ctx) {
              const raw = (parts) => parts.raw[0];
              return { payload: { value: raw`\\x while` } };
            }
            export function response(ctx) {
              return ctx.result.value;
            }
            """;

    private static final String TAGGED_RAW_INVALID_ESCAPE_HOLE_RESOLVER = """
            export function request(ctx) {
              const raw = (parts, value) => value;
              return { payload: { value: raw`\\x while ${(() => { while (false) {} return 1; })()}` } };
            }
            """;

    private static final String UNTAGGED_INVALID_ESCAPE_RESOLVER = """
            export function request(ctx) {
              return { payload: { value: `\\x while` } };
            }
            """;

    private String apiId;
    private String apiKey;
    private static final String NORMALIZE_STRING_RESOLVER = """
        import { util } from "@aws-appsync/utils";
        export function request(ctx) {
          const value = "\\u00E9";
          return { payload: {
            defaultForm: util.str.normalize(value),
            nfc: util.str.normalize(value, "NFC"),
            nfd: util.str.normalize(value, "NFD"),
            nfkc: util.str.normalize(value, "NFKC"),
            nfkd: util.str.normalize(value, "NFKD"),
            lowercaseNfkd: util.str.normalize(value, "nfkd")
          } };
        }
        export function response(ctx) {
          return JSON.stringify(ctx.result);
        }
        """;

    @BeforeAll
    static void configure() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
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
        createUnitResolver(apiId, "Query", "echoArg", "local", ECHO_ARG_RESOLVER);
        createUnitResolver(apiId, "Query", "failing", "local", FAILING_RESOLVER);
        createUnitResolver(apiId, "Query", "warned", "local", WARNING_RESOLVER);
        createUnitResolver(apiId, "Query", "asyncResolver", "local", ASYNC_RESOLVER);
        createUnitResolver(apiId, "Query", "importsNode", "local", NODE_IMPORT_RESOLVER);
        createUnitResolver(apiId, "Query", "loops", "local", WHILE_RESOLVER);
        createUnitResolver(apiId, "Query", "withStatement", "local", WITH_RESOLVER);
        createUnitResolver(apiId, "Query", "misplacedAwait", "local", AWAIT_RESOLVER);
        createUnitResolver(apiId, "Query", "misplacedYield", "local", YIELD_RESOLVER);
        createUnitResolver(apiId, "Query", "malformedSyntax", "local", MALFORMED_RESOLVER);
        createUnitResolver(apiId, "Query", "keywordsInText", "local", KEYWORDS_IN_TEXT_RESOLVER);
        createUnitResolver(apiId, "Query", "regexLiteral", "local", REGEX_LITERAL_RESOLVER);
        createUnitResolver(apiId, "Query", "regexKeyword", "local", REGEX_KEYWORD_RESOLVER);
        createUnitResolver(apiId, "Query", "regexClassThenUnsupported", "local",
                REGEX_CLASS_UNSUPPORTED_RESOLVER);
        createUnitResolver(apiId, "Query", "regexDivision", "local", REGEX_DIVISION_RESOLVER);
        createUnitResolver(apiId, "Query", "forOfIdentifierDivision", "local",
                FOR_OF_IDENTIFIER_DIVISION_RESOLVER);
        createUnitResolver(apiId, "Query", "regexAfterCaseBlock", "local", REGEX_AFTER_CASE_BLOCK_RESOLVER);
        createUnitResolver(apiId, "Query", "regexAfterLabelBlock", "local", REGEX_AFTER_LABEL_BLOCK_RESOLVER);
        createUnitResolver(apiId, "Query", "commentCr", "local", COMMENT_CR_RESOLVER);
        createUnitResolver(apiId, "Query", "commentLineSeparator", "local", COMMENT_LINE_SEPARATOR_RESOLVER);
        createUnitResolver(apiId, "Query", "commentParagraphSeparator", "local",
                COMMENT_PARAGRAPH_SEPARATOR_RESOLVER);
        createUnitResolver(apiId, "Query", "divisionAfterFunction", "local",
                DIVISION_AFTER_FUNCTION_RESOLVER);
        createUnitResolver(apiId, "Query", "divisionAfterObject", "local",
                DIVISION_AFTER_OBJECT_RESOLVER);
        createUnitResolver(apiId, "Query", "taggedRawInvalidEscape", "local",
                TAGGED_RAW_INVALID_ESCAPE_RESOLVER);
        createUnitResolver(apiId, "Query", "taggedRawInvalidEscapeHole", "local",
                TAGGED_RAW_INVALID_ESCAPE_HOLE_RESOLVER);
        createUnitResolver(apiId, "Query", "untaggedInvalidEscape", "local",
                UNTAGGED_INVALID_ESCAPE_RESOLVER);
        createUnitResolver(apiId, "Query", "normalizeString", "local", NORMALIZE_STRING_RESOLVER);
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
    void aVtlPipelineRunsThroughTheGraphqlSidecar() {
        String functionId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name":"Query_vtlPipeline_0","dataSourceName":"local",
                 "functionVersion":"2018-05-29",
                 "requestMappingTemplate":"%s",
                 "responseMappingTemplate":"%s"}
                """.formatted(escape("""
                        {"version":"2018-05-29","payload":{
                         "value":$util.toJson($ctx.stash.value),
                         "before":$util.toJson($ctx.prev.result.before)}}
                        """), escape("$util.toJson($ctx.result)")))
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .extract().path("functionConfiguration.functionId");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"typeName":"Query","fieldName":"vtlPipeline","kind":"PIPELINE",
                 "pipelineConfig":{"functions":["%s"]},
                 "requestMappingTemplate":"%s",
                 "responseMappingTemplate":"%s"}
                """.formatted(functionId, escape("""
                        #set($discard = $ctx.stash.put("value", $ctx.args.value))
                        {"before":$util.toJson($ctx.args.value)}
                        """), escape("$util.toJson($ctx.prev.result)")))
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200);

        query("{ vtlPipeline(value: \\\"live\\\") { value before } }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.vtlPipeline.value", equalTo("live"))
            .body("data.vtlPipeline.before", equalTo("live"));
    }

    @Test
    void aUnitResolverRunsRequestAndResponseAroundItsDataSource() {
        query("{ ping }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.ping", equalTo("pong"));
    }

    @Test
    void utilStrNormalizeRespectsTheRequestedFormAndDefaultsToNfc() {
        query("{ normalizeString }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.normalizeString", equalTo(
                "{\"defaultForm\":\"é\",\"nfc\":\"é\",\"nfd\":\"é\","
                    + "\"nfkc\":\"é\",\"nfkd\":\"é\",\"lowercaseNfkd\":\"é\"}"
            ));
    }

    @Test
    void anExplicitNullArgumentReachesTheResolverInsteadOfFailingTheBatch() {
        // nextToken: null is ordinary GraphQL, and an explicit null is not the same as an absent
        // argument to a resolver reading ctx.args. It also has to survive the callback: one null
        // used to fail every field in that batch, not only the field carrying it.
        query("{ ping(x: null) }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.ping", equalTo("pong"));

        query("{ echoArg(x: null) }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.echoArg", equalTo("count=1 value=null"));

        // The contrast that matters: an absent argument is not in ctx.args at all, where an explicit
        // null is present and null. Only the count is asserted, since the value of an absent
        // argument is JavaScript's undefined and how that renders is not this test's subject.
        query("{ echoArg }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.echoArg", containsString("count=0"));
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
    void strictModeFailuresKeepTheirUnsupportedFeatureDiagnostics() {
        query("{ withStatement }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("with statements"));
        query("{ misplacedAwait }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("await"));
        query("{ misplacedYield }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("generators"));
    }

    @Test
    void malformedCodeWithBannedWordsOnlyInLiteralsRemainsSyntaxError() {
        query("{ malformedSyntax }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("SyntaxError"));
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
    void aRegexLiteralIsReportedAsUnsupported() {
        query("{ regexLiteral }")
            .statusCode(200)
            .body("data.regexLiteral", nullValue())
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("regular expressions"));
    }

    @Test
    void aKeywordInsideARegexLiteralIsReportedAsUnsupportedRegex() {
        query("{ regexKeyword }")
            .statusCode(200)
            .body("data.regexKeyword", nullValue())
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("regular expressions"));
    }

    @Test
    void slashesInsideARegexCharacterClassCannotHideUnsupportedCode() {
        query("{ regexClassThenUnsupported }")
            .statusCode(200)
            .body("data.regexClassThenUnsupported", nullValue())
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("this"));
    }

    @Test
    void divisionInsideTwoTemplateInterpolationsIsNotReadAsRegex() {
        query("{ regexDivision }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.regexDivision", equalTo("3% vs 4%"));
    }

    @Test
    void anIdentifierNamedOfInsideAForOfIterableKeepsDivisionOperators() {
        query("{ forOfIdentifierDivision }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.forOfIdentifierDivision", equalTo("2"));
    }

    @Test
    void regexAfterCaseAndLabelBlocksIsRefused() {
        query("{ regexAfterCaseBlock(n: 1) }")
            .statusCode(200)
            .body("data.regexAfterCaseBlock", nullValue())
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("regular expressions"));
        query("{ regexAfterLabelBlock }")
            .statusCode(200)
            .body("data.regexAfterLabelBlock", nullValue())
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("regular expressions"));
    }

    @Test
    void everyJavaScriptLineTerminatorEndsALineComment() {
        query("{ commentCr }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("while loops"));
        query("{ commentLineSeparator }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("regular expressions"));
        query("{ commentParagraphSeparator }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("this"));
    }

    @Test
    void divisionAfterFunctionExpressionDoesNotHideLaterUnsupportedCode() {
        query("{ divisionAfterFunction }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("while loops"));
    }

    @Test
    void divisionAfterTernaryObjectDoesNotHideLaterUnsupportedCode() {
        query("{ divisionAfterObject }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("this"));
    }

    @Test
    void invalidEscapeInTaggedRawTemplateDoesNotExposeKeywordsToSubsetScan() {
        query("{ taggedRawInvalidEscape }")
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.taggedRawInvalidEscape", equalTo("\\x while"));
    }

    @Test
    void invalidEscapeInUntaggedTemplateRemainsSyntaxError() {
        query("{ untaggedInvalidEscape }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("SyntaxError"))
            .body("errors[0].message", containsString("escape sequence"));
    }

    @Test
    void invalidEscapeInTaggedRawTemplateStillScansExpressions() {
        query("{ taggedRawInvalidEscapeHole }")
            .statusCode(200)
            .body("errors[0].errorType", equalTo("UnsupportedFeature"))
            .body("errors[0].message", containsString("while loops"));
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

    private ValidatableResponse query(String graphql) {
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

    static String escape(String value) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(value));
    }
}
