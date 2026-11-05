package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.AlbFixtures.createListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.createLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.createTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A listener port carries no credentials, so a load balancer outside the default account must still serve. */
@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2OtherAccountDataPlaneIntegrationTest {

    private static final String ACCOUNT = "111122223333";

    @Inject
    Vertx vertx;

    @Inject
    ElbV2DataPlane dataPlane;

    @Inject
    ElbV2Service elbV2Service;

    @Test
    void listenerForwardsAndIsRestoredForItsOwnAccount() throws Exception {
        HttpServer backend = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("other-account"))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        int listenerPort = FreePorts.anyInterfacePort();
        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        try {
            loadBalancerArn = createLoadBalancer(ACCOUNT, "other-account-lb");
            targetGroupArn = createTargetGroup(ACCOUNT, "other-account-tg", "ip", backend.actualPort());
            registerTarget(ACCOUNT, targetGroupArn, "127.0.0.1", backend.actualPort());
            listenerArn = createListener(ACCOUNT, loadBalancerArn, targetGroupArn, listenerPort);

            awaitForwarded(listenerPort);

            assertTrue(dataPlane.removeListener(listenerArn, listenerPort, () -> true));
            elbV2Service.initializeStorage();
            elbV2Service.restorePersistedRuntime();

            awaitForwarded(listenerPort);
        } finally {
            try {
                deleteListener(ACCOUNT, listenerArn);
                deleteTargetGroup(ACCOUNT, targetGroupArn);
                deleteLoadBalancer(ACCOUNT, loadBalancerArn);
            } finally {
                backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitForwarded(int listenerPort) {
        await().atMost(Duration.ofSeconds(5)).ignoreExceptions().untilAsserted(() -> given()
                .baseUri("http://127.0.0.1").port(listenerPort)
                .get("/").then().statusCode(200).body(equalTo("other-account")));
    }
}
