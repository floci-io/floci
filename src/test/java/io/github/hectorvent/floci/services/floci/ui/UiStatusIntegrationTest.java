package io.github.hectorvent.floci.services.floci.ui;

import io.github.hectorvent.floci.services.floci.ui.FlociUiManager.UiStatus;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/** Verifies ready-sidecar redirect URLs through HTTP without starting a Docker sidecar. */
@QuarkusTest
class UiStatusIntegrationTest {

    @InjectMock
    FlociUiManager uiManager;

    @ParameterizedTest
    @CsvSource({
        "[::1]:4566, http://[::1]:4500/",
        "[::1], http://[::1]:4500/",
        "[2001:db8::1]:4566, http://[2001:db8::1]:4500/",
        "[2001:db8::1], http://[2001:db8::1]:4500/",
        "floci.example:4566, http://floci.example:4500/",
        "floci.example, http://floci.example:4500/",
        "127.0.0.1:4566, http://127.0.0.1:4500/",
        "127.0.0.1, http://127.0.0.1:4500/"
    })
    void readyStatusUsesTheBrowserHostAndPublishedPort(String host, String url) {
        when(uiManager.status()).thenReturn(new UiStatus(true, true, 4500, null));

        for (String prefix : List.of("_floci", "_localstack")) {
            given()
                .header("Host", host)
                .accept("application/json")
            .when()
                .get("/" + prefix + "/ui/status")
            .then()
                .statusCode(200)
                .body("ready", is(true))
                .body("url", is(url))
                .body("error", nullValue());
        }
    }

}
