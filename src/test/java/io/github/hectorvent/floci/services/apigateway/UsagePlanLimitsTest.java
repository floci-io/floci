package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.ApiStage;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Limits;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Quota;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Throttle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class UsagePlanLimitsTest {

    private static Map<String, Object> request(Object... keyValues) {
        Map<String, Object> request = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            request.put((String) keyValues[i], keyValues[i + 1]);
        }
        return request;
    }

    private static void assertRefused(Executable call, String code, int status, String message) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals(code, e.getErrorCode());
        assertEquals(status, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    @Test
    void aRequestWithoutLimitsHasNoStagesThrottleOrQuota() {
        assertEquals(new Limits(List.of(), null, null), UsagePlanLimits.fromRequest(request("name", "p")));
    }

    @Test
    void throttleFieldsLeftOutReadAsZero() {
        assertEquals(new Throttle(10, 0.0),
                UsagePlanLimits.fromRequest(request("throttle", Map.of("burstLimit", 10))).throttle());
        assertEquals(new Throttle(0, 5.5),
                UsagePlanLimits.fromRequest(request("throttle", Map.of("rateLimit", "5.5"))).throttle());
    }

    @Test
    void quotaOffsetDefaultsToZeroAndNumbersMayBeStrings() {
        assertEquals(new Quota(100, 0, "WEEK"),
                UsagePlanLimits.fromRequest(request("quota", Map.of("limit", 100, "period", "WEEK"))).quota());
        assertEquals(new Quota(100, 2, "WEEK"), UsagePlanLimits.fromRequest(
                request("quota", Map.of("limit", "100", "offset", "2", "period", "WEEK"))).quota());
    }

    @Test
    void stagesKeepTheirMethodThrottlesAndAnEmptyOneIsDropped() {
        Limits limits = UsagePlanLimits.fromRequest(request(
                "throttle", Map.of("burstLimit", 20, "rateLimit", 10),
                "apiStages", List.of(
                        Map.of("apiId", "a", "stage", "v1",
                                "throttle", Map.of("/pets/GET", Map.of("burstLimit", 10, "rateLimit", 5))),
                        Map.of("apiId", "a", "stage", "v2", "throttle", Map.of()))));

        assertEquals(List.of(new ApiStage("a", "v1", Map.of("/pets/GET", new Throttle(10, 5.0))),
                new ApiStage("a", "v2")), limits.apiStages());
    }

    // Measured on API Gateway: a positive JSON number for an int member is truncated and clamped.
    @Test
    void aJsonNumberIsTruncatedTowardZeroAndClampedToTheIntRange() {
        assertEquals(new Quota(1, 0, "DAY"),
                UsagePlanLimits.fromRequest(request("quota", Map.of("limit", 1.5, "period", "DAY"))).quota());
        assertEquals(new Throttle(1, 0.0),
                UsagePlanLimits.fromRequest(request("throttle", Map.of("burstLimit", 1.5))).throttle());
        assertEquals(new Quota(100, 0, "DAY"),
                UsagePlanLimits.fromRequest(request("quota", Map.of("limit", 100.0, "period", "DAY"))).quota());
        assertEquals(new Quota(Integer.MAX_VALUE, 0, "DAY"),
                UsagePlanLimits.fromRequest(request("quota", Map.of("limit", 3000000000L, "period", "DAY"))).quota());
        assertEquals(new Throttle(0, 2.5),
                UsagePlanLimits.fromRequest(request("throttle", Map.of("rateLimit", 2.5))).throttle());
    }

    // Measured on API Gateway: the decimal digits are kept, so a value just under a whole number is not rounded up.
    @Test
    void aJsonNumberIsTruncatedFromItsDecimalDigitsWithoutRoundingThroughADouble() {
        assertEquals(new Quota(1, 0, "DAY"), UsagePlanLimits.fromRequest(
                request("quota", Map.of("limit", new BigDecimal("1.99999999999999999"), "period", "DAY"))).quota());
        assertEquals(new Quota(1, 0, "DAY"), UsagePlanLimits.fromRequest(request("quota",
                Map.of("limit", 1, "offset", new BigDecimal("0.99999999999999999"), "period", "DAY"))).quota());
        assertEquals(new Throttle(2, 0.0), UsagePlanLimits.fromRequest(
                request("throttle", Map.of("burstLimit", new BigDecimal("2.99999999999999999")))).throttle());
        assertEquals(new Quota(Integer.MAX_VALUE, 0, "DAY"), UsagePlanLimits.fromRequest(
                request("quota", Map.of("limit", new BigDecimal("2147483647.9"), "period", "DAY"))).quota());
        assertEquals(new Throttle(0, 1.0), UsagePlanLimits.fromRequest(
                request("throttle", Map.of("rateLimit", new BigDecimal("1.00000000000000000001")))).throttle());
    }

    // Measured on API Gateway: a negative fraction is refused as negative, not truncated to 0.
    @Test
    void aNegativeFractionIsRefusedAsNegative() {
        String limit = "Usage Plan quota limit must be a non-negative numeric";
        assertRefused(() -> UsagePlanLimits.fromRequest(
                        request("quota", Map.of("limit", new BigDecimal("-0.9"), "period", "DAY"))),
                "BadRequestException", 400, limit);
        assertRefused(() -> UsagePlanLimits.fromRequest(request("quota", Map.of("limit", -0.9, "period", "DAY"))),
                "BadRequestException", 400, limit);
    }

    // The int bounds are compared before rounding, so an extreme exponent is never expanded into its digits.
    @Test
    void anExtremeExponentIsReadWithoutExpandingIt() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(new Quota(Integer.MAX_VALUE, 0, "DAY"), UsagePlanLimits.fromRequest(
                    request("quota", Map.of("limit", new BigDecimal("1e1000000000"), "period", "DAY"))).quota());
            assertEquals(new Quota(0, 0, "DAY"), UsagePlanLimits.fromRequest(
                    request("quota", Map.of("limit", new BigDecimal("1e-1000000000"), "period", "DAY"))).quota());
            assertRefused(() -> UsagePlanLimits.fromRequest(
                            request("quota", Map.of("limit", new BigDecimal("-1e-1000000000"), "period", "DAY"))),
                    "BadRequestException", 400, "Usage Plan quota limit must be a non-negative numeric");
        });
    }

    // CreateUsagePlan's input model: the service lists this with a missing name in one ValidationException.
    @Test
    void aPeriodOutsideTheEnumIsAnInputModelViolation() {
        assertEquals("Value 'YEAR' at 'createUsagePlanInput.quota.period' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [MONTH, WEEK, DAY]",
                UsagePlanLimits.periodViolation(request("quota", Map.of("limit", 1, "period", "YEAR"))));
        assertNull(UsagePlanLimits.periodViolation(request("quota", Map.of("limit", 1, "period", "DAY"))));
        assertNull(UsagePlanLimits.periodViolation(request("quota", Map.of("limit", 1))));
        assertNull(UsagePlanLimits.periodViolation(request("name", "p")));
    }

    // The storage backends persist plans with a plain ObjectMapper, so this is the shape on disk.
    @Test
    void aPlanSurvivesTheStorageJsonRoundTripAndOlderPlansStillLoad() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UsagePlan plan = new UsagePlan();
        plan.setId("p");
        plan.applyLimits(new Limits(List.of(new ApiStage("a", "v1", Map.of("/pets/GET", new Throttle(10, 5.0))),
                new ApiStage("a", "v2")), new Throttle(20, 10.0), new Quota(1000, 3, "MONTH")));

        String json = mapper.writeValueAsString(plan);
        assertFalse(json.contains("\"limits\""), json);
        assertEquals(plan.limits(), mapper.readValue(json, UsagePlan.class).limits());

        UsagePlan older = mapper.readValue(
                "{\"id\":\"p\",\"name\":\"n\",\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\"}]}", UsagePlan.class);
        assertEquals(new Limits(List.of(new ApiStage("a", "v1")), null, null), older.limits());
    }

    static Stream<Arguments> refusedCreates() {
        String throttle = "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
        String limit = "Usage Plan quota limit must be a non-negative numeric";
        return Stream.of(
                Arguments.of(Map.of("quota", Map.of("limit", 100)), "BadRequestException", 400,
                        "Invalid Usage Plan quota period specified. Must be one of [DAY, WEEK, MONTH]"),
                Arguments.of(Map.of("quota", Map.of("limit", 100, "period", "YEAR")), "ValidationException", 400,
                        "1 validation error detected: Value 'YEAR' at 'createUsagePlanInput.quota.period' failed to "
                                + "satisfy constraint: Member must satisfy enum value set: [MONTH, WEEK, DAY]"),
                // The input model's period check outranks every other check, measured on API Gateway.
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "YEAR"), "throttle", Map.of("burstLimit", -1)),
                        "ValidationException", 400,
                        "1 validation error detected: Value 'YEAR' at 'createUsagePlanInput.quota.period' failed to "
                                + "satisfy constraint: Member must satisfy enum value set: [MONTH, WEEK, DAY]"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "YEAR"), "apiStages", List.of(
                                Map.of("apiId", "a", "stage", "v1"), Map.of("apiId", "a", "stage", "v1"))),
                        "ValidationException", 400,
                        "1 validation error detected: Value 'YEAR' at 'createUsagePlanInput.quota.period' failed to "
                                + "satisfy constraint: Member must satisfy enum value set: [MONTH, WEEK, DAY]"),
                Arguments.of(Map.of("quota", Map.of("period", "DAY")), "BadRequestException", 400, limit),
                Arguments.of(Map.of("quota", Map.of("limit", -1, "period", "DAY")), "BadRequestException", 400, limit),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "DAY", "offset", 1)), "BadRequestException", 400,
                        "Usage Plan quota offset must be zero in the DAY period"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "WEEK", "offset", 7)), "BadRequestException", 400,
                        "Usage Plan quota offset must be between 0 and 6 inclusive in the WEEK period"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "WEEK", "offset", -1)), "BadRequestException", 400,
                        "Usage Plan quota offset must be between 0 and 6 inclusive in the WEEK period"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "MONTH", "offset", 28)), "BadRequestException",
                        400, "Usage Plan quota offset must be between 0 and 27 inclusive in the MONTH period"),
                Arguments.of(Map.of("throttle", Map.of("burstLimit", -1, "rateLimit", 1)), "BadRequestException", 400,
                        throttle),
                // Text is parsed as written, so a fraction is refused; a JSON number would be truncated.
                Arguments.of(Map.of("throttle", Map.of("burstLimit", "1.5")), "BadRequestException", 400, throttle),
                Arguments.of(Map.of("throttle", Map.of("rateLimit", "abc")), "BadRequestException", 400, throttle),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", "v1",
                                "throttle", Map.of("/pets/GET", Map.of("burstLimit", 1))))), "BadRequestException", 400,
                        "Can not define fine grain throttling if usage plan throttle is not defined."),
                Arguments.of(Map.of("throttle", Map.of("burstLimit", 1), "apiStages", List.of(Map.of("apiId", "a",
                                "stage", "v1", "throttle", Map.of("/pets/GET", Map.of("burstLimit", -1))))),
                        "BadRequestException", 400, throttle),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", "v1"),
                                Map.of("apiId", "a", "stage", "v1"))), "ConflictException", 409,
                        "API Stage is already defined for Usage Plan: a:v1"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("stage", "v1"))), "BadRequestException", 400,
                        "Invalid API Stage specified"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a"))), "BadRequestException", 400,
                        "Invalid API Stage specified"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "", "stage", "v1"))), "BadRequestException",
                        400, "Invalid API Stage specified"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", ""))), "BadRequestException",
                        400, "Invalid API Stage specified"),
                Arguments.of(stageThrottle("/GET"), "BadRequestException", 400,
                        "Invalid method {resourcePath: /GET,method: } specified"),
                Arguments.of(stageThrottle(""), "BadRequestException", 400,
                        "Invalid method {resourcePath: ,method: } specified"),
                Arguments.of(stageThrottle("/pets/"), "BadRequestException", 400,
                        "Invalid method {resourcePath: /pets/,method: } specified"),
                Arguments.of(stageThrottle("GET"), "BadRequestException", 400,
                        "Invalid method {resourcePath: GET,method: } specified"));
    }

    /** A create request with a plan throttle and stage a:v1 throttling {@code methodKey}. */
    private static Map<String, Object> stageThrottle(String methodKey) {
        return Map.of("throttle", Map.of("burstLimit", 20, "rateLimit", 10), "apiStages", List.of(Map.of("apiId", "a",
                "stage", "v1", "throttle", Map.of(methodKey, Map.of("burstLimit", 5, "rateLimit", 2)))));
    }

    // Floci does not check that the method exists, so keys AWS refuses for that reason are kept.
    @Test
    void aMethodKeyIsOnlyCheckedForHavingAMethod() {
        assertEquals(Map.of("//GET", new Throttle(5, 2.0)),
                UsagePlanLimits.fromRequest(stageThrottle("//GET")).apiStages().get(0).throttle());
        assertEquals(Map.of("/pets/GET/extra", new Throttle(5, 2.0)),
                UsagePlanLimits.fromRequest(stageThrottle("/pets/GET/extra")).apiStages().get(0).throttle());
    }

    @Test
    void jsonNullReadsAsAbsent() {
        assertEquals(new Limits(List.of(), null, null),
                UsagePlanLimits.fromRequest(request("throttle", null, "quota", null, "apiStages", null)));
        assertEquals(List.of(new ApiStage("a", "v1")), UsagePlanLimits.fromRequest(
                request("apiStages", List.of(request("apiId", "a", "stage", "v1", "throttle", null)))).apiStages());
    }

    @ParameterizedTest
    @MethodSource("refusedCreates")
    void createRefusesWhatApiGatewayRefuses(Map<String, Object> request, String code, int status, String message) {
        assertRefused(() -> UsagePlanLimits.fromRequest(request), code, status, message);
    }
}
