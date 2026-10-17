package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.testing.LogsAuthorizationFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
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

import static io.github.hectorvent.floci.testing.LogsAuthorizationFixtures.expectedTags;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(LogsUnknownRegionAuthorizationIntegrationTest.UnknownRegionProfile.class)
class LogsUnknownRegionAuthorizationIntegrationTest {

    public static class UnknownRegionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true",
                    "floci.partitions.allow-unknown-regions", "true");
        }
    }

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
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void directTagArnRejectsColonRegionAndAcceptsUnambiguousUnknownRegion(String action) {
        String region = "custom-region-1";
        String group = "logs-tag-marker-" + UUID.randomUUID();
        int port = endpoint.getPort();
        cleanup.register(() -> call(port, ACCOUNT, region, "DeleteLogGroup", Map.of("logGroupName", group))
                .then().statusCode(200));
        logsService.createLogGroupForAccount(ACCOUNT, group, null, Map.of("team", "blue"), region);
        String caller = LogsAuthorizationFixtures.user(cleanup, port, ACCOUNT, "logs-tag-marker-", "marker-tags",
                "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"}");
        Map<String, Object> body = LogsAuthorizationFixtures.tagBody(action,
                "arn:aws:logs:" + region + ":" + ACCOUNT + ":log-group:" + group);
        call(port, caller, "custom:log-group:zone", action, body)
                .then().statusCode(400).body(containsString("InvalidSignatureException"));
        call(port, ACCOUNT, region, "ListTagsLogGroup", Map.of("logGroupName", group))
                .then().statusCode(200).body("tags", equalTo(Map.of("team", "blue")));
        Response result = call(port, caller, region, action, body);
        result.then().statusCode(200);
        Map<String, String> expected = expectedTags(action);
        if (action.equals("ListTagsForResource")) {
            result.then().body("tags", equalTo(expected));
        }
        call(port, ACCOUNT, region, "ListTagsLogGroup", Map.of("logGroupName", group))
                .then().statusCode(200).body("tags", equalTo(expected));
    }

    @ParameterizedTest
    @CsvSource({"CreateLogStream,false,false", "DeleteLogStream,false,false", "CreateLogStream,true,false",
            "DeleteLogStream,true,false", "DeleteLogStream,false,true"})
    void optedInRegionLabelsCannotAliasHistoricalGroupNames(String action, boolean conditional, boolean streamAlias) {
        int port = endpoint.getPort();
        String group = "logs-region-alias-" + UUID.randomUUID();
        String historical = streamAlias ? group : "x::" + group;
        String historicalStream = streamAlias ? "x::" + group + "::s" : "s";
        String aliasRegion = streamAlias ? REGION + "::" + group + "::x" : REGION + "::x";
        String validUnknownRegion = "custom-region-1";
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> logsService.deleteLogGroup(historical, REGION)));
        cleanup.register(() -> call(port, ACCOUNT, validUnknownRegion, "DeleteLogGroup", Map.of("logGroupName", group))
                .then().statusCode(200));
        logsService.createLogGroupForAccount(ACCOUNT, historical, null, Map.of("team", "blue"), REGION);
        logsService.createLogGroupForAccount(ACCOUNT, group, null, Map.of("team", "blue"), validUnknownRegion);
        boolean delete = action.equals("DeleteLogStream");
        if (delete) {
            for (String[] target : List.of(new String[]{historical, REGION, historicalStream},
                    new String[]{group, validUnknownRegion, "s"})) {
                logsService.createLogStreamForAccount(ACCOUNT, target[0], target[2], target[1]);
                logsService.putLogEventsForAccount(ACCOUNT, target[0], target[2],
                        List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "retained")), target[1]);
            }
        }
        assertPresent(port, REGION, historical, historicalStream, delete);
        String condition = conditional ? ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}" : "";
        String resources = "[\"arn:aws:logs:" + aliasRegion + ":" + ACCOUNT + ":log-group:" + group
                + ":log-stream:s\",\"arn:aws:logs:" + validUnknownRegion + ":" + ACCOUNT
                + ":log-group:" + group + ":log-stream:s\"]";
        String caller = LogsAuthorizationFixtures.user(cleanup, port, ACCOUNT, "logs-region-alias-", "streams",
                "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action
                + "\",\"Resource\":" + resources + condition + "}");
        Response response = call(port, caller, aliasRegion, action,
                Map.of("logGroupName", group, "logStreamName", "s"));
        assertAll("preserve stored identity across an opted-in region delimiter",
                () -> response.then().statusCode(400).body(containsString(
                        "InvalidSignatureException")),
                () -> assertPresent(port, REGION, historical, historicalStream, delete),
                () -> {
                    if (delete) {
                        call(port, ACCOUNT, REGION, "GetLogEvents",
                                Map.of("logGroupName", historical, "logStreamName", historicalStream))
                                .then().statusCode(200).body("events[0].message", equalTo("retained"));
                    }
                });
        call(port, caller, validUnknownRegion, action, Map.of("logGroupName", group, "logStreamName", "s"))
                .then().statusCode(200);
        assertPresent(port, validUnknownRegion, group, !delete);
    }

    @ParameterizedTest
    @CsvSource({"CreateLogStream,Allow,false", "DeleteLogStream,Allow,false",
            "CreateLogStream,Allow,true", "DeleteLogStream,Allow,true",
            "CreateLogStream,Deny,false", "DeleteLogStream,Deny,false",
            "CreateLogStream,Deny,true", "DeleteLogStream,Deny,true"})
    void resourceAccountUsesStorageOwnerInOptedInRegion(String action, String effect, boolean actualOwner) {
        int port = endpoint.getPort();
        String otherAccount = "222222222222";
        String region = "custom-" + otherAccount;
        String group = "logs-resource-owner-" + UUID.randomUUID();
        cleanup.register(() -> call(port, ACCOUNT, region, "DeleteLogGroup", Map.of("logGroupName", group))
                .then().statusCode(200));
        logsService.createLogGroupForAccount(ACCOUNT, group, null, null, region);
        boolean delete = action.equals("DeleteLogStream");
        if (delete) {
            logsService.createLogStreamForAccount(ACCOUNT, group, "s", region);
            logsService.putLogEventsForAccount(ACCOUNT, group, "s",
                    List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "retained")), region);
        }
        String grant = effect.equals("Deny")
                ? "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"}," : "";
        String owner = actualOwner ? ACCOUNT : otherAccount;
        String statement = "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceAccount\":\""
                + owner + "\"}}}";
        String caller = LogsAuthorizationFixtures.user(cleanup, port, ACCOUNT, "logs-resource-owner-", "streams",
                grant + statement);
        boolean allowed = effect.equals("Allow") == actualOwner;
        Response response = call(port, caller, region, action, Map.of("logGroupName", group, "logStreamName", "s"));
        assertAll("use the owning account in an admitted custom region",
                () -> response.then().statusCode(allowed ? 200 : 400),
                () -> assertPresent(port, region, group, allowed != delete),
                () -> {
                    if (!allowed) {
                        response.then().body(containsString("AccessDeniedException"));
                        if (delete) {
                            call(port, ACCOUNT, region, "GetLogEvents",
                                    Map.of("logGroupName", group, "logStreamName", "s"))
                                    .then().statusCode(200).body("events[0].message", equalTo("retained"));
                        }
                    }
                });
    }

    @ParameterizedTest
    @CsvSource({"TagResource,Allow", "TagLogGroup,Allow", "TagResource,Deny", "TagLogGroup,Deny"})
    void taggedGroupCreationUsesCanonicalOwnerForBothAlternatives(String taggingAction, String effect) {
        int port = endpoint.getPort();
        String region = "custom-222222222222";
        String group = "logs-create-owner-" + UUID.randomUUID();
        cleanup.register(() -> {
            List<Map<String, Object>> groups = call(port, ACCOUNT, region, "DescribeLogGroups",
                    Map.of("logGroupNamePrefix", group)).then().statusCode(200).extract().path("logGroups");
            if (groups.stream().anyMatch(item -> group.equals(item.get("logGroupName")))) {
                call(port, ACCOUNT, region, "DeleteLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
            }
        });
        String statements = "{\"Effect\":\"Allow\",\"Action\":\"logs:CreateLogGroup\",\"Resource\":\"*\"},";
        if (effect.equals("Deny")) {
            statements += "{\"Effect\":\"Allow\",\"Action\":\"logs:" + taggingAction + "\",\"Resource\":\"*\"},";
        }
        statements += "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + taggingAction
                + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceAccount\":\""
                + ACCOUNT + "\"}}}";
        String caller = LogsAuthorizationFixtures.user(cleanup, port, ACCOUNT, "logs-create-owner-",
                "tagging-owner", statements);
        Response response = call(port, caller, region, "CreateLogGroup",
                Map.of("logGroupName", group, "tags", Map.of("team", "blue")));
        List<Map<String, Object>> groups = call(port, ACCOUNT, region, "DescribeLogGroups",
                Map.of("logGroupNamePrefix", group)).then().statusCode(200).extract().path("logGroups");
        boolean allowed = effect.equals("Allow");
        assertAll("both tagging alternatives use the actual storage owner",
                () -> response.then().statusCode(allowed ? 200 : 400),
                () -> assertEquals(allowed, groups.stream().anyMatch(item -> group.equals(item.get("logGroupName")))),
                () -> {
                    if (!allowed) {
                        response.then().body(containsString("AccessDeniedException"));
                    }
                });
    }

    private static void assertPresent(int port, String region, String group, boolean expected) {
        assertPresent(port, region, group, "s", expected);
    }

    private static void assertPresent(int port, String region, String group, String name, boolean expected) {
        List<Map<String, Object>> streams = call(port, ACCOUNT, region, "DescribeLogStreams",
                Map.of("logGroupName", group)).then().statusCode(200).extract().path("logStreams");
        assertEquals(expected, streams.stream().anyMatch(stream -> name.equals(stream.get("logStreamName"))));
    }

    private static Response call(int port, String caller, String region, String action, Map<String, ?> body) {
        return LogsAuthorizationFixtures.call(port, caller, region, action, body);
    }
}
