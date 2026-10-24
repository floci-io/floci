package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.ApiStage;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Limits;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Quota;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Throttle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A usage plan's API stages, throttle and quota: read from a {@code CreateUsagePlan} request, with
 * the errors API Gateway answers. Every message was measured against API Gateway in eu-west-1.
 */
public final class UsagePlanLimits {

    private static final List<String> PERIODS = List.of("DAY", "WEEK", "MONTH");
    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final String BAD_REQUEST = "BadRequestException";
    private static final String THROTTLE_INVALID =
            "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
    private static final String FINE_GRAIN_WITHOUT_PLAN_THROTTLE =
            "Can not define fine grain throttling if usage plan throttle is not defined.";
    private static final String QUOTA_OFFSET_NOT_NUMERIC =
            "Usage Plan quota offset must be numeric and less than the number of time units in the period";

    private UsagePlanLimits() {
    }

    /**
     * The stages, throttle and quota of a {@code CreateUsagePlan} request whose JSON types
     * {@link ApiGatewayRequestShapes} has checked. A JSON number is read as API Gateway reads it, and
     * text, which only CloudFormation's template values send, is parsed as written.
     */
    public static Limits fromRequest(Map<String, Object> request) {
        Map<?, ?> quotaSettings = request.get("quota") instanceof Map<?, ?> settings ? settings : null;
        Object period = quotaSettings == null ? null : quotaSettings.get("period");
        String periodViolation = periodViolation(request);
        if (periodViolation != null) {
            // CreateUsagePlan's input model refuses an unknown period before any of API Gateway's own checks.
            throw new AwsException("ValidationException", "1 validation error detected: " + periodViolation, 400);
        }
        Throttle throttle = request.get("throttle") instanceof Map<?, ?> settings ? throttle(settings) : null;
        Quota quota = quotaSettings == null
                ? null
                : quota(quotaSettings.get("limit"), quotaSettings.get("offset"), period);
        List<ApiStage> stages = new ArrayList<>();
        if (request.get("apiStages") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> stage)) {
                    throw new AwsException(BAD_REQUEST, "Invalid API Stage specified", 400);
                }
                String apiId = stage.get("apiId") == null ? null : String.valueOf(stage.get("apiId"));
                String stageName = stage.get("stage") == null ? null : String.valueOf(stage.get("stage"));
                if (apiId == null || apiId.isEmpty() || stageName == null || stageName.isEmpty()) {
                    throw new AwsException(BAD_REQUEST, "Invalid API Stage specified", 400);
                }
                if (indexOf(stages, apiId, stageName) >= 0) {
                    throw alreadyDefined(apiId, stageName);
                }
                // A stage throttle with no methods is dropped on create, as API Gateway does.
                Map<String, Throttle> methods = stage.get("throttle") instanceof Map<?, ?> raw && !raw.isEmpty()
                        ? methodThrottles(raw, THROTTLE_INVALID)
                        : null;
                stages.add(new ApiStage(apiId, stageName, methods));
            }
        }
        if (throttle == null && hasMethodThrottles(stages)) {
            throw new AwsException(BAD_REQUEST, FINE_GRAIN_WITHOUT_PLAN_THROTTLE, 400);
        }
        return new Limits(stages, throttle, quota);
    }

    /**
     * CreateUsagePlan's input-model violation for a quota period outside the enum, without the
     * {@code N validation error(s) detected:} prefix, or null when the period is absent or valid.
     */
    static String periodViolation(Map<String, Object> request) {
        Object period = request.get("quota") instanceof Map<?, ?> settings ? settings.get("period") : null;
        if (period == null || PERIODS.contains(String.valueOf(period))) {
            return null;
        }
        return "Value '" + period + "' at 'createUsagePlanInput.quota.period' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [MONTH, WEEK, DAY]";
    }

    /** A field API Gateway leaves out reads 0. */
    static Throttle throttle(Map<?, ?> settings) {
        Object burst = settings.get("burstLimit");
        Object rate = settings.get("rateLimit");
        return new Throttle(burst == null ? 0 : burstLimit(burst), rate == null ? 0.0 : rateLimit(rate));
    }

    /** Every value's shape is checked before any key, then each key before its limits. */
    static Map<String, Throttle> methodThrottles(Map<?, ?> raw, String invalidMessage) {
        Map<String, Map<?, ?>> settingsByKey = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> settings)) {
                throw new AwsException(BAD_REQUEST, invalidMessage, 400);
            }
            settingsByKey.put(String.valueOf(entry.getKey()), settings);
        }
        Map<String, Throttle> methods = new LinkedHashMap<>();
        for (Map.Entry<String, Map<?, ?>> entry : settingsByKey.entrySet()) {
            String[] resourcePathAndMethod = methodKey(entry.getKey());
            if (resourcePathAndMethod[1].isEmpty()) {
                // No space after the comma: API Gateway's own formatting.
                throw new AwsException(BAD_REQUEST, "Invalid method {resourcePath: " + resourcePathAndMethod[0]
                        + ",method: " + resourcePathAndMethod[1] + "} specified", 400);
            }
            methods.put(entry.getKey(), throttle(entry.getValue()));
        }
        return methods;
    }

    /**
     * {@code [resourcePath, method]} of a method throttle key, split as API Gateway splits it: at the
     * last slash, unless that slash leads or ends the key, which leaves the method empty. Floci does
     * not check that the method exists, so {@code //GET} and {@code /pets/GET/extra} pass.
     */
    static String[] methodKey(String key) {
        int slash = key.lastIndexOf('/');
        if (slash <= 0 || slash == key.length() - 1) {
            return new String[] {key, ""};
        }
        return new String[] {key.substring(0, slash), key.substring(slash + 1)};
    }

    /** Validated in API Gateway's order: period, then limit, then offset. */
    static Quota quota(Object limit, Object offset, Object period) {
        String periodName = period == null ? null : String.valueOf(period);
        if (periodName == null || !PERIODS.contains(periodName)) {
            throw new AwsException(BAD_REQUEST,
                    "Invalid Usage Plan quota period specified. Must be one of [DAY, WEEK, MONTH]", 400);
        }
        Integer parsedLimit = limit == null ? null : intValue(limit);
        if (parsedLimit == null || parsedLimit < 0) {
            throw new AwsException(BAD_REQUEST, "Usage Plan quota limit must be a non-negative numeric", 400);
        }
        int parsedOffset = 0;
        if (offset != null) {
            Integer value = intValue(offset);
            if (value == null) {
                throw new AwsException(BAD_REQUEST, QUOTA_OFFSET_NOT_NUMERIC, 400);
            }
            parsedOffset = value;
        }
        int units = switch (periodName) {
            case "WEEK" -> 7;
            case "MONTH" -> 28;
            default -> 1;
        };
        if (parsedOffset < 0 || parsedOffset >= units) {
            throw new AwsException(BAD_REQUEST, units == 1
                    ? "Usage Plan quota offset must be zero in the DAY period"
                    : "Usage Plan quota offset must be between 0 and " + (units - 1) + " inclusive in the "
                            + periodName + " period", 400);
        }
        return new Quota(parsedLimit, parsedOffset, periodName);
    }

    static int burstLimit(Object value) {
        Integer burst = value == null ? null : intValue(value);
        if (burst == null || burst < 0) {
            throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
        }
        return burst;
    }

    static double rateLimit(Object value) {
        Double rate = value == null ? null : doubleValue(value);
        if (rate == null || rate < 0) {
            throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
        }
        return rate;
    }

    static int indexOf(List<ApiStage> stages, String apiId, String stage) {
        for (int i = 0; i < stages.size(); i++) {
            if (Objects.equals(stages.get(i).apiId(), apiId) && Objects.equals(stages.get(i).stage(), stage)) {
                return i;
            }
        }
        return -1;
    }

    static boolean hasMethodThrottles(List<ApiStage> stages) {
        return stages.stream().anyMatch(stage -> stage.throttle() != null && !stage.throttle().isEmpty());
    }

    static AwsException alreadyDefined(String apiId, String stage) {
        return new AwsException("ConflictException",
                "API Stage is already defined for Usage Plan: " + apiId + ":" + stage, 409);
    }

    /**
     * {@code value} as an int, or null when it is not one. A number is read from its decimal digits,
     * rounded toward negative infinity and clamped to the int range, as API Gateway reads a JSON
     * number; text must be a whole number in int range.
     */
    private static Integer intValue(Object value) {
        try {
            if (value instanceof Number number) {
                return floorToInt(new BigDecimal(number.toString()));
            }
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException expected) {
            // The caller reports a non-integer, or a NaN or infinite double, with API Gateway's message for the field.
            return null;
        }
    }

    /**
     * {@code value} rounded toward negative infinity and clamped to the int range. The bounds and a
     * fraction below one are settled before any rounding, so an exponent such as {@code 1e1000000000}
     * or {@code 1e-1000000000} is never expanded into its digits.
     */
    private static int floorToInt(BigDecimal value) {
        if (value.compareTo(INT_MAX) >= 0) {
            return Integer.MAX_VALUE;
        }
        if (value.compareTo(INT_MIN) <= 0) {
            return Integer.MIN_VALUE;
        }
        if (value.abs().compareTo(BigDecimal.ONE) < 0) {
            return value.signum() < 0 ? -1 : 0;
        }
        // FLOOR: positive values truncate and negative ones stay negative, so they are refused, as on API Gateway.
        return value.setScale(0, RoundingMode.FLOOR).intValue();
    }

    /** {@code value} as a finite double, or null when it is not one. */
    private static Double doubleValue(Object value) {
        try {
            double parsed = value instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException expected) {
            // The caller reports a non-number with API Gateway's message for the field.
            return null;
        }
    }
}
