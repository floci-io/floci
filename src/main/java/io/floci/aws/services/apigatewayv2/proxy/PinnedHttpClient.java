package io.floci.aws.services.apigatewayv2.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sends one plain-HTTP request to an already checked {@link InetAddress} and reads the response
 * off the socket.
 *
 * <p>The address is supplied by the caller, which resolves the integration host once and runs it
 * through {@code SsrfProtection}. Connecting to that same address, rather than handing the name
 * back to a client that would resolve it again, is what closes the DNS-rebinding window between
 * the check and the connection.
 *
 * <p>Response framing is bounded on every path: the body stops at the API Gateway payload quota
 * whether it arrives with a Content-Length, chunked, or delimited by the connection closing, chunk
 * sizes are rejected when negative or oversized, and the CRLF that must follow each chunk is
 * verified. Both HTTP_PROXY and the WebSocket integrations go through here so those limits stay in
 * one place.
 */
public final class PinnedHttpClient {

    /** API Gateway's integration payload quota: 10 MB, not adjustable. */
    public static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int MAX_STATUS_LINE_PARTS = 3;

    /** Written by this client itself, so a caller-supplied copy would duplicate the header. */
    private static final Set<String> CLIENT_OWNED_HEADERS =
            Set.of("host", "connection", "content-length");

    /** Methods that carry no body, so an empty payload needs no Content-Length. */
    private static final Set<String> BODYLESS_METHODS =
            Set.of("GET", "HEAD", "OPTIONS", "DELETE", "TRACE");

    /** An RFC 9110 field name: a token, so it cannot carry a separator or a line break. */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private static final char HORIZONTAL_TAB = '\t';
    private static final char DELETE = 0x7F;

    private PinnedHttpClient() {
    }

    /**
     * A backend response, with the header names as the backend spelled them and repeated header
     * lines kept separate.
     */
    public record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
    }

    /** Raised once more than the payload quota has arrived, on any of the framing paths. */
    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() {
            super("integration response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
    }

    /** Raised before anything is sent when a request header would not stay a single header line. */
    public static final class InvalidRequestHeaderException extends IOException {
        public InvalidRequestHeaderException(String message) {
            super(message);
        }
    }

    /**
     * Rejects a header that would not survive as one HTTP/1.1 header line: a name that is not a
     * token, or a value carrying NUL, CR, LF or any other control character besides horizontal tab.
     * Mapped values can come from the caller's request body or query string, so writing them as
     * given would let a caller add header lines of its own or smuggle a second request. The
     * messages name the header but never echo the value.
     *
     * @param name  the header name
     * @param value the header value
     * @throws InvalidRequestHeaderException when the name or value cannot be sent
     */
    public static void requireValidHeader(String name, String value) throws InvalidRequestHeaderException {
        if (name == null || !HEADER_NAME.matcher(name).matches()) {
            throw new InvalidRequestHeaderException("invalid request header name");
        }
        if (value == null) {
            throw new InvalidRequestHeaderException("invalid request header value for " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < ' ' && c != HORIZONTAL_TAB) || c == DELETE) {
                throw new InvalidRequestHeaderException("invalid request header value for " + name);
            }
        }
    }

    /**
     * Connects to {@code address} and exchanges one request/response pair.
     *
     * @param address    the checked address to connect to, never re-resolved from the URI
     * @param target     the integration URI, read for the port, path, query and default Host
     * @param method     the HTTP method to send
     * @param hostHeader the Host header to advertise, or null to use the target's host and port
     * @param headers    extra request headers; Host, Connection and Content-Length are ignored
     *                   because this client writes them itself
     * @param body       the request body, may be null or empty
     * @param timeout    how long to wait for the backend to respond
     * @throws InvalidRequestHeaderException before connecting, when the Host header or any header
     *                                       that would be written fails {@link #requireValidHeader}
     */
    public static Response send(InetAddress address, URI target, String method, String hostHeader,
                                Map<String, List<String>> headers, byte[] body, Duration timeout)
            throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        String upperMethod = method.toUpperCase(Locale.ROOT);
        int port = target.getPort() == -1 ? DEFAULT_HTTP_PORT : target.getPort();
        if (hostHeader != null) {
            requireValidHeader("Host", hostHeader);
        }
        requireValidHeaders(headers);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout((int) Math.min(timeout.toMillis(), Integer.MAX_VALUE));

            OutputStream out = socket.getOutputStream();
            String requestHead = buildRequestHead(
                    target, upperMethod,
                    hostHeader == null ? hostAndPort(target) : hostHeader,
                    headers, payload.length);
            out.write(requestHead.getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();

            return readResponse(socket.getInputStream(), upperMethod);
        }
    }

    private static void requireValidHeaders(Map<String, List<String>> headers)
            throws InvalidRequestHeaderException {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (CLIENT_OWNED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : header.getValue()) {
                requireValidHeader(header.getKey(), value);
            }
        }
    }

    /** The authority to advertise as Host, without any {@code user:pass@} the URI carries. */
    private static String hostAndPort(URI target) {
        String host = target.getHost();
        if (host == null) {
            return target.getRawAuthority();
        }
        return target.getPort() == -1 ? host : host + ":" + target.getPort();
    }

    private static String buildRequestHead(URI target, String method, String hostHeader,
                                           Map<String, List<String>> headers, int contentLength) {
        String path = target.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (target.getRawQuery() != null) {
            path += "?" + target.getRawQuery();
        }

        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("Connection: close\r\n");
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (CLIENT_OWNED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (String value : header.getValue()) {
                    request.append(header.getKey()).append(": ").append(value).append("\r\n");
                }
            }
        }
        if (contentLength > 0 || !BODYLESS_METHODS.contains(method)) {
            request.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        return request.append("\r\n").toString();
    }

    private static Response readResponse(InputStream input, String method) throws IOException {
        String headersText = readHead(input);
        String[] lines = headersText.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("invalid HTTP response");
        }
        String[] status = lines[0].split(" ", MAX_STATUS_LINE_PARTS);
        if (status.length < 2) {
            throw new IOException("invalid HTTP status line");
        }
        int statusCode = Integer.parseInt(status[1]);

        Map<String, List<String>> headers = new LinkedHashMap<>();
        String transferEncoding = null;
        long contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = lines[i].substring(0, separator);
            String value = lines[i].substring(separator + 1).trim();
            if (name.equalsIgnoreCase("Transfer-Encoding")) {
                transferEncoding = value;
            }
            if (name.equalsIgnoreCase("Content-Length")) {
                contentLength = Long.parseLong(value);
            }
            // Repeated header lines (Set-Cookie) accumulate rather than overwrite.
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        boolean bodyless = "HEAD".equals(method) || statusCode == 204 || statusCode == 304
                || (statusCode >= 100 && statusCode < 200);
        byte[] body = bodyless
                ? new byte[0]
                : transferEncoding != null
                        && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")
                ? readChunkedBody(input)
                : contentLength >= 0 ? readContentLength(input, contentLength) : readBounded(input);
        return new Response(statusCode, headers, body);
    }

    /** Reads up to and including the blank line that ends the response head. */
    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            headBytes.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        return headBytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(input);
            if (sizeLine == null) {
                throw new IOException("unexpected end of chunked response");
            }
            int extension = sizeLine.indexOf(';');
            int size = Integer.parseInt(
                    (extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim(), 16);
            if (size == 0) {
                while (true) {
                    String trailer = readAsciiLine(input);
                    if (trailer == null || trailer.isEmpty()) {
                        return body.toByteArray();
                    }
                }
            }
            if (size < 0 || size > MAX_RESPONSE_BYTES - body.size()) {
                throw new ResponseTooLargeException();
            }
            body.write(input.readNBytes(size));
            expectCrlf(input);
        }
    }

    private static byte[] readContentLength(InputStream input, long contentLength) throws IOException {
        if (contentLength > MAX_RESPONSE_BYTES) {
            throw new ResponseTooLargeException();
        }
        return input.readNBytes((int) contentLength);
    }

    /** Reads to end of stream, failing once more than the payload quota has arrived. */
    public static byte[] readBounded(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new ResponseTooLargeException();
        }
        return body;
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b == -1) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\n') {
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\r') {
                int next = input.read();
                if (next == '\n') {
                    return line.toString(StandardCharsets.ISO_8859_1);
                }
                line.write(b);
                if (next != -1) {
                    line.write(next);
                }
                continue;
            }
            line.write(b);
        }
    }

    private static void expectCrlf(InputStream input) throws IOException {
        int cr = input.read();
        int lf = input.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("invalid chunked response");
        }
    }
}
