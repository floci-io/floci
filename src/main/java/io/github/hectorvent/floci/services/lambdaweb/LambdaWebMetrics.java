package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * The CloudWatch metrics of Web Functions: one datapoint per request when it is answered, and one
 * per environment when it starts, each minute and when it ends. Both namespaces go to the region
 * that served, and {@code AWS/Lambda/Web} also to the home region, where AWS centralizes it.
 */
@ApplicationScoped
public class LambdaWebMetrics {

    static final String NAMESPACE = "AWS/Lambda/Web";
    static final String USAGE_NAMESPACE = "AWS/Lambda/Web/Usage";
    static final String REQUEST_THROTTLES = "EndpointRequestThrottles";
    static final String ACTIVE_ENVIRONMENT_THROTTLES = "EndpointActiveEnvironmentThrottles";
    static final int MAX_QUEUED = 10_000;
    private static final Logger LOG = Logger.getLogger(LambdaWebMetrics.class);

    /** One datapoint of each environment metric, for the time since the previous one. */
    interface EnvironmentMetrics {
        void publish(long activeMillis, long cpuMillis, long maxMemoryMb, int concurrentRequests, long responseBytes);
    }

    private record Datapoint(String namespace, MetricDatum datum) {
    }

    private final CloudWatchMetricsService cloudWatch;
    /**
     * A persistent store writes to disk on each datapoint, which must not happen on the event loop.
     * The datapoints of one request or one window are one write. Once {@code maxQueued} writes wait,
     * new ones are dropped.
     */
    private final ThreadPoolExecutor publisher;
    /** The writes run one at a time in order, so once the last one is done all are. */
    private Future<?> lastWrite = CompletableFuture.completedFuture(null);
    private boolean dropping;

    @Inject
    public LambdaWebMetrics(CloudWatchMetricsService cloudWatch) {
        this(cloudWatch, MAX_QUEUED);
    }

    LambdaWebMetrics(CloudWatchMetricsService cloudWatch, int maxQueued) {
        this.cloudWatch = cloudWatch;
        this.publisher = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(maxQueued),
                task -> {
                    Thread thread = new Thread(task, "lambda-web-metrics");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** A request that reached an environment, with the status the caller got. */
    void executed(WebFunctionTarget target, int status, int activeEnvironments) {
        long now = Instant.now().getEpochSecond();
        List<Dimension> request = requestDimensions(target);
        List<Dimension> usage = usageDimensions(target);
        List<Datapoint> datapoints = new ArrayList<>();
        datapoints.add(new Datapoint(NAMESPACE, MetricDatum.of("ExecutedRequests", "Count", 1, request, now)));
        datapoints.add(new Datapoint(USAGE_NAMESPACE, MetricDatum.of("ExecutedRequests", "Count", 1, usage, now)));
        datapoints.add(new Datapoint(USAGE_NAMESPACE,
                MetricDatum.of("EndpointActiveEnvironments", "Count", activeEnvironments, usage, now)));
        statusClass(datapoints, status, request, now);
        publish(target.accountId(), target.servingRegion(), target.region(), datapoints);
    }

    /** A request the platform answered without an environment. */
    void answered(WebFunctionTarget target, int status) {
        List<Datapoint> datapoints = new ArrayList<>();
        statusClass(datapoints, status, requestDimensions(target), Instant.now().getEpochSecond());
        publish(target.accountId(), target.servingRegion(), target.region(), datapoints);
    }

    /** A request refused with 429. {@code metric} names the limit it hit. */
    void throttled(WebFunctionTarget target, String metric) {
        long now = Instant.now().getEpochSecond();
        List<Datapoint> datapoints = new ArrayList<>();
        datapoints.add(new Datapoint(USAGE_NAMESPACE,
                MetricDatum.of(metric, "Count", 1, usageDimensions(target), now)));
        statusClass(datapoints, 429, requestDimensions(target), now);
        publish(target.accountId(), target.servingRegion(), target.region(), datapoints);
    }

    EnvironmentMetrics environment(String accountId, String region, String homeRegion, String functionName) {
        List<Dimension> dimensions = List.of(new Dimension("FunctionName", functionName));
        return (activeMillis, cpuMillis, maxMemoryMb, concurrentRequests, responseBytes) -> {
            long now = Instant.now().getEpochSecond();
            publish(accountId, region, homeRegion, Stream.of(
                    MetricDatum.of("EnvironmentActiveTime", "Milliseconds", activeMillis, dimensions, now),
                    MetricDatum.of("EnvironmentCPUTime", "Milliseconds", cpuMillis, dimensions, now),
                    MetricDatum.of("EnvironmentMaxMemoryUsage", "Megabytes", maxMemoryMb, dimensions, now),
                    MetricDatum.of("EnvironmentConcurrentRequests", "Count", concurrentRequests, dimensions, now),
                    MetricDatum.of("ResponseBytes", "Bytes", responseBytes, dimensions, now))
                    .map(datum -> new Datapoint(NAMESPACE, datum)).toList());
        };
    }

    /** Waits until what is queued is stored, so the last flush of the store at shutdown has it. */
    void awaitPublished(Duration limit) {
        Future<?> last;
        synchronized (this) {
            last = lastWrite;
        }
        try {
            last.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warnv(e, "Lambda Web metrics still waiting to be stored may be lost");
        }
    }

    @PreDestroy
    void shutdown() {
        publisher.shutdown();
    }

    /** The status classes have no datapoint of 0. */
    private static void statusClass(List<Datapoint> datapoints, int status, List<Dimension> dimensions, long now) {
        if (400 <= status && status <= 499) {
            datapoints.add(new Datapoint(NAMESPACE,
                    MetricDatum.of("HTTPResponseStatusClass4XX", "Count", 1, dimensions, now)));
        } else if (500 <= status && status <= 599) {
            datapoints.add(new Datapoint(NAMESPACE,
                    MetricDatum.of("HTTPResponseStatusClass5XX", "Count", 1, dimensions, now)));
        }
    }

    private static List<Dimension> requestDimensions(WebFunctionTarget target) {
        return List.of(new Dimension("FunctionName", target.function().functionName),
                new Dimension("EndpointName", target.endpoint().endpointName));
    }

    private static List<Dimension> usageDimensions(WebFunctionTarget target) {
        return List.of(new Dimension("FunctionName", target.function().functionName),
                new Dimension("EndpointName", target.endpoint().endpointName),
                new Dimension("ExecutionRegion", target.servingRegion()));
    }

    private synchronized void publish(String accountId, String region, String homeRegion, List<Datapoint> datapoints) {
        if (datapoints.isEmpty()) {
            return;
        }
        try {
            lastWrite = publisher.submit(() -> {
                for (Datapoint datapoint : datapoints) {
                    store(accountId, region, datapoint);
                    if (!region.equals(homeRegion) && NAMESPACE.equals(datapoint.namespace())) {
                        store(accountId, homeRegion, datapoint);
                    }
                }
            });
            dropping = false;
        } catch (RejectedExecutionException rejected) {
            if (!publisher.isShutdown() && !dropping) {
                dropping = true;
                LOG.warnv("Dropping Lambda Web metrics while {0} writes wait", publisher.getQueue().size());
            }
        }
    }

    private void store(String accountId, String region, Datapoint datapoint) {
        try {
            cloudWatch.publishMetricForAccount(accountId, datapoint.namespace(), datapoint.datum(), region,
                    UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not publish {0} {1} in {2}", datapoint.namespace(), datapoint.datum().getMetricName(),
                    region);
        }
    }
}
