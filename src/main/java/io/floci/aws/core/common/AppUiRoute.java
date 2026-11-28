package io.floci.aws.core.common;

import java.util.Map;

/**
 * Where a request to an application UI's host goes ({@link AppUiProxy}): the interface's address; each link to one of
 * its sibling interfaces its pages hold, as their daemons write it, and as the browser reaches that interface; what
 * to do when the address does not answer, and how such a request is answered, {@code unansweredStatus} with a page
 * saying {@code unanswered}, or, where that is null, a {@code 502} saying why; else why the request is refused, with
 * its HTTP status; and the {@code headers} the request goes on with besides its own, in place of any it has of
 * those names.
 */
public record AppUiRoute(String host, int port, Map<String, String> rewrites, Runnable unreachable, int status,
                         String refusal, int unansweredStatus, String unanswered, Map<String, String> headers) {

    /** A route that adds no header of its own. */
    public AppUiRoute(String host, int port, Map<String, String> rewrites, Runnable unreachable, int status,
                      String refusal, int unansweredStatus, String unanswered) {
        this(host, port, rewrites, unreachable, status, refusal, unansweredStatus, unanswered, Map.of());
    }

    /** A route whose interface, when it does not answer, is a {@code 502}. */
    public AppUiRoute(String host, int port, Map<String, String> rewrites, Runnable unreachable, int status,
                      String refusal) {
        this(host, port, rewrites, unreachable, status, refusal, 502, null);
    }

    /** A request refused with {@code status}, its page saying {@code refusal}. */
    public static AppUiRoute refused(int status, String refusal) {
        return new AppUiRoute(null, 0, Map.of(), () -> { }, status, refusal);
    }

    /**
     * A request to the interface at {@code host} and {@code port}, whose pages link to no sibling, answered with
     * {@code unansweredStatus} and a page saying {@code unanswered} while the interface does not answer.
     */
    public static AppUiRoute to(String host, int port, int unansweredStatus, String unanswered) {
        return new AppUiRoute(host, port, Map.of(), () -> { }, 0, null, unansweredStatus, unanswered);
    }

    /** This route, its requests going on with {@code headers} too. */
    public AppUiRoute withHeaders(Map<String, String> headers) {
        return new AppUiRoute(host, port, rewrites, unreachable, status, refusal, unansweredStatus, unanswered,
                Map.copyOf(headers));
    }
}
