package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookAuthenticatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-25T06:00:00Z");
    private static final long NOW_SECONDS = NOW.getEpochSecond();
    private static final byte[] BODY = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec-0123456789abcdef";

    // ──────────────────────────── Static token ────────────────────────────

    @Test
    void aStaticTokenMustEqualTheWholeHeaderValue() {
        JsonNode webhook = webhook("{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"Authorization\"}}");

        assertTrue(allows(webhook, "Bearer abc", Map.of("authorization", "Bearer abc")));
        assertFalse(allows(webhook, "Bearer abc", Map.of("authorization", "abc")));
        assertFalse(allows(webhook, "Bearer abc", Map.of("authorization", "bearer abc")));
        assertFalse(allows(webhook, "Bearer abc", Map.of("authorization", "Bearer abc ")));
        assertFalse(allows(webhook, "Bearer abc", Map.of()));
    }

    @Test
    void aStaticTokenHeaderIsMatchedWhateverItsCase() {
        JsonNode webhook = webhook("{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Webhook-Secret\"}}");

        assertTrue(allows(webhook, "s3cret", Map.of("x-webhook-secret", "s3cret")));
        assertFalse(allows(webhook, "s3cret", Map.of("x-webhook-secret", "S3CRET")));
    }

    // ──────────────────────────── Basic ────────────────────────────

    @Test
    void basicChecksTheUsernameAndThePassword() {
        JsonNode webhook = webhook("{\"BasicHttpAuthConfiguration\":{}}");
        String secret = "{\"username\":\"alice\",\"password\":\"p4ss:word\"}";

        assertTrue(allows(webhook, secret, Map.of("authorization", "Basic " + b64("alice:p4ss:word"))));
        assertFalse(allows(webhook, secret, Map.of("authorization", "Basic " + b64("alice:wrong"))));
        assertFalse(allows(webhook, secret, Map.of("authorization", "Basic " + b64("bob:p4ss:word"))));
        assertFalse(allows(webhook, secret, Map.of("authorization", "Basic " + b64("alice"))));
        assertFalse(allows(webhook, secret, Map.of()));
    }

    @Test
    void basicTakesTheSchemeInAnyCaseAndExactlyOneSpace() {
        JsonNode webhook = webhook("{\"BasicHttpAuthConfiguration\":{}}");
        String secret = "{\"username\":\"alice\",\"password\":\"pw\"}";
        String credentials = b64("alice:pw");

        assertTrue(allows(webhook, secret, Map.of("authorization", "basic " + credentials)));
        assertTrue(allows(webhook, secret, Map.of("authorization", "BASIC " + credentials)));
        assertFalse(allows(webhook, secret, Map.of("authorization", "Basic  " + credentials)));
        assertFalse(allows(webhook, secret, Map.of("authorization", credentials)));
        assertFalse(allows(webhook, secret, Map.of("authorization", "Basic !!!notbase64")));
        assertFalse(allows(webhook, secret, Map.of("authorization", "Bearer " + credentials)));
    }

    @Test
    void aBasicSecretWithoutAUsernameAndAPasswordAllowsNoRequest() {
        JsonNode webhook = webhook("{\"BasicHttpAuthConfiguration\":{}}");
        Map<String, String> emptyCredentials = Map.of("authorization", "Basic " + b64(":"));

        for (String secret : List.of("{}", "{\"username\":\"\"}", "{\"Username\":\"\",\"Password\":\"\"}", "\"text\"",
                "12345678", "null", "", "not json")) {
            assertFalse(allows(webhook, secret, emptyCredentials), secret);
        }
    }

    // ──────────────────────────── HMAC, body only ────────────────────────────

    @Test
    void aHexSignatureWithAPrefixIsAcceptedInAnyCaseOfItsDigits() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SignaturePrefix\":\"sha256=\","
                + "\"SignatureHttpHeader\":\"X-Hub-Signature-256\",\"SignedMessageComposition\":\"BODY\"");
        String digest = hex(mac("HmacSHA256", SECRET.getBytes(StandardCharsets.UTF_8), BODY));

        assertTrue(allows(webhook, SECRET, Map.of("x-hub-signature-256", "sha256=" + digest)));
        assertTrue(allows(webhook, SECRET, Map.of("x-hub-signature-256", "sha256=" + digest.toUpperCase())));
        assertFalse(allows(webhook, SECRET, Map.of("x-hub-signature-256", digest)));
        assertFalse(allows(webhook, SECRET, Map.of("x-hub-signature-256", "sha256=" + digest.substring(1))));
        assertFalse(allows(webhook, SECRET, Map.of("x-hub-signature-256", "sha256=" + "0".repeat(64))));
        assertFalse(allows(webhook, SECRET, Map.of()));
    }

    @Test
    void aSignatureOfAnotherBodyIsRefused() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\","
                + "\"SignedMessageComposition\":\"BODY\"");
        byte[] other = "{\"n\":2}".getBytes(StandardCharsets.UTF_8);

        assertTrue(allows(webhook, SECRET, Map.of("x-sig", hex(mac("HmacSHA256", key(SECRET), BODY)))));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", hex(mac("HmacSHA256", key(SECRET), other)))));
    }

    @Test
    void sha1SignaturesUseHmacSha1() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA1\",\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\","
                + "\"SignedMessageComposition\":\"BODY\"");

        assertTrue(allows(webhook, SECRET, Map.of("x-sig", hex(mac("HmacSHA1", key(SECRET), BODY)))));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", hex(mac("HmacSHA256", key(SECRET), BODY)))));
    }

    @Test
    void aRawSecretIsTheKeyAsTypedAndABase64SecretIsTheKeyDecoded() {
        byte[] decoded = "0123456789abcdef-key".getBytes(StandardCharsets.UTF_8);
        String base64Secret = Base64.getEncoder().encodeToString(decoded);
        JsonNode raw = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SecretEncoding\":\"RAW\","
                + "\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\"");
        JsonNode base64 = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SecretEncoding\":\"BASE64\","
                + "\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\"");

        assertTrue(allows(raw, base64Secret, Map.of("x-sig", hex(mac("HmacSHA256", key(base64Secret), BODY)))));
        assertFalse(allows(raw, base64Secret, Map.of("x-sig", hex(mac("HmacSHA256", decoded, BODY)))));
        assertTrue(allows(base64, base64Secret, Map.of("x-sig", hex(mac("HmacSHA256", decoded, BODY)))));
        assertFalse(allows(base64, base64Secret, Map.of("x-sig", hex(mac("HmacSHA256", key(base64Secret), BODY)))));
    }

    @Test
    void aBase64SignatureMayBeUnpaddedButNotUrlSafe() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"BASE64\",\"SignatureHttpHeader\":\"X-Sig\","
                + "\"SignedMessageComposition\":\"BODY\"");
        String secret = secretWhoseDigestNeedsUrlSafeCharacters();
        byte[] digest = mac("HmacSHA256", key(secret), BODY);
        String padded = Base64.getEncoder().encodeToString(digest);

        assertTrue(padded.endsWith("="));
        assertTrue(allows(webhook, secret, Map.of("x-sig", padded)));
        assertTrue(allows(webhook, secret, Map.of("x-sig", Base64.getEncoder().withoutPadding().encodeToString(digest))));
        assertFalse(allows(webhook, secret, Map.of("x-sig", Base64.getUrlEncoder().encodeToString(digest))));
        assertFalse(allows(webhook, secret, Map.of("x-sig", " " + padded)));
    }

    /** A secret whose digest of the body has '+' or '/' in Base64, so the URL-safe form differs. */
    private static String secretWhoseDigestNeedsUrlSafeCharacters() {
        for (int i = 0; ; i++) {
            String secret = "secret-for-digest-" + i;
            String standard = Base64.getEncoder().encodeToString(mac("HmacSHA256", key(secret), BODY));
            if (standard.contains("+") || standard.contains("/")) {
                return secret;
            }
        }
    }

    @Test
    void aSpaceDelimitedListOfPrefixedSignaturesMatchesWhenAnyOneDoes() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SignaturePrefix\":\"v1,\","
                + "\"Delimiter\":\" \",\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\"");
        String good = hex(mac("HmacSHA256", key(SECRET), BODY));

        assertTrue(allows(webhook, SECRET, Map.of("x-sig", "v1," + "0".repeat(64) + " v1," + good)));
        assertTrue(allows(webhook, SECRET, Map.of("x-sig", "v1," + good + " v1," + "0".repeat(64))));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", "v1," + "0".repeat(64) + " v1," + "1".repeat(64))));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", good)));
    }

    // ──────────────────────────── HMAC with a timestamp ────────────────────────────

    private static final String STRIPE = "\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SignatureHttpHeader\":"
            + "\"Stripe-Signature\",\"TimestampHttpHeader\":\"Stripe-Signature\",\"Delimiter\":\",\","
            + "\"SignaturePrefix\":\"v1=\",\"TimestampPrefix\":\"t=\",\"SignedMessageComposition\":"
            + "\"TIMESTAMP_DOT_BODY\",\"TimestampToleranceInSeconds\":300";

    @Test
    void theSameHeaderFormTakesItsElementsInAnyOrderAndAnySignatureMayMatch() {
        JsonNode webhook = hmac(STRIPE);
        String good = signTimestamped(Long.toString(NOW_SECONDS));
        String bad = "0".repeat(64);

        assertTrue(allows(webhook, SECRET, Map.of("stripe-signature", "t=" + NOW_SECONDS + ",v1=" + good)));
        assertTrue(allows(webhook, SECRET, Map.of("stripe-signature", "v1=" + good + ",t=" + NOW_SECONDS)));
        assertTrue(allows(webhook, SECRET,
                Map.of("stripe-signature", "t=" + NOW_SECONDS + ",v1=" + bad + ",v1=" + good + ",v0=zzz")));
        assertFalse(allows(webhook, SECRET, Map.of("stripe-signature", "t=" + NOW_SECONDS + ", v1=" + good)));
        assertFalse(allows(webhook, SECRET, Map.of("stripe-signature", "t=" + NOW_SECONDS + ",v1=" + bad)));
        assertFalse(allows(webhook, SECRET, Map.of("stripe-signature", "v1=" + good)));
        assertFalse(allows(webhook, SECRET, Map.of("stripe-signature", "t=" + NOW_SECONDS)));
    }

    @Test
    void theToleranceHoldsForThePastAndTheFuture() {
        JsonNode webhook = hmac(STRIPE);

        assertTrue(allows(webhook, SECRET, stripe(NOW_SECONDS - 280)));
        assertTrue(allows(webhook, SECRET, stripe(NOW_SECONDS - 300)));
        assertFalse(allows(webhook, SECRET, stripe(NOW_SECONDS - 301)));
        assertTrue(allows(webhook, SECRET, stripe(NOW_SECONDS + 280)));
        assertTrue(allows(webhook, SECRET, stripe(NOW_SECONDS + 300)));
        assertFalse(allows(webhook, SECRET, stripe(NOW_SECONDS + 320)));
    }

    @Test
    void aTimestampIsEpochSecondsOrIso8601AndNothingElse() {
        JsonNode webhook = hmac(STRIPE);

        assertTrue(allows(webhook, SECRET, stripeText(NOW.toString())));
        assertTrue(allows(webhook, SECRET, stripeText("2026-11-25T15:00:00+09:00")));
        assertFalse(allows(webhook, SECRET, stripeText(Long.toString(NOW.toEpochMilli()))));
        assertFalse(allows(webhook, SECRET, stripeText(NOW_SECONDS + ".123")));
        assertFalse(allows(webhook, SECRET, stripeText("abc")));
        assertFalse(allows(webhook, SECRET, stripeText("")));
        assertFalse(allows(webhook, SECRET, stripeText("99999999999999999999999")));
    }

    @Test
    void withNoToleranceTheWindowIs300Seconds() {
        JsonNode webhook = hmac(STRIPE.replace(",\"TimestampToleranceInSeconds\":300", ""));

        assertTrue(allows(webhook, SECRET, stripe(NOW_SECONDS - 280)));
        assertFalse(allows(webhook, SECRET, stripe(NOW_SECONDS - 301)));
        assertTrue(allows(webhook, SECRET, stripe(NOW_SECONDS + 280)));
        assertFalse(allows(webhook, SECRET, stripe(NOW_SECONDS + 320)));
        assertFalse(allows(webhook, SECRET, stripeText(Long.toString(NOW.toEpochMilli()))));
        assertFalse(allows(webhook, SECRET, stripeText("abc")));
    }

    @Test
    void theSignedTextHoldsTheTimestampAsSent() {
        JsonNode webhook = hmac(STRIPE);
        String iso = NOW.toString();

        assertTrue(allows(webhook, SECRET, stripeText(iso)));
        assertFalse(allows(webhook, SECRET, Map.of("stripe-signature",
                "t=" + iso + ",v1=" + signTimestamped(Long.toString(NOW_SECONDS)))));
    }

    @Test
    void separateSignatureAndTimestampHeadersSignTheTimestampDotTheBody() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\","
                + "\"TimestampHttpHeader\":\"X-Ts\",\"TimestampPrefix\":\"ts:\",\"SignedMessageComposition\":"
                + "\"TIMESTAMP_DOT_BODY\",\"TimestampToleranceInSeconds\":60");
        String text = Long.toString(NOW_SECONDS);
        String signature = signTimestamped(text);

        assertTrue(allows(webhook, SECRET, Map.of("x-sig", signature, "x-ts", "ts:" + text)));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", signature, "x-ts", text)));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", signature)));
        assertFalse(allows(webhook, SECRET, Map.of("x-sig", signature, "x-ts", "ts:" + (NOW_SECONDS - 61))));
    }

    @Test
    void aMessageIdIsSignedBeforeTheTimestampAndMayNotHoldADot() {
        JsonNode webhook = hmac("\"Algorithm\":\"SHA256\",\"Encoding\":\"BASE64\",\"SignaturePrefix\":\"v1,\","
                + "\"SignatureHttpHeader\":\"webhook-signature\",\"TimestampHttpHeader\":\"webhook-timestamp\","
                + "\"MessageIdHttpHeader\":\"webhook-id\",\"SignedMessageComposition\":"
                + "\"MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY\",\"TimestampToleranceInSeconds\":300");
        String timestamp = Long.toString(NOW_SECONDS);
        String signature = "v1," + Base64.getEncoder().encodeToString(
                mac("HmacSHA256", key(SECRET), ("msg_1." + timestamp + ".").getBytes(StandardCharsets.UTF_8), BODY));

        assertTrue(allows(webhook, SECRET, Map.of("webhook-signature", signature, "webhook-timestamp", timestamp,
                "webhook-id", "msg_1")));
        assertFalse(allows(webhook, SECRET, Map.of("webhook-signature", signature, "webhook-timestamp", timestamp,
                "webhook-id", "msg_2")));
        assertFalse(allows(webhook, SECRET, Map.of("webhook-signature", signature, "webhook-timestamp", timestamp)));

        String dotted = "v1," + Base64.getEncoder().encodeToString(mac("HmacSHA256", key(SECRET),
                ("msg.1." + timestamp + ".").getBytes(StandardCharsets.UTF_8), BODY));
        assertFalse(allows(webhook, SECRET, Map.of("webhook-signature", dotted, "webhook-timestamp", timestamp,
                "webhook-id", "msg.1")));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static boolean allows(JsonNode webhook, String secret, Map<String, String> headers) {
        return WebhookAuthenticator.allows(webhook, secret, headers, BODY, NOW);
    }

    private static Map<String, String> stripe(long seconds) {
        return stripeText(Long.toString(seconds));
    }

    private static Map<String, String> stripeText(String timestamp) {
        return Map.of("stripe-signature", "t=" + timestamp + ",v1=" + signTimestamped(timestamp));
    }

    private static String signTimestamped(String timestamp) {
        return hex(mac("HmacSHA256", key(SECRET), (timestamp + ".").getBytes(StandardCharsets.UTF_8), BODY));
    }

    private static JsonNode webhook(String auth) {
        return json("{\"RoleArn\":\"arn:aws:iam::000000000000:role/r\",\"AuthConfiguration\":" + auth + "}");
    }

    private static JsonNode hmac(String members) {
        return webhook("{\"HmacAuthConfiguration\":{" + members + "}}");
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static byte[] key(String secret) {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] mac(String algorithm, byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
