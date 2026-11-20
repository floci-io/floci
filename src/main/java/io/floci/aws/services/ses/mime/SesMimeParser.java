package io.floci.aws.services.ses.mime;

import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;

/** The lenient MIME parse the send path runs once and shares between header extraction and the content scan. */
public final class SesMimeParser {

    private static final Logger LOG = Logger.getLogger(SesMimeParser.class);

    private SesMimeParser() {
    }

    /** Parses MIME bytes, or returns {@code null} for anything the parser cannot read. */
    public static Message parse(byte[] mimeBytes) {
        try {
            return new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(mimeBytes));
        } catch (Exception | StackOverflowError e) {
            // The caller treats an unparseable message as such, so the reason is only worth a debug
            // line. mime4j nests a reader per forwarded message, so a hostile depth overflows too;
            // the throwable is named rather than attached because that trace is thousands of frames.
            LOG.debugv("MIME message could not be parsed: {0}", e.toString());
            return null;
        }
    }
}
