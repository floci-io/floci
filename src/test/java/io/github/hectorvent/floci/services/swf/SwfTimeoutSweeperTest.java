package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The sweep interval reaches {@code scheduleWithFixedDelay}, which rejects a non-positive
 * delay. Because the schedule happens in a {@link StartupEvent} observer, an
 * IllegalArgumentException there stops the whole emulator from becoming ready — so a
 * misconfigured interval must not be passed through unchecked.
 */
class SwfTimeoutSweeperTest {

    @Test
    void nonPositiveConfiguredInterval_doesNotPreventStartup() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(null, configWithInterval(interval));
            assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()),
                    "interval " + interval + " must not abort startup");
            sweeper.onStop(new io.quarkus.runtime.ShutdownEvent());
        }
    }

    @Test
    void positiveConfiguredInterval_startsNormally() {
        SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(null, configWithInterval(30L));
        assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()));
        sweeper.onStop(new io.quarkus.runtime.ShutdownEvent());
    }

    /**
     * Minimal {@link EmulatorConfig} view: the sweeper reads only the three SWF settings, so
     * a proxy answering those avoids standing up the Quarkus config container.
     */
    private static EmulatorConfig configWithInterval(long intervalSeconds) {
        Map<String, Object> answers = Map.of(
                "enabled", Boolean.TRUE,
                "timeoutSweepEnabled", Boolean.TRUE,
                "timeoutSweepIntervalSeconds", intervalSeconds);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                Object answer = answers.get(method.getName());
                if (answer != null) {
                    return answer;
                }
                // services() and swf() return further config views; proxy those too.
                return Proxy.newProxyInstance(method.getReturnType().getClassLoader(),
                        new Class<?>[] {method.getReturnType()}, this);
            }
        };
        return (EmulatorConfig) Proxy.newProxyInstance(EmulatorConfig.class.getClassLoader(),
                new Class<?>[] {EmulatorConfig.class}, handler);
    }
}
