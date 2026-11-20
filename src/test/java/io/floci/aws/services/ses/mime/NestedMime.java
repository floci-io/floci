package io.floci.aws.services.ses.mime;

/** Builds messages wrapped in {@code message/rfc822} layers, for the depth limits of mime4j. */
public final class NestedMime {

    /** Deep enough that mime4j, which nests a reader per forwarded message, overflows the stack parsing it. */
    public static final int OVERFLOWS_THE_PARSER = 100_000;

    /** Shallow enough to parse, too deep for the writer that re-serializes embedded messages. */
    public static final int OVERFLOWS_THE_WRITER = 10_000;

    private NestedMime() {
    }

    public static String rfc822Layers(int depth) {
        return "Content-Type: message/rfc822\r\n\r\n".repeat(depth) + "Content-Type: text/plain\r\n\r\nbody\r\n";
    }
}
