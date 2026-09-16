package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.model.LogConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcsContainerManagerFirelensTest {

    @Test
    void createsFluentdLogConfigForAwsFirelensApplication() {
        LogConfig logConfig = EcsContainerManager.firelensLogConfig("task-123", "app", 32768);

        assertEquals(LogConfig.LoggingType.FLUENTD, logConfig.getType());
        assertEquals("127.0.0.1:32768", logConfig.getConfig().get("fluentd-address"));
        assertEquals("task-123.app", logConfig.getConfig().get("tag"));
        assertEquals("true", logConfig.getConfig().get("fluentd-async"));
    }

    @Test
    void ordersRouterBeforeAwsFirelensApplications() {
        ContainerDefinition app = container("app", "awsfirelens", null);
        ContainerDefinition router = container("router", null,
                new FirelensConfiguration("fluentbit", Map.of()));

        List<ContainerDefinition> ordered = EcsContainerManager.orderFirelensContainers(List.of(app, router));

        assertEquals("router", ordered.get(0).getName());
        assertEquals("app", ordered.get(1).getName());
    }

    @Test
    void rejectsAwsFirelensApplicationWithoutRouter() {
        ContainerDefinition app = container("app", "awsfirelens", null);

        assertThrows(AwsException.class,
                () -> EcsContainerManager.orderFirelensContainers(List.of(app)));
    }

    private static ContainerDefinition container(String name, String driver,
                                                 FirelensConfiguration firelensConfiguration) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName(name);
        definition.setImage("alpine:latest");
        definition.setFirelensConfiguration(firelensConfiguration);
        if (driver != null) {
            definition.setLogConfiguration(new LogConfiguration(driver, Map.of(), null));
        }
        return definition;
    }
}
