package io.floci.aws.services.ses.mime;

import org.apache.james.mime4j.dom.Message;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SesMimeParserTest {

    @Test
    void readableMessageIsParsed() {
        Message message = SesMimeParser.parse(
                "From: a@example.com\r\nSubject: hello\r\n\r\nbody\r\n".getBytes(StandardCharsets.US_ASCII));

        assertNotNull(message);
        assertEquals("hello", message.getSubject());
    }

    @Test
    void nestingThatOverflowsTheParserIsNullNotAnError() {
        byte[] mime = NestedMime.rfc822Layers(NestedMime.OVERFLOWS_THE_PARSER)
                .getBytes(StandardCharsets.US_ASCII);

        assertNull(SesMimeParser.parse(mime));
    }

    @Test
    void nullInputIsNull() {
        assertNull(SesMimeParser.parse(null));
    }
}
