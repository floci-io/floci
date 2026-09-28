package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real {@link SchedulerService} over account-aware storage, driving the same list-then-delete path
 * that {@link ScheduleDispatcher} takes when a one-time schedule completes with
 * {@code ActionAfterCompletion=DELETE}.
 */
class SchedulerServiceAccountTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final SchedulerService service = new SchedulerService(
            AccountAwareStorageBackend.inMemory(ACCOUNT),
            AccountAwareStorageBackend.inMemory(ACCOUNT),
            new RegionResolver(REGION, ACCOUNT));

    private static ScheduleRequest oneTimeDeleteAfterCompletion(String name, String description) {
        ScheduleRequest req = new ScheduleRequest();
        req.setName(name);
        req.setScheduleExpression("at(2030-01-01T00:00:00)");
        req.setFlexibleTimeWindow(new FlexibleTimeWindow("OFF", null));
        Target target = new Target();
        target.setArn("arn:aws:sqs:us-east-1:000000000000:q");
        target.setRoleArn("arn:aws:iam::000000000000:role/r");
        req.setTarget(target);
        req.setActionAfterCompletion("DELETE");
        req.setDescription(description);
        return req;
    }

    @Test
    void updatedScheduleIsDeletedAfterCompletionInItsAccount() {
        service.createSchedule(oneTimeDeleteAfterCompletion("once", "v1"), REGION);
        service.updateSchedule(oneTimeDeleteAfterCompletion("once", "v2"), REGION);

        Schedule completed = service.listAllSchedules().getFirst();
        service.deleteScheduleForAccount(completed.getAccountId(), completed.getName(),
                completed.getGroupName(), REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.getSchedule("once", null, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}
