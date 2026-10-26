package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.params.provider.ValueSource;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsagePlanLimitsTest {

    // "apidId" is AWS's own spelling in these lists.
    private static final String ADD_PATHS = "[/apiStages, /productCode, /quota/period, /quota/limit, /quota/offset, "
            + "/throttle/rateLimit, /throttle/burstLimit, "
            + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/rateLimit, "
            + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/burstLimit, "
            + "/apiStages/<apidId:stageName>/throttle]";
    private static final String REPLACE_PATHS = "[/name, /description, /productCode, /quota/period, /quota/limit, "
            + "/quota/offset, /throttle/rateLimit, /throttle/burstLimit, "
            + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/rateLimit, "
            + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/burstLimit, "
            + "/apiStages/<apidId:stageName>/throttle]";
    private static final String REMOVE_PATHS = "[/apiStages, /quota, /throttle, /productCode, "
            + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>, "
            + "/apiStages/<apidId:stageName>/throttle]";

    private static final String INVALID_OP = "Invalid patch operation specified. Must be 'add'|'remove'|'replace'";

    /** API Gateway's message for a path the op does not allow; note the two spaces after "path". */
    private static String invalidPath(String op, String path, String allowed) {
        return "Invalid patch path  '" + path + "' specified for op '" + op + "'. Must be one of: " + allowed;
    }

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
        assertEquals(new Throttle(0, 1.0), patch(fixture(), "replace", "/apiStages/a:v1/throttle///GET/rateLimit", "1")
                .apiStages().get(0).throttle().get("//GET"));
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

    /** id plan1; stage a:v1 with /pets/GET at 10/5.0; plan throttle 20/10.0; quota 1000/3/MONTH. */
    private static UsagePlan fixture() {
        UsagePlan plan = new UsagePlan();
        plan.setId("plan1");
        plan.applyLimits(new Limits(List.of(new ApiStage("a", "v1", Map.of("/pets/GET", new Throttle(10, 5.0)))),
                new Throttle(20, 10.0), new Quota(1000, 3, "MONTH")));
        return plan;
    }

    /** Applies op/path/value triples (value may be null) the way updateUsagePlan does. */
    private static Limits patch(UsagePlan plan, String... opPathValue) {
        UsagePlanLimits.Patch patch = new UsagePlanLimits.Patch(plan);
        for (int i = 0; i < opPathValue.length; i += 3) {
            assertTrue(patch.apply(opPathValue[i], opPathValue[i + 1], opPathValue[i + 2]), opPathValue[i + 1]);
        }
        return patch.result();
    }

    @Test
    void addAndRemoveStagesInOneRequest() {
        Limits limits = patch(fixture(), "add", "/apiStages", "a:v2", "add", "/apiStages", "a:v3",
                "remove", "/apiStages", "a:v3");
        assertEquals(List.of(new ApiStage("a", "v1", Map.of("/pets/GET", new Throttle(10, 5.0))),
                new ApiStage("a", "v2")), limits.apiStages());
    }

    @Test
    void throttleFieldsCreateAPlanThrottleWhenThereIsNone() {
        UsagePlan plan = new UsagePlan();
        plan.setId("p");
        assertEquals(new Throttle(30, 0.0), patch(plan, "replace", "/throttle/burstLimit", "30").throttle());
        assertEquals(new Throttle(30, 15.0), patch(plan, "replace", "/throttle/burstLimit", "30",
                "add", "/throttle/rateLimit", "15").throttle());
    }

    @Test
    void aQuotaIsBuiltFromSeparateOpsAndRemovedWithoutAValue() {
        UsagePlan plan = new UsagePlan();
        plan.setId("p");
        assertEquals(new Quota(10, 0, "DAY"), patch(plan, "replace", "/quota/limit", "10",
                "replace", "/quota/period", "DAY").quota());
        assertNull(patch(fixture(), "replace", "/quota/limit", "5", "remove", "/quota", null).quota());
        assertNull(patch(plan, "remove", "/quota", null).quota());
        assertEquals(new Quota(2000, 1, "WEEK"), patch(fixture(), "replace", "/quota/limit", "2000",
                "replace", "/quota/period", "WEEK", "replace", "/quota/offset", "1").quota());
    }

    @Test
    void methodThrottleOpsUseTheRawResourcePathAsKey() {
        Limits limits = patch(fixture(),
                "replace", "/apiStages/a:v1/throttle//pets/GET/burstLimit", "8",
                "add", "/apiStages/a:v1/throttle//pets/POST/rateLimit", "3");
        assertEquals(Map.of("/pets/GET", new Throttle(8, 5.0), "/pets/POST", new Throttle(0, 3.0)),
                limits.apiStages().get(0).throttle());
    }

    @Test
    void removingTheLastMethodLeavesAnEmptyStageThrottle() {
        Limits limits = patch(fixture(), "remove", "/apiStages/a:v1/throttle//pets/GET", null);
        assertEquals(Map.of(), limits.apiStages().get(0).throttle());
        // An empty stage throttle is not fine-grain throttling, so the plan throttle can go.
        assertNull(patch(fixture(), "remove", "/apiStages/a:v1/throttle//pets/GET", null,
                "remove", "/throttle", null).throttle());
    }

    @Test
    void aWholeStageThrottleIsReplacedAndRemoved() {
        assertEquals(Map.of("/a/GET", new Throttle(1, 2.0)), patch(fixture(), "replace", "/apiStages/a:v1/throttle",
                "{\"/a/GET\":{\"burstLimit\":1,\"rateLimit\":2}}").apiStages().get(0).throttle());
        assertNull(patch(fixture(), "remove", "/apiStages/a:v1/throttle", null,
                "remove", "/apiStages/a:v1/throttle", null).apiStages().get(0).throttle());
    }

    @Test
    void aWholeStageThrottleIsReplacedOnlyWhereOneExists() {
        assertRefused(() -> patch(fixture(), "add", "/apiStages", "a:v2",
                        "replace", "/apiStages/a:v2/throttle", "{\"/pets/POST\":{\"burstLimit\":3,\"rateLimit\":2}}"),
                "NotFoundException", 404, "API Stage throttle not found for usageplan plan1");
        assertEquals(Map.of("/pets/POST", new Throttle(3, 2.0)), patch(fixture(), "add", "/apiStages", "a:v2",
                "add", "/apiStages/a:v2/throttle", "{\"/pets/POST\":{\"burstLimit\":3,\"rateLimit\":2}}")
                .apiStages().get(1).throttle());
    }

    @Test
    void aPlanThrottleMayPassThroughANegativeValueWithinOnePatch() {
        assertEquals(new Throttle(20, 5.0), patch(fixture(), "replace", "/throttle/rateLimit", "-1",
                "replace", "/throttle/rateLimit", "5").throttle());
        assertEquals(new Throttle(5, 10.0), patch(fixture(), "replace", "/throttle/burstLimit", "-1",
                "replace", "/throttle/burstLimit", "5").throttle());
    }

    @Test
    void aPlanThrottleIsNumericAtEachOpAndNonNegativeAtTheEnd() {
        String throttle = "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
        assertRefused(() -> patch(fixture(), "replace", "/throttle/rateLimit", "5",
                "replace", "/throttle/rateLimit", "-1"), "BadRequestException", 400, throttle);
        assertRefused(() -> patch(fixture(), "replace", "/throttle/rateLimit", "-1"), "BadRequestException", 400,
                throttle);
        UsagePlan plan = new UsagePlan();
        plan.setId("p");
        plan.applyLimits(new Limits(List.of(), new Throttle(20, 10.0), null));
        assertRefused(() -> patch(plan, "replace", "/throttle/burstLimit", "abc", "remove", "/throttle", null),
                "BadRequestException", 400, throttle);
        // A method throttle limit is checked in full at every op.
        assertRefused(() -> patch(fixture(), "replace", "/apiStages/a:v1/throttle//pets/GET/rateLimit", "-1",
                "replace", "/apiStages/a:v1/throttle//pets/GET/rateLimit", "5"), "BadRequestException", 400, throttle);
    }

    @Test
    void removingThePlanThrottleIsCheckedAgainstTheStagesAtThatOp() {
        assertRefused(() -> patch(fixture(), "remove", "/throttle", null, "replace", "/throttle/burstLimit", "5"),
                "BadRequestException", 400,
                "Can not remove usage plan throttling if fine grain throttling is defined for one or more api-stages.");
        Limits limits = patch(fixture(), "remove", "/apiStages", "a:v1", "remove", "/throttle", null);
        assertNull(limits.throttle());
        assertEquals(List.of(), limits.apiStages());
    }

    @Test
    void aWholeStageThrottleAddRefusesAKeyWithoutAMethod() {
        assertRefused(() -> patch(fixture(), "add", "/apiStages", "a:v2", "add", "/apiStages/a:v2/throttle",
                        "{\"\":{\"burstLimit\":1,\"rateLimit\":1}}"),
                "BadRequestException", 400, "Invalid method {resourcePath: ,method: } specified");
    }

    @Test
    void anEmptyWholeStageThrottleIsKeptAsAnEmptyMap() {
        assertEquals(Map.of(), patch(fixture(), "replace", "/apiStages/a:v1/throttle", "{}")
                .apiStages().get(0).throttle());
        assertEquals(Map.of(), patch(fixture(), "add", "/apiStages", "a:v2", "add", "/apiStages/a:v2/throttle", "{}")
                .apiStages().get(1).throttle());
    }

    @Test
    void aValuelessStageThrottleAddIsRefused() {
        assertRefused(() -> patch(fixture(), "add", "/apiStages", "a:v2", "add", "/apiStages/a:v2/throttle", null),
                "BadRequestException", 400, "Invalid API Stage throttle specified for usageplan");
    }

    @Test
    void nameAndDescriptionAreNotLimitPaths() {
        assertFalse(new UsagePlanLimits.Patch(fixture()).apply("replace", "/name", "x"));
        assertFalse(new UsagePlanLimits.Patch(fixture()).apply("replace", "/description", "x"));
    }

    static Stream<Arguments> refusedPatches() {
        String throttle = "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
        return Stream.of(
                Arguments.of("add", "/apiStages", "a:v1", "ConflictException", 409,
                        "API Stage is already defined for Usage Plan: a:v1"),
                Arguments.of("remove", "/apiStages", "a:v9", "NotFoundException", 404,
                        "Invalid API Stage {api: a, stage: v9} specified for usageplan plan1"),
                Arguments.of("add", "/apiStages", "bad", "BadRequestException", 400, "Invalid API Stage specified"),
                Arguments.of("replace", "/apiStages", "a:v2", "BadRequestException", 400,
                        invalidPath("replace", "/apiStages", REPLACE_PATHS)),
                Arguments.of("remove", "/throttle", null, "BadRequestException", 400,
                        "Can not remove usage plan throttling if fine grain throttling is defined for one or more "
                                + "api-stages."),
                Arguments.of("replace", "/throttle/burstLimit", "abc", "BadRequestException", 400, throttle),
                Arguments.of("replace", "/throttle/burstLimit", "-1", "BadRequestException", 400, throttle),
                Arguments.of("replace", "/throttle/burstLimit", "1.5", "BadRequestException", 400, throttle),
                Arguments.of("replace", "/quota/period", "YEAR", "BadRequestException", 400,
                        "Invalid Usage Plan quota period specified. Must be one of [DAY, WEEK, MONTH]"),
                Arguments.of("replace", "/quota/period", "DAY", "BadRequestException", 400,
                        "Usage Plan quota offset must be zero in the DAY period"),
                Arguments.of("replace", "/quota/offset", "x", "BadRequestException", 400,
                        "Usage Plan quota offset must be numeric and less than the number of time units in the period"),
                Arguments.of("replace", "/quota/limit", "-5", "BadRequestException", 400,
                        "Usage Plan quota limit must be a non-negative numeric"),
                Arguments.of("replace", "/quota/offset", null, "BadRequestException", 400,
                        "Usage Plan quota offset must be numeric and less than the number of time units in the period"),
                Arguments.of("add", "/apiStages", null, "BadRequestException", 400, "Invalid API Stage specified"),
                Arguments.of("replace", "/apiStages/a:v1/throttle", null, "BadRequestException", 400,
                        "Invalid API Stage throttle specified for usageplan"),
                // API Gateway answers this with a 500 InternalFailure, a fault rather than a contract, so it stays a 400.
                Arguments.of("replace", "/throttle/rateLimit", null, "BadRequestException", 400, throttle),
                Arguments.of("replace", "/quota", "x", "BadRequestException", 400,
                        invalidPath("replace", "/quota", REPLACE_PATHS)),
                Arguments.of("replace", "/throttle", "x", "BadRequestException", 400,
                        invalidPath("replace", "/throttle", REPLACE_PATHS)),
                Arguments.of("remove", "/throttle/rateLimit", null, "BadRequestException", 400,
                        invalidPath("remove", "/throttle/rateLimit", REMOVE_PATHS)),
                Arguments.of("add", "/quota", "x", "BadRequestException", 400, invalidPath("add", "/quota", ADD_PATHS)),
                // Any op but add, remove and replace is refused before the path is looked at.
                Arguments.of("move", "/quota/limit", "1", "BadRequestException", 400, INVALID_OP),
                Arguments.of("copy", "/apiStages", "a:v2", "BadRequestException", 400, INVALID_OP),
                Arguments.of("test", "/apiStages/a:v1/throttle", "{}", "BadRequestException", 400, INVALID_OP),
                Arguments.of(null, "/throttle", null, "BadRequestException", 400, INVALID_OP),
                Arguments.of("move", "/nope", "x", "BadRequestException", 400, INVALID_OP),
                Arguments.of("replace", "/apiStages/a:v2/throttle//pets/GET/rateLimit", "1", "NotFoundException", 404,
                        "Invalid API Stage {api: a, stage: v2} specified for usageplan plan1"),
                Arguments.of("add", "/apiStages/a:v1/throttle", "{\"/a/GET\":{\"burstLimit\":4}}", "ConflictException",
                        409, "API Stage throttle is already defined for usageplan plan1"),
                Arguments.of("replace", "/apiStages/a:v1/throttle", "not-json", "BadRequestException", 400,
                        "Invalid API Stage throttle specified for usageplan"),
                Arguments.of("replace", "/apiStages/a:v1/throttle", "{\"/a/GET\":5}", "BadRequestException", 400,
                        "Invalid API Stage throttle specified for usageplan"),
                Arguments.of("remove", "/apiStages/a:v1/throttle//pets/GET/rateLimit", null, "BadRequestException", 400,
                        invalidPath("remove", "/apiStages/a:v1/throttle//pets/GET/rateLimit", REMOVE_PATHS)),
                Arguments.of("remove", "/apiStages/a:v1/throttle//pets/GET/burstLimit", null, "BadRequestException",
                        400, invalidPath("remove", "/apiStages/a:v1/throttle//pets/GET/burstLimit", REMOVE_PATHS)),
                Arguments.of("remove", "/apiStages/a:v1/throttle//pets/POST", null, "NotFoundException", 404,
                        "Method throttle does not exist for {resourcePath: /pets, method: POST} for usageplan plan1"),
                Arguments.of("replace", "/apiStages/a:v1/throttle//pets/GET/rateLimit", "abc", "BadRequestException",
                        400, throttle),
                Arguments.of("replace", "/apiStages/a:v1/limits", "1", "BadRequestException", 400,
                        invalidPath("replace", "/apiStages/a:v1/limits", REPLACE_PATHS)),
                Arguments.of("replace", "/apiStages/a:v1/throttle", "{\"/GET\":{\"burstLimit\":1,\"rateLimit\":1}}",
                        "BadRequestException", 400, "Invalid method {resourcePath: /GET,method: } specified"),
                Arguments.of("replace", "/apiStages/a:v1/throttle//rateLimit", "1", "BadRequestException", 400,
                        invalidPath("replace", "/apiStages/a:v1/throttle//rateLimit", REPLACE_PATHS)),
                Arguments.of("replace", "/apiStages/a:v1/throttle/rateLimit", "1", "BadRequestException", 400,
                        invalidPath("replace", "/apiStages/a:v1/throttle/rateLimit", REPLACE_PATHS)),
                Arguments.of("add", "/apiStages/a:v1/throttle//GET/burstLimit", "1", "BadRequestException", 400,
                        invalidPath("add", "/apiStages/a:v1/throttle//GET/burstLimit", ADD_PATHS)),
                Arguments.of("replace", "/apiStages/a:v1/throttle//pets//rateLimit", "1", "BadRequestException", 400,
                        invalidPath("replace", "/apiStages/a:v1/throttle//pets//rateLimit", REPLACE_PATHS)));
    }

    @ParameterizedTest
    @MethodSource("refusedPatches")
    void patchRefusesWhatApiGatewayRefuses(String op, String path, String value, String code, int status,
                                           String message) {
        assertRefused(() -> patch(fixture(), op, path, value), code, status, message);
    }

    static Stream<Arguments> storedStageThrottleValues() {
        return Stream.of(
                Arguments.of("{\"burstLimit\":1.5,\"rateLimit\":2}", new Throttle(1, 2.0)),
                Arguments.of("{\"burstLimit\":1e1}", new Throttle(10, 0.0)),
                Arguments.of("{\"burstLimit\":2147483647}", new Throttle(Integer.MAX_VALUE, 0.0)),
                Arguments.of("{\"burstLimit\":\"7\"}", new Throttle(7, 0.0)),
                Arguments.of("{\"rateLimit\":\"5.5\"}", new Throttle(0, 5.5)),
                Arguments.of("{\"burstLimit\":1}", new Throttle(1, 0.0)),
                Arguments.of("{}", new Throttle(0, 0.0)),
                Arguments.of("{\"burstLimit\":null,\"rateLimit\":2}", new Throttle(0, 2.0)));
    }

    // Measured on API Gateway with a replace of /apiStages/<apiId>:<stage>/throttle.
    @ParameterizedTest
    @MethodSource("storedStageThrottleValues")
    void aStageThrottleValueIsReadAsApiGatewayReadsIt(String settings, Throttle stored) {
        assertEquals(Map.of("/pets/GET", stored), patch(fixture(), "replace", "/apiStages/a:v1/throttle",
                "{\"/pets/GET\":" + settings + "}").apiStages().get(0).throttle());
    }

    static Stream<Arguments> refusedStageThrottleValues() {
        String stageThrottle = "Invalid API Stage throttle specified for usageplan";
        String throttle = "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
        return Stream.of(
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":3000000000}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":-3000000000}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":\"7.5\"}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"rateLimit\":\"abc\"}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":true}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"rateLimit\":true}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":[]}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":1,\"rateLimit\":2,\"foo\":3}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":5}", stageThrottle),
                // API Gateway answers this with a 500 InternalFailure, a fault rather than a contract.
                Arguments.of("{\"/pets/GET\":{\"rateLimit\":1e400}}", stageThrottle),
                Arguments.of("{\"/pets/GET\":null}",
                        "Invalid throttle setting specified for method {resourcePath: /pets,method: GET}"),
                Arguments.of("{\"/pets/GET\":{\"burstLimit\":-1}}", throttle),
                Arguments.of("{\"/pets/GET\":{\"rateLimit\":-2}}", throttle),
                Arguments.of("{\"/GET\":{\"burstLimit\":1}}",
                        "Invalid method {resourcePath: /GET,method: } specified"));
    }

    @ParameterizedTest
    @MethodSource("refusedStageThrottleValues")
    void aStageThrottleValueApiGatewayCannotReadIsRefused(String value, String message) {
        assertRefused(() -> patch(fixture(), "replace", "/apiStages/a:v1/throttle", value), "BadRequestException",
                400, message);
    }

    // Measured on API Gateway: unlike a create body, a whole-stage throttle value is read through a double, so
    // a burst limit just under a whole number rounds up and a negative fraction truncates to 0.
    @Test
    void aStageThrottleValueReadsItsNumbersThroughADouble() {
        assertEquals(Map.of("/pets/GET", new Throttle(2, 2.0)),
                replacedStageThrottle("{\"/pets/GET\":{\"burstLimit\":1.99999999999999999,\"rateLimit\":2}}"));
        assertEquals(Map.of("/pets/GET", new Throttle(Integer.MAX_VALUE, 0.0)),
                replacedStageThrottle("{\"/pets/GET\":{\"burstLimit\":2147483647.0000001}}"));
        assertEquals(Map.of("/pets/GET", new Throttle(0, 0.0)),
                replacedStageThrottle("{\"/pets/GET\":{\"burstLimit\":-0.5}}"));
        assertRefused(() -> replacedStageThrottle("{\"/pets/GET\":{\"burstLimit\":2147483647.9}}"),
                "BadRequestException", 400, "Invalid API Stage throttle specified for usageplan");
    }

    /** The method throttles a replace of stage a:v1's whole throttle with {@code value} leaves. */
    private static Map<String, Throttle> replacedStageThrottle(String value) {
        return patch(fixture(), "replace", "/apiStages/a:v1/throttle", value).apiStages().get(0).throttle();
    }

    // A single limit is read as before: outside the int range it is an invalid throttle, not a stage throttle.
    @Test
    void aSingleBurstLimitOutsideTheIntRangeIsAnInvalidThrottle() {
        assertRefused(() -> patch(fixture(), "replace", "/apiStages/a:v1/throttle//pets/GET/burstLimit",
                        "3000000000"), "BadRequestException", 400,
                "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero");
    }

    // Measured on API Gateway: UpdateUsagePlan's input model lists every op outside its enum, by 1-based index.
    @Test
    void anOpOutsideTheModelsEnumIsAnInputModelViolation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode operations = mapper.readTree("[{\"op\":\"frobnicate\"},{\"op\":\"replace\"},"
                + "{\"op\":\"ADD\"},{\"path\":\"/name\"},{\"op\":\"\"},{\"op\":\"copy\"},{\"op\":null},null]");
        assertEquals(List.of(opViolation("frobnicate", 1), opViolation("ADD", 3), opViolation("", 5)),
                UsagePlanLimits.opViolations(operations));
        assertEquals(List.of(), UsagePlanLimits.opViolations(mapper.readTree("[]")));
        assertEquals(List.of(), UsagePlanLimits.opViolations(mapper.readTree("{}").path("patchOperations")));
    }

    private static String opViolation(String op, int index) {
        return "Value '" + op + "' at 'updateUsagePlanInput.patchOperations." + index + ".member.op' failed to satisfy "
                + "constraint: Member must satisfy enum value set: [add, remove, move, test, replace, copy]";
    }

    // Measured on API Gateway: a remove whose method throttle path names no method is an invalid path, as for
    // add and replace, before the key is looked up.
    @ParameterizedTest
    @ValueSource(strings = {"/nope", "//pets/", "/GET", "//GET", "/"})
    void removingAMethodThrottlePathThatNamesNoMethodIsAnInvalidPath(String suffix) {
        String path = "/apiStages/a:v1/throttle" + suffix;
        assertRefused(() -> patch(fixture(), "remove", path, null), "BadRequestException", 400,
                invalidPath("remove", path, REMOVE_PATHS));
    }

    @Test
    void removingAMethodThrottleThePlanLacksIsStillNotFound() {
        assertRefused(() -> patch(fixture(), "remove", "/apiStages/a:v1/throttle//pets/POST", null),
                "NotFoundException", 404,
                "Method throttle does not exist for {resourcePath: /pets, method: POST} for usageplan plan1");
    }

    @Test
    void aMethodThrottleNeedsAPlanThrottle() {
        UsagePlan plan = new UsagePlan();
        plan.setId("p");
        plan.applyLimits(new Limits(List.of(new ApiStage("a", "v1")), null, null));
        assertRefused(() -> patch(plan, "replace", "/apiStages/a:v1/throttle//pets/GET/rateLimit", "1"),
                "BadRequestException", 400, "Can not define fine grain throttling if usage plan throttle is not defined.");
        assertRefused(() -> patch(plan, "add", "/apiStages/a:v1/throttle",
                        "{\"/pets/GET\":{\"burstLimit\":3,\"rateLimit\":2}}"),
                "BadRequestException", 400, "Can not define fine grain throttling if usage plan throttle is not defined.");
    }
}
