package io.floci.aws.services.elbv2;

import io.floci.aws.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2BackendKeepAliveIntegrationTest extends ElbV2BackendConnectionTestSupport {

    @Test
    void reusesBackendConnectionsByDefaultAndRetainsTargetCloseErrors() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startBackend(request -> {
            if (requests.incrementAndGet() == 3) {
                request.connection().close();
            } else {
                request.response().putHeader("X-Backend-Connection",
                        Integer.toString(request.remoteAddress().port())).end("ok");
            }
        });

        Response first = given().baseUri("http://127.0.0.1").port(listenerPort).get("/first");
        assertEquals(200, first.statusCode());
        Response second = given().baseUri("http://127.0.0.1").port(listenerPort).get("/second");
        assertEquals(200, second.statusCode());
        assertEquals(first.header("X-Backend-Connection"), second.header("X-Backend-Connection"));
        assertEquals(502, given().baseUri("http://127.0.0.1").port(listenerPort).get("/third").statusCode());
        assertEquals(3, requests.get());
    }
}
