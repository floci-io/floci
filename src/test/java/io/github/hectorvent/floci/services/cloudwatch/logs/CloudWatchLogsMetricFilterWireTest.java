package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CloudWatchLogsMetricFilterWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PUT = """
            {"logGroupName":"/synthetic","filterName":"synthetic","filterPattern":"",
             "metricTransformations":[{"metricName":"Count","metricNamespace":"Synthetic","metricValue":"1"}]}
            """;
    private static final String DELETE = """
            {"logGroupName":"/synthetic","filterName":"synthetic"}
            """;
    private static final String TEST = """
            {"filterPattern":"","logEventMessages":["synthetic"]}
            """;
    private CloudWatchLogsMetricFilterService service;
    private CloudWatchLogsMetricFilterHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(CloudWatchLogsMetricFilterService.class);
        when(service.describeMetricFilters(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CloudWatchLogsMetricFilterService.DescribeMetricFiltersResult(List.of(), null));
        when(service.testMetricFilter(any(), any())).thenReturn(List.of());
        clearInvocations(service);
        handler = new CloudWatchLogsMetricFilterHandler(service, MAPPER);
    }

    static Stream<Arguments> invalidRequests() throws Exception {
        List<Arguments> cases = new ArrayList<>();
        for (String action : List.of("PutMetricFilter", "DescribeMetricFilters", "DeleteMetricFilter", "TestMetricFilter")) {
            for (String root : List.of("null", "[]", "\"text\"", "true", "1")) {
                cases.add(Arguments.of(action, MAPPER.readTree(root)));
            }
        }
        strings(cases, "PutMetricFilter", PUT,
                List.of("logGroupName", "filterName", "filterPattern", "fieldSelectionCriteria"));
        strings(cases, "DeleteMetricFilter", DELETE, List.of("logGroupName", "filterName"));
        strings(cases, "DescribeMetricFilters", "{}",
                List.of("logGroupName", "filterNamePrefix", "metricName", "metricNamespace", "nextToken"));
        strings(cases, "TestMetricFilter", TEST, List.of("filterPattern"));
        for (String member : List.of("metricName", "metricNamespace", "metricValue", "unit")) {
            for (String value : List.of("null", "true", "1", "[]", "{}")) {
                ObjectNode request = (ObjectNode) MAPPER.readTree(PUT);
                ((ObjectNode) request.path("metricTransformations").get(0)).set(member, MAPPER.readTree(value));
                cases.add(Arguments.of("PutMetricFilter", request));
            }
        }
        replace(cases, "PutMetricFilter", PUT, "applyOnTransformedLogs", "null", "\"true\"", "1", "[]", "{}");
        replace(cases, "PutMetricFilter", PUT, "metricTransformations",
                "null", "{}", "\"text\"", "1", "true", "[null]", "[[]]", "[1]", "[\"text\"]");
        replace(cases, "PutMetricFilter", PUT, "emitSystemFieldDimensions",
                "null", "{}", "\"@aws.region\"", "1", "true", "[null]", "[[]]", "[1]");
        replace(cases, "TestMetricFilter", TEST, "logEventMessages",
                "null", "{}", "\"text\"", "1", "true", "[null]", "[{}]", "[1]", "[true]", "[[]]");
        replace(cases, "DescribeMetricFilters", "{}", "limit",
                "null", "\"1\"", "true", "[]", "{}", "1.5", "1.0", "2147483649", "4294967297", "0", "51");
        for (String value : List.of("null", "[]", "\"text\"", "1", "true", "{\"Name\":null}", "{\"Name\":1}",
                "{\"Name\":true}", "{\"Name\":[]}", "{\"Name\":{}}")) {
            ObjectNode request = (ObjectNode) MAPPER.readTree(PUT);
            ((ObjectNode) request.path("metricTransformations").get(0)).set("dimensions", MAPPER.readTree(value));
            cases.add(Arguments.of("PutMetricFilter", request));
        }
        for (String value : List.of("null", "[]", "{}", "true", "1e309")) {
            ObjectNode request = (ObjectNode) MAPPER.readTree(PUT);
            ((ObjectNode) request.path("metricTransformations").get(0)).set("defaultValue", MAPPER.readTree(value));
            cases.add(Arguments.of("PutMetricFilter", request));
        }
        return cases.stream();
    }

    private static void strings(List<Arguments> cases, String action, String request, List<String> members)
            throws Exception {
        for (String member : members) {
            replace(cases, action, request, member, "null", "true", "1", "[]", "{}");
        }
    }

    private static void replace(List<Arguments> cases, String action, String json, String member, String... values)
            throws Exception {
        for (String value : values) {
            ObjectNode request = (ObjectNode) MAPPER.readTree(json);
            request.set(member, MAPPER.readTree(value));
            cases.add(Arguments.of(action, request));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsIncorrectNodeTypesBeforeCallingTheService(String action, JsonNode request) {
        AwsException error = assertThrows(AwsException.class, () -> handler.handle(action, request, "eu-west-1"),
                () -> action + " " + request);
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        verifyNoInteractions(service);
    }

    @Test
    void numericStringDefaultReportsTheSerializationErrorVerifiedOnLiveAws() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/cloudwatchlogs/metric-filter-default-value-wire-aws.json")) {
            JsonNode observation = MAPPER.readTree(input);
            ObjectNode request = (ObjectNode) MAPPER.readTree(PUT);
            ((ObjectNode) request.path("metricTransformations").get(0))
                    .set("defaultValue", observation.get("requestValue"));

            AwsException error = assertThrows(AwsException.class,
                    () -> handler.handle("PutMetricFilter", request, "eu-west-1"));

            assertEquals("Verified on Live AWS", observation.path("provenance").path("verification").textValue());
            assertEquals(observation.path("response").path("status").intValue(), error.getHttpStatus());
            assertEquals(observation.path("response").path("body").path("__type").textValue(), error.getErrorCode());
            assertEquals(observation.path("response").path("body").path("Message").textValue(), error.getMessage());
            verifyNoInteractions(service);
        }
    }

    @Test
    void otherStringsFollowTheSameNodeTypeRuleWithoutClaimingSeparateAwsObservations() throws Exception {
        for (String value : List.of("NaN", "zero")) {
            ObjectNode request = (ObjectNode) MAPPER.readTree(PUT);
            ((ObjectNode) request.path("metricTransformations").get(0)).put("defaultValue", value);
            AwsException error = assertThrows(AwsException.class,
                    () -> handler.handle("PutMetricFilter", request, "eu-west-1"));
            assertEquals("SerializationException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
        }
        verifyNoInteractions(service);
    }

    @Test
    void validShapesKeepObjectNodeResponsesAndNumericDefaults() throws Exception {
        for (String action : List.of("PutMetricFilter", "DescribeMetricFilters", "DeleteMetricFilter", "TestMetricFilter")) {
            String json = switch (action) {
                case "PutMetricFilter" -> PUT.replace("\"metricValue\":\"1\"", "\"metricValue\":\"1\",\"defaultValue\":0");
                case "DeleteMetricFilter" -> DELETE;
                case "TestMetricFilter" -> TEST;
                default -> "{\"limit\":50}";
            };
            Response response = handler.handle(action, MAPPER.readTree(json), "eu-west-1");
            assertEquals(200, response.getStatus());
            assertInstanceOf(ObjectNode.class, response.getEntity());
        }
    }
}
