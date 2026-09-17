package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(CloudWatchLogsMetricFilterRetryIntegrationTest.ResetIsolationProfile.class)
class CloudWatchLogsMetricFilterRetryIntegrationTest {
    /** HTTP reset wipes every service; do not share this application with other test classes. */
    public static class ResetIsolationProfile implements QuarkusTestProfile {}

    @InjectSpy CloudWatchMetricsService metrics;
    @Inject CloudWatchLogsMetricFilterService publications;
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260916/eu-west-1/logs/aws4_request";
    private static final long TIME = 1_700_000_040_000L;

    @BeforeAll
    static void contentTypes() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @AfterEach
    void removeFault() { reset(metrics); }

    @Test
    void managedWorkerSurvivesFailedRetryAndPublishesWithoutFurtherIngestion() throws Exception {
        String group = "/it/publication/automatic-" + System.nanoTime();
        create(group);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch published = new CountDownLatch(1);
        doAnswer(call -> {
            if (attempts.incrementAndGet() <= 2) { throw new IllegalStateException("temporary metric sink failure"); }
            Object result = call.callRealMethod();
            published.countDown();
            return result;
        }).when(metrics).publishMetricForAccount(anyString(), eq(group), any(), eq("eu-west-1"), anyString());
        ingest(group);
        assertTrue(published.await(10, TimeUnit.SECONDS), "no reads, ingestion or manual retry drive the worker");
        assertTrue(attempts.get() >= 3, "a failed retry must not suppress future scheduled ticks");
        statistics(group).then().statusCode(200).body("Datapoints", hasSize(1))
                .body("Datapoints[0].Sum", equalTo(7.0f)).body("Datapoints[0].SampleCount", equalTo(1.0f));
        publications.retryPending();
        statistics(group).then().body("Datapoints[0].SampleCount", equalTo(1.0f));
        logs("DeleteLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);
    }

    @Test
    void httpResetCancelsQueuedWorkOnTheLiveCdiInstance() {
        String group = "/it/publication/reset-" + System.nanoTime();
        create(group);
        doThrow(new IllegalStateException("outage through reset")).when(metrics)
                .publishMetricForAccount(anyString(), eq(group), any(), eq("eu-west-1"), anyString());
        ingest(group);
        await().atMost(Duration.ofSeconds(10)).until(() -> publications.pendingSamples() > 0);
        given().post("/_floci/state/reset").then().statusCode(200).body("status", equalTo("OK"));
        assertEquals(0, publications.pendingSamples());
        reset(metrics);
        publications.retryPending();
        statistics(group).then().statusCode(200).body("Datapoints", hasSize(0));
        create(group);
        ingest(group);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> statistics(group).then().statusCode(200)
                .body("Datapoints[0].Sum", equalTo(7.0f)).body("Datapoints[0].SampleCount", equalTo(1.0f)));
        logs("DeleteLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);
    }

    private static void create(String group) {
        logs("CreateLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);
        logs("CreateLogStream", "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"s\"}").then().statusCode(200);
        logs("PutMetricFilter", """
                {"logGroupName":"%s","filterName":"retry","filterPattern":"ERROR",
                 "metricTransformations":[{"metricName":"Value","metricNamespace":"%s","metricValue":"3","defaultValue":7}]}
                """.formatted(group, group)).then().statusCode(200);
    }

    private static void ingest(String group) {
        logs("PutLogEvents", """
                {"logGroupName":"%s","logStreamName":"s","logEvents":[{"timestamp":%d,"message":"INFO"}]}
                """.formatted(group, TIME)).then().statusCode(200);
        logs("GetLogEvents", "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"s\"}")
                .then().statusCode(200).body("events", hasSize(1));
    }

    private static Response logs(String action, String body) {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", AUTH)
                .header("X-Amz-Target", "Logs_20140328." + action).body(body).post("/");
    }

    private static Response statistics(String namespace) {
        return given().contentType("application/x-amz-json-1.0").header("Authorization", AUTH.replace("/logs/", "/monitoring/"))
                .header("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricStatistics").body("""
                        {"Namespace":"%s","MetricName":"Value","Period":60,"StartTime":%d,"EndTime":%d,
                         "Statistics":["Sum","SampleCount"]}
                        """.formatted(namespace, TIME / 1000, TIME / 1000 + 59)).post("/");
    }
}
