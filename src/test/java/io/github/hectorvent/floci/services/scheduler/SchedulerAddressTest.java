package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulerAddressTest {

    private static final String REGION = "us-east-1";

    @Test
    void updateAndDeleteAddressTheRecreatedSchedule() throws Exception {
        SchedulerService service = service(new InMemoryStorage<>());
        Schedule original = service.createSchedule(request("schedule", "default", "first"), REGION);
        service.deleteSchedule("schedule", "default", REGION);
        Schedule recreated = service.createSchedule(request("schedule", "default", "external"), REGION);
        assertEquals(original.getArn(), recreated.getArn());

        Schedule updated = service.updateSchedule(request("schedule", "default", "template"), REGION);

        assertEquals(recreated.getArn(), updated.getArn());
        assertEquals(recreated.getCreationDate(), updated.getCreationDate());
        assertEquals("template", service.getSchedule("schedule", "default", REGION).getTarget().getInput());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Schedule restored = mapper.readValue(mapper.writeValueAsString(updated), Schedule.class);
        assertEquals(updated.getArn(), restored.getArn());
        assertEquals(updated.getCreationDate(), restored.getCreationDate());
        assertEquals("template", restored.getTarget().getInput());
        service.deleteSchedule("schedule", "default", REGION);
        AwsException missing = assertThrows(AwsException.class, () -> service.getSchedule("schedule", "default", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void theSameNameInAnotherGroupIsUnaffectedByUpdateAndDeletion() {
        SchedulerService service = service(new InMemoryStorage<>());
        service.createScheduleGroup("other", Map.of(), REGION);
        service.createSchedule(request("same", "default", "default"), REGION);
        service.createSchedule(request("same", "other", "other"), REGION);

        service.updateSchedule(request("same", "default", "changed"), REGION);
        service.deleteSchedule("same", "default", REGION);

        assertEquals("other", service.getSchedule("same", "other", REGION).getTarget().getInput());
        service.deleteSchedule("same", "other", REGION);
        assertThrows(AwsException.class, () -> service.getSchedule("same", "other", REGION));
    }

    @Test
    void aPersistedScheduleCanBeUpdatedAndDeletedByItsAddress() throws Exception {
        InMemoryStorage<String, Schedule> store = new InMemoryStorage<>();
        Schedule persisted = new ObjectMapper().readValue("""
                {"name":"persisted","groupName":"default","state":"DISABLED",
                 "arn":"arn:aws:scheduler:us-east-1:000000000000:schedule/default/persisted"}
                """, Schedule.class);
        store.put("schedule:" + REGION + ":default:persisted", persisted);
        SchedulerService service = service(store);

        Schedule updated = service.updateSchedule(request("persisted", "default", "updated"), REGION);

        assertEquals(persisted.getArn(), updated.getArn());
        assertEquals("updated", service.getSchedule("persisted", "default", REGION).getTarget().getInput());
        service.deleteSchedule("persisted", "default", REGION);
        assertThrows(AwsException.class, () -> service.getSchedule("persisted", "default", REGION));
    }

    private static SchedulerService service(InMemoryStorage<String, Schedule> store) {
        return new SchedulerService(new InMemoryStorage<>(), store,
                new RegionResolver(REGION, "000000000000"));
    }

    private static ScheduleRequest request(String name, String group, String input) {
        ScheduleRequest request = new ScheduleRequest();
        request.setName(name);
        request.setGroupName(group);
        request.setScheduleExpression("rate(5 minutes)");
        request.setState("DISABLED");
        FlexibleTimeWindow window = new FlexibleTimeWindow();
        window.setMode("OFF");
        request.setFlexibleTimeWindow(window);
        Target target = new Target();
        target.setArn("arn:aws:sqs:us-east-1:000000000000:queue");
        target.setRoleArn("arn:aws:iam::000000000000:role/scheduler");
        target.setInput(input);
        request.setTarget(target);
        return request;
    }
}
