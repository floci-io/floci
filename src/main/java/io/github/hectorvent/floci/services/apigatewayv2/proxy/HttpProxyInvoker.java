package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Invokes an HTTP_PROXY integration: builds the target URL from the integration's
 * IntegrationUri (with {placeholder} substitution from path params), seeds an
 * outgoing request with the inbound headers + query, applies RequestParameters
 * transformations, and forwards the call to the backend via java.net.http.HttpClient.
 *
 * <p>Hop-by-hop headers (per RFC 7230 §6.1) are stripped from both the outgoing
 * request and the response. Java's HttpClient also restricts certain headers
 * (Content-Length, etc.) — those are skipped silently.
 *
 * <p>If the backend is unreachable or times out, returns a 502 Bad Gateway
 * ProxyResult so the controller can relay a clean error to the original client.
 */
public class HttpProxyInvoker {
    private static final Logger LOG = Logger.getLogger(HttpProxyInvoker.class);

    /** RFC 7230 hop-by-hop headers that must not be forwarded across proxies. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "content-length", "host");

    /** Headers java.net.http.HttpClient refuses to set via Builder.header(). */
    private static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    /**
     * Per-integration transport settings.
     *
     * @param timeout     how long to wait for the backend response
     * @param insecureTls stop requiring the backend certificate to be issued by a trusted
     *                    certificate authority, for an integration configured with
     *                    {@code tlsConfig.insecureSkipVerification}. Expiration, hostname and the
     *                    presence of a root certificate authority are still checked, as in AWS.
     */
    public record ProxyOptions(Duration timeout, boolean insecureTls) {
        /** HTTP API (v2) defaults: 30s, certificates verified. */
        public static final ProxyOptions DEFAULTS = new ProxyOptions(Duration.ofSeconds(30), false);
    }

    // Pin to HTTP/1.1: the default HTTP_2 setting attempts cleartext-HTTP/2 negotiation
    // against http:// backends, which hangs against plain HTTP/1.1 servers (notably the
    // in-JVM Vertx HttpServer used by ELBv2 listeners for HttpAlbIntegration).
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Built on first use: an integration opting out of TLS verification is the exception. */
    private volatile HttpClient insecureClient;

    private HttpClient clientFor(ProxyOptions options) {
        if (!options.insecureTls()) return client;
        HttpClient existing = insecureClient;
        if (existing != null) return existing;
        synchronized (this) {
            if (insecureClient == null) {
                insecureClient = buildInsecureClient();
            }
            return insecureClient;
        }
    }

    private HttpClient buildInsecureClient() {
        try {
            TrustManager[] trustManagers = {new CaIssuanceSkippingTrustManager()};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());
            // Hostname verification stays on. insecureSkipVerification waives only the "issued by a
            // supported CA" check; AWS documents that it still verifies the hostname, so a
            // certificate for the wrong host must fail here exactly as it would in AWS. Setting this
            // explicitly matters: SSLParameters defaults the algorithm to null, so handing
            // HttpClient a fresh instance without it would silently disable the check.
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .build();
        } catch (GeneralSecurityException e) {
            LOG.warnv("Could not build insecure TLS client, falling back to verified: {0}", e.getMessage());
            return client;
        }
    }

    /**
     * The trust behaviour {@code tlsConfig.insecureSkipVerification} actually buys in AWS: API
     * Gateway stops checking that the endpoint's certificate was issued by a supported certificate
     * authority, so private-CA and self-signed certificates are accepted, but it still performs
     * basic certificate validation covering the expiration date, the hostname and the presence of a
     * root certificate authority. A trust-all manager is looser than that, and looser in the
     * direction that hides bugs: a backend whose certificate has expired, or whose chain is broken,
     * would work locally and fail in AWS.
     *
     * <p>For anything beyond a lone self-signed certificate the chain goes through the platform's
     * PKIX validator, anchored on the chain's own root, so the rules AWS keeps for a private
     * certificate authority (cA=true together with keyUsage keyCertSign, pathLenConstraint, and
     * name constraints on the intermediates) are enforced by the code that already implements them
     * correctly. The root's own constraints are checked here instead, because PKIX treats a trust
     * anchor as given and never reads its extensions.
     *
     * <p>Hostname verification is not done here, it is the SSL engine's endpoint identification.
     */
    private static final class CaIssuanceSkippingTrustManager implements X509TrustManager {

        /** X509v3 Name Constraints, RFC 5280 §4.2.1.10. */
        private static final String NAME_CONSTRAINTS_OID = "2.5.29.30";


        /** Position of {@code keyCertSign} in the KeyUsage bit string, RFC 5280 §4.2.1.3. */
        private static final int KEY_CERT_SIGN_BIT = 5;

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // This manager only ever verifies a backend as a client would; it never authenticates
            // an inbound peer. Accepting one silently would be a genuine trust-all.
            throw new CertificateException("client certificates are not accepted by this proxy");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("backend presented no certificate");
            }

            // Expiration (and not-yet-valid), for every certificate in the chain. PKIX exempts a
            // trust anchor from this, and AWS does not, so it is checked here rather than left to
            // the validator below.
            for (X509Certificate certificate : chain) {
                certificate.checkValidity();
            }

            // The chain has to terminate in a self-signed certificate: that trailing certificate is
            // the "root certificate authority" whose presence AWS still requires. What is skipped is
            // only whether that root is one we trust.
            X509Certificate root = chain[chain.length - 1];
            if (!root.getIssuerX500Principal().equals(root.getSubjectX500Principal())) {
                throw new CertificateException(
                        "certificate chain does not terminate in a root certificate authority: "
                                + root.getSubjectX500Principal());
            }

            if (chain.length == 1) {
                // A lone self-signed certificate is its own root, and is the plain
                // self-signed-server case the setting exists to enable, so CA extensions are not
                // demanded of it: requiring them would reject what AWS documents as supported.
                // The signature still has to verify, so the certificate cannot have been tampered
                // with in flight.
                verifySignedBy(root, root);
                return;
            }

            // A longer chain means a real certificate authority issued the leaf, and AWS states two
            // constraints such a root "must satisfy": the x509 extension keyUsage must have
            // keyCertSign, and the x509 extension basicConstraints must have CA:TRUE. These are
            // checked here rather than left to the validator below, which treats the trust anchor
            // as given and never looks at its extensions.
            //
            // A root carrying no keyUsage extension at all fails the first of those. Generic PKIX
            // would read the missing extension as leaving the key unrestricted (RFC 5280 §4.2.1.3),
            // but API Gateway states the extension as a requirement on the root rather than a bit
            // to inspect when present, so the looser reading would accept a certificate authority
            // that real API Gateway turns away.
            if (root.getBasicConstraints() < 0) {
                throw new CertificateException(
                        "root certificate is not a certificate authority (BasicConstraints cA=false): "
                                + root.getSubjectX500Principal());
            }
            boolean[] keyUsage = root.getKeyUsage();
            if (keyUsage == null) {
                throw new CertificateException(
                        "root certificate carries no keyUsage extension, which API Gateway requires "
                                + "on a private certificate authority: " + root.getSubjectX500Principal());
            }
            if (keyUsage.length <= KEY_CERT_SIGN_BIT || !keyUsage[KEY_CERT_SIGN_BIT]) {
                throw new CertificateException(
                        "root certificate is not permitted to sign certificates "
                                + "(keyUsage without keyCertSign): " + root.getSubjectX500Principal());
            }

            // Name constraints on the root itself cannot be honoured: the platform validator refuses
            // to process constraints handed to it with a trust anchor, as opposed to ones it reads
            // from a certificate inside the path, so a root carrying them can be rejected but never
            // evaluated. Rejecting is both the safe direction and the documented one: AWS tells
            // operators hitting validation errors under insecureSkipVerification to check that their
            // CA certificates carry no Name Constraints extension and to reissue them without it.
            // Constraints on an intermediate sit in the path proper and are enforced normally.
            if (root.getExtensionValue(NAME_CONSTRAINTS_OID) != null) {
                throw new CertificateException(
                        "root certificate carries X509v3 Name Constraints, which cannot be enforced "
                                + "for a privately trusted root: " + root.getSubjectX500Principal());
            }

            // The rest of the rules AWS keeps are the ordinary PKIX ones. Handing them to the
            // platform validator, with this chain's own root as the trust anchor, is precisely the
            // "stop checking that the root is one we trust, keep checking everything else"
            // semantic: chain signatures, basicConstraints and keyCertSign on any intermediates,
            // pathLenConstraint, and the X509v3 name constraints AWS documents it enforces.
            try {
                CertPath path = CertificateFactory.getInstance("X.509")
                        .generateCertPath(List.of(chain).subList(0, chain.length - 1));
                PKIXParameters parameters = new PKIXParameters(
                        Set.of(new TrustAnchor(root, null)));
                // Revocation would mean CRL or OCSP fetches for an issuer we are deliberately not
                // trusting: AWS lists expiration, hostname and the presence of a root as what
                // survives insecureSkipVerification, not revocation.
                parameters.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX").validate(path, parameters);
            } catch (GeneralSecurityException e) {
                throw new CertificateException("certificate chain is not valid: " + e.getMessage(), e);
            }
        }

        private static void verifySignedBy(X509Certificate certificate, X509Certificate issuer)
                throws CertificateException {
            try {
                certificate.verify(issuer.getPublicKey());
            } catch (GeneralSecurityException e) {
                throw new CertificateException("certificate chain signature does not verify: "
                        + e.getMessage(), e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private final RequestParameterMapper mapper = new RequestParameterMapper(new ContextValueResolver());

    public ProxyResult invoke(Integration integration, RequestContext ctx) {
        return invoke(integration, ctx, ProxyOptions.DEFAULTS);
    }

    public ProxyResult invoke(Integration integration, RequestContext ctx, ProxyOptions options) {
        // 1. Resolve target URL from IntegrationUri template + captured path params
        String resolvedUrl = PathTemplateResolver.resolve(integration.getIntegrationUri(), ctx.pathParams());

        // 2. Determine HTTP method (integration.method=ANY/null means use the inbound method)
        String method = integration.getIntegrationMethod();
        if (method == null || method.isEmpty() || method.equalsIgnoreCase("ANY")) {
            method = ctx.httpMethod();
        }

        // 3. Build mutable request, seed with inbound headers/query (excluding hop-by-hop).
        // Seeded from the multi-valued view so repeated inbound headers and query parameters
        // reach the backend repeated rather than comma-joined.
        ProxyRequestBuilder builder = new ProxyRequestBuilder(resolvedUrl, method);
        if (ctx.multiValueHeaders() != null) {
            for (Map.Entry<String, List<String>> e : ctx.multiValueHeaders().entrySet()) {
                if (!HOP_BY_HOP.contains(e.getKey().toLowerCase())) {
                    builder.overwriteHeader(e.getKey(), e.getValue());
                }
            }
        }
        if (ctx.multiValueQueryParams() != null) {
            for (Map.Entry<String, List<String>> e : ctx.multiValueQueryParams().entrySet()) {
                builder.overwriteQuery(e.getKey(), e.getValue());
            }
        }
        builder.setBody(ctx.body());

        // 4. Apply RequestParameters
        mapper.apply(integration.getRequestParameters(), builder, ctx);

        // 5. Build java.net.http.HttpRequest
        String finalUrl = buildFinalUrl(builder);
        if (hasHeader(builder, "Host") && finalUrl.startsWith("http://")) {
            try {
                return invokeHttpWithHostOverride(finalUrl, method, builder, options.timeout());
            } catch (Exception e) {
                LOG.warnv("HTTP_PROXY backend call failed: {0}", e.getMessage());
                return errorResult("Bad Gateway: " + e.getMessage());
            }
        }

        HttpRequest.Builder hrb;
        try {
            hrb = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .timeout(options.timeout());
        } catch (IllegalArgumentException e) {
            LOG.warnv("HTTP_PROXY: invalid target URL: {0}", e.getMessage());
            return errorResult("Bad Gateway: invalid target URL: " + e.getMessage());
        }

        switch (method.toUpperCase()) {
            case "GET" -> hrb.GET();
            case "DELETE" -> hrb.DELETE();
            case "HEAD" -> hrb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "OPTIONS" -> hrb.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            default -> hrb.method(method.toUpperCase(),
                    builder.body() != null
                            ? HttpRequest.BodyPublishers.ofByteArray(builder.body())
                            : HttpRequest.BodyPublishers.noBody());
        }

        for (Map.Entry<String, List<String>> e : builder.headers().entrySet()) {
            if (RESTRICTED.contains(e.getKey().toLowerCase())) continue;
            for (String v : e.getValue()) {
                hrb.header(e.getKey(), v);
            }
        }

        try {
            HttpResponse<byte[]> resp =
                    clientFor(options).send(hrb.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, List<String>> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : resp.headers().map().entrySet()) {
                if (HOP_BY_HOP.contains(e.getKey().toLowerCase())) continue;
                respHeaders.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return new ProxyResult(resp.statusCode(), respHeaders, resp.body());
        } catch (Exception e) {
            LOG.warnv("HTTP_PROXY backend call failed: {0}", e.getMessage());
            return errorResult("Bad Gateway: " + e.getMessage());
        }
    }

    private static boolean hasHeader(ProxyRequestBuilder builder, String headerName) {
        return builder.headers().keySet().stream().anyMatch(headerName::equalsIgnoreCase);
    }

    private static String firstHeader(ProxyRequestBuilder builder, String headerName) {
        for (Map.Entry<String, List<String>> entry : builder.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static ProxyResult invokeHttpWithHostOverride(String finalUrl, String method,
                                                          ProxyRequestBuilder builder, Duration timeout)
            throws IOException {
        URI uri = URI.create(finalUrl);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 10_000);
            socket.setSoTimeout((int) Math.min(timeout.toMillis(), Integer.MAX_VALUE));

            OutputStream out = socket.getOutputStream();
            byte[] body = builder.body() == null ? new byte[0] : builder.body();
            StringBuilder request = new StringBuilder()
                    .append(method.toUpperCase(Locale.ROOT)).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(firstHeader(builder, "Host")).append("\r\n")
                    .append("Connection: close\r\n");
            for (Map.Entry<String, List<String>> header : builder.headers().entrySet()) {
                String name = header.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (RESTRICTED.contains(lower) || lower.equals("connection")) {
                    continue;
                }
                for (String value : header.getValue()) {
                    request.append(name).append(": ").append(value).append("\r\n");
                }
            }
            if (body.length > 0) {
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();

            return readRawHttpResponse(socket.getInputStream());
        }
    }

    private static ProxyResult readRawHttpResponse(InputStream input) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            headerBytes.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }

        String headersText = headerBytes.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headersText.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("invalid HTTP response");
        }
        String[] status = lines[0].split(" ", 3);
        int statusCode = Integer.parseInt(status[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String transferEncoding = null;
        int contentLength = -1;
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
                contentLength = Integer.parseInt(value);
            }
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                // Repeated header lines (Set-Cookie) accumulate rather than overwrite.
                headers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
            }
        }
        byte[] body = transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")
                ? readChunkedBody(input)
                : contentLength >= 0 ? input.readNBytes(contentLength) : input.readAllBytes();
        return new ProxyResult(statusCode, headers, body);
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(input);
            if (sizeLine == null) {
                throw new IOException("unexpected end of chunked response");
            }
            int extension = sizeLine.indexOf(';');
            int size = Integer.parseInt((extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim(), 16);
            if (size == 0) {
                while (true) {
                    String trailer = readAsciiLine(input);
                    if (trailer == null || trailer.isEmpty()) {
                        return body.toByteArray();
                    }
                }
            }
            body.write(input.readNBytes(size));
            expectCrlf(input);
        }
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

    private static String buildFinalUrl(ProxyRequestBuilder builder) {
        if (builder.queryParams().isEmpty()) return builder.url();
        URI parsed = URI.create(builder.url());
        StringJoiner sj = new StringJoiner("&");
        if (parsed.getRawQuery() != null) sj.add(parsed.getRawQuery());
        for (Map.Entry<String, List<String>> e : builder.queryParams().entrySet()) {
            for (String v : e.getValue()) {
                sj.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                       URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        String base = builder.url().split("\\?")[0];
        return base + "?" + sj;
    }

    private static ProxyResult errorResult(String message) {
        String body = "{\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
        return ProxyResult.withSingleValueHeaders(502,
                Map.of("Content-Type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
