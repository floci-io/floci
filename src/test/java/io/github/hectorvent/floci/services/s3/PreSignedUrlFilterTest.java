package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreSignedUrlFilterTest {

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }

    @Test
    void sortsByEncodedNameRegardlessOfInputOrder() {
        MultivaluedMap<String, String> params = params();
        params.add("X-Amz-Date", "20260101T000000Z");
        params.add("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        params.add("X-Amz-Expires", "300");

        assertEquals(
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20260101T000000Z&X-Amz-Expires=300",
                PreSignedUrlFilter.buildCanonicalQueryString(params));
    }

    @Test
    void encodesReservedCharactersAndSpacesPerSigV4() {
        MultivaluedMap<String, String> params = params();
        params.add("p", "a b+c/d%e");

        // space -> %20 (not +), '+' -> %2B, '/' -> %2F, '%' -> %25, uppercase hex
        assertEquals("p=a%20b%2Bc%2Fd%25e", PreSignedUrlFilter.buildCanonicalQueryString(params));
    }

    @Test
    void encodesParameterNameAsWellAsValue() {
        MultivaluedMap<String, String> params = params();
        params.add("a b", "c/d");

        assertEquals("a%20b=c%2Fd", PreSignedUrlFilter.buildCanonicalQueryString(params));
    }

    @Test
    void encodesNonAsciiValueByteByByte() {
        MultivaluedMap<String, String> params = params();
        params.add("p", "é€"); // é (C3 A9), € (E2 82 AC) in UTF-8

        assertEquals("p=%C3%A9%E2%82%AC", PreSignedUrlFilter.buildCanonicalQueryString(params));
    }

    @Test
    void representsEmptyValueAsNameEquals() {
        MultivaluedMap<String, String> params = params();
        params.add("acl", "");

        assertEquals("acl=", PreSignedUrlFilter.buildCanonicalQueryString(params));
    }

    @Test
    void sortsDuplicateNamesByEncodedValue() {
        MultivaluedMap<String, String> params = params();
        params.add("k", "b");
        params.add("k", "a");
        params.add("k", "10");

        // code-point order on encoded values: "10" < "a" < "b" ('1' < 'a' < 'b')
        assertEquals("k=10&k=a&k=b", PreSignedUrlFilter.buildCanonicalQueryString(params));
    }

    @Test
    void canonicalHeaderValueCollapsesSequentialSpaces() {
        assertEquals("a b", PreSignedUrlFilter.canonicalizeHeaderValue("a b"));
        assertEquals("a b", PreSignedUrlFilter.canonicalizeHeaderValue("a    b"));
        assertEquals("a b", PreSignedUrlFilter.canonicalizeHeaderValue("  a    b  "));
        assertEquals("", PreSignedUrlFilter.canonicalizeHeaderValue(null));
    }

    @Test
    void excludesSignatureButKeepsEveryOtherParameter() {
        MultivaluedMap<String, String> params = params();
        params.add("X-Amz-Signature", "deadbeef");
        params.add("X-Amz-Date", "20260101T000000Z");
        params.add("X-Amz-Algorithm", "AWS4-HMAC-SHA256");

        assertEquals(
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20260101T000000Z",
                PreSignedUrlFilter.buildCanonicalQueryString(params));
    }
}
