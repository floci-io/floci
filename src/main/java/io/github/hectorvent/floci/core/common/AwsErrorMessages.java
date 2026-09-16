package io.github.hectorvent.floci.core.common;

/**
 * Wording for errors that reach a protocol catch-all. A message-less exception (a
 * NullPointerException on the native image, where helpful NPE messages are absent) used to
 * render as "Unexpected error: null", which told the caller nothing (issue #3356).
 */
public final class AwsErrorMessages {

    private AwsErrorMessages() {}

    public static String describe(Throwable e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }
}
