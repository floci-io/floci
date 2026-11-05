package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.sqs.model.Message;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Predicate;

/** Immutable message list whose appends share one array, so a queue can grow without copying. */
final class MessageList extends AbstractList<Message> implements RandomAccess {

    static final MessageList EMPTY = new MessageList(new Slots(new Message[0], 0), 0);

    private static final class Slots {
        final Message[] items;
        // Slots any list has written. A list appends in place only when it is the one that wrote last.
        int filled;

        Slots(Message[] items, int filled) {
            this.items = items;
            this.filled = filled;
        }
    }

    private final Slots slots;
    private final int size;

    private MessageList(Slots slots, int size) {
        this.slots = slots;
        this.size = size;
    }

    static MessageList copyOf(List<Message> source) {
        Message[] items = source.toArray(new Message[0]);
        return new MessageList(new Slots(items, items.length), items.length);
    }

    @Override
    public Message get(int index) {
        Objects.checkIndex(index, size);
        return slots.items[index];
    }

    @Override
    public int size() {
        return size;
    }

    MessageList plus(Message message) {
        return plusAll(List.of(message));
    }

    MessageList plusAll(List<Message> added) {
        if (added.isEmpty()) {
            return this;
        }
        int newSize = size + added.size();
        if (size == slots.filled && newSize <= slots.items.length) {
            int index = size;
            for (Message message : added) {
                slots.items[index++] = message;
            }
            slots.filled = newSize;
            return new MessageList(slots, newSize);
        }
        Message[] items = grown(newSize);
        int index = size;
        for (Message message : added) {
            items[index++] = message;
        }
        return new MessageList(new Slots(items, newSize), newSize);
    }

    MessageList withFirst(Message message) {
        Message[] items = new Message[size + 1];
        items[0] = message;
        System.arraycopy(slots.items, 0, items, 1, size);
        return new MessageList(new Slots(items, items.length), items.length);
    }

    MessageList withoutFirst() {
        Message[] items = new Message[size - 1];
        System.arraycopy(slots.items, 1, items, 0, items.length);
        return new MessageList(new Slots(items, items.length), items.length);
    }

    MessageList without(Message message) {
        for (int i = 0; i < size; i++) {
            if (slots.items[i] == message) {
                Message[] items = new Message[size - 1];
                System.arraycopy(slots.items, 0, items, 0, i);
                System.arraycopy(slots.items, i + 1, items, i, size - i - 1);
                return new MessageList(new Slots(items, items.length), items.length);
            }
        }
        return this;
    }

    MessageList withoutAll(Predicate<Message> drop) {
        int first = 0;
        while (first < size && !drop.test(slots.items[first])) {
            first++;
        }
        if (first == size) {
            return this;
        }
        Message[] kept = new Message[size - 1];
        System.arraycopy(slots.items, 0, kept, 0, first);
        int count = first;
        for (int i = first + 1; i < size; i++) {
            Message message = slots.items[i];
            if (!drop.test(message)) {
                kept[count++] = message;
            }
        }
        if (count == kept.length) {
            return new MessageList(new Slots(kept, count), count);
        }
        Message[] items = new Message[count];
        System.arraycopy(kept, 0, items, 0, count);
        return new MessageList(new Slots(items, count), count);
    }

    private Message[] grown(int newSize) {
        Message[] items = new Message[Math.max(newSize, size + (size >> 1) + 10)];
        System.arraycopy(slots.items, 0, items, 0, size);
        return items;
    }
}
