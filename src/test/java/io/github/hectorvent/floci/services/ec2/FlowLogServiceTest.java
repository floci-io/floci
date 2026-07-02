package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowLogServiceTest {

    private FlowLogService flowLogService;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.describeInstances(any(), any(), any())).thenReturn(List.of());
        when(ec2Service.endpointNetworkInterfaces(any())).thenReturn(List.of());
        flowLogService = new FlowLogService(config, ec2Service, mock(S3Service.class),
                new InMemoryStorage<>());
    }

    @Test
    void deleteFlowLogsIsScopedToTheRequestRegion() {
        FlowLog fl = flowLogService.createFlowLog("us-east-1", "vpc-123", "VPC", "ALL",
                "s3", "arn:aws:s3:::flow-bucket", null, 600);

        List<String> otherRegion = flowLogService.deleteFlowLogs("eu-west-1", List.of(fl.getFlowLogId()));

        assertTrue(otherRegion.isEmpty(), "delete must not cross regions");
        assertEquals(1, flowLogService.describeFlowLogs("us-east-1", List.of()).size());

        List<String> sameRegion = flowLogService.deleteFlowLogs("us-east-1", List.of(fl.getFlowLogId()));

        assertEquals(List.of(fl.getFlowLogId()), sameRegion);
        assertTrue(flowLogService.describeFlowLogs("us-east-1", List.of()).isEmpty());
    }

    @Test
    void deleteFlowLogsIgnoresUnknownIds() {
        assertTrue(flowLogService.deleteFlowLogs("us-east-1", List.of("fl-doesnotexist")).isEmpty());
    }
}
