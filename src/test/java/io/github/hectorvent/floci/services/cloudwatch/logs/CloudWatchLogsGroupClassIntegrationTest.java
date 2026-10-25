package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudWatchLogsGroupClassIntegrationTest {

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"CreateLogGroup,7", "CreateLogGroup,true", "CreateLogGroup,[]", "CreateLogGroup,{}",
            "DescribeLogGroups,7", "DescribeLogGroups,true", "DescribeLogGroups,[]", "DescribeLogGroups,{}"})
    void rejectsNonStringClasses(String action, String jsonValue) {
        String name = "/class-shape-6232/" + UUID.randomUUID();
        Response response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body("{\"logGroupName\":\"" + name + "\",\"logGroupClass\":" + jsonValue + "}").post("/");
        try {
            response.then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        } finally {
            if ("CreateLogGroup".equals(action) && response.statusCode() == 200) {
                given().contentType("application/x-amz-json-1.1")
                        .header("X-Amz-Target", "Logs_20140328.DeleteLogGroup")
                        .body("{\"logGroupName\":\"" + name + "\"}").post("/").then().statusCode(200);
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"OMITTED,STANDARD", "STANDARD,STANDARD", "INFREQUENT_ACCESS,INFREQUENT_ACCESS",
            "DELIVERY,DELIVERY"}, nullValues = "OMITTED")
    void sdkReturnsTheCreatedClassAndFiltersIt(String requestedClass, String expectedClass) {
        String group = "/class-6232/" + UUID.randomUUID();
        try (CloudWatchLogsClient client = client()) {
            client.createLogGroup(request -> request.logGroupName(group).logGroupClass(requestedClass));
            try {
                List<LogGroup> groups = client.describeLogGroups(request -> request.logGroupNamePrefix(group)).logGroups();
                assertEquals(1, groups.size());
                assertEquals(expectedClass, groups.getFirst().logGroupClassAsString());
                assertEquals(groups, client.describeLogGroups(request -> request.logGroupNamePrefix(group)
                        .logGroupClass(expectedClass)).logGroups());
                String otherClass = "STANDARD".equals(expectedClass) ? "DELIVERY" : "STANDARD";
                assertEquals(List.of(), client.describeLogGroups(request -> request.logGroupNamePrefix(group)
                        .logGroupClass(otherClass)).logGroups());
            } finally {
                client.deleteLogGroup(request -> request.logGroupName(group));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOO", "COLD", "", "standard"})
    void sdkRejectsAnInvalidClassOnCreate(String invalidClass) {
        String group = "/class-invalid-6232/" + UUID.randomUUID();
        try (CloudWatchLogsClient client = client()) {
            try {
                CloudWatchLogsException invalid = assertThrows(CloudWatchLogsException.class,
                        () -> client.createLogGroup(request -> request.logGroupName(group).logGroupClass(invalidClass)));
                assertEquals("InvalidParameterException", invalid.awsErrorDetails().errorCode());
                assertEquals(400, invalid.statusCode());
                assertEquals("1 validation error detected: Value '" + invalidClass
                        + "' at 'logGroupClass' failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[STANDARD, INFREQUENT_ACCESS, DELIVERY]", invalid.awsErrorDetails().errorMessage());
            } finally {
                if (!client.describeLogGroups(request -> request.logGroupNamePrefix(group)).logGroups().isEmpty()) {
                    client.deleteLogGroup(request -> request.logGroupName(group));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOO", "COLD", "", "standard"})
    void sdkRejectsAnInvalidClassOnDescribe(String invalidClass) {
        try (CloudWatchLogsClient client = client()) {
            CloudWatchLogsException invalid = assertThrows(CloudWatchLogsException.class,
                    () -> client.describeLogGroups(request -> request.logGroupClass(invalidClass)));
            assertEquals("InvalidParameterException", invalid.awsErrorDetails().errorCode());
            assertEquals(400, invalid.statusCode());
            assertEquals("1 validation error detected: Value '" + invalidClass
                    + "' at 'logGroupClass' failed to satisfy constraint: Member must satisfy enum value set: "
                    + "[STANDARD, INFREQUENT_ACCESS, DELIVERY]", invalid.awsErrorDetails().errorMessage());
        }
    }

    @Test
    void sdkFiltersBeforePaginationAndBindsTheCursorToTheClass() {
        String prefix = "/class-pages-6232/" + UUID.randomUUID() + "/";
        List<String> names = List.of("a", "b", "c", "d", "e");
        try (CloudWatchLogsClient client = client()) {
            try {
                for (String name : names) {
                    String groupClass = List.of("b", "d").contains(name) ? "INFREQUENT_ACCESS" : "STANDARD";
                    client.createLogGroup(request -> request.logGroupName(prefix + name).logGroupClass(groupClass));
                }
                DescribeLogGroupsResponse first = client.describeLogGroups(request -> request.logGroupNamePrefix(prefix)
                        .logGroupClass("INFREQUENT_ACCESS").limit(1));
                assertEquals(List.of(prefix + "b"), first.logGroups().stream().map(LogGroup::logGroupName).toList());
                assertNotNull(first.nextToken());
                DescribeLogGroupsResponse second = client.describeLogGroups(request -> request.logGroupNamePrefix(prefix)
                        .logGroupClass("INFREQUENT_ACCESS").limit(1).nextToken(first.nextToken()));
                assertEquals(List.of(prefix + "d"), second.logGroups().stream().map(LogGroup::logGroupName).toList());
                assertNull(second.nextToken());
                CloudWatchLogsException invalid = assertThrows(CloudWatchLogsException.class,
                        () -> client.describeLogGroups(request -> request.logGroupNamePrefix(prefix)
                                .logGroupClass("STANDARD").nextToken(first.nextToken())));
                assertEquals("InvalidParameterException", invalid.awsErrorDetails().errorCode());
                CloudWatchLogsException omitted = assertThrows(CloudWatchLogsException.class,
                        () -> client.describeLogGroups(request -> request.logGroupNamePrefix(prefix)
                                .nextToken(first.nextToken())));
                assertEquals("InvalidParameterException", omitted.awsErrorDetails().errorCode());
            } finally {
                for (LogGroup group : client.describeLogGroups(request -> request.logGroupNamePrefix(prefix)).logGroups()) {
                    client.deleteLogGroup(request -> request.logGroupName(group.logGroupName()));
                }
            }
        }
    }

    private static CloudWatchLogsClient client() {
        return CloudWatchLogsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("741623200001", "test")))
                .build();
    }
}
