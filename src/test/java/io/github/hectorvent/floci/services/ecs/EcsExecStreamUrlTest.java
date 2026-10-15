package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ecs.exec.ExecSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsExecStreamUrlTest {

    private static final ExecSession SESSION = new ExecSession("exec-session", "token", "task", "cluster",
            "app", "container", "runtime", List.of("/bin/sh"), true, Instant.EPOCH);

    @ParameterizedTest
    @CsvSource({
        "http://localhost:6543/, ws://localhost:6543/v1/data-channel/exec-session",
        "https://public.example/, wss://public.example/v1/data-channel/exec-session",
        "https://public.example:8443/floci/, wss://public.example:8443/floci/v1/data-channel/exec-session",
        "http://[::1]:6543/, ws://[::1]:6543/v1/data-channel/exec-session"
    })
    void preservesTheRequestEndpointAndChoosesTheWebSocketScheme(String endpoint, String expected) {
        EcsService service = service("http://floci:4566");

        assertEquals(expected, service.execStreamUrl(SESSION, URI.create(endpoint)));
    }

    @Test
    void nonHttpCallersKeepTheConfiguredEndpointWithoutDuplicateSlashes() {
        EcsService service = service("https://floci:4566/floci/");

        assertEquals("wss://floci:4566/floci/v1/data-channel/exec-session", service.execStreamUrl(SESSION));
        assertEquals(service.execStreamUrl(SESSION), service.execStreamUrl(SESSION, null));
    }

    private static EcsService service(String baseUrl) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn(baseUrl);
        return new EcsService(null, null, config, null, null, null);
    }
}
