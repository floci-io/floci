package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.LogsAuthorizationFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LogsStreamAuthorizationIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudWatchLogsService logsService;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void exactStreamPermissionAllowsOnlyThatStream(String action) {
        String group = group(Map.of());
        prepare(action, group, "selected");
        prepare(action, group, "other");
        String caller = user(policy("Allow", action, streamArn(group, "selected"), ""));

        stream(caller, action, group, "other").statusCode(400)
                .body(containsString("AccessDeniedException"));
        assertPresent(group, "other", action.equals("DeleteLogStream"));
        stream(caller, action, group, "selected").statusCode(200);
        assertPresent(group, "selected", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void groupTagDenyPreventsMutationEvenWithWildcardAllow(String action) {
        String group = group(Map.of("protected", "true"));
        prepare(action, group, "selected");
        String caller = user(policy("Allow", action, "*", "") + ","
                + policy("Deny", action, "*",
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/protected\":\"true\"}}"));

        stream(caller, action, group, "selected").statusCode(400)
                .body(containsString("AccessDeniedException"));
        assertPresent(group, "selected", action.equals("DeleteLogStream"));
        if (action.equals("DeleteLogStream")) {
            assertEvent(group, "selected");
        }
        for (Map<String, String> tags : List.of(Map.of("protected", "false"), Map.<String, String>of())) {
            String allowed = group(tags);
            prepare(action, allowed, "selected");
            stream(caller, action, allowed, "selected").statusCode(200);
            assertPresent(allowed, "selected", action.equals("CreateLogStream"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void resourceTagAllowReadsTheOwningGroup(String action) {
        String group = group(Map.of("team", "blue"));
        String other = group(Map.of("team", "red"));
        String untagged = group(Map.of());
        prepare(action, group, "selected");
        prepare(action, other, "selected");
        prepare(action, untagged, "selected");
        String caller = user(policy("Allow", action, "*",
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}"));

        stream(caller, action, other, "selected").statusCode(400)
                .body(containsString("AccessDeniedException"));
        assertPresent(other, "selected", action.equals("DeleteLogStream"));
        stream(caller, action, untagged, "selected").statusCode(400)
                .body(containsString("AccessDeniedException"));
        assertPresent(untagged, "selected", action.equals("DeleteLogStream"));
        stream(caller, action, group, "selected").statusCode(200);
        assertPresent(group, "selected", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void exactDenyOverridesWildcardAllowWithoutChangingTheStream(String action) {
        String group = group(Map.of());
        prepare(action, group, "protected");
        prepare(action, group, "allowed");
        String caller = user(policy("Allow", action, "*", "") + ","
                + policy("Deny", action, streamArn(group, "protected"), ""));
        stream(caller, action, group, "protected").statusCode(400)
                .body(containsString("AccessDeniedException"));
        assertPresent(group, "protected", action.equals("DeleteLogStream"));
        if (action.equals("DeleteLogStream")) {
            assertEvent(group, "protected");
        }
        stream(caller, action, group, "allowed").statusCode(200);
        assertPresent(group, "allowed", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void numericNamesUseTheSameTargetAsTheHandler(String action) {
        String group = group(Map.of());
        prepare(action, group, "42");
        String caller = user(policy("Allow", action, "*", "") + ","
                + policy("Deny", action, streamArn(group, "42"), ""));
        call(endpoint.getPort(), caller, action, Map.of("logGroupName", group, "logStreamName", 42))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertPresent(group, "42", action.equals("DeleteLogStream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void sameNamedGroupsKeepTheirAccountAndRegionTags(String action) {
        String group = "logs-stream-scope-" + UUID.randomUUID();
        String otherAccount = "222222222222";
        String otherRegion = "eu-west-1";
        createGroup(ACCOUNT, REGION, group, Map.of("team", "blue"));
        createGroup(ACCOUNT, otherRegion, group, Map.of("team", "red"));
        createGroup(otherAccount, REGION, group, Map.of("team", "red"));
        if (action.equals("DeleteLogStream")) {
            for (String[] scope : List.of(new String[]{ACCOUNT, REGION}, new String[]{ACCOUNT, otherRegion},
                    new String[]{otherAccount, REGION})) {
                call(endpoint.getPort(), scope[0], scope[1], "CreateLogStream",
                        Map.of("logGroupName", group, "logStreamName", "selected")).statusCode(200);
            }
        }
        String statements = policy("Allow", action, "*",
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}");
        String caller = user(statements);
        String otherCaller = user(otherAccount, statements);
        call(endpoint.getPort(), caller, otherRegion, action,
                Map.of("logGroupName", group, "logStreamName", "selected"))
                .statusCode(400).body(containsString("AccessDeniedException"));
        call(endpoint.getPort(), otherCaller, REGION, action,
                Map.of("logGroupName", group, "logStreamName", "selected"))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertPresent(ACCOUNT, otherRegion, group, "selected", action.equals("DeleteLogStream"));
        assertPresent(otherAccount, REGION, group, "selected", action.equals("DeleteLogStream"));
        stream(caller, action, group, "selected").statusCode(200);
        assertPresent(group, "selected", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void missingGroupKeepsTheHandlersResourceNotFoundResponse(String action) {
        String caller = user(policy("Allow", action, "*", ""));
        stream(caller, action, "missing-" + UUID.randomUUID(), "selected")
                .statusCode(400).body(containsString("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void queryCredentialsAuthorizeAndMutateTheSameRegion(String action) {
        String group = "logs-query-scope-" + UUID.randomUUID();
        String region = "eu-west-1";
        createGroup(ACCOUNT, REGION, group, Map.of("team", "red"));
        createGroup(ACCOUNT, region, group, Map.of("team", "blue"));
        if (action.equals("DeleteLogStream")) {
            for (String targetRegion : List.of(REGION, region)) {
                call(endpoint.getPort(), ACCOUNT, targetRegion, "CreateLogStream",
                        Map.of("logGroupName", group, "logStreamName", "selected")).statusCode(200);
            }
        }
        String resource = "arn:aws:logs:" + region + ":" + ACCOUNT + ":log-group:" + group + ":log-stream:selected";
        String caller = user(policy("Allow", action, resource,
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}"));
        given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", caller + "/20261015/" + region + "/logs/aws4_request")
                .queryParam("X-Amz-Signature", "abc")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(Map.of("logGroupName", group, "logStreamName", "selected"))
                .when().post("/").then().statusCode(200);
        assertPresent(ACCOUNT, REGION, group, "selected", action.equals("DeleteLogStream"));
        assertPresent(ACCOUNT, region, group, "selected", action.equals("CreateLogStream"));
        // A real Authorization header remains authoritative over conflicting query credentials.
        given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("Authorization", EnforcementFixtures.auth(caller, "logs"))
                .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", caller + "/20261015/" + region + "/logs/aws4_request")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(Map.of("logGroupName", group, "logStreamName", "selected"))
                .when().post("/").then().statusCode(400).body(containsString("AccessDeniedException"));
        assertPresent(ACCOUNT, REGION, group, "selected", action.equals("DeleteLogStream"));
        assertPresent(ACCOUNT, region, group, "selected", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @CsvSource({"CreateLogStream,blank-header", "DeleteLogStream,blank-header",
            "CreateLogStream,missing-algorithm", "DeleteLogStream,missing-algorithm"})
    void credentialEdgeCasesKeepTheCanonicalRequestRegion(String action, String mode) {
        String account = "000000000000";
        String remote = "eu-west-1";
        String group = "logs-query-edge-" + UUID.randomUUID();
        boolean blankHeader = mode.equals("blank-header");
        String selectedRegion = blankHeader ? remote : REGION;
        String otherRegion = blankHeader ? REGION : remote;
        createGroup(account, selectedRegion, group, Map.of("team", "blue"));
        createGroup(account, otherRegion, group, Map.of("team", "red"));
        if (action.equals("DeleteLogStream")) {
            for (String region : List.of(REGION, remote)) {
                call(endpoint.getPort(), account, region, "CreateLogStream",
                        Map.of("logGroupName", group, "logStreamName", "selected")).statusCode(200);
            }
        }
        String resource = "arn:aws:logs:" + selectedRegion + ":" + account
                + ":log-group:" + group + ":log-stream:selected";
        String caller = user(account, policy("Allow", action, resource,
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}"));
        RequestSpecification request = given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .queryParam("X-Amz-Credential", caller + "/20261015/" + remote + "/logs/aws4_request")
                .queryParam("X-Amz-Signature", "abc")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(Map.of("logGroupName", group, "logStreamName", "selected"));
        if (blankHeader) {
            request.header("Authorization", "").queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        }
        request.when().post("/").then().statusCode(200);
        assertPresent(account, otherRegion, group, "selected", action.equals("DeleteLogStream"));
        assertPresent(account, selectedRegion, group, "selected", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageKeyAliasesCannotDeleteAnotherGroupsStream(boolean exactGrant) {
        String group = "logs-key-alias-" + UUID.randomUUID();
        String protectedGroup = group + "::x";
        createGroup(ACCOUNT, REGION, group, Map.of("team", "blue"));
        seedHistoricalGroup(protectedGroup, Map.of("team", "red"));
        seedHistoricalStream(protectedGroup, "s");
        assertPresent(protectedGroup, "s", true);
        assertEvent(protectedGroup, "s");
        String caller = user(policy("Allow", "DeleteLogStream", exactGrant ? streamArn(group, "x::s") : "*",
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}"));
        Response response = stream(caller, "DeleteLogStream", group, "x::s").extract().response();
        assertAll("reject storage alias and retain historical stream and event",
                () -> response.then().statusCode(400).body(containsString("InvalidParameterException")),
                () -> assertPresent(protectedGroup, "s", true),
                () -> assertEvent(protectedGroup, "s"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void arnDelimiterAliasesCannotGrantAccessToAnotherGroup(String action) {
        String group = "logs-arn-alias-" + UUID.randomUUID();
        String actualGroup = group + ":log-stream:x";
        createGroup(ACCOUNT, REGION, group, Map.of());
        seedHistoricalGroup(actualGroup, Map.of());
        boolean delete = action.equals("DeleteLogStream");
        if (delete) {
            seedHistoricalStream(actualGroup, "s");
        }
        prepare(action, group, "safe");
        assertPresent(actualGroup, "s", delete);
        String caller = user(policy("Allow", action, streamArn(group, "x:log-stream:s"), "")
                + "," + policy("Allow", action, streamArn(group, "safe"), ""));
        Response response = stream(caller, action, actualGroup, "s").extract().response();
        assertAll("reject ambiguous ARN identity without changing the stored target",
                () -> response.then().statusCode(400).body(containsString("InvalidParameterException")),
                () -> assertPresent(actualGroup, "s", delete),
                () -> {
                    if (delete) {
                        assertEvent(actualGroup, "s");
                    }
                });
        stream(caller, action, group, "safe").statusCode(200);
        assertPresent(group, "safe", !delete);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void publicStreamNamesFollowTheDocumentedConstraints(String action) {
        String group = group(Map.of());
        String caller = user(policy("Allow", action, "*", ""));
        prepare(action, group, "selected");
        for (String invalid : List.of("", "bad:group", "bad*group", "bad group", "g".repeat(513))) {
            stream(caller, action, invalid, "selected").statusCode(400)
                    .body(containsString("InvalidParameterException"));
        }
        for (String invalid : List.of("", "bad:stream", "bad*stream", "s".repeat(513))) {
            stream(caller, action, group, invalid).statusCode(400)
                    .body(containsString("InvalidParameterException"));
        }
        assertPresent(group, "selected", action.equals("DeleteLogStream"));
        if (action.equals("DeleteLogStream")) {
            assertEvent(group, "selected");
        }
        stream(caller, action, group, "selected").statusCode(200);
        assertPresent(group, "selected", action.equals("CreateLogStream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream"})
    void maximumLengthAndWhitespaceStreamNamesRemainUsable(String action) {
        String prefix = "logs-name-boundary-" + UUID.randomUUID();
        String group = prefix + "g".repeat(512 - prefix.length());
        createGroup(ACCOUNT, REGION, group, Map.of());
        String caller = user(policy("Allow", action, "*", ""));
        for (String stream : List.of("s", " ", "🚀".repeat(512))) {
            prepare(action, group, stream);
            stream(caller, action, group, stream).statusCode(200);
            assertPresent(group, stream, action.equals("CreateLogStream"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s::child", "s::child::nested"})
    void deletingAnAuthorizedStreamPreservesHistoricalSiblingEvents(String sibling) {
        String group = group(Map.of());
        seedHistoricalStream(group, "s", "selected-event");
        seedHistoricalStream(group, sibling, "protected-sibling-event");
        assertPresent(group, "s", true);
        assertPresent(group, sibling, true);
        call(endpoint.getPort(), ACCOUNT, "GetLogEvents", Map.of("logGroupName", group, "logStreamName", sibling))
                .statusCode(200).body("events[0].message", equalTo("protected-sibling-event"));
        String caller = user(policy("Allow", "DeleteLogStream", streamArn(group, "s"), ""));
        stream(caller, "DeleteLogStream", group, "s").statusCode(200);
        assertAll("delete only the selected stream and its emitted events",
                () -> assertPresent(group, "s", false),
                () -> assertPresent(group, sibling, true),
                () -> call(endpoint.getPort(), ACCOUNT, "GetLogEvents",
                        Map.of("logGroupName", group, "logStreamName", sibling))
                        .statusCode(200).body("events[0].message", equalTo("protected-sibling-event")),
                () -> call(endpoint.getPort(), ACCOUNT, "GetLogEvents",
                        Map.of("logGroupName", group, "logStreamName", "s"))
                        .statusCode(200).body("events.message", not(hasItem("selected-event"))));
    }

    /** Existing service storage APIs seed historical invalid names without bypassing the HTTP request under test. */
    private void seedHistoricalGroup(String group, Map<String, String> tags) {
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> logsService.deleteLogGroup(group, REGION)));
        logsService.createLogGroupForAccount(ACCOUNT, group, null, tags, REGION);
    }

    private void seedHistoricalStream(String group, String stream) {
        seedHistoricalStream(group, stream, "retained");
    }

    private void seedHistoricalStream(String group, String stream, String message) {
        logsService.createLogStreamForAccount(ACCOUNT, group, stream, REGION);
        logsService.putLogEventsForAccount(ACCOUNT, group, stream,
                List.of(Map.of("timestamp", System.currentTimeMillis(), "message", message)), REGION);
    }

    private void prepare(String action, String group, String name) {
        if (action.equals("DeleteLogStream")) {
            stream(ACCOUNT, "CreateLogStream", group, name).statusCode(200);
            call(endpoint.getPort(), ACCOUNT, "PutLogEvents", Map.of("logGroupName", group,
                    "logStreamName", name, "logEvents", List.of(Map.of("timestamp", System.currentTimeMillis(),
                            "message", "retained")))).statusCode(200);
        }
    }

    private String group(Map<String, String> tags) {
        String group = "logs-stream-auth-" + UUID.randomUUID();
        createGroup(ACCOUNT, REGION, group, tags);
        return group;
    }

    private void createGroup(String account, String region, String group, Map<String, String> tags) {
        int port = endpoint.getPort();
        cleanup.register(() -> call(port, account, region, "DeleteLogGroup", Map.of("logGroupName", group))
                .statusCode(200));
        call(port, account, region, "CreateLogGroup", Map.of("logGroupName", group, "tags", tags)).statusCode(200);
    }

    private String user(String statements) {
        return user(ACCOUNT, statements);
    }

    private String user(String account, String statements) {
        return LogsAuthorizationFixtures.user(cleanup, endpoint.getPort(), account, "logs-stream-auth-",
                "streams", statements);
    }

    private static String policy(String effect, String action, String resource, String condition) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + resource + "\"" + condition + "}";
    }

    private static String streamArn(String group, String stream) {
        return "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:" + group + ":log-stream:" + stream;
    }

    private ValidatableResponse stream(String caller, String action, String group, String stream) {
        return call(endpoint.getPort(), caller, action, Map.of("logGroupName", group, "logStreamName", stream));
    }

    private void assertPresent(String group, String stream, boolean expected) {
        assertPresent(ACCOUNT, REGION, group, stream, expected);
    }

    private void assertEvent(String group, String stream) {
        call(endpoint.getPort(), ACCOUNT, "GetLogEvents", Map.of("logGroupName", group, "logStreamName", stream))
                .statusCode(200).body("events[0].message", equalTo("retained"));
    }

    private void assertPresent(String account, String region, String group, String stream, boolean expected) {
        List<Map<String, Object>> streams = call(endpoint.getPort(), account, region, "DescribeLogStreams",
                Map.of("logGroupName", group)).statusCode(200).extract().path("logStreams");
        assertEquals(expected, streams.stream().anyMatch(item -> stream.equals(item.get("logStreamName"))));
    }

    private static ValidatableResponse call(int port, String caller, String action, Map<String, ?> body) {
        return call(port, caller, REGION, action, body);
    }

    private static ValidatableResponse call(int port, String caller, String region, String action, Map<String, ?> body) {
        return LogsAuthorizationFixtures.call(port, caller, region, action, body).then();
    }
}
