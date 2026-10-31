package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.SigV4aSignature;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A RestAssured filter that header-signs an S3 request with SigV4A ({@code AWS4-ECDSA-P256-SHA256}),
 * the way {@link S3RequestSigner} does for SigV4. The signature covers the method, raw path, query,
 * {@code host}, {@code x-amz-content-sha256}, {@code x-amz-date} and {@code x-amz-region-set}.
 * {@link #presignedQuery} signs the same credential into a presigned URL's query string instead.
 *
 * <p>The signing here is independent of the code under test apart from the key derivation, which
 * {@code SigV4aSignatureTest} checks against signatures produced by aws-crt.
 */
public final class S3SigV4aRequestSigner implements Filter {

    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String secretKey;
    private final String regionSet;
    private final String signatureOverride;
    private final boolean signRegionSet;

    private S3SigV4aRequestSigner(String accessKeyId, String secretKey, String regionSet,
                                  String signatureOverride, boolean signRegionSet) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.regionSet = regionSet;
        this.signatureOverride = signatureOverride;
        this.signRegionSet = signRegionSet;
    }

    public static S3SigV4aRequestSigner signedAs(String accessKeyId, String secretKey) {
        return new S3SigV4aRequestSigner(accessKeyId, secretKey, "*", null, true);
    }

    /** Signs for {@code regionSet} (comma separated, or {@code *}) instead of every region. */
    public S3SigV4aRequestSigner forRegions(String regionSet) {
        return new S3SigV4aRequestSigner(accessKeyId, secretKey, regionSet, signatureOverride, signRegionSet);
    }

    /** Sends {@code signature} in place of the computed one, everything else intact. */
    public S3SigV4aRequestSigner withSignature(String signature) {
        return new S3SigV4aRequestSigner(accessKeyId, secretKey, regionSet, signature, signRegionSet);
    }

    /** Leaves {@code x-amz-region-set} out of {@code SignedHeaders}, which no real signer does. */
    public S3SigV4aRequestSigner withoutSignedRegionSet() {
        return new S3SigV4aRequestSigner(accessKeyId, secretKey, regionSet, signatureOverride, false);
    }

    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification response, FilterContext ctx) {
        try {
            headersFor(request.getMethod(), URI.create(request.getURI()),
                    S3RequestSigner.bodyBytes(request.getBody()))
                    .forEach(request::header);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the request", e);
        }
        return ctx.next(request, response);
    }

    public Map<String, String> headersFor(String method, URI uri, byte[] body) throws IOException {
        String amzDate = AMZ_DATE.format(Instant.now());
        String scopeDate = amzDate.substring(0, 8);
        String contentSha256 = Digests.sha256Hex(body);

        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", S3RequestSigner.host(uri));
        signed.put("x-amz-content-sha256", contentSha256);
        signed.put("x-amz-date", amzDate);
        if (signRegionSet) {
            signed.put("x-amz-region-set", regionSet);
        }
        String signedHeaders = String.join(";", signed.keySet());
        String canonicalHeaders = signed.entrySet().stream()
                .map(header -> header.getKey() + ":" + header.getValue() + "\n")
                .collect(Collectors.joining());
        String canonicalRequest = method + "\n" + rawPath(uri) + "\n"
                + S3RequestSigner.canonicalQueryString(uri.getRawQuery()) + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + contentSha256;
        String signature = signature(amzDate, scopeDate, canonicalRequest);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", contentSha256);
        headers.put("X-Amz-Region-Set", regionSet);
        headers.put("Authorization", SigV4aSignature.ALGORITHM + " Credential=" + accessKeyId + "/" + scopeDate
                + "/" + SERVICE + "/aws4_request, SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return headers;
    }

    /**
     * The query string of a SigV4A presigned URL: the {@code X-Amz-*} parameters including
     * {@code X-Amz-Region-Set}, {@code host} as the only signed header, an unsigned payload and the
     * signature last. Send it with URL encoding off, since it is already encoded.
     */
    public String presignedQuery(String method, URI uri, int expiresSeconds) throws IOException {
        String amzDate = AMZ_DATE.format(Instant.now());
        String scopeDate = amzDate.substring(0, 8);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("X-Amz-Algorithm", SigV4aSignature.ALGORITHM);
        params.put("X-Amz-Credential", accessKeyId + "/" + scopeDate + "/" + SERVICE + "/aws4_request");
        params.put("X-Amz-Date", amzDate);
        params.put("X-Amz-Expires", Integer.toString(expiresSeconds));
        params.put("X-Amz-Region-Set", regionSet);
        params.put("X-Amz-SignedHeaders", "host");
        String query = params.entrySet().stream()
                .map(param -> S3RequestSigner.uriEncode(param.getKey()) + "="
                        + S3RequestSigner.uriEncode(param.getValue()))
                .collect(Collectors.joining("&"));
        String canonicalRequest = method + "\n" + rawPath(uri) + "\n"
                + S3RequestSigner.canonicalQueryString(query) + "\n"
                + "host:" + S3RequestSigner.host(uri) + "\n\nhost\nUNSIGNED-PAYLOAD";
        return query + "&X-Amz-Signature=" + signature(amzDate, scopeDate, canonicalRequest);
    }

    private String signature(String amzDate, String scopeDate, String canonicalRequest) {
        if (signatureOverride != null) {
            return signatureOverride;
        }
        String stringToSign = SigV4aSignature.ALGORITHM + "\n" + amzDate + "\n"
                + scopeDate + "/" + SERVICE + "/aws4_request\n"
                + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        return SigV4aSigning.sign(accessKeyId, secretKey, stringToSign);
    }

    private static String rawPath(URI uri) {
        return uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    }
}
