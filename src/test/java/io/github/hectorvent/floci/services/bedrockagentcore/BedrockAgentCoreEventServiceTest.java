package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryEvent;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService.MemoryDeleted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class BedrockAgentCoreEventServiceTest {

    private static final String REGION = "us-east-1";

    @Test
    void deletingAMemoryDropsOnlyItsEvents() {
        InMemoryStorage<String, MemoryEvent> events = new InMemoryStorage<>();
        BedrockAgentCoreMemoryService memories = mock(BedrockAgentCoreMemoryService.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return null;
        }).when(memories).writeEvent(anyString(), anyString(), any());
        BedrockAgentCoreEventService service = new BedrockAgentCoreEventService(events, memories);
        String gone = "gone-0123456789";
        String kept = "kept-0123456789";
        service.createEvent(gone, "actor1", "sess1", 1789300800d, List.of(), true, null, REGION);
        service.createEvent(kept, "actor1", "sess1", 1789300800d, List.of(), true, null, REGION);

        service.onMemoryDeleted(new MemoryDeleted(gone));

        assertEquals(List.of(kept), events.scan(key -> true).stream().map(MemoryEvent::getMemoryId).toList());
    }
}
