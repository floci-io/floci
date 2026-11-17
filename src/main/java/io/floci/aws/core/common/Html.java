package io.floci.aws.core.common;

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
}
