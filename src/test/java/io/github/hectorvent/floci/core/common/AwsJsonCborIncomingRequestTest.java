package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import org.jboss.resteasy.reactive.server.jaxrs.HttpHeadersImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;


/** */
@QuarkusTest
class AwsJsonCborIncomingRequestTest {

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

        byte[] compressedNode = AwsJsonCborController.nodeToSmithyCbor(metrics);
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gon = new GZIPOutputStream(gzipped)) {
            gon.write(compressedNode);
        }


        JsonNode node =
                awsJsonCborController.bodyToJson(
                        new HttpHeadersImpl(Map.of("Content-Encoding", "gzip").entrySet()),
                        gzipped.toByteArray());

        Assertions.assertEquals(metrics, node);
    }

    @Test
    void payloadToLargeExceptionThrown() throws Exception {

        ObjectMapper jsonMapper = new ObjectMapper();
        ObjectNode metrics = jsonMapper.createObjectNode();
        metrics.put("Namespace", "Test");
        ArrayNode metricData = metrics.putArray("MetricData");

        ObjectNode o = metricData.addObject();
        o.put("UUID", UUID.randomUUID().toString());

        for(int i = 0; i < 1000000; i++) {
            metricData.add(o);
        }

        byte[] compressedNode = AwsJsonCborController.nodeToSmithyCbor(metrics);
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gon = new GZIPOutputStream(gzipped)) {
            gon.write(compressedNode);
        }

        Assertions.assertThrows(AwsException.class, () -> {
            awsJsonCborController.bodyToJson(
                    new HttpHeadersImpl(Map.of("Content-Encoding", "gzip").entrySet()),
                    gzipped.toByteArray());
        });
    }
}
