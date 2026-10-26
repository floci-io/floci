package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LambdaWebMetricsTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";

    @Mock
    CloudWatchMetricsService cloudWatch;

    private LambdaWebMetrics metrics;
    private WebFunctionTarget target;

    @BeforeEach
    void start() {
        metrics = new LambdaWebMetrics(cloudWatch);
        WebFunction function = new WebFunction();
        function.functionName = "web";
        WebFunctionEndpoint endpoint = new WebFunctionEndpoint();
        endpoint.endpointName = "dev";
        target = new WebFunctionTarget(ACCOUNT, REGION, function, endpoint, REGION);
    }

    @AfterEach
    void stop() {
        metrics.shutdown();
    }

    @Test
    void anExecutedRequestCountsInBothNamespacesWithItsStatusClass() {
        metrics.executed(target, 404, 3);

        assertEquals(List.of(
                "AWS/Lambda/Web ExecutedRequests FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web HTTPResponseStatusClass4XX FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web/Usage EndpointActiveEnvironments FunctionName=web,EndpointName=dev,ExecutionRegion=eu-west-1"
                        + " 3.0 Count",
                "AWS/Lambda/Web/Usage ExecutedRequests FunctionName=web,EndpointName=dev,ExecutionRegion=eu-west-1"
                        + " 1.0 Count"),
                published(4));
    }

    @Test
    void aSuccessHasNoStatusClassAndAPlatformAnswerOnlyItsStatusClass() {
        metrics.executed(target, 302, 1);
        metrics.answered(target, 503);

        assertEquals(List.of(
                "AWS/Lambda/Web ExecutedRequests FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web HTTPResponseStatusClass5XX FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web/Usage EndpointActiveEnvironments FunctionName=web,EndpointName=dev,ExecutionRegion=eu-west-1"
                        + " 1.0 Count",
                "AWS/Lambda/Web/Usage ExecutedRequests FunctionName=web,EndpointName=dev,ExecutionRegion=eu-west-1"
                        + " 1.0 Count"),
                published(4));
    }

    @Test
    void aThrottledRequestCountsTheLimitItHitAndA4xx() {
        metrics.throttled(target, LambdaWebMetrics.ACTIVE_ENVIRONMENT_THROTTLES);

        assertEquals(List.of(
                "AWS/Lambda/Web HTTPResponseStatusClass4XX FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web/Usage EndpointActiveEnvironmentThrottles"
                        + " FunctionName=web,EndpointName=dev,ExecutionRegion=eu-west-1 1.0 Count"),
                published(2));
    }

    @Test
    void anEnvironmentPublishesEachMetricByFunctionName() {
        metrics.environment(ACCOUNT, REGION, REGION, "web").publish(244, 178, 142, 2, 37);

        assertEquals(List.of(
                "AWS/Lambda/Web EnvironmentActiveTime FunctionName=web 244.0 Milliseconds",
                "AWS/Lambda/Web EnvironmentCPUTime FunctionName=web 178.0 Milliseconds",
                "AWS/Lambda/Web EnvironmentConcurrentRequests FunctionName=web 2.0 Count",
                "AWS/Lambda/Web EnvironmentMaxMemoryUsage FunctionName=web 142.0 Megabytes",
                "AWS/Lambda/Web ResponseBytes FunctionName=web 37.0 Bytes"),
                published(5));
    }

    @Test
    void aRequestServedInAnotherRegionAlsoCountsInTheHomeRegionButItsUsageDoesNot() {
        WebFunctionTarget replica = new WebFunctionTarget(ACCOUNT, REGION, target.function(), target.endpoint(),
                "eu-central-1");
        metrics.executed(replica, 404, 3);

        assertEquals(List.of(
                "AWS/Lambda/Web ExecutedRequests FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web HTTPResponseStatusClass4XX FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web/Usage EndpointActiveEnvironments"
                        + " FunctionName=web,EndpointName=dev,ExecutionRegion=eu-central-1 3.0 Count",
                "AWS/Lambda/Web/Usage ExecutedRequests FunctionName=web,EndpointName=dev,ExecutionRegion=eu-central-1"
                        + " 1.0 Count"),
                published("eu-central-1", 4));
        assertEquals(List.of(
                "AWS/Lambda/Web ExecutedRequests FunctionName=web,EndpointName=dev 1.0 Count",
                "AWS/Lambda/Web HTTPResponseStatusClass4XX FunctionName=web,EndpointName=dev 1.0 Count"),
                published(REGION, 2));
    }

    @Test
    void anEnvironmentInAnotherRegionPublishesThereAndInTheHomeRegion() {
        metrics.environment(ACCOUNT, "eu-central-1", REGION, "web").publish(244, 178, 142, 2, 37);

        List<String> expected = List.of(
                "AWS/Lambda/Web EnvironmentActiveTime FunctionName=web 244.0 Milliseconds",
                "AWS/Lambda/Web EnvironmentCPUTime FunctionName=web 178.0 Milliseconds",
                "AWS/Lambda/Web EnvironmentConcurrentRequests FunctionName=web 2.0 Count",
                "AWS/Lambda/Web EnvironmentMaxMemoryUsage FunctionName=web 142.0 Megabytes",
                "AWS/Lambda/Web ResponseBytes FunctionName=web 37.0 Bytes");
        assertEquals(expected, published("eu-central-1", 5));
        assertEquals(expected, published(REGION, 5));
    }

    @Test
    void writesPastTheQueueLimitAreDroppedAndTheRestAreStoredBeforeTheWaitEnds() throws InterruptedException {
        metrics.shutdown();
        metrics = new LambdaWebMetrics(cloudWatch, 2);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).doNothing().when(cloudWatch).publishMetricForAccount(eq(ACCOUNT), anyString(), any(MetricDatum.class),
                eq(REGION), anyString());

        metrics.answered(target, 500);
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 4; i++) {
            metrics.answered(target, 500);
        }
        // The wait starts while the queue is still full.
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(release::countDown);
        metrics.awaitPublished(Duration.ofSeconds(5));

        verify(cloudWatch, times(3)).publishMetricForAccount(eq(ACCOUNT), anyString(), any(MetricDatum.class),
                eq(REGION), anyString());
    }

    private List<String> published(int count) {
        return published(REGION, count);
    }

    private List<String> published(String region, int count) {
        ArgumentCaptor<String> namespaces = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MetricDatum> datums = ArgumentCaptor.forClass(MetricDatum.class);
        metrics.awaitPublished(Duration.ofSeconds(5));
        verify(cloudWatch, times(count)).publishMetricForAccount(eq(ACCOUNT), namespaces.capture(),
                datums.capture(), eq(region), anyString());
        List<String> published = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MetricDatum datum = datums.getAllValues().get(i);
            published.add(namespaces.getAllValues().get(i) + " " + datum.getMetricName() + " "
                    + datum.getDimensions().stream().map(each -> each.name() + "=" + each.value())
                    .collect(Collectors.joining(",")) + " " + datum.getValue() + " " + datum.getUnit());
        }
        published.sort(null);
        return published;
    }
}
