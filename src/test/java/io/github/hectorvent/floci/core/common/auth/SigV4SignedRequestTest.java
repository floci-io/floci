package io.github.hectorvent.floci.core.common.auth;

import io.github.hectorvent.floci.core.common.auth.SigV4SignedRequest.Freshness;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigV4SignedRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String AUTHORIZATION = "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261017/us-east-1/lambda/aws4_request,"
            + " SignedHeaders=Host;X-Amz-Date, Signature=abc123";

    @Test
    void theHeaderSignatureNeedsEveryPartAndADate() {
        SigV4SignedRequest signed = SigV4SignedRequest.fromHeader(AUTHORIZATION, "20261017T120000Z");
        assertEquals("AKIDEXAMPLE/20261017/us-east-1/lambda/aws4_request", signed.credential());
        assertEquals("host;x-amz-date", signed.signedHeaders());
        assertEquals("abc123", signed.signature());
        assertFalse(signed.presigned());

        assertNull(SigV4SignedRequest.fromHeader(AUTHORIZATION, null));
        assertNull(SigV4SignedRequest.fromHeader("Bearer abc", "20261017T120000Z"));
        assertNull(SigV4SignedRequest.fromHeader("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261017/us-east-1/lambda/aws4_request,"
                + " SignedHeaders=host", "20261017T120000Z"));
    }

    @Test
    void aSigV4aHeaderIsReadOnlyForItsOwnAlgorithm() {
        String authorization = "AWS4-ECDSA-P256-SHA256 Credential=AKIDEXAMPLE/20261017/lambda/aws4_request,"
                + " SignedHeaders=host;x-amz-date;x-amz-region-set, Signature=3044";
        assertNull(SigV4SignedRequest.fromHeader(authorization, "20261017T120000Z"));
        SigV4SignedRequest signed = SigV4SignedRequest.fromHeader(authorization, "20261017T120000Z",
                SigV4aSignature.ALGORITHM);
        assertEquals("AKIDEXAMPLE/20261017/lambda/aws4_request", signed.credential());
        assertEquals("host;x-amz-date;x-amz-region-set", signed.signedHeaders());
        assertNull(SigV4SignedRequest.fromHeader(AUTHORIZATION, "20261017T120000Z", SigV4aSignature.ALGORITHM));
    }

    @Test
    void thePresignedSignatureNeedsEveryPartAndKeepsAnExpiryItCannotRead() {
        Map<String, String> query = presigned(NOW, "300");
        SigV4SignedRequest signed = SigV4SignedRequest.fromQuery(query::get);
        assertTrue(signed.presigned());
        assertEquals(300L, signed.expiresSeconds());

        query.put("X-Amz-Expires", "soon");
        assertNull(SigV4SignedRequest.fromQuery(query::get).expiresSeconds());
        query.remove("X-Amz-Signature");
        assertNull(SigV4SignedRequest.fromQuery(query::get));
    }

    @Test
    void aHeaderSignatureIsFreshWithinFiveMinutesEitherWay() {
        assertEquals(Freshness.FRESH, header(NOW.minus(Duration.ofMinutes(4))).freshness(NOW));
        assertEquals(Freshness.FRESH, header(NOW.plus(Duration.ofMinutes(4))).freshness(NOW));
        assertEquals(Freshness.EXPIRED, header(NOW.minus(Duration.ofMinutes(6))).freshness(NOW));
        assertEquals(Freshness.NOT_YET_VALID, header(NOW.plus(Duration.ofMinutes(6))).freshness(NOW));
        assertEquals(Freshness.MALFORMED_DATE,
                SigV4SignedRequest.fromHeader(AUTHORIZATION, "20261017T99").freshness(NOW));
    }

    @Test
    void aPresignedSignatureLastsItsExpiryOfUpToSevenDays() {
        assertEquals(Freshness.FRESH, query(NOW.minus(Duration.ofMinutes(4)), "300").freshness(NOW));
        assertEquals(Freshness.EXPIRED, query(NOW.minus(Duration.ofMinutes(6)), "300").freshness(NOW));
        assertEquals(Freshness.NOT_YET_VALID, query(NOW.plus(Duration.ofMinutes(6)), "300").freshness(NOW));
        assertEquals(Freshness.FRESH, query(NOW.minus(Duration.ofDays(6)), "604800").freshness(NOW));
        assertEquals(Freshness.BAD_EXPIRES, query(NOW, "604801").freshness(NOW));
        assertEquals(Freshness.BAD_EXPIRES, query(NOW, "0").freshness(NOW));
        assertEquals(Freshness.BAD_EXPIRES, query(NOW, null).freshness(NOW));
    }

    private static SigV4SignedRequest header(Instant signedAt) {
        return SigV4SignedRequest.fromHeader(AUTHORIZATION, AMZ_DATE.format(signedAt));
    }

    private static SigV4SignedRequest query(Instant signedAt, String expires) {
        Map<String, String> query = presigned(signedAt, expires);
        return SigV4SignedRequest.fromQuery(query::get);
    }

    private static Map<String, String> presigned(Instant signedAt, String expires) {
        Map<String, String> query = new HashMap<>();
        query.put("X-Amz-Credential", "AKIDEXAMPLE/20261017/us-east-1/lambda/aws4_request");
        query.put("X-Amz-SignedHeaders", "host");
        query.put("X-Amz-Signature", "abc123");
        query.put("X-Amz-Date", AMZ_DATE.format(signedAt));
        if (expires != null) {
            query.put("X-Amz-Expires", expires);
        }
        return query;
    }
}
