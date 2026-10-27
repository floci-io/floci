package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Classic load balancer names are unique within one account and region, so two accounts can each
 * run a balancer called {@code web}. These tests run the real health checker against a backend on
 * loopback and check that one account's balancer never changes the health the other one reports.
 */
class ElbClassicAccountIsolationTest {

    private static final String REGION = "us-west-2";
    private static final String FIRST = "111111111111";
    private static final String SECOND = "222222222222";
    private static final String INSTANCE = "i-0123456789abcdef0";

    private final Vertx vertx = Vertx.vertx();
    private final RequestContext context = new RequestContext();
    private final AtomicInteger backendStatus = new AtomicInteger(200);
    private HttpServer backend;
    private ElbClassicHealthChecker healthChecker;
    private ElbClassicService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        backend = vertx.createHttpServer()
                .requestHandler(request -> request.response().setStatusCode(backendStatus.get()).end())
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(ignored -> context.getAccountId());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("elb"), anyString(), any())).thenAnswer(ignored ->
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, FIRST));

        // The registered instance answers on loopback. The EC2 model's Instance is qualified
        // because this file also imports CDI's Instance for the request context.
        io.github.hectorvent.floci.services.ec2.model.Instance instance =
                new io.github.hectorvent.floci.services.ec2.model.Instance();
        instance.setContainerBridgeIp("127.0.0.1");
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.findInstanceById(anyString(), eq(INSTANCE))).thenReturn(instance);

        // Without an EmulatorConfig the checker is not in mock mode, so it really probes.
        healthChecker = new ElbClassicHealthChecker(vertx, null, ec2Service);
        context.setAccountId(FIRST);
        service = new ElbClassicService(ec2Service, healthChecker, factory, null, resolver);
        service.initializeStorage();
    }

    @AfterEach
    void tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void deletingAnotherAccountsBalancerOfTheSameNameLeavesMyInstanceInService() {
        context.setAccountId(FIRST);
        createWeb();
        registerHealthyInstance();

        context.setAccountId(SECOND);
        createWeb();
        service.deleteLoadBalancer(REGION, "web");

        context.setAccountId(FIRST);
        assertEquals("InService", health().state());
    }

    @Test
    void anotherAccountsBalancerOfTheSameNameDoesNotStopMyHealthChecks() {
        context.setAccountId(FIRST);
        createWeb();
        registerHealthyInstance();

        context.setAccountId(SECOND);
        createWeb();
        backendStatus.set(503);

        // The instance now fails, which only the first account's own periodic check can notice.
        healthChecker.runArmedChecks();
        context.setAccountId(FIRST);
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ElbClassicHealthChecker.InstanceHealth health = health();
            assertEquals("OutOfService", health.state());
            assertEquals("Instance", health.reasonCode());
        });

        // And it recovers, which the other account's default TCP:80 check could not report.
        backendStatus.set(200);
        healthChecker.runArmedChecks();
        await().pollInSameThread().atMost(Duration.ofSeconds(5))
                .until(() -> "InService".equals(health().state()));
    }

    private void createWeb() {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(80);
        listener.setInstanceProtocol("HTTP");
        listener.setInstancePort(8080);
        service.createLoadBalancer(REGION, "web", List.of(listener), List.of(REGION + "a"),
                List.of(), List.of(), null, Map.of());
    }

    /**
     * Gives the stored balancer a check that one probe settles and re-arms the checker, as
     * ConfigureHealthCheck does but with a timeout and thresholds below the minimums that action
     * enforces. The interval is the longest the API allows, so the periodic timer never fires
     * during a test: the probe that registration schedules settles the instance here, and the
     * tests run later rounds themselves through {@link ElbClassicHealthChecker#runArmedChecks()}.
     */
    private void registerHealthyInstance() {
        ClassicHealthCheck check = new ClassicHealthCheck();
        check.setTarget("HTTP:" + backend.actualPort() + "/");
        check.setInterval(300);
        check.setTimeout(1);
        check.setHealthyThreshold(1);
        check.setUnhealthyThreshold(1);
        ClassicLoadBalancer lb = service.requireLoadBalancer(REGION, "web");
        lb.setHealthCheck(check);
        healthChecker.healthCheckChanged(lb);
        service.registerInstances(REGION, "web", List.of(INSTANCE));
        await().pollInSameThread().atMost(Duration.ofSeconds(5))
                .until(() -> "InService".equals(health().state()));
    }

    private ElbClassicHealthChecker.InstanceHealth health() {
        return service.describeInstanceHealth(REGION, "web", List.of(INSTANCE)).get(INSTANCE);
    }
}
