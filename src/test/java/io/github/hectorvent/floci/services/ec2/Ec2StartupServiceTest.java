package io.github.hectorvent.floci.services.ec2;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Ec2StartupServiceTest {

    @Test
    void startupObserverRunsAfterDefaultLifecycleObservers() throws ReflectiveOperationException {
        Method observer = Ec2Startup.class.getDeclaredMethod("onStart", StartupEvent.class);
        Priority priority = observer.getParameters()[0].getAnnotation(Priority.class);

        assertTrue(priority.value() > ObserverMethod.DEFAULT_PRIORITY,
                "EC2 initialization must follow the lifecycle's boot hooks and path validation");
    }

    @Test
    void startupSeedsThroughTheSameEntryPointAsReset() {
        Ec2Service service = mock(Ec2Service.class);

        new Ec2Startup(service).onStart(new StartupEvent());

        verify(service).seedDefaultRegionIfEnabled();
    }
}
