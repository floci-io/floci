package io.github.hectorvent.floci.services.ec2;

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
        flowLogService = serviceCallingAs("000000000000");
    }

    /** A service whose EC2 dependency resolves the caller to {@code accountId}. */
    private FlowLogService serviceCallingAs(String accountId) {
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.callerAccountId()).thenReturn(accountId);
        when(ec2Service.describeInstances(any(), any(), any())).thenReturn(List.of());
        when(ec2Service.endpointNetworkInterfaces(any())).thenReturn(List.of());
        return new FlowLogService(ec2Service, mock(S3Service.class), new InMemoryStorage<>());
    }

    @Test
    void createFlowLogRecordsTheCallingAccountNotTheDefault() {
        // The account lands in the delivered record's account-id field and in the S3 key prefix
        // (AWSLogs/{account}/vpcflowlogs/...), so a flow log created by a non-default caller must
        // carry that caller. Before issue #3775 this was pinned to the configured default account.
        FlowLog fl = serviceCallingAs("444444444444").createFlowLog("us-east-1", "vpc-123", "VPC",
                "ALL", "s3", "arn:aws:s3:::flow-bucket", null, null, 600);

        assertEquals("444444444444", fl.getAccountId());
    }

    @Test
    void deleteFlowLogsIsScopedToTheRequestRegion() {
        FlowLog fl = flowLogService.createFlowLog("us-east-1", "vpc-123", "VPC", "ALL",
                "s3", "arn:aws:s3:::flow-bucket", null, null, 600);

        List<String> otherRegion = flowLogService.deleteFlowLogs("eu-west-1", List.of(fl.getFlowLogId()));

        assertTrue(otherRegion.isEmpty(), "delete must not cross regions");
        assertEquals(1, flowLogService.describeFlowLogs("us-east-1", List.of()).size());

        List<String> sameRegion = flowLogService.deleteFlowLogs("us-east-1", List.of(fl.getFlowLogId()));

        assertEquals(List.of(fl.getFlowLogId()), sameRegion);
        assertTrue(flowLogService.describeFlowLogs("us-east-1", List.of()).isEmpty());
    }

    @Test
    void createFlowLogKeepsTheDeliverLogsPermissionArn() {
        FlowLog fl = flowLogService.createFlowLog("us-east-1", "vpc-123", "VPC", "ALL",
                "cloud-watch-logs", "arn:aws:logs:us-east-1:000000000000:log-group:flows",
                "arn:aws:iam::000000000000:role/flow-logs-role", null, 600);

        FlowLog described = flowLogService.describeFlowLogs("us-east-1", List.of(fl.getFlowLogId())).get(0);

        assertEquals("arn:aws:iam::000000000000:role/flow-logs-role", described.getDeliverLogsPermissionArn());
    }

    @Test
    void deleteFlowLogsIgnoresUnknownIds() {
        assertTrue(flowLogService.deleteFlowLogs("us-east-1", List.of("fl-doesnotexist")).isEmpty());
    }
}
