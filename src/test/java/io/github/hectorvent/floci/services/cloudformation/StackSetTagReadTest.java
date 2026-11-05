package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class StackSetTagReadTest {
    private static final String SET = "tag-snapshot";
    private StackSetService service;
    private Map<String, String> initialTags;

    @BeforeEach
    void setUp() {
        service = new StackSetService(mock(CloudFormationService.class), new InMemoryStorageFactory("000000000000"));
        initialTags = new LinkedHashMap<>();
        initialTags.put("team", "red");
        initialTags.put("owner", "original");
        service.createStackSet(SET, "{\"Resources\":{}}", null, null, initialTags, null);
    }

    @Test
    void updatingTagsLeavesAnActiveReadOnItsOriginalMap() {
        Map<String, String> tagsBeingRead = service.describeStackSet(SET).getTags();
        Iterator<Map.Entry<String, String>> iterator = tagsBeingRead.entrySet().iterator();
        assertEquals("team", iterator.next().getKey());
        Map<String, String> updated = Map.of("team", "blue", "owner", "updated");

        service.updateStackSet(SET, null, null, null, updated, null);

        Map.Entry<String, String> remaining = assertDoesNotThrow(iterator::next);
        assertEquals("owner", remaining.getKey());
        assertEquals("original", remaining.getValue());
        assertEquals(initialTags, tagsBeingRead);
        assertEquals(updated, service.describeStackSet(SET).getTags());
    }

    @Test
    void updatingTagsCopiesTheRequestMap() {
        Map<String, String> updated = new LinkedHashMap<>();
        updated.put("team", "blue");
        service.updateStackSet(SET, null, null, null, updated, null);

        updated.put("team", "green");

        assertEquals(Map.of("team", "blue"), service.describeStackSet(SET).getTags());
    }

}
