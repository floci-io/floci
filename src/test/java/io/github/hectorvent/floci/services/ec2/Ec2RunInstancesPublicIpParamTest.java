package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunInstances accepts the launch-time public-IP override in two wire shapes:
 * the primary network-interface spec (what Terraform sends) and the legacy
 * top-level parameter (what the CLI and older SDKs send). Both must reach
 * Ec2Service; absent must stay null so the subnet's MapPublicIpOnLaunch
 * default still applies.
 */
class Ec2RunInstancesPublicIpParamTest {

    private static final String REGION = "us-east-1";

    @Test
    void topLevelAssociatePublicIpAddressReachesTheService() {
        assertForwarded(params("AssociatePublicIpAddress", "true"), Boolean.TRUE);
        assertForwarded(params("AssociatePublicIpAddress", "false"), Boolean.FALSE);
    }

    @Test
    void networkInterfaceSpecStillReachesTheService() {
        assertForwarded(params("NetworkInterface.1.AssociatePublicIpAddress", "true"), Boolean.TRUE);
        assertForwarded(params("NetworkInterface.1.AssociatePublicIpAddress", "false"), Boolean.FALSE);
    }

    @Test
    void networkInterfaceSpecWinsWhenBothArePresent() {
        MultivaluedMap<String, String> p = params("NetworkInterface.1.AssociatePublicIpAddress", "false");
        p.putSingle("AssociatePublicIpAddress", "true");
        assertForwarded(p, Boolean.FALSE);
    }

    @Test
    void absentStaysNullSoTheSubnetDefaultApplies() {
        assertForwarded(baseParams(), null);
    }

    private void assertForwarded(MultivaluedMap<String, String> p, Boolean expected) {
        Ec2Service service = mock(Ec2Service.class);
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class))).thenReturn(new Reservation());

        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class));
        handler.handle("RunInstances", p, REGION);

        ArgumentCaptor<Boolean> associatePublicIp = ArgumentCaptor.forClass(Boolean.class);
        verify(service).runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                associatePublicIp.capture());
        if (expected == null) {
            assertNull(associatePublicIp.getValue(),
                    "an absent override must stay null so the subnet default decides");
        } else {
            assertEquals(expected, associatePublicIp.getValue());
        }
    }

    private static MultivaluedMap<String, String> baseParams() {
        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.putSingle("ImageId", "ami-0abcdef1234567890");
        p.putSingle("InstanceType", "t3.micro");
        p.putSingle("MinCount", "1");
        p.putSingle("MaxCount", "1");
        return p;
    }

    private static MultivaluedMap<String, String> params(String key, String value) {
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle(key, value);
        return p;
    }
}
