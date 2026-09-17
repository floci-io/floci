package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated, non-CDI unit tests for {@link BoundedWriter}, exercising writes under, at, and over
 * the configured character budget directly, without going through Velocity or Quarkus.
 */
class BoundedWriterTest {

    @Test
    void write_underLimit_passesThroughToDelegate() throws Exception {
        StringWriter delegate = new StringWriter();
        BoundedWriter writer = new BoundedWriter(delegate, 10);

        writer.write("hello".toCharArray(), 0, 5);

        assertEquals("hello", delegate.toString());
    }

    @Test
    void write_exactlyAtLimit_isAllowed() throws Exception {
        StringWriter delegate = new StringWriter();
        BoundedWriter writer = new BoundedWriter(delegate, 5);

        writer.write("hello".toCharArray(), 0, 5);

        assertEquals("hello", delegate.toString());
    }

    @Test
    void write_overLimit_throwsAndDoesNotWriteToDelegate() {
        StringWriter delegate = new StringWriter();
        BoundedWriter writer = new BoundedWriter(delegate, 4);

        assertThrows(VtlLimitExceededException.class, () -> writer.write("hello".toCharArray(), 0, 5));
        assertEquals("", delegate.toString(), "a write that exceeds the budget must not partially land");
    }

    @Test
    void write_multipleWritesAccumulate_andTripOnceTotalExceedsLimit() throws Exception {
        StringWriter delegate = new StringWriter();
        BoundedWriter writer = new BoundedWriter(delegate, 6);

        writer.write("abc".toCharArray(), 0, 3);
        writer.write("def".toCharArray(), 0, 3);
        assertEquals("abcdef", delegate.toString());

        assertThrows(VtlLimitExceededException.class, () -> writer.write("g".toCharArray(), 0, 1));
    }

    @Test
    void flushAndClose_delegateToUnderlyingWriter() throws Exception {
        StringWriter delegate = new StringWriter();
        BoundedWriter writer = new BoundedWriter(delegate, 10);

        writer.write("ok".toCharArray(), 0, 2);
        writer.flush();
        writer.close();

        assertEquals("ok", delegate.toString());
    }
}
