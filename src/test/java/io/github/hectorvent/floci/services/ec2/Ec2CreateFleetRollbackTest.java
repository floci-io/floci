package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ensures CreateFleet rollback keeps attempting every instance when one termination call fails.
 */
class Ec2CreateFleetRollbackTest {

    private static final String REGION = "us-east-1";

    @Test
    void rollbackContinuesAfterOneTerminationFailureAndReportsIncompleteCleanup() {
        Ec2Service service = mock(Ec2Service.class);
        LaunchTemplateData template = new LaunchTemplateData();
        template.setImageId("ami-test");
        template.setInstanceType("t3.micro");
        when(service.resolveLaunchTemplateData(REGION, "lt-test", null, "1")).thenReturn(template);

        when(service.runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), nullable(String.class),
                anyList(), nullable(String.class), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), nullable(Boolean.class), nullable(String.class), anyInt(),
                nullable(String.class)))
                .thenReturn(reservation("i-first"))
                .thenReturn(reservation("i-second"))
                .thenThrow(new AwsException("InvalidSubnetID.NotFound", "launch failed", 400));
        when(service.terminateInstances(REGION, List.of("i-first")))
                .thenThrow(new AwsException("InternalError", "cleanup failed", 500));
        when(service.terminateInstances(REGION, List.of("i-second"))).thenReturn(List.of());

        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class), mock(Ec2IpamService.class));
        Response response = handler.handle("CreateFleet", params(), REGION);

        assertEquals(400, response.getStatus());
        String responseBody = response.getEntity().toString();
        assertTrue(responseBody.contains("<Code>InvalidSubnetID.NotFound</Code>"));
        assertTrue(responseBody.contains("CreateFleet rollback incomplete for instance(s): i-first"));
        verify(service).terminateInstances(REGION, List.of("i-first"));
        verify(service).terminateInstances(REGION, List.of("i-second"));
    }

    private static Reservation reservation(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        Reservation reservation = new Reservation();
        reservation.getInstances().add(instance);
        return reservation;
    }

    private static MultivaluedMap<String, String> params() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Type", "instant");
        params.putSingle("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", "lt-test");
        params.putSingle("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1");
        params.putSingle("LaunchTemplateConfig.1.Overrides.1.InstanceType", "t3.micro");
        params.putSingle("TargetCapacitySpecification.TotalTargetCapacity", "3");
        return params;
    }
}
