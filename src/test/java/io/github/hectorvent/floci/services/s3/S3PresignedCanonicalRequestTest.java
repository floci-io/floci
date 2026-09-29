package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3PresignedCanonicalRequestTest {

    @Test
    void preservesWirePathSignedHeadersAndExplicitPayloadHash() {
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.add("tag", "b b");
        query.add("tag", "a+a");
        query.add("X-Amz-Signature", "excluded");
        Map<String, String> headers = Map.of("host", "localhost:4566", "x-custom", "  a  b  ");

        String canonicalRequest = S3PresignedCanonicalRequest.build(
                "PUT", "/bucket//a%2Bb", PreSignedUrlFilter.buildCanonicalQueryString(query),
                "host;x-custom", headers::get, "explicit-payload-hash");

        assertEquals("PUT\n/bucket//a%2Bb\ntag=a%2Ba&tag=b%20b\n"
                + "host:localhost:4566\nx-custom:a b\n\nhost;x-custom\nexplicit-payload-hash",
                canonicalRequest);
    }
}
