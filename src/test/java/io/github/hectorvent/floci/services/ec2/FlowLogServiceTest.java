package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    /**
     * The delivered records follow the flow log's LogFormat.
     *
     * <p>record used to emit all 29 available fields in alphabetical order whatever the format
     * said, so a custom format produced a short header line above long rows and a reader following
     * the header took the wrong value for every column.
     */
    @Test
    void aCustomFormatDeliversThoseFieldsInThatOrder() throws Exception {
        List<String> lines = deliver("${srcaddr} ${dstaddr} ${action}");

        assertEquals("srcaddr dstaddr action", lines.get(0));
        assertFalse(lines.size() < 2, "expected at least one record");
        for (String row : lines.subList(1, lines.size())) {
            assertEquals(3, row.split(" ").length, "row does not match the header: " + row);
            assertTrue(row.endsWith(" ACCEPT"), "action is not the last field: " + row);
        }
    }

    /** A flow log with no LogFormat delivers the version 2 fields, and reports version 2. */
    @Test
    void noCustomFormatDeliversTheVersion2Default() throws Exception {
        List<String> lines = deliver(null);

        assertEquals(String.join(" ", FlowLogService.DEFAULT_FIELDS), lines.get(0));
        for (String row : lines.subList(1, lines.size())) {
            String[] columns = row.split(" ");
            assertEquals(FlowLogService.DEFAULT_FIELDS.size(), columns.length,
                    "row does not match the header: " + row);
            assertEquals("2", columns[0], "version is not the version 2 default: " + row);
        }
    }

    /** The version a record reports is the highest its fields belong to, not a fixed 5. */
    @Test
    void aVersion5FieldRaisesTheReportedVersion() throws Exception {
        List<String> lines = deliver("${version} ${flow-direction}");

        assertEquals("version flow-direction", lines.get(0));
        for (String row : lines.subList(1, lines.size())) {
            assertEquals("5", row.split(" ")[0], "version did not follow the fields: " + row);
        }
    }

    /**
     * A field this emulator carries no value for keeps its column and reads "-".
     *
     * <p>Dropping it would shift every later value one place left against the header the caller
     * asked for, which is worse than saying the field does not apply.
     */
    @Test
    void anUnsupportedFieldKeepsItsColumnAsADash() throws Exception {
        List<String> lines = deliver("${srcaddr} ${ecs-cluster-arn} ${action}");

        assertEquals("srcaddr ecs-cluster-arn action", lines.get(0));
        for (String row : lines.subList(1, lines.size())) {
            assertEquals("-", row.split(" ")[1], "unsupported field is not a dash: " + row);
        }
    }

    /** Deliver one file for a flow log with this LogFormat and return its lines. */
    private List<String> deliver(String logFormat) throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.callerAccountId()).thenReturn("000000000000");
        when(ec2Service.describeInstances(any(), any(), any()))
                .thenReturn(List.of(reservation()));
        when(ec2Service.endpointNetworkInterfaces(any())).thenReturn(List.of());
        S3Service s3Service = mock(S3Service.class);
        FlowLogService service =
                new FlowLogService(ec2Service, s3Service, new InMemoryStorage<>());

        FlowLog fl = service.createFlowLog("us-east-1", "vpc-123", "VPC", "ALL", "s3",
                "arn:aws:s3:::flow-bucket", null, logFormat, 600);
        service.generateAndDeliver(fl);

        // createFlowLog delivers once on creation, so take the file from the explicit call.
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, atLeastOnce()).putObject(eq("flow-bucket"), anyString(), body.capture(),
                anyString(), any(Map.class));
        return List.of(gunzip(body.getValue()).split("\n"));
    }

    private static Reservation reservation() {
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance("i-aaa", "10.0.0.4"), instance("i-bbb", "10.0.0.5")));
        return reservation;
    }

    private static Instance instance(String instanceId, String privateIp) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setPrivateIpAddress(privateIp);
        instance.setVpcId("vpc-123");
        instance.setSubnetId("subnet-123");
        Placement placement = new Placement();
        placement.setAvailabilityZone("us-east-1a");
        instance.setPlacement(placement);
        return instance;
    }

    private static String gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
