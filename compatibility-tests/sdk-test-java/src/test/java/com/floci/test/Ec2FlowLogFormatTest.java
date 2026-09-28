package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.FlowLog;
import software.amazon.awssdk.services.ec2.model.FlowLogsResourceType;
import software.amazon.awssdk.services.ec2.model.TrafficType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2FlowLogFormatTest {
    private static final String FORMAT =
            "${version} ${account-id} ${interface-id} ${srcaddr} ${dstaddr} ${action}";
    /** What AWS creates a flow log with when LogFormat is omitted, the version 2 fields. */
    private static final String DEFAULT_FORMAT =
            "${version} ${account-id} ${interface-id} ${srcaddr} ${dstaddr} ${srcport} "
            + "${dstport} ${protocol} ${packets} ${bytes} ${start} ${end} ${action} ${log-status}";

    private static String createFlowLog(Ec2Client ec2, String vpcId, String logFormat) {
        return ec2.createFlowLogs(r -> {
            r.resourceIds(vpcId)
                    .resourceType(FlowLogsResourceType.VPC)
                    .trafficType(TrafficType.ALL)
                    .logDestinationType("s3")
                    .logDestination("arn:aws:s3:::flow-logs-bucket");
            if (logFormat != null) {
                r.logFormat(logFormat);
            }
        }).flowLogIds().get(0);
    }

    private static FlowLog describe(Ec2Client ec2, String flowLogId) {
        List<FlowLog> logs = ec2.describeFlowLogs(r -> r.flowLogIds(flowLogId)).flowLogs();
        assertThat(logs).hasSize(1);
        return logs.get(0);
    }

    @Test
    @DisplayName("DescribeFlowLogs returns the custom LogFormat, or the default when none was set")
    void describesTheCustomLogFormat() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.72.0.0/16")).vpc().vpcId();
            String withFormat = null;
            String withoutFormat = null;
            try {
                withFormat = createFlowLog(ec2, vpcId, FORMAT);
                withoutFormat = createFlowLog(ec2, vpcId, null);

                assertThat(describe(ec2, withFormat).logFormat()).isEqualTo(FORMAT);
                assertThat(describe(ec2, withoutFormat).logFormat()).isEqualTo(DEFAULT_FORMAT);
            } finally {
                for (String id : new String[] {withFormat, withoutFormat}) {
                    if (id != null) {
                        ec2.deleteFlowLogs(r -> r.flowLogIds(id));
                    }
                }
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }
}
