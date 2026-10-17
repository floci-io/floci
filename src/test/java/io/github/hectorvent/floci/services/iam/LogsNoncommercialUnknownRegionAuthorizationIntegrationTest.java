package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.LogsAuthorizationFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(LogsNoncommercialUnknownRegionAuthorizationIntegrationTest.ChinaProfile.class)
class LogsNoncommercialUnknownRegionAuthorizationIntegrationTest {
    public static class ChinaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true",
                    "floci.partitions.allow-unknown-regions", "true", "floci.default-region", "cn-north-1");
        }
    }

    public static class GovProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true",
                    "floci.partitions.allow-unknown-regions", "true", "floci.default-region", "us-gov-west-1");
        }
    }

    private static final String ACCOUNT = "111111111111";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();
    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"CreateLogStream,false,polygondwanaland-west-1", "DeleteLogStream,false,polygondwanaland-west-1",
            "CreateLogStream,true,polygondwanaland-west-1", "DeleteLogStream,true,polygondwanaland-west-1",
            "CreateLogStream,false,cn-north-1", "DeleteLogStream,false,cn-north-1",
            "CreateLogStream,true,cn-north-1", "DeleteLogStream,true,cn-north-1",
            "CreateLogStream,false,us-gov-west-1", "DeleteLogStream,false,us-gov-west-1",
            "CreateLogStream,true,us-gov-west-1", "DeleteLogStream,true,us-gov-west-1",
            "CreateLogStream,false,us-east-1", "DeleteLogStream,false,us-east-1",
            "CreateLogStream,true,us-east-1", "DeleteLogStream,true,us-east-1"})
    void streamPoliciesUseTheSamePartitionAsTheStoredGroup(String action, boolean deny, String region) {
        int port = endpoint.getPort();
        String group = "logs-partition-" + UUID.randomUUID();
        cleanup.register(() -> call(ACCOUNT, region, "DeleteLogGroup", Map.of("logGroupName", group))
                .then().statusCode(200));
        call(ACCOUNT, region, "CreateLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        String partition = switch (region) {
            case "cn-north-1" -> "aws-cn";
            case "us-gov-west-1" -> "aws-us-gov";
            case "us-east-1" -> "aws";
            default -> deploymentPartition();
        };
        String groupArn = "arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":log-group:" + group;
        call(ACCOUNT, region, "DescribeLogGroups", Map.of("logGroupNamePrefix", group))
                .then().statusCode(200).body("logGroups[0].arn", equalTo(groupArn + ":*"));
        boolean delete = action.equals("DeleteLogStream");
        if (delete) {
            call(ACCOUNT, region, "CreateLogStream", Map.of("logGroupName", group, "logStreamName", "s"))
                    .then().statusCode(200);
            call(ACCOUNT, region, "PutLogEvents", Map.of("logGroupName", group, "logStreamName", "s",
                    "logEvents", List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "retained"))))
                    .then().statusCode(200);
        }
        String allow = deny ? "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"*\"}," : "";
        String effect = deny ? "Deny" : "Allow";
        String statement = "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + groupArn + ":log-stream:s\"}";
        String caller = LogsAuthorizationFixtures.user(cleanup, port, ACCOUNT, "logs-partition-", "streams",
                allow + statement);
        Response response = call(caller, region, action, Map.of("logGroupName", group, "logStreamName", "s"));
        assertAll("canonical partition governs the same stream and retained events",
                () -> response.then().statusCode(deny ? 400 : 200),
                () -> {
                    if (deny) {
                        response.then().body(containsString("AccessDeniedException"));
                    }
                },
                () -> {
                    List<Map<String, Object>> streams = call(ACCOUNT, region, "DescribeLogStreams",
                            Map.of("logGroupName", group)).then().statusCode(200).extract().path("logStreams");
                    assertEquals(deny == delete, streams.stream().anyMatch(s -> "s".equals(s.get("logStreamName"))));
                },
                () -> {
                    if (deny && delete) {
                        call(ACCOUNT, region, "GetLogEvents", Map.of("logGroupName", group, "logStreamName", "s"))
                                .then().statusCode(200).body("events[0].message", equalTo("retained"));
                    }
                });
    }

    String deploymentPartition() {
        return "aws-cn";
    }

    private Response call(String caller, String region, String action, Map<String, ?> body) {
        return LogsAuthorizationFixtures.call(endpoint.getPort(), caller, region, action, body);
    }
}
