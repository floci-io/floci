package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageListTest {

    private final Message a = new Message("a");
    private final Message b = new Message("b");
    private final Message c = new Message("c");

    private static void assertIdentical(MessageList actual, Message... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertSame(expected[i], actual.get(i));
        }
    }

    @Test
    void plusFromSharedBaseKeepsBothBranchesIntact() {
        MessageList base = MessageList.EMPTY.plus(a);
        MessageList left = base.plus(b);
        MessageList right = base.plus(c);

        assertIdentical(base, a);
        assertIdentical(left, a, b);
        assertIdentical(right, a, c);
    }

    @Test
    void plusAllFromSharedBaseKeepsBothBranchesIntact() {
        MessageList base = MessageList.EMPTY.plus(a);
        MessageList left = base.plusAll(List.of(b));
        MessageList right = base.plusAll(List.of(c, b));

        assertIdentical(left, a, b);
        assertIdentical(right, a, c, b);
        assertSame(base, base.plusAll(List.of()));
    }

    @Test
    void plusKeepsEarlierListsAcrossGrowth() {
        MessageList list = MessageList.EMPTY;
        MessageList[] versions = new MessageList[50];
        Message[] added = new Message[50];
        for (int i = 0; i < 50; i++) {
            added[i] = new Message("m" + i);
            list = list.plus(added[i]);
            versions[i] = list;
        }
        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1, versions[i].size());
            for (int j = 0; j <= i; j++) {
                assertSame(added[j], versions[i].get(j));
            }
        }
    }

    @Test
    void withoutRemovesFirstMiddleAndLast() {
        MessageList list = MessageList.copyOf(List.of(a, b, c));

        assertIdentical(list.without(a), b, c);
        assertIdentical(list.without(b), a, c);
        assertIdentical(list.without(c), a, b);
        assertIdentical(list, a, b, c);
    }

    @Test
    void withoutReturnsSameInstanceWhenAbsent() {
        MessageList list = MessageList.copyOf(List.of(a, b));

        assertSame(list, list.without(c));
    }

    @Test
    void withoutAllKeepsTheOtherMessagesInOrder() {
        MessageList list = MessageList.copyOf(List.of(a, b, c));

        assertIdentical(list.withoutAll(message -> message == b), a, c);
        assertTrue(list.withoutAll(message -> true).isEmpty());
        assertSame(list, list.withoutAll(message -> false));
    }

    @Test
    void withFirstAndWithoutFirstKeepOrder() {
        MessageList list = MessageList.copyOf(List.of(a, b));

        assertIdentical(list.withFirst(c), c, a, b);
        assertIdentical(list.withoutFirst(), b);
        assertIdentical(list, a, b);
    }

    @Test
    void listCannotBeChangedFromOutside() {
        MessageList list = MessageList.copyOf(List.of(a));

        assertThrows(UnsupportedOperationException.class, () -> list.add(b));
        assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> list.set(0, b));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
    }
}
