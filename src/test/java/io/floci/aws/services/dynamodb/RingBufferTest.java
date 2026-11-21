package io.floci.aws.services.dynamodb;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingBufferTest {

    @Test
    void anEmptyBufferHasNothingToGiveBack() {
        RingBuffer<String> buffer = new RingBuffer<>();

        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
        assertThrows(NoSuchElementException.class, buffer::getFirst);
        assertThrows(NoSuchElementException.class, buffer::getLast);
        assertThrows(NoSuchElementException.class, buffer::removeFirst);
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.get(0));
        assertEquals(0, buffer.firstIndexWhere(element -> true));
        assertFalse(buffer.iterator().hasNext());
    }

    @Test
    void elementsLeaveInTheOrderTheyCame() {
        RingBuffer<Integer> buffer = new RingBuffer<>();
        for (int i = 0; i < 5; i++) {
            buffer.addLast(i);
        }

        assertEquals(0, buffer.getFirst());
        assertEquals(4, buffer.getLast());
        assertEquals(0, buffer.removeFirst());
        assertEquals(1, buffer.removeFirst());
        assertEquals(List.of(2, 3, 4), contents(buffer));
        assertEquals(3, buffer.size());
    }

    @Test
    void positionsCountFromTheOldestAcrossTheWrapAround() {
        RingBuffer<Integer> buffer = new RingBuffer<>();
        for (int i = 0; i < 16; i++) {
            buffer.addLast(i);
        }
        for (int i = 0; i < 10; i++) {
            buffer.removeFirst();
        }
        for (int i = 16; i < 24; i++) {
            buffer.addLast(i);
        }

        assertEquals(14, buffer.size());
        for (int i = 0; i < buffer.size(); i++) {
            assertEquals(10 + i, buffer.get(i));
        }
        assertEquals(10, buffer.getFirst());
        assertEquals(23, buffer.getLast());
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.get(14));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.get(-1));
    }

    @Test
    void growingKeepsTheOrderEvenWhenTheElementsHadWrapped() {
        RingBuffer<Integer> buffer = new RingBuffer<>();
        for (int i = 0; i < 12; i++) {
            buffer.addLast(i);
        }
        for (int i = 0; i < 8; i++) {
            buffer.removeFirst();
        }
        for (int i = 12; i < 100; i++) {
            buffer.addLast(i);
        }

        List<Integer> expected = new ArrayList<>();
        for (int i = 8; i < 100; i++) {
            expected.add(i);
        }
        assertEquals(expected, contents(buffer));
    }

    @Test
    void aNullIsRefused() {
        RingBuffer<String> buffer = new RingBuffer<>();

        assertThrows(NullPointerException.class, () -> buffer.addLast(null));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void theBinarySearchFindsTheFirstElementPastAThreshold() {
        RingBuffer<Integer> buffer = new RingBuffer<>();
        for (int i = 0; i < 40; i++) {
            buffer.addLast(i * 2);
        }
        for (int i = 0; i < 7; i++) {
            buffer.removeFirst();
            buffer.addLast(80 + i * 2);
        }

        for (int threshold = 0; threshold <= 100; threshold++) {
            int bound = threshold;
            int expected = 0;
            while (expected < buffer.size() && buffer.get(expected) < bound) {
                expected++;
            }
            assertEquals(expected, buffer.firstIndexWhere(element -> element >= bound), "threshold " + threshold);
        }
    }

    @Test
    void itBehavesLikeAnArrayDequeUnderAnyMixOfAddsAndRemoves() {
        Random random = new Random(8787);
        RingBuffer<Integer> buffer = new RingBuffer<>();
        ArrayDeque<Integer> reference = new ArrayDeque<>();
        int next = 0;
        for (int step = 0; step < 20_000; step++) {
            if (reference.isEmpty() || random.nextInt(5) < 3) {
                buffer.addLast(next);
                reference.addLast(next);
                next++;
            } else {
                assertEquals(reference.removeFirst(), buffer.removeFirst());
            }
            assertEquals(reference.size(), buffer.size());
            if (!reference.isEmpty()) {
                assertEquals(reference.getFirst(), buffer.getFirst());
                assertEquals(reference.getLast(), buffer.getLast());
                int bound = reference.getFirst() + random.nextInt(reference.size() + 1);
                assertEquals(bound - reference.getFirst(), buffer.firstIndexWhere(element -> element >= bound));
            }
        }
        assertEquals(new ArrayList<>(reference), contents(buffer));
    }

    private static <T> List<T> contents(RingBuffer<T> buffer) {
        List<T> elements = new ArrayList<>();
        for (T element : buffer) {
            elements.add(element);
        }
        return elements;
    }
}
