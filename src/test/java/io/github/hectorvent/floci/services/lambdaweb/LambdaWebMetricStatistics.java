package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.restassured.path.json.JsonPath;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/** Reads Web Function metrics back with GetMetricStatistics, as a client does. */
final class LambdaWebMetricStatistics {

    private LambdaWebMetricStatistics() {
    }

    /** {@code Sum} and {@code SampleCount} add up over the periods, {@code Maximum} is the largest. */
    static double of(String namespace, String metric, Map<String, String> dimensions, String statistic) {
        return of("us-east-1", namespace, metric, dimensions, statistic);
    }

    static double of(String region, String namespace, String metric, Map<String, String> dimensions,
                     String statistic) {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Instant now = Instant.now();
        String filter = dimensions.entrySet().stream()
                .map(each -> "{\"Name\":\"" + each.getKey() + "\",\"Value\":\"" + each.getValue() + "\"}")
                .collect(Collectors.joining(","));
        JsonPath result = given().contentType("application/x-amz-json-1.0")
                .header("Authorization", auth("test", region, "monitoring"))
                .header("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricStatistics")
                .body("{\"Namespace\":\"" + namespace + "\",\"MetricName\":\"" + metric + "\",\"Dimensions\":[" + filter
                        + "],\"StartTime\":" + now.minusSeconds(600).getEpochSecond() + ",\"EndTime\":"
                        + now.plusSeconds(60).getEpochSecond() + ",\"Period\":60,\"Statistics\":[\"" + statistic + "\"]}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();
        List<Float> values = result.getList("Datapoints." + statistic, Float.class);
        if ("Maximum".equals(statistic)) {
            return values.stream().mapToDouble(Float::doubleValue).max().orElse(0);
        }
        return values.stream().mapToDouble(Float::doubleValue).sum();
    }
}
