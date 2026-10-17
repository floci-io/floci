package io.github.hectorvent.floci.services.lambdaweb;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The HTML pages a Web Function endpoint answers with, as recorded against AWS. */
final class LambdaWebErrorPages {

    record Page(int status, String reason, String errorType, String message) {
    }

    private static final String APPLICATION_ERROR = "Request failed due to an application error.";

    static final Page UNSUPPORTED_HTTP_VERSION = new Page(505, "HTTP Version Not Supported",
            "Platform.UnsupportedHttpVersion", "The provided HTTP version is not supported. Please use HTTP/1.1.");
    static final Page INVALID_ENDPOINT = new Page(404, "Not Found", "Platform.InvalidEndpoint",
            "The requested endpoint could not be found.");
    static final Page ENDPOINT_DISABLED = new Page(503, "Service Unavailable", "Platform.EndpointDisabled",
            "The service is temporarily unable to handle your request. Please try again later.");
    static final Page REQUEST_TIMEOUT = new Page(504, "Gateway Timeout", "Runtime.RequestTimeout",
            "The server failed to respond within the required time limit.");
    static final Page ORIGIN_REQUEST_ERROR = new Page(503, "Service Unavailable", "Runtime.OriginRequestError",
            APPLICATION_ERROR);
    static final Page TIMEOUT_LISTEN_ERROR = new Page(502, "Bad Gateway", "Application.TimeoutListenError",
            APPLICATION_ERROR);
    static final Page INIT_CODE_ERROR = new Page(502, "Bad Gateway", "Application.InitCodeError", APPLICATION_ERROR);
    static final Page RUNTIME_EXIT = new Page(502, "Bad Gateway", "Runtime.Exit", APPLICATION_ERROR);
    private static final String RATE_EXCEEDED = "Request rate exceeded. Please try again later.";
    static final Page ENDPOINT_MAX_ENV_THROTTLE = new Page(429, "Too Many Requests",
            "Platform.EndpointMaxEnvThrottle", RATE_EXCEEDED);
    static final Page ENDPOINT_REQUEST_THROTTLE = new Page(429, "Too Many Requests",
            "Platform.EndpointRequestThrottle", RATE_EXCEEDED);

    /** The edge in front of the platform refuses a method itself, with its own page and header casing. */
    static final String MALFORMED_REQUEST_TYPE = "Platform.MalformedRequest";
    static final String MALFORMED_REQUEST_HTML = "<!DOCTYPE html>\n<html>\n<head>\n<title>HTTP Error 400</title>\n"
            + "</head>\n<body>\n<h1>400 Bad Request</h1>\n"
            + "<p>The request could not be parsed as a valid HTTP request.</p>\n</body>\n</html>\n";
    /** The edge writes a two-digit year in its Date header. */
    private static final DateTimeFormatter EDGE_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yy HH:mm:ss 'GMT'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private LambdaWebErrorPages() {
    }

    static String html(Page page, String requestId) {
        return "<!DOCTYPE html>\n<html>\n<head>\n    <title>HTTP Error " + page.status() + "</title>\n</head>\n<body>\n"
                + "    <h1>" + page.status() + " " + page.reason() + "</h1>\n"
                + "    <p>" + page.message() + "</p>\n"
                + "    <p>Request ID: " + requestId + "</p>\n</body>\n</html>";
    }

    static String edgeDate(Instant now) {
        return EDGE_DATE.format(now);
    }
}
