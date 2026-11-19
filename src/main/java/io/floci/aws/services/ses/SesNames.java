package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;

import java.util.regex.Pattern;

/**
 * The rule SES applies to the names of templates, configuration sets, event destinations and
 * tenants: at most {@value #MAX_LENGTH} characters, all of them ASCII letters, digits, '_' or '-'.
 * The wording, the error code and the order of the two checks differ per name, so each caller
 * passes its own and keeps its own blank check.
 */
final class SesNames {

    static final int MAX_LENGTH = 64;

    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9_-]+");

    private SesNames() {
    }

    static boolean hasOnlyAllowedCharacters(String name) {
        return ALLOWED_CHARACTERS.matcher(name).matches();
    }

    /** Refuses a name over the limit with {@code "<subject> cannot exceed 64 characters."}. */
    static void requireMaxLength(String name, String errorCode, String subject) {
        if (name.length() > MAX_LENGTH) {
            throw new AwsException(errorCode, subject + " cannot exceed " + MAX_LENGTH + " characters.", 400);
        }
    }

    /** Refuses a name with any other character, quoting it after {@code subject}. */
    static void requireAllowedCharacters(String name, String errorCode, String subject) {
        if (!hasOnlyAllowedCharacters(name)) {
            throw new AwsException(errorCode, subject + " <" + name
                    + ">: only alphanumeric ASCII characters, '_', and '-' are allowed.", 400);
        }
    }
}
