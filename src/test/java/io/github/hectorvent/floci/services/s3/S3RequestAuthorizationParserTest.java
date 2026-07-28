package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3RequestAuthorizationParserTest {

    @Test
    void returnsUnsignedAuthorizationWithoutAuthMaterial() {
        S3Service.RequestAuthorization authorization = parse(null, query());

        assertFalse(authorization.signed());
        assertNull(authorization.accessKeyId());
    }

    @Test
    void parsesAuthorizationHeaderAccessKey() {
        S3Service.RequestAuthorization authorization = parse(authorizationHeader("test"), query());

        assertTrue(authorization.signed());
        assertEquals("test", authorization.accessKeyId());
    }

    @Test
    void parsesPresignedQueryAccessKey() {
        S3Service.RequestAuthorization authorization = parse(null, presignedQuery("bad-key"));

        assertTrue(authorization.signed());
        assertEquals("bad-key", authorization.accessKeyId());
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverPresignedQuery() {
        String authorizationHeader = authorizationHeader("header-key");
        MultivaluedMap<String, String> query = presignedQuery("query-key");

        S3Service.RequestAuthorization authorization = parse(authorizationHeader, query);

        assertTrue(authorization.signed());
        assertEquals("header-key", authorization.accessKeyId());
    }

    @Test
    void rejectsMalformedAuthorizationHeader() {
        String authorization = "AWS4-HMAC-SHA256 Credential=test/20260702/us-east-1/s3/aws4_request, SignedHeaders=host";

        AwsException e = assertThrows(AwsException.class, () -> parse(authorization, query()));

        assertEquals("AuthorizationHeaderMalformed", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void rejectsMalformedPresignedQuery() {
        MultivaluedMap<String, String> query = query();
        query.add("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.add("X-Amz-Credential", "test/20260702/us-east-1/s3/aws4_request");
        query.add("X-Amz-Date", "20260702T120000Z");
        query.add("X-Amz-Expires", "3600");
        query.add("X-Amz-SignedHeaders", "host");

        AwsException e = assertThrows(AwsException.class, () -> parse(null, query));

        assertEquals("AuthorizationQueryParametersError", e.getErrorCode());
        assertEquals("Query-string authentication version 4 requires the X-Amz-Algorithm, "
                + "X-Amz-Credential, X-Amz-Signature, X-Amz-Date, X-Amz-SignedHeaders, "
                + "and X-Amz-Expires parameters.", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void ignoresNonPresignedQueryParameters() {
        MultivaluedMap<String, String> query = query();
        query.add("versionId", "123");

        S3Service.RequestAuthorization authorization = parse(null, query);

        assertFalse(authorization.signed());
        assertNull(authorization.accessKeyId());
    }

    @Test
    void ignoresStrayXAmzQueryParametersWithoutAlgorithm() {
        MultivaluedMap<String, String> query = query();
        query.add("X-Amz-Meta-Test", "ignored");

        S3Service.RequestAuthorization authorization = parse(null, query);

        assertFalse(authorization.signed());
        assertNull(authorization.accessKeyId());
    }

    @Test
    void disabledEnforcementDoesNotValidateMalformedAuthorizationHeader() {
        String authorization = "not-a-valid-signature";

        S3Service.RequestAuthorization parsed = S3RequestAuthorizationParser.parseIfRequired(
                false, authorization, query());

        assertFalse(parsed.signed());
        assertNull(parsed.accessKeyId());
    }

    @Test
    void disabledEnforcementDoesNotValidateMalformedPresignedQuery() {
        MultivaluedMap<String, String> query = query();
        query.add("X-Amz-Algorithm", "AWS4-HMAC-SHA256");

        S3Service.RequestAuthorization parsed = S3RequestAuthorizationParser.parseIfRequired(
                false, null, query);

        assertFalse(parsed.signed());
        assertNull(parsed.accessKeyId());
    }

    @Test
    void signedDetectionDoesNotValidateMalformedAuthMaterial() {
        MultivaluedMap<String, String> query = query();
        query.add("X-Amz-Algorithm", "AWS4-HMAC-SHA256");

        assertTrue(S3RequestAuthorizationParser.isSigned("not-a-valid-signature", query()));
        assertTrue(S3RequestAuthorizationParser.isSigned(null, query));
    }

    private static S3Service.RequestAuthorization parse(String authorization, MultivaluedMap<String, String> query) {
        return S3RequestAuthorizationParser.parse(authorization, query);
    }

    private static String authorizationHeader(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + credential(accessKeyId)
                + ", SignedHeaders=host;x-amz-date, Signature=test";
    }

    private static MultivaluedMap<String, String> presignedQuery(String accessKeyId) {
        MultivaluedMap<String, String> query = query();
        query.add("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.add("X-Amz-Credential", credential(accessKeyId));
        query.add("X-Amz-Date", "20260702T120000Z");
        query.add("X-Amz-Expires", "3600");
        query.add("X-Amz-SignedHeaders", "host");
        query.add("X-Amz-Signature", "test");
        return query;
    }

    private static String credential(String accessKeyId) {
        return accessKeyId + "/20260702/us-east-1/s3/aws4_request";
    }

    private static MultivaluedMap<String, String> query() {
        return new MultivaluedHashMap<>();
    }
}
