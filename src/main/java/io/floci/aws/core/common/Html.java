package io.floci.aws.core.common;

import java.util.List;

/** Text put into an HTML page Floci serves, escaped so that it is shown as it is and never read as markup. */
public final class Html {

    private Html() {
    }

    /**
     * {@code value} with each character HTML reads as markup, in an element's text or in an attribute quoted either
     * way, written as its entity.
     */
    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** A plain page titled {@code title}, the title escaped and repeated as its heading, its body's lines after it. */
    public static String page(String title, List<String> body) {
        return "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + escape(title)
                + "</title></head>\n<body>\n<h1>" + escape(title) + "</h1>\n"
                + String.join("\n", body) + "\n</body></html>\n";
    }

    /** Adds a table row of {@code name} and {@code value}, both escaped; none when there is no value. */
    public static void row(List<String> lines, String name, String value) {
        if (value != null && !value.isEmpty()) {
            lines.add("<tr><th>" + escape(name) + "</th><td>" + escape(value) + "</td></tr>");
        }
    }
}
