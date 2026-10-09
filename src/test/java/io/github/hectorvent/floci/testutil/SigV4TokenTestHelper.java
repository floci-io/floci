package io.github.hectorvent.floci.testutil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
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
        return createElastiCacheToken(clusterId, user, accessKeyId, secretKey, timestamp, expiresSeconds, null);
    }

    public static String createElastiCacheToken(
            String clusterId,
            String user,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds,
            String sessionToken
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        params.put("User", user);
        if (sessionToken != null) {
            params.put("X-Amz-Security-Token", sessionToken);
        }
        return signToken(clusterId, null, accessKeyId, secretKey, "us-east-1",
                "elasticache", timestamp, expiresSeconds, params, Map.of("host", clusterId));
    }

    public static String createElastiCacheTokenWithoutUser(
            String clusterId,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        return signToken(clusterId, null, accessKeyId, secretKey, "us-east-1",
                "elasticache", timestamp, expiresSeconds, params, Map.of("host", clusterId));
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
        return createRdsToken(host, port, dbUser, accessKeyId, secretKey, timestamp, expiresSeconds, null);
    }

    public static String createRdsTokenWithScope(
            String host,
            int port,
            String dbUser,
            String accessKeyId,
            String secretKey,
            String region,
            String service,
            Instant timestamp,
            int expiresSeconds
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        params.put("DBUser", dbUser);
        return signToken(host, port, accessKeyId, secretKey, region, service, timestamp, expiresSeconds,
                params, Map.of("host", host + ":" + port));
    }

    public static String createRdsToken(
            String host,
            int port,
            String dbUser,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds,
            String sessionToken
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "connect");
        params.put("DBUser", dbUser);
        if (sessionToken != null) {
            params.put("X-Amz-Security-Token", sessionToken);
        }
        return signToken(host, port, accessKeyId, secretKey, "us-east-1",
                "rds-db", timestamp, expiresSeconds, params, Map.of("host", host + ":" + port));
    }

    /**
     * An RDS token that carries the signer's credential under a percent-encoded parameter name
     * ({@code X-Amz%2DCredential}) and, after it, a second {@code X-Amz-Credential} naming
     * {@code otherAccessKeyId}: a token that tries to have one key's signature verified and another
     * key authorized. Signed as SigV4 signs a query, over every parameter decoded, so its signature
     * is valid for the signer's key.
     */
    public static String createRdsTokenWithASecondCredential(
            String host,
            int port,
            String dbUser,
            String accessKeyId,
            String secretKey,
            String otherAccessKeyId,
            Instant timestamp,
            int expiresSeconds
    ) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(timestamp);
        String dateTime = DATETIME_FMT.format(timestamp);
        String credentialScope = date + "/us-east-1/rds-db/aws4_request";
        List<String[]> params = List.of(
                new String[] {"Action", "connect"},
                new String[] {"DBUser", dbUser},
                new String[] {"X-Amz-Algorithm", "AWS4-HMAC-SHA256"},
                new String[] {"X-Amz-Credential", accessKeyId + "/" + credentialScope},
                new String[] {"X-Amz-Credential", otherAccessKeyId + "/" + credentialScope},
                new String[] {"X-Amz-Date", dateTime},
                new String[] {"X-Amz-Expires", Integer.toString(expiresSeconds)},
                new String[] {"X-Amz-SignedHeaders", "host"});

        String canonicalQuery = params.stream()
                .map(param -> uriEncode(param[0]) + "=" + uriEncode(param[1]))
                .sorted()
                .reduce((a, b) -> a + "&" + b)
                .orElseThrow();
        String canonicalRequest = "GET\n/\n"
                + canonicalQuery + "\n"
                + "host:" + host + ":" + port + "\n\n"
                + "host\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String stringToSign = "AWS4-HMAC-SHA256\n" + dateTime + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        String signature = hexEncode(hmacSha256(
                deriveSigningKey(secretKey, date, "us-east-1", "rds-db"), stringToSign));

        List<String> wire = new ArrayList<>();
        for (String[] param : params) {
            String name = param[1].startsWith(accessKeyId + "/") ? "X-Amz%2DCredential" : param[0];
            wire.add(name + "=" + uriEncode(param[1]));
        }
        return host + ":" + port + "/?" + String.join("&", wire) + "&X-Amz-Signature=" + signature;
    }

    public static String createEksToken(
            String clusterName,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds
    ) throws Exception {
        return createEksToken(clusterName, accessKeyId, secretKey, timestamp, expiresSeconds, null);
    }

    public static String createEksToken(
            String clusterName,
            String accessKeyId,
            String secretKey,
            Instant timestamp,
            int expiresSeconds,
            String sessionToken
    ) throws Exception {
        String host = "sts.us-east-1.amazonaws.com";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "GetCallerIdentity");
        params.put("Version", "2011-06-15");
        if (sessionToken != null) {
            params.put("X-Amz-Security-Token", sessionToken);
        }
        String url = "https://" + signToken(host, null, accessKeyId, secretKey, "us-east-1", "sts",
                timestamp, expiresSeconds, params,
                Map.of("host", host, "x-k8s-aws-id", clusterName));
        return "k8s-aws-v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    private static String signToken(
            String host,
            Integer port,
            String accessKeyId,
            String secretKey,
            String region,
            String service,
            Instant timestamp,
            int expiresSeconds,
            Map<String, String> params,
            Map<String, String> signedHeaders
    ) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(timestamp);
        String dateTime = DATETIME_FMT.format(timestamp);
        String credentialScope = date + "/" + region + "/" + service + "/aws4_request";

        Map<String, String> queryParams = new LinkedHashMap<>(params);
        queryParams.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        queryParams.put("X-Amz-Credential", accessKeyId + "/" + credentialScope);
        queryParams.put("X-Amz-Date", dateTime);
        queryParams.put("X-Amz-Expires", Integer.toString(expiresSeconds));
        String signedHeaderNames = signedHeaders.keySet().stream()
                .sorted()
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        queryParams.put("X-Amz-SignedHeaders", signedHeaderNames);

        List<String> encodedPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            encodedPairs.add(entry.getKey() + "=" + uriEncode(entry.getValue()));
        }

        String canonicalQuery = encodedPairs.stream()
                .sorted(Comparator.comparing(SigV4TokenTestHelper::rawParamName))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        String canonicalHeaders = signedHeaders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue() + "\n")
                .reduce("", String::concat);
        String canonicalRequest = "GET\n/\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaderNames + "\n"
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

    /**
     * SigV4's UriEncode, as the AWS SDK presigners apply it to every query value: RFC 3986, with
     * only {@code A-Z a-z 0-9 - . _ ~} left as they are and uppercase hex. Not {@code URLEncoder},
     * whose form encoding writes a space as {@code +} and leaves {@code *} unescaped.
     */
    private static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(raw);
            if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '.'
                    || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(String.format("%02X", unsigned));
            }
        }
        return encoded.toString();
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
