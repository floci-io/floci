package io.github.hectorvent.floci.testutil;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3RequestSignerTest {

    private static final S3RequestSigner SIGNER =
            S3RequestSigner.signedAs("AKIDEXAMPLE", "secret").signedAt(Instant.parse("2026-10-17T00:00:00Z"));

    @Test
    void presignedQuerySignsTheUrisOwnQueryAndLeadsWithIt() throws Exception {
        URI plain = URI.create("http://localhost:4566/bucket/key");
        URI part = URI.create("http://localhost:4566/bucket/key?partNumber=1&uploadId=abc");

        String plainQuery = SIGNER.presignedQuery("PUT", plain, 300);
        String partQuery = SIGNER.presignedQuery("PUT", part, 300);

        assertTrue(partQuery.startsWith("partNumber=1&uploadId=abc&X-Amz-Algorithm="), partQuery);
        assertEquals(plainQuery.substring(0, plainQuery.indexOf("&X-Amz-Signature=")),
                partQuery.substring("partNumber=1&uploadId=abc&".length(), partQuery.indexOf("&X-Amz-Signature=")));
        assertNotEquals(signature(plainQuery), signature(partQuery), "the upload id is part of what is signed");
    }

    @Test
    void presignedQueryRefusesAUriThatIsAlreadyPresigned() throws Exception {
        URI uri = URI.create("http://localhost:4566/bucket/key");
        URI presigned = URI.create(uri + "?" + SIGNER.presignedQuery("GET", uri, 300));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> SIGNER.presignedQuery("GET", presigned, 300));
        assertTrue(e.getMessage().contains("X-Amz-Algorithm"), e.getMessage());
    }

    private static String signature(String query) {
        return query.substring(query.indexOf("&X-Amz-Signature=") + "&X-Amz-Signature=".length());
    }
}
