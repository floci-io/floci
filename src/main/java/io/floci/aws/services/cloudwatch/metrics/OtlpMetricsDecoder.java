package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

final class OtlpMetricsDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> METRIC_TYPES = List.of("gauge", "sum", "histogram", "exponentialHistogram");

    DynamicMessage decodeJson(byte[] body) throws InvalidProtocolBufferException {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null) {
                throw new InvalidProtocolBufferException("Empty OTLP metrics request");
            }
            convertExemplarIds(root);
            DynamicMessage.Builder request = DynamicMessage.newBuilder(OtlpDescriptors.EXPORT_REQUEST);
            JsonFormat.parser().ignoringUnknownFields().merge(MAPPER.writeValueAsString(root), request);
            return request.build();
        } catch (InvalidProtocolBufferException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidProtocolBufferException(e);
        }
    }

    private void convertExemplarIds(JsonNode root) {
        for (JsonNode resource : root.path("resourceMetrics")) {
            for (JsonNode scope : resource.path("scopeMetrics")) {
                for (JsonNode metric : scope.path("metrics")) {
                    for (String metricType : METRIC_TYPES) {
                        for (JsonNode point : metric.path(metricType).path("dataPoints")) {
                            for (JsonNode exemplar : point.path("exemplars")) {
                                if (exemplar instanceof ObjectNode object) {
                                    convertHexField(object, "traceId");
                                    convertHexField(object, "spanId");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void convertHexField(ObjectNode exemplar, String field) {
        JsonNode value = exemplar.get(field);
        if (value != null && value.isTextual()) {
            byte[] decoded = HexFormat.of().parseHex(value.textValue());
            exemplar.put(field, Base64.getEncoder().encodeToString(decoded));
        }
    }

    DynamicMessage decodeProtobuf(byte[] body) throws InvalidProtocolBufferException {
        if (body.length == 0) {
            throw new InvalidProtocolBufferException("Empty OTLP metrics request");
        }
        return DynamicMessage.parseFrom(OtlpDescriptors.EXPORT_REQUEST, body);
    }
}
