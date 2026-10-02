package io.github.hectorvent.floci.services.ses.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailContentTest {

    @Test
    void withSafeHeaders_dropsUnsafeHeadersAndKeepsTheRestInOrder() {
        EmailContent.Simple content = new EmailContent.Simple("Subject", "text", "<p>html</p>", List.of(
                new MessageHeader("X-First", "one"),
                new MessageHeader(" ", "blank name"),
                new MessageHeader("X-Injected\r\nBcc", "value"),
                new MessageHeader("X-Split", "line\nBcc: victim@example.com"),
                new MessageHeader("X-Null", null),
                new MessageHeader("X-Second", "two")));

        EmailContent.Simple safe = content.withSafeHeaders();

        assertEquals(new EmailContent.Simple("Subject", "text", "<p>html</p>", List.of(
                new MessageHeader("X-First", "one"),
                new MessageHeader("X-Second", "two"))), safe);
    }

    @Test
    void withSafeHeaders_nullHeaders_returnsTheSameContent() {
        EmailContent.Simple content = new EmailContent.Simple("Subject", "text", null, null);

        assertSame(content, content.withSafeHeaders());
    }

    @Test
    void withSafeHeaders_emptyHeaders_returnsTheSameContent() {
        EmailContent.Simple content = new EmailContent.Simple("Subject", "text", null, List.of());

        assertSame(content, content.withSafeHeaders());
    }

    @Test
    void hasHeaders_falseForNullOrEmptyAndTrueOtherwise() {
        assertFalse(new EmailContent.Simple("Subject", "text", null, null).hasHeaders());
        assertFalse(new EmailContent.Simple("Subject", "text", null, List.of()).hasHeaders());
        assertTrue(new EmailContent.Simple("Subject", "text", null,
                List.of(new MessageHeader("X-Header", "value"))).hasHeaders());
    }
}
