package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

final class CloudWatchTestRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchTestRequests() {
    }

    static void deleteAlarmJson(URI endpoint, String scope, String name) {
        try {
            call(endpoint, scope, true, "DeleteAlarms", Map.of("AlarmNames", List.of(name)))
                    .then().statusCode(200);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Response call(URI endpoint, String scope, boolean json, String operation, Map<String, ?> body)
            throws JsonProcessingException {
        if (json) {
            return given().port(endpoint.getPort()).header("Authorization", scope)
                    .contentType("application/x-amz-json-1.0")
                    .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                    .body(MAPPER.writeValueAsString(body)).post("/");
        }
        return given().port(endpoint.getPort()).header("Authorization", scope)
                .contentType("application/x-www-form-urlencoded")
                .formParams(queryParameters(operation, body)).post("/");
    }

    private static Map<String, Object> queryParameters(String operation, Map<String, ?> body) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("Action", operation);
        parameters.put("Version", "2010-08-01");
        for (Map.Entry<String, ?> member : body.entrySet()) {
            if (member.getValue() instanceof List<?> items) {
                if (items.isEmpty()) {
                    parameters.put(member.getKey(), "");
                }
                for (int index = 0; index < items.size(); index++) {
                    parameters.put(member.getKey() + ".member." + (index + 1), items.get(index));
                }
            } else {
                parameters.put(member.getKey(), member.getValue());
            }
        }
        return parameters;
    }
}
