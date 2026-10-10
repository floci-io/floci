package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A RestAssured filter that header-signs a request for a service other than S3 the way an AWS SDK
 * does: each segment of the wire path URI-encoded a second time, no {@code x-amz-content-sha256}
 * header, and the body's SHA-256 as the payload hash. Signs {@code host}, {@code x-amz-date} and,
 * when present, {@code x-amz-security-token} and {@code x-amz-target}. {@code Content-Type} is
 * signed only on request ({@link #signingContentType()}), because RestAssured appends a charset to
 * text types after the filter runs.
 *
 * <p>Attach with {@code given().filter(AwsRequestSigner.signedAs("test", "test", "sqs"))}. The
 * {@code with*} methods return a copy that deliberately deviates for the rejection cases.
 */
public final class AwsRequestSigner implements Filter {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String secretKey;
    private final String sessionToken;
    private final String service;
    private final Instant signedAt;
    private final boolean signContentType;

    private AwsRequestSigner(String accessKeyId, String secretKey, String sessionToken, String service,
                             Instant signedAt, boolean signContentType) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.service = service;
        this.signedAt = signedAt;
        this.signContentType = signContentType;
    }

    public static AwsRequestSigner signedAs(String accessKeyId, String secretKey, String service) {
        return new AwsRequestSigner(accessKeyId, secretKey, null, service, null, false);
    }

    /** Signs as of a fixed instant instead of now; used to trip the clock-skew window. */
    public AwsRequestSigner signedAt(Instant instant) {
        return new AwsRequestSigner(accessKeyId, secretKey, sessionToken, service, instant, signContentType);
    }

    /** Signs with the same key but a different secret, as a client with a stale secret would. */
    public AwsRequestSigner withSecret(String otherSecret) {
        return new AwsRequestSigner(accessKeyId, otherSecret, sessionToken, service, signedAt, signContentType);
    }

    /** Also signs {@code content-type}, as the Java SDK and botocore do; for binary bodies only. */
    public AwsRequestSigner signingContentType() {
        return new AwsRequestSigner(accessKeyId, secretKey, sessionToken, service, signedAt, true);
    }

    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification response, FilterContext ctx) {
        try {
            sign(request);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the request", e);
        }
        return ctx.next(request, response);
    }

    private void sign(FilterableRequestSpecification request) throws Exception {
        Map<String, String> signed = new LinkedHashMap<>();
        if (signContentType) {
            signed.put("content-type", request.getContentType());
        }
        String target = request.getHeaders().getValue("X-Amz-Target");
        if (target != null) {
            signed.put("x-amz-target", target);
        }
        headersFor(request.getMethod(), URI.create(request.getURI()), signed, bodyBytes(request.getBody()))
                .forEach(request::header);
    }

    /**
     * The headers a signed request must carry ({@code X-Amz-Date}, {@code Authorization} and, with a
     * session token, {@code X-Amz-Security-Token}), for tests that send with a client other than
     * RestAssured. {@code host}, {@code x-amz-date} and the token are always signed; {@code extra}
     * names any further headers to sign, by lowercase name, with the values the request will carry.
     */
    public Map<String, String> headersFor(String method, URI uri, Map<String, String> extra, byte[] body)
            throws Exception {
        String amzDate = AMZ_DATE.format(signedAt != null ? signedAt : Instant.now());
        String scopeDate = amzDate.substring(0, 8);
        String host = uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443
                ? uri.getHost() + ":" + uri.getPort()
                : uri.getHost();

        TreeMap<String, String> headers = new TreeMap<>(extra);
        headers.put("host", host);
        headers.put("x-amz-date", amzDate);
        if (sessionToken != null) {
            headers.put("x-amz-security-token", sessionToken);
        }
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalHeaders = headers.entrySet().stream()
                .map(h -> h.getKey() + ":" + h.getValue() + "\n")
                .collect(Collectors.joining());

        String canonicalRequest = method + "\n"
                + doubleEncodedPath(uri.getRawPath()) + "\n"
                + S3RequestSigner.canonicalQueryString(uri.getRawQuery()) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + SigV4RequestValidator.sha256Hex(body);
        String credentialScope = scopeDate + "/us-east-1/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + credentialScope + "\n"
                + SigV4RequestValidator.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secretKey, scopeDate, "us-east-1", service);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, stringToSign));

        Map<String, String> result = new LinkedHashMap<>();
        result.put("X-Amz-Date", amzDate);
        if (sessionToken != null) {
            result.put("X-Amz-Security-Token", sessionToken);
        }
        result.put("Authorization", ALGORITHM + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return result;
    }

    private static String doubleEncodedPath(String rawPath) {
        String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        String[] segments = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(S3RequestSigner.uriEncode(segments[i]));
        }
        return encoded.toString();
    }

    private static byte[] bodyBytes(Object body) {
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("unsupported body type " + body.getClass().getName());
    }
}
