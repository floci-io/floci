package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.core.common.Digests;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A RestAssured filter that header-signs an S3 request with SigV4 the way an AWS SDK would, so
 * tests can exercise {@code S3HeaderSignatureFilter} against real signatures over the request
 * actually sent rather than against a fixture produced by the code under test.
 *
 * <p>Attach with {@code given().filter(S3RequestSigner.signedAs("test", "test"))} in place of a
 * hand-written {@code Authorization} header. The signature covers the method, the raw path, the
 * query string, {@code host}, {@code x-amz-content-sha256}, {@code x-amz-date} and, when a
 * session token is given, {@code x-amz-security-token}; the content hash is the SHA-256 of the
 * body the request carries. The {@code with*} methods return a copy that deliberately deviates
 * (a stale timestamp, a forged signature, a wrong content hash) for the rejection cases.
 * {@link #presignedQuery} signs the same credential into a presigned URL's query string instead.
 */
public final class S3RequestSigner implements Filter {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /** The query parameters a presigned URL carries, lowercased: a URI holding one is already signed. */
    private static final Set<String> PRESIGN_PARAMETERS = Set.of("x-amz-algorithm", "x-amz-credential",
            "x-amz-date", "x-amz-expires", "x-amz-security-token", "x-amz-signedheaders", "x-amz-signature");

    private final String accessKeyId;
    private final String secretKey;
    private final String sessionToken;
    private final String region;
    private final Instant signedAt;
    private final String signatureOverride;
    private final String contentSha256Override;
    private final boolean signHost;

    private S3RequestSigner(String accessKeyId, String secretKey, String sessionToken, String region,
                            Instant signedAt, String signatureOverride, String contentSha256Override,
                            boolean signHost) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.region = region;
        this.signedAt = signedAt;
        this.signatureOverride = signatureOverride;
        this.contentSha256Override = contentSha256Override;
        this.signHost = signHost;
    }

    public static S3RequestSigner signedAs(String accessKeyId, String secretKey) {
        return new S3RequestSigner(accessKeyId, secretKey, null, "us-east-1", null, null, null, true);
    }

    public static S3RequestSigner signedAs(String accessKeyId, String secretKey, String sessionToken) {
        return new S3RequestSigner(accessKeyId, secretKey, sessionToken, "us-east-1", null, null, null, true);
    }

    /** Signs as of a fixed instant instead of now; used to trip the clock-skew window. */
    public S3RequestSigner signedAt(Instant instant) {
        return new S3RequestSigner(accessKeyId, secretKey, sessionToken, region, instant,
                signatureOverride, contentSha256Override, signHost);
    }

    /** Sends {@code signature} in place of the computed one, everything else intact. */
    public S3RequestSigner withSignature(String signature) {
        return new S3RequestSigner(accessKeyId, secretKey, sessionToken, region, signedAt,
                signature, contentSha256Override, signHost);
    }

    /**
     * Declares and signs {@code contentSha256} regardless of the body actually sent, so the
     * signature verifies but the payload does not match its declared hash.
     */
    public S3RequestSigner withContentSha256(String contentSha256) {
        return new S3RequestSigner(accessKeyId, secretKey, sessionToken, region, signedAt,
                signatureOverride, contentSha256, signHost);
    }

    /** Signs with {@code region} in the credential scope instead of {@code us-east-1}. */
    public S3RequestSigner inRegion(String region) {
        return new S3RequestSigner(accessKeyId, secretKey, sessionToken, region, signedAt,
                signatureOverride, contentSha256Override, signHost);
    }

    /** Leaves {@code host} out of {@code SignedHeaders}; no real signer does this. */
    public S3RequestSigner withoutSignedHost() {
        return new S3RequestSigner(accessKeyId, secretKey, sessionToken, region, signedAt,
                signatureOverride, contentSha256Override, false);
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
        headersFor(request.getMethod(), URI.create(request.getURI()), bodyBytes(request.getBody()))
                .forEach(request::header);
    }

    /**
     * The headers a signed request must carry ({@code Authorization}, {@code x-amz-date},
     * {@code x-amz-content-sha256} and, with a session token, {@code X-Amz-Security-Token}), for
     * tests that send with a client other than RestAssured.
     */
    public Map<String, String> headersFor(String method, URI uri, byte[] body) throws Exception {
        Instant at = signedAt != null ? signedAt : Instant.now();
        String amzDate = AMZ_DATE.format(at);
        String scopeDate = amzDate.substring(0, 8);
        String contentSha256 = contentSha256Override != null
                ? contentSha256Override
                : Digests.sha256Hex(body);

        List<String[]> headers = new ArrayList<>();
        if (signHost) {
            headers.add(new String[]{"host", host(uri)});
        }
        headers.add(new String[]{"x-amz-content-sha256", contentSha256});
        headers.add(new String[]{"x-amz-date", amzDate});
        if (sessionToken != null) {
            headers.add(new String[]{"x-amz-security-token", sessionToken});
        }
        String signedHeaders = headers.stream().map(h -> h[0]).collect(Collectors.joining(";"));
        String canonicalHeaders = headers.stream()
                .map(h -> h[0] + ":" + h[1] + "\n")
                .collect(Collectors.joining());

        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String canonicalRequest = method + "\n"
                + rawPath + "\n"
                + canonicalQueryString(uri.getRawQuery()) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + contentSha256;
        String credentialScope = scopeDate + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = signatureOverride != null
                ? signatureOverride
                : hexEncode(hmacSha256(signingKey(scopeDate), stringToSign));

        Map<String, String> result = new LinkedHashMap<>();
        result.put("x-amz-date", amzDate);
        result.put("x-amz-content-sha256", contentSha256);
        if (sessionToken != null) {
            result.put("X-Amz-Security-Token", sessionToken);
        }
        result.put("Authorization", ALGORITHM
                + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature);
        return result;
    }

    /**
     * The query string of a presigned URL for {@code method} on {@code uri}, written the way an AWS
     * SDK presigner writes it: the {@code X-Amz-*} parameters, {@code X-Amz-Security-Token} when a
     * session token is given, {@code host} as the only signed header, an unsigned payload, and the
     * signature last. Parameters already in {@code uri}'s query, such as an upload id, are signed too
     * and lead the result, so send it in place of that query. A {@code uri} that is already presigned
     * is refused, since signing over its signature would give a URL no verifier accepts. Send the
     * result with URL encoding off, since it is already encoded.
     */
    public String presignedQuery(String method, URI uri, int expiresSeconds) throws Exception {
        String uriQuery = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        for (String pair : uriQuery.split("&")) {
            String name = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
            if (PRESIGN_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("already presigned, it carries " + name + ": " + uri);
            }
        }
        Instant at = signedAt != null ? signedAt : Instant.now();
        String amzDate = AMZ_DATE.format(at);
        String scopeDate = amzDate.substring(0, 8);
        String credentialScope = scopeDate + "/" + region + "/" + SERVICE + "/aws4_request";

        Map<String, String> params = new LinkedHashMap<>();
        params.put("X-Amz-Algorithm", ALGORITHM);
        params.put("X-Amz-Credential", accessKeyId + "/" + credentialScope);
        params.put("X-Amz-Date", amzDate);
        params.put("X-Amz-Expires", Integer.toString(expiresSeconds));
        if (sessionToken != null) {
            params.put("X-Amz-Security-Token", sessionToken);
        }
        params.put("X-Amz-SignedHeaders", "host");
        String query = params.entrySet().stream()
                .map(param -> uriEncode(param.getKey()) + "=" + uriEncode(param.getValue()))
                .collect(Collectors.joining("&"));
        if (!uriQuery.isEmpty()) {
            query = uriQuery + "&" + query;
        }

        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String canonicalRequest = method + "\n"
                + rawPath + "\n"
                + canonicalQueryString(query) + "\n"
                + "host:" + host(uri) + "\n"
                + "\n"
                + "host" + "\n"
                + "UNSIGNED-PAYLOAD";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = signatureOverride != null
                ? signatureOverride
                : hexEncode(hmacSha256(signingKey(scopeDate), stringToSign));
        return query + "&X-Amz-Signature=" + signature;
    }

    private static String host(URI uri) {
        return uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443
                ? uri.getHost() + ":" + uri.getPort()
                : uri.getHost();
    }

    private byte[] signingKey(String scopeDate) throws Exception {
        byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), scopeDate);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, SERVICE);
        return hmacSha256(kService, "aws4_request");
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

    static String canonicalQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals >= 0 ? pair.substring(0, equals) : pair, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(equals >= 0 ? pair.substring(equals + 1) : "", StandardCharsets.UTF_8);
            pairs.add(new String[]{uriEncode(name), uriEncode(value)});
        }
        pairs.sort(Comparator.comparing((String[] p) -> p[0]).thenComparing(p -> p[1]));
        return pairs.stream().map(p -> p[0] + "=" + p[1]).collect(Collectors.joining("&"));
    }

    static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
