package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.SigV4aSignature;
import io.github.hectorvent.floci.testutil.SigV4aSigning;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Signs a request to a Lambda Web endpoint with the AWS SDK signer, as a client would. */
final class LambdaWebRequestSigner {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** What goes on the wire: the method, the path with its query, and the headers to add. */
    record Signed(String method, String pathAndQuery, Map<String, String> headers) {

        Signed withPathAndQuery(String other) {
            return new Signed(method, other, headers);
        }

        Signed withHeader(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(headers);
            if (value == null) {
                copy.remove(name);
            } else {
                copy.put(name, value);
            }
            return new Signed(method, pathAndQuery, copy);
        }
    }

    private String accessKeyId = "test";
    private String secretKey = "test";
    private String sessionToken;
    private String region = "us-east-1";
    private String service = "lambda";
    private Instant signedAt = Instant.now();
    private boolean presigned;
    private boolean normalizePath = true;
    private String contentSha256 = "UNSIGNED-PAYLOAD";
    private String regionSet;

    LambdaWebRequestSigner credentials(String accessKeyId, String secretKey, String sessionToken) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        return this;
    }

    LambdaWebRequestSigner region(String region) {
        this.region = region;
        return this;
    }

    LambdaWebRequestSigner service(String service) {
        this.service = service;
        return this;
    }

    LambdaWebRequestSigner signedAt(Instant signedAt) {
        this.signedAt = signedAt;
        return this;
    }

    LambdaWebRequestSigner presigned() {
        this.presigned = true;
        return this;
    }

    /** Signs the path as it is sent, without resolving its dot segments. */
    LambdaWebRequestSigner rawPath() {
        this.normalizePath = false;
        return this;
    }

    /** The {@code X-Amz-Content-SHA256} value to send and sign, or null to send none. */
    LambdaWebRequestSigner contentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
        return this;
    }

    /** Signs with SigV4a for {@code regionSet} instead of SigV4 for the region. */
    LambdaWebRequestSigner sigV4a(String regionSet) {
        this.regionSet = regionSet;
        return this;
    }

    Signed sign(String method, String host, String pathAndQuery) {
        if (regionSet != null) {
            return signV4a(method, host, pathAndQuery);
        }
        URI uri = URI.create("http://" + host + pathAndQuery);
        SdkHttpFullRequest.Builder builder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(uri);
        if (contentSha256 != null) {
            builder.putHeader("X-Amz-Content-SHA256", contentSha256);
        }
        AwsCredentialsIdentity identity = sessionToken == null
                ? AwsCredentialsIdentity.create(accessKeyId, secretKey)
                : AwsSessionCredentialsIdentity.create(accessKeyId, secretKey, sessionToken);
        SdkHttpRequest signed = AwsV4HttpSigner.create().sign(request -> request
                .identity(identity)
                .request(builder.build())
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, normalizePath)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, presigned ? AuthLocation.QUERY_STRING : AuthLocation.HEADER)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, presigned ? Duration.ofMinutes(5) : null)
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC))).request();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : signed.headers().entrySet()) {
            if (!"Host".equalsIgnoreCase(header.getKey())) {
                headers.put(header.getKey(), String.join(",", header.getValue()));
            }
        }
        if (contentSha256 != null) {
            headers.put("X-Amz-Content-SHA256", contentSha256);
        }
        String query = signed.encodedQueryParameters().map(encoded -> "?" + encoded).orElse("");
        return new Signed(method, signed.encodedPath() + query, headers);
    }

    /**
     * A SigV4a signature built by hand, since the SDK signs SigV4a only through aws-crt. It takes a
     * path without dot segments or escapes and a query of plain names and values.
     */
    private Signed signV4a(String method, String host, String pathAndQuery) {
        URI uri = URI.create("http://" + host + pathAndQuery);
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String amzDate = AMZ_DATE.format(signedAt);
        String scope = amzDate.substring(0, 8) + "/" + service + "/aws4_request";
        Map<String, String> query = new TreeMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int equals = pair.indexOf('=');
                query.put(equals < 0 ? pair : pair.substring(0, equals), equals < 0 ? "" : pair.substring(equals + 1));
            }
        }
        Map<String, String> signedHeaders = new TreeMap<>(Map.of("host", host));
        Map<String, String> headers = new LinkedHashMap<>();
        if (presigned) {
            query.put("X-Amz-Algorithm", SigV4aSignature.ALGORITHM);
            query.put("X-Amz-Credential", encode(accessKeyId + "/" + scope));
            query.put("X-Amz-Date", amzDate);
            query.put("X-Amz-Expires", "300");
            query.put("X-Amz-Region-Set", encode(regionSet));
            query.put("X-Amz-SignedHeaders", "host");
            if (sessionToken != null) {
                query.put("X-Amz-Security-Token", encode(sessionToken));
            }
        } else {
            signedHeaders.put("x-amz-date", amzDate);
            signedHeaders.put("x-amz-region-set", regionSet);
            headers.put("X-Amz-Date", amzDate);
            headers.put("X-Amz-Region-Set", regionSet);
            if (contentSha256 != null) {
                signedHeaders.put("x-amz-content-sha256", contentSha256);
            }
            if (sessionToken != null) {
                signedHeaders.put("x-amz-security-token", sessionToken);
                headers.put("X-Amz-Security-Token", sessionToken);
            }
        }
        String canonicalQuery = query.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        String names = String.join(";", signedHeaders.keySet());
        String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery + "\n"
                + signedHeaders.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue() + "\n")
                        .collect(Collectors.joining())
                + "\n" + names + "\n" + (contentSha256 == null ? "UNSIGNED-PAYLOAD" : contentSha256);
        String signature = SigV4aSigning.sign(accessKeyId, secretKey, SigV4aSignature.ALGORITHM + "\n" + amzDate
                + "\n" + scope + "\n" + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        if (contentSha256 != null) {
            headers.put("X-Amz-Content-SHA256", contentSha256);
        }
        if (presigned) {
            return new Signed(method, path + "?" + canonicalQuery + "&X-Amz-Signature=" + signature, headers);
        }
        headers.put("Authorization", SigV4aSignature.ALGORITHM + " Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + names + ", Signature=" + signature);
        return new Signed(method, pathAndQuery, headers);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }
}
