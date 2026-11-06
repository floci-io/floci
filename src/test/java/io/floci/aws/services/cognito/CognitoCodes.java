package io.floci.aws.services.cognito;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The codes Cognito's messages carry, as the tests read them out of an email or an SMS. */
final class CognitoCodes {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    private static final Pattern EIGHT_DIGITS = Pattern.compile("\\b(\\d{8})\\b");

    private CognitoCodes() {
    }

    /** A sign-up, forgotten-password, attribute verification or MFA code: six digits. */
    static String sixDigitCode(String message) {
        return code(SIX_DIGITS, message, "a six-digit code");
    }

    /** An email sign-in code, USER_AUTH's EMAIL_OTP: eight digits, as on AWS. */
    static String emailSignInCode(String message) {
        return code(EIGHT_DIGITS, message, "an eight-digit code");
    }

    private static String code(Pattern pattern, String message, String what) {
        Matcher matcher = pattern.matcher(message);
        assertTrue(matcher.find(), "the message should carry " + what + ": " + message);
        return matcher.group(1);
    }
}
