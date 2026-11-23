package io.floci.aws.services.dynamodb;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * A first-in, first-out buffer over a circular array. Adding at the tail and removing from the head take constant
 * time, as in {@link java.util.ArrayDeque}, but any element can also be read by its position, so a buffer kept in
 * order can be binary searched. Not thread-safe.
 */
final class RingBuffer<T> implements Iterable<T> {

    private static final int INITIAL_CAPACITY = 16;

    private Object[] elements = new Object[INITIAL_CAPACITY];
    /** Where the oldest element sits in {@link #elements}. */
    private int head;
    private int size;

    void addLast(T element) {
        if (element == null) {
            throw new NullPointerException("A ring buffer holds no nulls");
        }
        if (size == elements.length) {
            grow();
        }
        elements[slot(size)] = element;
        size++;
    }

    T removeFirst() {
        T first = getFirst();
        elements[head] = null;
        head = head + 1 == elements.length ? 0 : head + 1;
        size--;
        return first;
    }

    T getFirst() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return element(head);
    }

    T getLast() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return element(slot(size - 1));
    }

    /** The element {@code index} places after the oldest. */
    T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
        }
        return element(slot(index));
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /**
     * The position of the first element {@code reached} accepts, or {@link #size()} when it accepts none. The buffer
     * must be ordered for it: once an element is accepted, every newer one is too.
     */
    int firstIndexWhere(Predicate<? super T> reached) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (reached.test(element(slot(middle)))) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /** Oldest first. The buffer must not change while it is iterated. */
    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < size;
            }

            @Override
            public T next() {
                if (next >= size) {
                    throw new NoSuchElementException();
                }
                return element(slot(next++));
            }
        };
    }

    private int slot(int index) {
        int slot = head + index;
        return slot < elements.length ? slot : slot - elements.length;
    }

    private void grow() {
        Object[] larger = new Object[elements.length * 2];
        for (int i = 0; i < size; i++) {
            larger[i] = elements[slot(i)];
        }
        elements = larger;
        head = 0;
    }

    @SuppressWarnings("unchecked")
    private T element(int slot) {
        return (T) elements[slot];
    }
}
