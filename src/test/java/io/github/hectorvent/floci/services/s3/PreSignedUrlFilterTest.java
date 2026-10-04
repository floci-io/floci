package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static String resolveSecretKey(
            PreSignedUrlFilter filter, String accessKeyId, String sessionToken) throws Exception {
        Method method = PreSignedUrlFilter.class.getDeclaredMethod(
                "resolveSecretKey", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(filter, accessKeyId, sessionToken);
    }

    @Test
    void resolveSecretKeyRejectsUnregisteredNumericAccessKeyId() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDUNRELATED", "unrelated-secret");
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, iamService, null);

        assertNull(resolveSecretKey(filter, "123456789012"));
    }

    @Test
    void temporaryCredentialRequiresMatchingSessionToken() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                "ASIAS3EXAMPLE", "temporary-secret", "issued-token", java.time.Instant.now().plusSeconds(3600));
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, iamService, null);

        assertEquals("temporary-secret", resolveSecretKey(filter, "ASIAS3EXAMPLE", "issued-token"));
        assertNull(resolveSecretKey(filter, "ASIAS3EXAMPLE", null));
        assertNull(resolveSecretKey(filter, "ASIAS3EXAMPLE", "wrong-token"));
    }

    @Test
    void temporaryCredentialIsRejectedAfterExpiration() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                "ASIAS3EXPIRED", "temporary-secret", "issued-token", java.time.Instant.now().minusSeconds(1));
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, iamService, null);

        assertNull(resolveSecretKey(filter, "ASIAS3EXPIRED", "issued-token"));
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

    @Test
    void identifiesUnsignedChecksumHeadersCaseInsensitively() {
        assertEquals(
                List.of(
                        "x-amz-checksum-algorithm",
                        "x-amz-checksum-crc32",
                        "x-amz-checksum-crc32c",
                        "x-amz-checksum-crc64nvme",
                        "x-amz-checksum-sha1",
                        "x-amz-checksum-sha256",
                        "x-amz-sdk-checksum-algorithm"),
                PreSignedUrlFilter.unsignedHeadersRequiringSignature(
                        Set.of(
                                "Host",
                                "X-Amz-Checksum-Algorithm",
                                "X-Amz-Checksum-CRC32",
                                "X-Amz-Checksum-CRC32C",
                                "X-Amz-Checksum-CRC64NVME",
                                "X-Amz-Checksum-SHA1",
                                "X-Amz-Checksum-SHA256",
                                "X-Amz-SDK-Checksum-Algorithm"),
                        "host"));
    }

    @Test
    void acceptsChecksumHeadersIncludedInSignedHeaders() {
        assertEquals(
                List.of(),
                PreSignedUrlFilter.unsignedHeadersRequiringSignature(
                        Set.of("host", "x-amz-checksum-algorithm", "x-amz-checksum-crc32",
                                "x-amz-checksum-sha256", "x-amz-sdk-checksum-algorithm"),
                        "host;x-amz-checksum-algorithm;x-amz-checksum-crc32;"
                                + "x-amz-checksum-sha256;x-amz-sdk-checksum-algorithm"));
    }

    @Test
    void reportsUnsignedUserMetadataHeaders() {
        assertEquals(
                List.of("x-amz-meta-source"),
                PreSignedUrlFilter.unsignedHeadersRequiringSignature(
                        Set.of("host", "X-Amz-Meta-Source", "x-amz-meta-signed"),
                        "host;x-amz-meta-signed"));
    }

    @Test
    void ignoresHeadersOutsideChecksumFamily() {
        assertEquals(
                List.of(),
                PreSignedUrlFilter.unsignedHeadersRequiringSignature(
                        Set.of("content-type", "user-agent", "x-amz-content-sha256",
                                "x-amz-user-agent", "x-amz-checksum-type"),
                        "host"));
    }

    @Test
    void abortsWhenPresignedParamPresentWithoutAlgorithm() {
        PreSignedUrlGenerator presignGenerator = mock(PreSignedUrlGenerator.class);
        when(presignGenerator.shouldValidateSignatures()).thenReturn(true);
        PreSignedUrlFilter filter = new PreSignedUrlFilter(presignGenerator, null, null, null, s3ResourceInfo());
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("X-Amz-Credential", "111122223333/20261001/us-east-1/s3/aws4_request");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(400, response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("<Code>AuthorizationQueryParametersError</Code>"), entity);
        assertTrue(
                entity.contains(S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE),
                entity);
    }

    @Test
    void abortsWhenOnlySignatureParamPresentWithoutAlgorithm() {
        PreSignedUrlGenerator presignGenerator = mock(PreSignedUrlGenerator.class);
        when(presignGenerator.shouldValidateSignatures()).thenReturn(true);
        PreSignedUrlFilter filter = new PreSignedUrlFilter(presignGenerator, null, null, null, s3ResourceInfo());
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("X-Amz-Signature", "abc");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(400, captor.getValue().getStatus());
        assertTrue(((String) captor.getValue().getEntity()).contains("<Code>AuthorizationQueryParametersError</Code>"));
    }

    @Test
    void allowsPresignedParamWithoutAlgorithmWhenValidationDisabled() {
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, null, null, s3ResourceInfo());
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("X-Amz-Credential", "111122223333/20261001/us-east-1/s3/aws4_request");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsRequestWithoutAnyPresignedParams() {
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, null, null, s3ResourceInfo());
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("prefix", "photos/");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsHeaderSignedRequestWithDateOrExpiresQueryParam() {
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, null, null, s3ResourceInfo());
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("X-Amz-Date", "20261001T000000Z");
        queryParams.add("X-Amz-Expires", "3600");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsNonS3Routes() {
        ResourceInfo nonS3Info = mock(ResourceInfo.class);
        doReturn(Object.class).when(nonS3Info).getResourceClass();
        PreSignedUrlFilter filter = new PreSignedUrlFilter(null, null, null, null, nonS3Info);

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("X-Amz-Credential", "111122223333/20261001/us-east-1/s3/aws4_request");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    private static ResourceInfo s3ResourceInfo() {
        ResourceInfo info = mock(ResourceInfo.class);
        doReturn(S3Controller.class).when(info).getResourceClass();
        return info;
    }
}
