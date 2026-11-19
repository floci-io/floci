package io.floci.aws.services.ses.mime;

import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;

import java.io.ByteArrayInputStream;

/** The lenient MIME parse the send path runs once and shares between header extraction and the content scan. */
public final class SesMimeParser {

    private SesMimeParser() {
    }

    /** Parses MIME bytes, or returns {@code null} for anything the parser cannot read. */
    public static Message parse(byte[] mimeBytes) {
        try {
            return new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(mimeBytes));
        } catch (Exception | StackOverflowError ignored) {
            // The caller treats an unparseable message as such; there is nothing to diagnose here,
            // and mime4j nests a reader per forwarded message, so a hostile depth overflows too.
            return null;
        }
    }
}
