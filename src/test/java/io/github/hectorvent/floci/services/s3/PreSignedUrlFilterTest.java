package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreSignedUrlFilterTest {

    /**
     * A bare 12-digit access key ID that isn't registered in IAM must be rejected like any
     * other unknown key, not resolved to the well-known "test" secret. That fallback would let
     * a client sign an arbitrary account's X-Amz-Credential with the public "test" secret and
     * have account resolution treat the forged value as the request's account — an
     * authentication bypass under S3 auth enforcement (see AccountResolver, which reads a
     * 12-digit access key ID as the account directly).
     */
    private static String resolveSecretKey(PreSignedUrlFilter filter, String accessKeyId) throws Exception {
        Method method = PreSignedUrlFilter.class.getDeclaredMethod("resolveSecretKey", String.class);
        method.setAccessible(true);
        return (String) method.invoke(filter, accessKeyId);
    }

    private static String resolveSecretKey(PreSignedUrlFilter filter, String accessKeyId,
                                           String sessionToken) throws Exception {
        Method method = PreSignedUrlFilter.class.getDeclaredMethod(
                "resolveSecretKey", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(filter, accessKeyId, sessionToken);
    }

    @Test
    void resolveSecretKeyRejectsUnregisteredNumericAccessKeyId() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDUNRELATED", "unrelated-secret");
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, iamService);

        assertNull(resolveSecretKey(filter, "123456789012"));
    }

    @Test
    void resolveSecretKeyBindsEcsCredentialToTheIssuedToken() throws Exception {
        IamService iamService = mock(IamService.class);
        String accessKeyId = "ASIAECS" + "A".repeat(13);
        when(iamService.isEcsTaskRoleCredential(accessKeyId)).thenReturn(true);
        when(iamService.findSecretKey(accessKeyId, "token+with/slash"))
                .thenReturn(Optional.of("ecs-secret"));
        when(iamService.findSecretKey(accessKeyId, "forged-token"))
                .thenReturn(Optional.empty());
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, iamService);

        assertEquals("ecs-secret", resolveSecretKey(filter, accessKeyId, "token+with/slash"));
        assertNull(resolveSecretKey(filter, accessKeyId, "forged-token"));
        assertNull(resolveSecretKey(filter, accessKeyId, null));
        verify(iamService).findSecretKey(accessKeyId, "token+with/slash");
    }

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
