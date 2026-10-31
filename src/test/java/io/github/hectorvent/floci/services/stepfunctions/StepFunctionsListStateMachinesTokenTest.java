package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepFunctionsListStateMachinesTokenTest {

    private static final long ISSUED = 1_790_000_000_000L;
    private static final long DAY = Duration.ofHours(24).toMillis();

    private final ObjectMapper mapper = new ObjectMapper();
    private final StepFunctionsService service = mock(StepFunctionsService.class);
    private final Clock clock = mock(Clock.class);
    private StepFunctionsJsonHandler handler;

    @BeforeEach
    void setUp() {
        StateMachine machine = new StateMachine();
        machine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:page-one");
        machine.setName("page-one");
        when(service.listStateMachines("us-east-1", 1, null))
                .thenReturn(new PaginatedResult<>(List.of(machine), "inner-token"));
        when(service.listStateMachines("us-east-1", 1, "inner-token"))
                .thenReturn(new PaginatedResult<>(List.of(), null));
        when(clock.millis()).thenReturn(ISSUED);
        handler = new StepFunctionsJsonHandler(service, mapper, clock);
    }

    @Test
    void tokenExpiresAfterTwentyFourHours() {
        String token = firstPageToken();
        when(clock.millis()).thenReturn(ISSUED + DAY);
        assertEquals(0, page(handler, "us-east-1", token).path("stateMachines").size());

        when(clock.millis()).thenReturn(ISSUED + DAY + 1);
        assertInvalid(handler, "us-east-1", token);
    }

    @Test
    void tokenCannotBeUsedInAnotherRegionOrAfterRestart() {
        String token = firstPageToken();
        assertInvalid(handler, "us-west-2", token);
        assertInvalid(new StepFunctionsJsonHandler(service, mapper, clock), "us-east-1", token);
    }

    @Test
    void editedTokenIsRejected() {
        String token = firstPageToken();
        int middle = token.length() / 2;
        String edited = token.substring(0, middle) + (token.charAt(middle) == 'A' ? 'B' : 'A')
                + token.substring(middle + 1);
        assertInvalid(handler, "us-east-1", edited);
    }

    private String firstPageToken() {
        return (String) page(handler, "us-east-1", null).path("nextToken").asText();
    }

    private JsonNode page(StepFunctionsJsonHandler target, String region, String token) {
        ObjectNode request = mapper.createObjectNode().put("maxResults", 1);
        if (token != null) {
            request.put("nextToken", token);
        }
        return (JsonNode) target.handle("ListStateMachines", request, region).getEntity();
    }

    private void assertInvalid(StepFunctionsJsonHandler target, String region, String token) {
        AwsException error = assertThrows(AwsException.class, () -> page(target, region, token));
        assertEquals("InvalidToken", error.getErrorCode());
    }
}
