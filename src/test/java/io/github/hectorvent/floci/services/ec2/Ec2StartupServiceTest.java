package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ec2StartupServiceTest {

    @Test
    void startupObserverRunsAfterDefaultLifecycleObservers() throws ReflectiveOperationException {
        Method observer = Ec2Startup.class.getDeclaredMethod("onStart", StartupEvent.class);
        Priority priority = observer.getParameters()[0].getAnnotation(Priority.class);

        assertTrue(priority.value() > ObserverMethod.DEFAULT_PRIORITY,
                "EC2 initialization must follow the lifecycle's boot hooks and path validation");
    }

    @Test
    void enabledEc2SeedsTheConfiguredRegionAtStartup() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("eu-central-1");
        Ec2Service service = mock(Ec2Service.class);

        new Ec2Startup(config, service).onStart(new StartupEvent());

        verify(service).ensureDefaultResources("eu-central-1");
    }

    @Test
    void mockEc2StillSeedsDefaultResourcesAtStartup() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");
        Ec2Service service = mock(Ec2Service.class);

        new Ec2Startup(config, service).onStart(new StartupEvent());

        verify(service).ensureDefaultResources("us-east-1");
    }

    @Test
    void disabledEc2DoesNotInitializeTheService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().enabled()).thenReturn(false);
        Ec2Service service = mock(Ec2Service.class);

        new Ec2Startup(config, service).onStart(new StartupEvent());

        verifyNoInteractions(service);
    }
}
