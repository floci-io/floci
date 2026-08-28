package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsCapacityAdjusterTest {

    private static final String REGION = "us-east-1";
    private static final String RESOURCE_ID = "service/my-cluster/my-service";

    private final EcsService ecsService = mock(EcsService.class);
    private final EcsCapacityAdjuster adjuster = new EcsCapacityAdjuster(ecsService);

    @Test
    void supportsOnlyEcsDesiredCountDimension() {
        assertTrue(adjuster.supports("ecs:service:DesiredCount"));
        assertEquals(false, adjuster.supports("dynamodb:table:ReadCapacityUnits"));
    }

    @Test
    void readsCurrentDesiredCountFromParsedClusterAndService() {
        EcsServiceModel svc = new EcsServiceModel();
        svc.setDesiredCount(4);
        when(ecsService.describeServices("my-cluster", List.of("my-service"), REGION))
                .thenReturn(List.of(svc));

        assertEquals(4, adjuster.getCurrentCapacity(RESOURCE_ID, REGION));
    }

    @Test
    void writesDesiredCountViaUpdateServiceLeavingOtherFieldsUntouched() {
        adjuster.setCapacity(RESOURCE_ID, 7, REGION);

        verify(ecsService).updateService("my-cluster", "my-service", null, 7, null, REGION);
    }

    @Test
    void rejectsResourceIdNotShapedLikeAnEcsService() {
        AwsException e = assertThrows(AwsException.class,
                () -> adjuster.setCapacity("not-a-valid-id", 1, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void throwsWhenServiceNotFound() {
        when(ecsService.describeServices("my-cluster", List.of("my-service"), REGION))
                .thenReturn(List.of());

        assertThrows(AwsException.class, () -> adjuster.getCurrentCapacity(RESOURCE_ID, REGION));
    }
}
