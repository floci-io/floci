package io.floci.aws.services.cognito;

/**
 * Escapes text placed in Cognito HTML: managed login, the sign-up confirmation page, and the
 * HTML part of a confirmation-link email. Apostrophes are escaped too, so the rules stay the
 * same for every one of those pages.
 */
public final class CognitoHtml {

    private CognitoHtml() {
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
