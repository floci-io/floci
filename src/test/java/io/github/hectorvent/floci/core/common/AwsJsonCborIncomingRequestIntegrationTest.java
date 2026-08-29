package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.jboss.resteasy.reactive.server.jaxrs.HttpHeadersImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * Integration test for {@link AwsJsonCborController#bodyToJson(HttpHeaders, byte[])}.
 * <p>
 * Verifies handling of incoming smithy-rpc-v2-cbor request bodies: a gzip-encoded body
 * (as signalled by a {@code Content-Encoding: gzip} header) must be transparently
 * decompressed and decoded back into the original {@link JsonNode}, and a decompressed
 * body that exceeds the 10 MB safety limit enforced in {@code decodeBody} must fail fast
 * with an {@link AwsException} (413 Payload Too Large) rather than risk an OOM from an
 * unbounded gzip bomb.
 * <p>
 * Boots Quarkus ({@code @QuarkusTest}) since the controller is injected and exercises the
 * real request-decoding path used by CloudWatch Metrics and other smithy-rpc-v2-cbor
 * services.
 */
@QuarkusTest
class AwsJsonCborIncomingRequestIntegrationTest {

    @Inject AwsJsonCborController awsJsonCborController;

    @Test
    void gzippedRequestIsDecompressed() throws Exception {

        ObjectMapper jsonMapper = new ObjectMapper();
        ObjectNode metrics = jsonMapper.createObjectNode();
        metrics.put("Namespace", "Test");
        ArrayNode metricData = metrics.putArray("MetricData");

        ObjectNode o = metricData.addObject();
        o.put("MetricName", "SYSTEM_NORMALIZED_CPU_STEAL");
        ArrayNode dimensions = o.putArray("Dimensions");
        ObjectNode dimension = dimensions.addObject();
        dimension.put("Name","clusterName");
        dimension.put("Value", "test");
        o.put("Value", 9344221);
        o.put("Unit", "Seconds");

        // Encode the metrics payload to CBOR and gzip it, simulating what a real AWS SDK
        // client sends when it compresses a smithy-rpc-v2-cbor request body.
        byte[] compressedNode = AwsJsonCborController.nodeToSmithyCbor(metrics);
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gon = new GZIPOutputStream(gzipped)) {
            gon.write(compressedNode);
        }

        // bodyToJson must detect the Content-Encoding header, gunzip the body, and decode
        // the resulting CBOR bytes back into the original JsonNode.
        JsonNode node =
                awsJsonCborController.bodyToJson(
                        new HttpHeadersImpl(Map.of("Content-Encoding", "gzip").entrySet()),
                        gzipped.toByteArray());

        Assertions.assertEquals(metrics, node);
    }

    @Test
    void payloadTooLargeExceptionThrown() throws Exception {

        // No need for a valid CBOR structure here — decodeBody's 10 MB limit is enforced
        // purely on decompressed byte count, so raw pseudo-random bytes are enough to
        // exceed it. The varying pattern (instead of all-zero bytes) also keeps gzip from
        // trivially collapsing the payload via run-length compression.
        int elevenMB = 11 * 1024 * 1024;
        byte[] rawBytes = new byte[elevenMB];
        for (int i = 0,j = 37; i < elevenMB; i++, j+=19) {
            rawBytes[i] = (byte) (i+j & 0xFF);
        }
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gon = new GZIPOutputStream(gzipped)) {
            gon.write(rawBytes);
        }

        // Exceeding the decompressed size limit must surface as a 413 Payload Too Large
        // AwsException, not an OutOfMemoryError or a silently truncated body.
        Assertions.assertThrows(AwsException.class, () -> {
            awsJsonCborController.bodyToJson(
                    new HttpHeadersImpl(Map.of("Content-Encoding", "gzip").entrySet()),
                    gzipped.toByteArray());
        });
    }
}
