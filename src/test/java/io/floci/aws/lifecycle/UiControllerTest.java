package io.floci.aws.lifecycle;

import io.floci.aws.services.floci.ui.FlociUiManager;
import io.floci.aws.services.floci.ui.FlociUiManager.UiStatus;
import io.floci.aws.services.floci.ui.UiPages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UiControllerTest {

    private FlociUiManager uiManager;
    private UiController controller;

    @BeforeEach
    void setUp() {
        uiManager = mock(FlociUiManager.class);
        controller = new UiController(uiManager, mock(UiPages.class));
    }

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
    void readyStatusReplacesOnlyTheHostPort(String host, String url) {
        when(uiManager.status()).thenReturn(new UiStatus(true, true, 4500, null));

        assertEquals(body(true, url, null), status(host));
    }

    @ParameterizedTest
    @CsvSource({
        "[::1]@evil.example:4566, http://[:4500/",
        "[::1, http://[:4500/",
        "::1, http://:4500/"
    })
    void malformedHostsKeepTheExistingBehavior(String host, String url) {
        when(uiManager.status()).thenReturn(new UiStatus(true, true, 4500, null));

        assertEquals(body(true, url, null), status(host));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingHostDefaultsToLocalhost(String host) {
        when(uiManager.status()).thenReturn(new UiStatus(true, true, 4500, null));

        assertEquals(body(true, "http://localhost:4500/", null), status(host));
    }

    @ParameterizedTest
    @CsvSource({"false, 4500", "true, 0", "true, -1"})
    void statusWithoutAReadyPublishedPortHasNoUrl(boolean ready, int port) {
        when(uiManager.status()).thenReturn(new UiStatus(true, ready, port, null));

        assertEquals(body(false, null, null), status("[::1]:4566"));
    }

    @Test
    void statusErrorTakesPrecedenceOverReadiness() {
        when(uiManager.status()).thenReturn(new UiStatus(true, true, 4500, "sidecar failed"));

        assertEquals(body(false, null, "sidecar failed"), status("[::1]:4566"));
    }

    private Map<?, ?> status(String host) {
        return (Map<?, ?>) controller.status(host).getEntity();
    }

    /** The {@code {ready, url, error}} body the wait view reads, every key present even when its value is null. */
    private static Map<String, Object> body(boolean ready, String url, String error) {
        Map<String, Object> body = new HashMap<>();
        body.put("ready", ready);
        body.put("url", url);
        body.put("error", error);
        return body;
    }
}
