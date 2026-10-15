package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
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
    @CsvSource({"CreateLogStream,false,false", "DeleteLogStream,false,false", "CreateLogStream,true,false",
            "DeleteLogStream,true,false", "DeleteLogStream,false,true"})
    void optedInRegionLabelsCannotAliasHistoricalGroupNames(String action, boolean conditional, boolean streamAlias) {
        int port = endpoint.getPort();
        String group = "logs-region-alias-" + UUID.randomUUID();
        String historical = streamAlias ? group : "x::" + group;
        String historicalStream = streamAlias ? "x::" + group + "::s" : "s";
        String aliasRegion = streamAlias ? REGION + "::" + group + "::x" : REGION + "::x";
        String validUnknownRegion = "custom-region-1";
        cleanup.register(() -> call(port, ACCOUNT, REGION, "DeleteLogGroup", Map.of("logGroupName", historical))
                .then().statusCode(200));
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
        String user = "logs-region-alias-" + UUID.randomUUID();
        String root = EnforcementFixtures.auth(ACCOUNT, "iam");
        String caller = EnforcementFixtures.createUser(cleanup, port, root, user).accessKeyId();
        String condition = conditional ? ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}" : "";
        String resources = "[\"arn:aws:logs:" + aliasRegion + ":" + ACCOUNT + ":log-group:" + group
                + ":log-stream:s\",\"arn:aws:logs:" + validUnknownRegion + ":" + ACCOUNT
                + ":log-group:" + group + ":log-stream:s\"]";
        given().port(port).header("Authorization", root).formParam("Action", "PutUserPolicy")
                .formParam("UserName", user).formParam("PolicyName", "streams")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"logs:" + action + "\",\"Resource\":" + resources + condition + "}]}")
                .when().post("/").then().statusCode(200);
        Response response = call(port, caller, aliasRegion, action,
                Map.of("logGroupName", group, "logStreamName", "s"));
        assertAll("preserve stored identity across an opted-in region delimiter",
                () -> response.then().statusCode(400).body(containsString(
                        conditional ? "AccessDeniedException" : "ResourceNotFoundException")),
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
        String region = "custom:" + otherAccount;
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
        String user = "logs-resource-owner-" + UUID.randomUUID();
        String root = EnforcementFixtures.auth(ACCOUNT, "iam");
        String caller = EnforcementFixtures.createUser(cleanup, port, root, user).accessKeyId();
        String grant = effect.equals("Deny")
                ? "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"}," : "";
        String owner = actualOwner ? ACCOUNT : otherAccount;
        String statement = "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceAccount\":\""
                + owner + "\"}}}";
        given().port(port).header("Authorization", root).formParam("Action", "PutUserPolicy")
                .formParam("UserName", user).formParam("PolicyName", "streams")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + grant + statement + "]}")
                .when().post("/").then().statusCode(200);
        boolean allowed = effect.equals("Allow") == actualOwner;
        Response response = call(port, caller, region, action, Map.of("logGroupName", group, "logStreamName", "s"));
        assertAll("use the canonical account independently of region punctuation",
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

    private static void assertPresent(int port, String region, String group, boolean expected) {
        assertPresent(port, region, group, "s", expected);
    }

    private static void assertPresent(int port, String region, String group, String name, boolean expected) {
        List<Map<String, Object>> streams = call(port, ACCOUNT, region, "DescribeLogStreams",
                Map.of("logGroupName", group)).then().statusCode(200).extract().path("logStreams");
        assertEquals(expected, streams.stream().anyMatch(stream -> name.equals(stream.get("logStreamName"))));
    }

    private static Response call(int port, String caller, String region, String action, Map<String, ?> body) {
        String auth = "AWS4-HMAC-SHA256 Credential=" + caller + "/20261015/" + region
                + "/logs/aws4_request, SignedHeaders=host, Signature=abc";
        return given().port(port).contentType("application/x-amz-json-1.1").header("Authorization", auth)
                .header("X-Amz-Target", "Logs_20140328." + action).body(body).when().post("/");
    }
}
