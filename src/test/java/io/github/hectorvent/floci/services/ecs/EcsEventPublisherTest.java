package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsEventPublisherTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EcsTask runningTask() {
        EcsTask t = new EcsTask();
        t.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/c/abc");
        t.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/c");
        t.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/fam:1");
        t.setLastStatus("RUNNING");
        t.setDesiredStatus("RUNNING");
        Container c = new Container();
        c.setName("app");
        c.setLastStatus("RUNNING");
        c.setExitCode(0);
        t.setContainers(List.of(c));
        return t;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capture(EventBridgeService eb, int times) {
        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(eb, times(times)).putEvents(cap.capture(), eq(REGION));
        return cap.getAllValues().stream().map(List::getFirst).toList();
    }

    @Test
    void startLadderEmitsProvisioningPendingActivatingRunning() throws Exception {
        EventBridgeService eb = mock(EventBridgeService.class);
        RegionResolver rr = new RegionResolver(REGION, "000000000000");
        EcsEventPublisher publisher = new EcsEventPublisher(eb, rr, objectMapper);

        EcsTask task = runningTask();
        publisher.emitTaskLadder(task, TaskStatus.PENDING, TaskStatus.RUNNING, REGION);

        List<Map<String, Object>> entries = capture(eb, 4);
        assertEquals(List.of("PROVISIONING", "PENDING", "ACTIVATING", "RUNNING"),
                entries.stream().map(e -> {
                    try {
                        return objectMapper.readTree((String) e.get("Detail")).path("lastStatus").asText();
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                }).toList());
        Map<String, Object> first = entries.getFirst();
        assertEquals("aws.ecs", first.get("Source"));
        assertEquals("ECS Task State Change", first.get("DetailType"));
        assertEquals("RUNNING", task.getLastStatus(), "task status is restored after the ladder");
    }

    @Test
    void stopLadderEmitsDeactivatingStoppingDeprovisioningStopped() throws Exception {
        EventBridgeService eb = mock(EventBridgeService.class);
        RegionResolver rr = new RegionResolver(REGION, "000000000000");
        EcsEventPublisher publisher = new EcsEventPublisher(eb, rr, objectMapper);

        EcsTask task = runningTask();
        task.setLastStatus("STOPPED");
        task.getContainers().getFirst().setExitCode(137);
        publisher.emitTaskLadder(task, TaskStatus.RUNNING, TaskStatus.STOPPED, REGION);

        List<Map<String, Object>> entries = capture(eb, 4);
        JsonNode lastDetail = objectMapper.readTree((String) entries.get(3).get("Detail"));
        assertEquals("STOPPED", lastDetail.path("lastStatus").asText());
        assertEquals(137, lastDetail.path("containers").path(0).path("exitCode").asInt());
    }

    @Test
    void putEventsFailureIsSwallowed() {
        EventBridgeService eb = mock(EventBridgeService.class);
        when(eb.putEvents(any(), any())).thenThrow(new RuntimeException("boom"));
        RegionResolver rr = new RegionResolver(REGION, "000000000000");
        EcsEventPublisher publisher = new EcsEventPublisher(eb, rr, objectMapper);

        publisher.emitTaskLadder(runningTask(), TaskStatus.PENDING, TaskStatus.RUNNING, REGION);
        // no exception propagates
    }
}
