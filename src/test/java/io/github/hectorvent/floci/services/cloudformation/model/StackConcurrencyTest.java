package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stack is read by request threads while the operation executor mutates it (#2419): events are
 * appended throughout a deploy while DescribeStackEvents copies them, resources are put and removed
 * while DescribeStackResources walks them. Plain collections lose writes, throw
 * {@code ConcurrentModificationException} or {@code ArrayIndexOutOfBoundsException} under that
 * load; the model's collections and snapshots must not.
 */
class StackConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int WRITES_PER_WRITER = 5_000;

    @Test
    void concurrentWritesAndSnapshotsNeitherLoseWritesNorThrow() throws Exception {
        Stack stack = new Stack();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();

        for (int w = 0; w < WRITERS; w++) {
            int writer = w;
            threads.add(new Thread(() -> run(start, failures, () -> {
                for (int i = 0; i < WRITES_PER_WRITER; i++) {
                    StackEvent event = new StackEvent();
                    event.setEventId("w" + writer + "-" + i);
                    stack.getEvents().add(event);
                    StackResource resource = new StackResource();
                    resource.setLogicalId("R" + writer + "-" + (i % 50));
                    stack.getResources().put(resource.getLogicalId(), resource);
                    if (i % 7 == 0) {
                        stack.getResources().remove("R" + writer + "-" + ((i + 3) % 50));
                    }
                    stack.getOutputs().put("O" + (i % 20), "v" + i);
                    ChangeSet changeSet = new ChangeSet();
                    changeSet.setChangeSetName("cs" + (i % 10));
                    stack.getChangeSets().put(changeSet.getChangeSetName(), changeSet);
                }
            })));
        }
        for (int r = 0; r < 3; r++) {
            threads.add(new Thread(() -> run(start, failures, () -> {
                while (!stop.get()) {
                    stack.eventsSnapshot().size();
                    stack.resourcesSnapshot().values().forEach(StackResource::getLogicalId);
                    stack.outputsSnapshot().forEach((k, v) -> k.length());
                    stack.changeSetsSnapshot().size();
                }
            })));
        }

        threads.forEach(Thread::start);
        start.countDown();
        for (int w = 0; w < WRITERS; w++) {
            threads.get(w).join();
        }
        stop.set(true);
        for (Thread reader : threads.subList(WRITERS, threads.size())) {
            reader.join();
        }

        assertTrue(failures.isEmpty(), "concurrent access failed: " + failures);
        assertEquals(WRITERS * WRITES_PER_WRITER, stack.eventsSnapshot().size(), "an appended event was lost");
        assertEquals(20, stack.outputsSnapshot().size());
        assertEquals(10, stack.changeSetsSnapshot().size());
    }

    /**
     * A replace is one step to a reader: a snapshot taken while the outputs are being replaced
     * holds either the old set or the new one, never the emptied map in between (a DescribeStacks
     * reporting no Outputs mid-update).
     */
    @Test
    void replaceIsNeverObservedHalfApplied() throws Exception {
        Stack stack = new Stack();
        Map<String, String> full = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            full.put("O" + i, "v" + i);
        }
        stack.replaceOutputs(full);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Thread writer = new Thread(() -> run(start, failures, () -> {
            for (int i = 0; i < 20_000; i++) {
                stack.replaceOutputs(full);
            }
        }));
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            readers.add(new Thread(() -> run(start, failures, () -> {
                while (!stop.get()) {
                    int size = stack.outputsSnapshot().size();
                    if (size != 20) {
                        throw new AssertionError("snapshot saw a half-applied replace: " + size + " outputs");
                    }
                }
            })));
        }
        writer.start();
        readers.forEach(Thread::start);
        start.countDown();
        writer.join();
        stop.set(true);
        for (Thread reader : readers) {
            reader.join();
        }

        assertTrue(failures.isEmpty(), "concurrent replace was observed half-applied: " + failures);
    }

    /** Insertion order is what the wire responses reproduce, so the safe collections must keep it. */
    @Test
    void collectionsKeepInsertionOrder() {
        Stack stack = new Stack();
        for (int i = 9; i >= 0; i--) {
            stack.getOutputs().put("O" + i, "v" + i);
        }
        assertEquals(List.of("O9", "O8", "O7", "O6", "O5", "O4", "O3", "O2", "O1", "O0"),
                new ArrayList<>(stack.outputsSnapshot().keySet()));
        stack.setOutputs(Map.of("only", "one"));
        assertEquals(List.of("only"), new ArrayList<>(stack.outputsSnapshot().keySet()));
    }

    /** The persisted shape is unchanged: the same property names, serialized from a consistent snapshot. */
    @Test
    void jacksonRoundTripKeepsTheStoredShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Stack stack = new Stack();
        stack.setStackName("s");
        StackEvent event = new StackEvent();
        event.setEventId("e1");
        stack.getEvents().add(event);
        StackResource resource = new StackResource();
        resource.setLogicalId("Bucket");
        stack.getResources().put("Bucket", resource);
        stack.getOutputs().put("Name", "value");
        stack.getOutputExportNames().put("Name", "exp");
        stack.getExports().put("exp", "value");
        stack.getParameters().put("P", "1");
        stack.getResolvedParameters().put("P", "1");
        stack.getTags().put("k", "v");
        ChangeSet changeSet = new ChangeSet();
        changeSet.setChangeSetName("cs");
        stack.getChangeSets().put("cs", changeSet);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(stack));
        Set<String> expected = Set.of("parameters", "resolvedParameters", "outputs", "exports",
                "outputExportNames", "resources", "events", "changeSets", "tags");
        for (String field : expected) {
            assertTrue(json.has(field), "persisted stack lacks " + field + ": " + json.fieldNames());
        }
        assertTrue(json.get("events").isArray() && json.get("events").size() == 1);
        assertTrue(json.get("resources").isObject() && json.get("resources").has("Bucket"));

        Stack restored = mapper.readValue(mapper.writeValueAsString(stack), Stack.class);
        assertEquals("e1", restored.getEvents().get(0).getEventId());
        assertEquals("Bucket", restored.getResources().get("Bucket").getLogicalId());
        assertEquals("value", restored.getOutputs().get("Name"));
        assertEquals("exp", restored.getOutputExportNames().get("Name"));
        assertEquals("cs", restored.getChangeSets().get("cs").getChangeSetName());
        assertEquals("v", restored.getTags().get("k"));
        // A restored stack is thread-safe too, not a plain LinkedHashMap handed back by Jackson.
        assertEquals("java.util.Collections$SynchronizedMap", restored.getResources().getClass().getName());
        assertEquals("java.util.concurrent.CopyOnWriteArrayList", restored.getEvents().getClass().getName());
    }

    private static void run(CountDownLatch start, ConcurrentLinkedQueue<Throwable> failures, Runnable body) {
        try {
            start.await();
            body.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }
}
