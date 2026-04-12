package io.github.hectorvent.floci.testutil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SigV4TokenTestHelper {

    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private SigV4TokenTestHelper() {
    }

    public static String createElastiCacheToken(
            String clusterId,
            String user,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        params.put("User", user);
        return signToken(clusterId, null, clusterId, accessKeyId, secretKey, "us-east-1",
                "elasticache", timestamp, expiresSeconds, params);
    }

    public static String createRdsToken(
            String host,
            int port,
            String dbUser,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        params.put("DBUser", dbUser);
        return signToken(host, port, host + ":" + port, accessKeyId, secretKey, "us-east-1",
                "rds-db", timestamp, expiresSeconds, params);
    }

    private static String signToken(
            String host,
            Integer port,
            String canonicalHostHeader,
            String accessKeyId,
            String secretKey,
            String region,
            String service,
            Instant timestamp,
            int expiresSeconds,
            Map<String, String> params
    ) throws Exception {
        String date = DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC).format(timestamp);
        String dateTime = DATETIME_FMT.format(timestamp);
        String credentialScope = date + "/" + region + "/" + service + "/aws4_request";

        Map<String, String> queryParams = new LinkedHashMap<>(params);
        queryParams.put("X-Amz-Credential", accessKeyId + "/" + credentialScope);
        queryParams.put("X-Amz-Date", dateTime);
        queryParams.put("X-Amz-Expires", Integer.toString(expiresSeconds));
        queryParams.put("X-Amz-SignedHeaders", "host");

        List<String> encodedPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            encodedPairs.add(entry.getKey() + "=" + urlEncode(entry.getValue()));
        }

        String canonicalQuery = encodedPairs.stream()
                .sorted(Comparator.comparing(SigV4TokenTestHelper::rawParamName))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        String canonicalRequest = "GET\n/\n"
                + canonicalQuery + "\n"
                + "host:" + canonicalHostHeader + "\n\n"
                + "host\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String stringToSign = "AWS4-HMAC-SHA256\n"
                + dateTime + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] signingKey = deriveSigningKey(secretKey, date, region, service);
        String signature = hexEncode(hmacSha256(signingKey, stringToSign));

        String authority = port == null ? host : host + ":" + port;
        return authority + "/?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private static String rawParamName(String rawPair) {
        int eq = rawPair.indexOf('=');
        return eq >= 0 ? rawPair.substring(0, eq) : rawPair;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region,
                                           String service) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
