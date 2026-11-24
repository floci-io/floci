package io.floci.aws.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@ResourceLock(ParallelLocks.TLS_CONFIG)
class CertificateMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void learnedHostnamesRoundTripThroughJson() throws Exception {
        CertificateMetadata metadata = CertificateMetadata.create(List.of("localhost"), "dev");
        metadata.setLearnedHostnames(List.of("api.example.localhost.floci.io"));

        CertificateMetadata read = MAPPER.readValue(MAPPER.writeValueAsString(metadata), CertificateMetadata.class);

        assertEquals(List.of("api.example.localhost.floci.io"), read.getLearnedHostnames());
        assertEquals(List.of("localhost"), read.getHostnames(), "configured names stay apart from learned ones");
        assertEquals(metadata, read);
    }

    @Test
    void metadataWrittenBeforeTheFieldExistedReadsAsNoLearnedHostnames() throws Exception {
        String legacyJson = "{\"hostnames\":[\"localhost\"],\"generatedAt\":\"2026-01-01T00:00:00Z\",\"flociVersion\":\"dev\"}";

        CertificateMetadata read = MAPPER.readValue(legacyJson, CertificateMetadata.class);

        assertEquals(List.of(), read.getLearnedHostnames());
    }

    @Test
    void nullLearnedHostnamesReadAsEmpty() {
        CertificateMetadata metadata = new CertificateMetadata();
        metadata.setLearnedHostnames(null);

        assertEquals(List.of(), metadata.getLearnedHostnames());
    }

    @Test
    void learnedHostnamesTakePartInEquality() {
        CertificateMetadata a = new CertificateMetadata(List.of("localhost"), "2026-01-01T00:00:00Z", "dev");
        CertificateMetadata b = new CertificateMetadata(List.of("localhost"), "2026-01-01T00:00:00Z", "dev");
        assertEquals(a, b);

        b.setLearnedHostnames(List.of("api.example.localhost.floci.io"));

        assertNotEquals(a, b);
        assertNotEquals(a.hashCode(), b.hashCode());
    }
}
