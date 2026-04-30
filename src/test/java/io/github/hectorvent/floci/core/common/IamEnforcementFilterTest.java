package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IamEnforcementFilter#accessDeniedResponse}, focused on
 * the protocol-aware response shape. AWS SDKs hard-fail on wrong-shape error
 * payloads — an XML parser blows up on a leading {@code "{"} and a JSON parser
 * blows up on a leading {@code "<"} — so each protocol has to get the right
 * envelope.
 */
class IamEnforcementFilterTest {

    @Test
    void queryProtocolGetsXmlErrorResponse() {
        // IAM/STS/EC2/SQS/SNS/RDS/ELBv2/CFN/... — Query protocol, form-encoded body, XML response.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "iam:ListUsers", "iam", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_XML_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.contains("<ErrorResponse>"), body);
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
        assertTrue(body.contains("<Type>Sender</Type>"), body);
        assertTrue(body.contains("User is not authorized to perform: iam:ListUsers"), body);
        assertTrue(body.contains("<RequestId>"), body);
    }

    @Test
    void s3GetsS3FlavoredXmlError() {
        // S3 — credential-scope is "s3"; S3 errors are <Error>... at the root, no <ErrorResponse> wrapper.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "s3:GetObject", "s3", null);

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_XML_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.startsWith("<?xml"), body);
        assertTrue(body.contains("<Error>"), body);
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
        assertTrue(body.contains("User is not authorized to perform: s3:GetObject"), body);
        // S3 errors do not have the Query <Type>Sender</Type> envelope.
        assertTrue(!body.contains("<ErrorResponse>"), body);
    }

    @Test
    void jsonProtocolGetsJsonErrorResponse() {
        // DynamoDB / Cognito / Kinesis / ... — JSON 1.0/1.1, JSON error response.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "dynamodb:PutItem", "dynamodb", MediaType.valueOf("application/x-amz-json-1.0"));

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.contains("\"__type\":\"AccessDeniedException\""), body);
        assertTrue(body.contains("User is not authorized to perform: dynamodb:PutItem"), body);
    }

    @Test
    void restJsonProtocolGetsJsonErrorResponse() {
        // Lambda / API Gateway — REST-JSON.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "lambda:InvokeFunction", "lambda", MediaType.APPLICATION_JSON_TYPE);

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.contains("\"__type\":\"AccessDeniedException\""), body);
    }

    @Test
    void formEncodedTakesPrecedenceOverNonS3Service() {
        // Even if the credentialScope isn't recognized, a form-encoded body
        // means we're talking to a Query-protocol service — XML response.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "rds:CreateDBInstance", "rds", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        assertEquals(MediaType.APPLICATION_XML_TYPE, r.getMediaType());
        assertTrue(entityString(r).contains("<ErrorResponse>"));
    }

    @Test
    void s3WithFormEncodedBodyStillGetsS3XmlShape() {
        // S3 presigned POST uploads use multipart/form-data, not x-www-form-urlencoded,
        // but if a form-encoded body ever does land here, the s3 scope must still win.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "s3:PutObject", "s3", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        String body = entityString(r);
        assertTrue(body.contains("<Error>"));
        assertTrue(!body.contains("<ErrorResponse>"));
    }

    @Test
    void unknownContentTypeFallsBackToJson() {
        // No Content-Type at all — most likely a GET against a REST-JSON service.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "kms:Decrypt", "kms", null);

        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        assertTrue(entityString(r).contains("\"__type\":\"AccessDeniedException\""));
    }

    private static String entityString(Response r) {
        Object entity = r.getEntity();
        assertNotNull(entity, "response body should not be null");
        if (entity instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return entity.toString();
    }
}
