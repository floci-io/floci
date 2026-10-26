package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudWatchLogsMissingResourceIntegrationTest {

    private static final String STREAM = "app";

    @Test
    void putLogEventsReportsTheMissingGroupThenTheMissingStreamAsAwsDoes() {
        String group = "/missing-resource/" + UUID.randomUUID();
        try (CloudWatchLogsClient logs = client()) {
            assertResourceNotFound("The specified log group does not exist.", () -> put(logs, group));
            assertResourceNotFound("The specified log group does not exist.",
                    () -> logs.describeLogStreams(request -> request.logGroupName(group)));

            logs.createLogGroup(request -> request.logGroupName(group));
            try {
                assertResourceNotFound("The specified log stream does not exist.", () -> put(logs, group));

                logs.createLogStream(request -> request.logGroupName(group).logStreamName(STREAM));
                put(logs, group);
                assertEquals(1, logs.getLogEvents(request -> request.logGroupName(group).logStreamName(STREAM))
                        .events().size());
            } finally {
                logs.deleteLogGroup(request -> request.logGroupName(group));
            }
        }
    }

    @Test
    void filterLogEventsChecksGroupBeforePatternAndPreservesExistingGroupResults() {
        String group = "/filter-missing-resource/" + UUID.randomUUID();
        try (CloudWatchLogsClient logs = client()) {
            assertResourceNotFound("The specified log group does not exist.",
                    () -> logs.filterLogEvents(request -> request.logGroupName(group)));
            assertResourceNotFound("The specified log group does not exist.",
                    () -> logs.filterLogEvents(request -> request.logGroupName(group).filterPattern("[")));
            logs.createLogGroup(request -> request.logGroupName(group));
            try {
                assertEquals(0, logs.filterLogEvents(request -> request.logGroupName(group)).events().size());
                logs.createLogStream(request -> request.logGroupName(group).logStreamName(STREAM));
                put(logs, group);
                assertEquals("x", logs.filterLogEvents(request -> request.logGroupName(group))
                        .events().getFirst().message());
            } finally {
                logs.deleteLogGroup(request -> request.logGroupName(group));
            }
            assertResourceNotFound("The specified log group does not exist.",
                    () -> logs.filterLogEvents(request -> request.logGroupName(group)));
        }
    }

    private static void put(CloudWatchLogsClient logs, String group) {
        logs.putLogEvents(request -> request.logGroupName(group).logStreamName(STREAM)
                .logEvents(event -> event.timestamp(Instant.now().toEpochMilli()).message("x")));
    }

    private static void assertResourceNotFound(String message, Executable call) {
        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class, call);
        assertEquals(400, error.statusCode());
        assertEquals(message, error.awsErrorDetails().errorMessage());
    }

    private static CloudWatchLogsClient client() {
        return CloudWatchLogsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
