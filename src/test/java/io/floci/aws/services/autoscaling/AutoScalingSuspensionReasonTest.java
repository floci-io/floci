package io.floci.aws.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.restassured.path.xml.XmlPath;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AutoScalingSuspensionReasonTest {
    private static final Instant OLD = Instant.parse("2024-02-09T01:26:39Z");
    private static final String PATH = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member.SuspendedProcesses.member";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyProcessNamesOmitReasonsWhenNoSuspensionTimeWasRecorded(boolean explicitNull) throws Exception {
        ObjectMapper mapper = mapper();
        String extra = explicitNull ? ", \"suspendedProcessTimes\":null" : "";
        AutoScalingGroup group = mapper.readValue("{\"suspendedProcesses\":[\"Launch\",\"Terminate\"]"
                + extra + "}", AutoScalingGroup.class);
        AutoScalingService service = service(group, "legacy-reason");
        XmlPath xml = describe(service, "legacy-reason");
        assertEquals(List.of("Launch", "Terminate"), xml.getList(PATH + ".ProcessName", String.class));
        assertTrue(xml.getList(PATH + ".SuspensionReason", String.class).isEmpty());
        service.suspendProcesses("us-east-1", "legacy-reason", List.of("Launch"));
        assertTrue(group.getSuspendedProcessTimes().isEmpty(), "duplicate suspension must not invent a legacy date");
        assertFalse(mapper.writeValueAsString(group).contains("suspensionState"));
    }

    @Test
    void timestampsRestoreInEitherJsonPropertyOrderAndSnapshotCopiesInputs() throws Exception {
        for (String json : List.of(
                "{\"suspendedProcessTimes\":{\"Launch\":\"2024-02-09T01:26:39Z\"},\"suspendedProcesses\":[\"Launch\"]}",
                "{\"suspendedProcesses\":[\"Launch\"],\"suspendedProcessTimes\":{\"Launch\":\"2024-02-09T01:26:39Z\"}}")) {
            AutoScalingGroup restored = mapper().readValue(json, AutoScalingGroup.class);
            assertEquals(Map.of("Launch", OLD), restored.suspensionState().times());
            assertEquals(List.of("Launch"), restored.suspensionState().processes());
        }
        AutoScalingGroup group = new AutoScalingGroup();
        List<String> names = new ArrayList<>(List.of("Launch"));
        Map<String, Instant> times = new LinkedHashMap<>(Map.of("Launch", OLD));
        group.updateSuspensionState(names, times);
        names.clear();
        times.clear();
        assertEquals(List.of("Launch"), group.suspensionState().processes());
        assertEquals(Map.of("Launch", OLD), group.suspensionState().times());
        assertThrows(UnsupportedOperationException.class, () -> group.suspensionState().times().clear());
        assertThrows(UnsupportedOperationException.class, () -> group.suspensionState().processes().clear());
    }

    @Test
    void duplicatePreservesTheIntervalAndNewSuspensionReplacesOrphanHistory() {
        AutoScalingGroup group = new AutoScalingGroup();
        group.updateSuspensionState(List.of("Launch"), Map.of("Launch", OLD, "Terminate", OLD));
        AutoScalingService service = service(group, "interval");
        service.suspendProcesses("us-east-1", "interval", List.of("Launch", "Launch"));
        assertEquals(Map.of("Launch", OLD), group.getSuspendedProcessTimes());
        service.suspendProcesses("us-east-1", "interval", List.of("Terminate"));
        assertNotEquals(OLD, group.getSuspendedProcessTimes().get("Terminate"));
        service.resumeProcesses("us-east-1", "interval", List.of("Launch"));
        assertFalse(group.getSuspendedProcessTimes().containsKey("Launch"));
        service.suspendProcesses("us-east-1", "interval", List.of("Launch"));
        assertNotEquals(OLD, group.getSuspendedProcessTimes().get("Launch"));
        assertEquals(0, group.getSuspendedProcessTimes().get("Launch").getNano());
        service.resumeProcesses("us-east-1", "interval", List.of());
        assertTrue(group.getSuspendedProcesses().isEmpty());
        assertTrue(group.getSuspendedProcessTimes().isEmpty());
    }

    @Test
    void describeKeepsOneIntervalSnapshotWhileSameGroupAndUnrelatedGroupProgress() throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        AutoScalingGroup group = new AutoScalingGroup() {
            @Override
            public SuspensionState suspensionState() {
                SuspensionState snapshot = super.suspensionState();
                if (Thread.currentThread() == reader.get()) {
                    captured.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return snapshot;
            }
        };
        group.updateSuspensionState(List.of("Launch"), Map.of("Launch", OLD));
        AutoScalingService service = service(group, "snapshot");
        service(service, new AutoScalingGroup(), "unrelated");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<XmlPath> description = workers.submit(() -> {
                reader.set(Thread.currentThread());
                return describe(service, "snapshot");
            });
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            Future<?> mutations = workers.submit(() -> {
                service.resumeProcesses("us-east-1", "snapshot", List.of());
                service.suspendProcesses("us-east-1", "snapshot", List.of("Terminate"));
                service.suspendProcesses("us-east-1", "unrelated", List.of("Launch"));
            });
            mutations.get(2, TimeUnit.SECONDS);
            release.countDown();
            XmlPath old = description.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("Launch"), old.getList(PATH + ".ProcessName", String.class));
            assertEquals(List.of("User suspended at " + OLD), old.getList(PATH + ".SuspensionReason", String.class));
            reader.set(null);
            XmlPath current = describe(service, "snapshot");
            assertEquals(List.of("Terminate"), current.getList(PATH + ".ProcessName", String.class));
            assertEquals(List.of("User suspended at " + group.getSuspendedProcessTimes().get("Terminate")),
                    current.getList(PATH + ".SuspensionReason", String.class));
            assertEquals(List.of("Launch"), service.describeAutoScalingGroups("us-east-1", List.of("unrelated"))
                    .getFirst().getSuspendedProcesses());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static AutoScalingService service(AutoScalingGroup group, String name) {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver("us-east-1", "000000000000");
        service(service, group, name);
        return service;
    }

    private static void service(AutoScalingService service, AutoScalingGroup group, String name) {
        group.setAutoScalingGroupName(name);
        group.setAutoScalingGroupArn("arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:id:autoScalingGroupName/" + name);
        group.setCreatedTime(OLD);
        group.setRegion("us-east-1");
        service.groups.put(AutoScalingService.asgKey("us-east-1", name), group);
    }

    private static XmlPath describe(AutoScalingService service, String name) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AutoScalingGroupNames.member.1", name);
        Response response = new AutoScalingQueryHandler(service).handle("DescribeAutoScalingGroups", params, "us-east-1");
        assertEquals(200, response.getStatus());
        return new XmlPath((String) response.getEntity());
    }
}
