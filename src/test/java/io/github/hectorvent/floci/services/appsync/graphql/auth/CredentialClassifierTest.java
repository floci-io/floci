package io.github.hectorvent.floci.services.appsync.graphql.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialClassifierTest {

    @Test
    void sigV4WinsWhenBothHeadersPresent() {
        ClassifiedMode mode = CredentialClassifier.classify(Map.of(
                "x-api-key", "da2-abc",
                "Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request"));
        assertEquals(ClassifiedMode.AWS_IAM, mode);
    }

    @Test
    void apiKeyWinsOverBearerWhenBothPresent() {
        String jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.sig";
        ClassifiedMode mode = CredentialClassifier.classify(Map.of(
                "x-api-key", "da2-abc",
                "Authorization", "Bearer " + jwt));
        assertEquals(ClassifiedMode.API_KEY, mode);
    }

    @Test
    void sigV4ClassifiesAsIam() {
        ClassifiedMode mode = CredentialClassifier.classify(Map.of(
                "Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260205/us-east-1/appsync/aws4_request"));
        assertEquals(ClassifiedMode.AWS_IAM, mode);
    }

    @Test
    void bearerJwtClassifiesAsBearer() {
        String jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.sig";
        ClassifiedMode mode = CredentialClassifier.classify(Map.of("Authorization", "Bearer " + jwt));
        assertEquals(ClassifiedMode.BEARER_JWT, mode);
    }

    @Test
    void opaqueAuthorizationClassifiesAsLambda() {
        ClassifiedMode mode = CredentialClassifier.classify(Map.of("Authorization", "ABC123"));
        assertEquals(ClassifiedMode.AWS_LAMBDA, mode);
    }

    @Test
    void missingHeadersClassifyAsNone() {
        assertEquals(ClassifiedMode.NONE, CredentialClassifier.classify(Map.of()));
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        ClassifiedMode mode = CredentialClassifier.classify(Map.of("X-Api-Key", "da2-xyz"));
        assertEquals(ClassifiedMode.API_KEY, mode);
    }
}
