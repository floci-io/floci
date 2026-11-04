package io.github.hectorvent.floci.core.common;

/**
 * Fluent, allocation-efficient XML builder backed by a plain {@link StringBuilder}.
 *
 * <p>All content written via {@link #elem} and {@link #start} is automatically escaped.
 * Pre-built XML fragments can be injected without re-escaping via {@link #raw}.
 *
 * <pre>{@code
 * String xml = new XmlBuilder()
 *     .start("CreateQueueResponse")
 *       .start("CreateQueueResult")
 *         .elem("QueueUrl", queue.getQueueUrl())
 *       .end("CreateQueueResult")
 *       .raw(AwsQueryResponse.responseMetadata())
 *     .end("CreateQueueResponse")
 *     .build();
 * }</pre>
 */
public final class XmlBuilder {

    private final StringBuilder sb;

    public XmlBuilder() {
        this.sb = new StringBuilder();
    }

    public XmlBuilder(int expectedLength) {
        this.sb = new StringBuilder(expectedLength);
    }

    /** Opens {@code <element xmlns="xmlns">}. Omits the xmlns attribute when {@code xmlns} is null. */
    public XmlBuilder start(String element, String xmlns) {
        sb.append('<').append(element);
        if (xmlns != null) {
            sb.append(" xmlns=\"").append(xmlns).append('"');
        }
        sb.append('>');
        return this;
    }

    /** Opens {@code <element>} without a namespace. */
    public XmlBuilder start(String element) {
        return start(element, null);
    }

    /** Appends {@code </element>}. */
    public XmlBuilder end(String element) {
        sb.append("</").append(element).append('>');
        return this;
    }

    /**
     * Appends {@code <name>escapedValue</name>}.
     * Skips the element entirely when {@code value} is {@code null}.
     */
    public XmlBuilder elem(String name, String value) {
        if (value == null) {
            return this;
        }
        sb.append('<').append(name).append('>');
        appendEscaped(sb, value, 0);
        sb.append("</").append(name).append('>');
        return this;
    }

    /** Convenience overload — converts {@code long} to string. */
    public XmlBuilder elem(String name, long value) {
        sb.append('<').append(name).append('>').append(value).append("</").append(name).append('>');
        return this;
    }

    /** Convenience overload — converts {@code boolean} to string. */
    public XmlBuilder elem(String name, boolean value) {
        sb.append('<').append(name).append('>').append(value).append("</").append(name).append('>');
        return this;
    }

    /**
     * Appends a pre-built XML fragment verbatim, without escaping.
     * The caller is responsible for correctness of the fragment.
     */
    public XmlBuilder raw(String fragment) {
        if (fragment != null) {
            sb.append(fragment);
        }
        return this;
    }

    /** Returns the accumulated XML string. */
    public String build() {
        return sb.toString();
    }

    /**
     * Escapes the five XML special characters: {@code & < > " '}, and a carriage return as
     * {@code &#13;}, which an XML parser would otherwise read back as a line feed.
     * Returns an empty string for null or empty input.
     */
    public static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (replacement(s.charAt(i)) != null) {
                StringBuilder out = new StringBuilder(len + 16);
                out.append(s, 0, i);
                appendEscaped(out, s, i);
                return out.toString();
            }
        }
        return s;
    }

    /** Appends {@code s} from {@code from} with the XML special characters replaced, in runs. */
    private static void appendEscaped(StringBuilder out, String s, int from) {
        int len = s.length();
        int runStart = from;
        for (int i = from; i < len; i++) {
            String replacement = replacement(s.charAt(i));
            if (replacement != null) {
                out.append(s, runStart, i).append(replacement);
                runStart = i + 1;
            }
        }
        out.append(s, runStart, len);
    }

    private static String replacement(char c) {
        return switch (c) {
            case '&'  -> "&amp;";
            case '<'  -> "&lt;";
            case '>'  -> "&gt;";
            case '"'  -> "&quot;";
            case '\'' -> "&apos;";
            case '\r' -> "&#13;";
            default   -> null;
        };
    }
}
