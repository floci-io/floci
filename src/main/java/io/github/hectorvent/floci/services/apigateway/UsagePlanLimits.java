package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
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
 * A usage plan's API stages, throttle and quota: read from a {@code CreateUsagePlan} request and
 * patched by {@code UpdateUsagePlan}, with the errors API Gateway answers. Every message was
 * measured against API Gateway in eu-west-1.
 */
public final class UsagePlanLimits {

    /** In the model's order, which an input-model enum violation lists. */
    private static final List<String> PERIODS = List.of("MONTH", "WEEK", "DAY");
    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final String BAD_REQUEST = "BadRequestException";
    private static final String THROTTLE_INVALID =
            "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
    private static final String FINE_GRAIN_WITHOUT_PLAN_THROTTLE =
            "Can not define fine grain throttling if usage plan throttle is not defined.";
    private static final String QUOTA_OFFSET_NOT_NUMERIC =
            "Usage Plan quota offset must be numeric and less than the number of time units in the period";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STAGE_THROTTLE_INVALID = "Invalid API Stage throttle specified for usageplan";
    private static final String PLAN_THROTTLE_UNDER_FINE_GRAIN =
            "Can not remove usage plan throttling if fine grain throttling is defined for one or more api-stages.";
    private static final String STAGES_PREFIX = "/apiStages/";
    private static final String STAGE_THROTTLE = "/throttle";
    /** The input model's op enum, in the order its violation lists it. */
    private static final List<String> OP_ENUM = List.of("add", "remove", "move", "test", "replace", "copy");
    /** The paths UpdateUsagePlan allows per op, verbatim from API Gateway's error ("apidId" is AWS's own spelling). */
    private static final Map<String, String> PATCH_PATHS = Map.of(
            "add", "[/apiStages, /productCode, /quota/period, /quota/limit, /quota/offset, /throttle/rateLimit, "
                    + "/throttle/burstLimit, "
                    + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/rateLimit, "
                    + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/burstLimit, "
                    + "/apiStages/<apidId:stageName>/throttle]",
            "replace", "[/name, /description, /productCode, /quota/period, /quota/limit, /quota/offset, "
                    + "/throttle/rateLimit, /throttle/burstLimit, "
                    + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/rateLimit, "
                    + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/burstLimit, "
                    + "/apiStages/<apidId:stageName>/throttle]",
            "remove", "[/apiStages, /quota, /throttle, /productCode, "
                    + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>, "
                    + "/apiStages/<apidId:stageName>/throttle]");

    private UsagePlanLimits() {
    }

    /**
     * The stages, throttle and quota of a {@code CreateUsagePlan} request whose JSON types
     * {@link ApiGatewayRequestShapes} has checked, or of the same shape as CloudFormation's provisioner
     * builds it. A JSON number is read as API Gateway reads it, and text, which only CloudFormation's
     * template values send, is parsed as written.
     */
    public static Limits fromRequest(Map<String, Object> request) {
        Map<?, ?> quotaSettings = request.get("quota") instanceof Map<?, ?> settings ? settings : null;
        Object period = quotaSettings == null ? null : quotaSettings.get("period");
        String periodViolation = periodViolation(request);
        if (periodViolation != null) {
            // CreateUsagePlan's input model refuses an unknown period before any of API Gateway's own checks;
            // UpdateUsagePlan has no such model and answers with quota()'s message instead.
            throw SmithyValidationErrors.validationException(periodViolation);
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
     * CreateUsagePlan's input-model violation for a quota period outside the enum, as
     * {@link SmithyValidationErrors#notInEnum} words it, or null when the period is absent or valid.
     */
    static String periodViolation(Map<String, Object> request) {
        Object period = request.get("quota") instanceof Map<?, ?> settings ? settings.get("period") : null;
        if (period == null || PERIODS.contains(String.valueOf(period))) {
            return null;
        }
        return SmithyValidationErrors.notInEnum(String.valueOf(period), "createUsagePlanInput.quota.period", PERIODS);
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
                throw new AwsException(BAD_REQUEST, "Invalid method " + methodRef(resourcePathAndMethod) + " specified",
                        400);
            }
            methods.put(entry.getKey(), throttle(entry.getValue()));
        }
        return methods;
    }

    /** {@code {resourcePath: <path>,method: <method>}}, with no space after the comma: API Gateway's own formatting. */
    private static String methodRef(String[] resourcePathAndMethod) {
        return "{resourcePath: " + resourcePathAndMethod[0] + ",method: " + resourcePathAndMethod[1] + "}";
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
        int burst = numericBurstLimit(value);
        if (burst < 0) {
            throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
        }
        return burst;
    }

    static double rateLimit(Object value) {
        double rate = numericRateLimit(value);
        if (rate < 0) {
            throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
        }
        return rate;
    }

    /** A whole number, negative or not; the sign is the caller's to check. */
    static int numericBurstLimit(Object value) {
        Integer burst = value == null ? null : intValue(value);
        if (burst == null) {
            throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
        }
        return burst;
    }

    /** A finite number, negative or not; the sign is the caller's to check. */
    static double numericRateLimit(Object value) {
        Double rate = value == null ? null : doubleValue(value);
        if (rate == null) {
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
     * The stage, throttle and quota part of one {@code UpdateUsagePlan} request. Ops apply in order to
     * pending values and {@link #result()} validates what they add up to, the way API Gateway accepts a
     * quota built from separate limit and period ops. Nothing touches the plan itself.
     */
    public static final class Patch {

        private final String usagePlanId;
        private final List<ApiStage> stages;
        private Throttle throttle;
        private boolean hasQuota;
        private Object quotaLimit;
        private Object quotaOffset;
        private Object quotaPeriod;

        public Patch(UsagePlan plan) {
            usagePlanId = plan.getId();
            stages = new ArrayList<>(plan.getApiStages());
            throttle = plan.getThrottle();
            Quota quota = plan.getQuota();
            hasQuota = quota != null;
            if (hasQuota) {
                quotaLimit = quota.limit();
                quotaOffset = quota.offset();
                quotaPeriod = quota.period();
            }
        }

        /** Applies one op; false when {@code path} is not a stage, throttle or quota path. */
        public boolean apply(String op, String path, String value) {
            requirePatchOp(op);
            if ("/apiStages".equals(path)) {
                applyStage(op, value);
            } else if (path.startsWith(STAGES_PREFIX)) {
                applyStageThrottle(op, path, value);
            } else if ("/throttle".equals(path) || path.startsWith("/throttle/")) {
                applyThrottle(op, path, value);
            } else if ("/quota".equals(path) || path.startsWith("/quota/")) {
                applyQuota(op, path, value);
            } else {
                return false;
            }
            return true;
        }

        public Limits result() {
            Quota quota = hasQuota ? quota(quotaLimit, quotaOffset, quotaPeriod) : null;
            // A plan throttle field may go negative between ops, as on API Gateway; only the outcome must not.
            if (throttle != null && (throttle.burstLimit() < 0 || throttle.rateLimit() < 0)) {
                throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
            }
            if (throttle == null && hasMethodThrottles(stages)) {
                throw new AwsException(BAD_REQUEST, FINE_GRAIN_WITHOUT_PLAN_THROTTLE, 400);
            }
            return new Limits(stages, throttle, quota);
        }

        private void applyStage(String op, String value) {
            if (!"add".equals(op) && !"remove".equals(op)) {
                throw invalidPatchPath(op, "/apiStages");
            }
            String[] ref = stageRef(value);
            if ("add".equals(op)) {
                if (indexOf(stages, ref[0], ref[1]) >= 0) {
                    throw alreadyDefined(ref[0], ref[1]);
                }
                stages.add(new ApiStage(ref[0], ref[1]));
            } else {
                stages.remove(stageIndex(ref));
            }
        }

        private void applyStageThrottle(String op, String path, String value) {
            String rest = path.substring(STAGES_PREFIX.length());
            int slash = rest.indexOf('/');
            String suffix = slash < 0 ? "" : rest.substring(slash);
            if (!STAGE_THROTTLE.equals(suffix) && !suffix.startsWith(STAGE_THROTTLE + "/")) {
                throw invalidPatchPath(op, path);
            }
            // A method throttle comes off whole: remove takes no single rateLimit or burstLimit of it.
            String last = path.substring(path.lastIndexOf('/') + 1);
            if ("remove".equals(op) && suffix.startsWith(STAGE_THROTTLE + "/")
                    && ("rateLimit".equals(last) || "burstLimit".equals(last))) {
                throw invalidPatchPath(op, path);
            }
            int index = stageIndex(stageRef(rest.substring(0, slash)));
            ApiStage stage = stages.get(index);
            Map<String, Throttle> methods = stage.throttle() == null ? null : new LinkedHashMap<>(stage.throttle());
            if (STAGE_THROTTLE.equals(suffix)) {
                methods = switch (op == null ? "" : op) {
                    case "add" -> {
                        if (methods != null) {
                            throw new AwsException("ConflictException",
                                    "API Stage throttle is already defined for usageplan " + usagePlanId, 409);
                        }
                        yield methodThrottles(value);
                    }
                    case "replace" -> {
                        if (methods == null) {
                            throw new AwsException("NotFoundException",
                                    "API Stage throttle not found for usageplan " + usagePlanId, 404);
                        }
                        yield methodThrottles(value);
                    }
                    case "remove" -> null;
                    default -> throw invalidPatchPath(op, path);
                };
            } else {
                methods = patchMethodThrottle(methods, op, path, suffix.substring(STAGE_THROTTLE.length() + 1), value);
            }
            stages.set(index, new ApiStage(stage.apiId(), stage.stage(), methods));
        }

        /**
         * {@code target} is {@code <resourcePath>/<httpMethod>[/<limit>]}; the key is the raw text, so
         * {@code //pets/GET/rateLimit} after {@code /throttle} names {@code /pets/GET} and {@code ~1} is
         * not decoded, as on API Gateway.
         */
        private Map<String, Throttle> patchMethodThrottle(Map<String, Throttle> methods, String op, String path,
                                                          String target, String value) {
            Map<String, Throttle> patched = methods == null ? new LinkedHashMap<>() : methods;
            int slash = target.lastIndexOf('/');
            if ("remove".equals(op)) {
                // As for add and replace, a path that names no method is invalid, before any key is looked up.
                if (methodKey(target)[1].isEmpty()) {
                    throw invalidPatchPath(op, path);
                }
                if (patched.remove(target) == null) {
                    throw new AwsException("NotFoundException", "Method throttle does not exist for {resourcePath: "
                            + (slash < 0 ? target : target.substring(0, slash)) + ", method: "
                            + (slash < 0 ? "" : target.substring(slash + 1)) + "} for usageplan " + usagePlanId, 404);
                }
                return patched;
            }
            requireAddOrReplace(op, path);
            String field = slash < 0 ? "" : target.substring(slash + 1);
            if (!"burstLimit".equals(field) && !"rateLimit".equals(field)) {
                throw invalidPatchPath(op, path);
            }
            String key = target.substring(0, slash);
            if (methodKey(key)[1].isEmpty()) {
                throw invalidPatchPath(op, path);
            }
            Throttle current = patched.getOrDefault(key, new Throttle(0, 0.0));
            patched.put(key, "burstLimit".equals(field)
                    ? new Throttle(burstLimit(value), current.rateLimit())
                    : new Throttle(current.burstLimit(), rateLimit(value)));
            return patched;
        }

        private void applyThrottle(String op, String path, String value) {
            switch (path) {
                case "/throttle" -> {
                    if (!"remove".equals(op)) {
                        throw invalidPatchPath(op, path);
                    }
                    // Checked against the stages as they stand at this op, not at the end of the patch.
                    if (hasMethodThrottles(stages)) {
                        throw new AwsException(BAD_REQUEST, PLAN_THROTTLE_UNDER_FINE_GRAIN, 400);
                    }
                    throttle = null;
                }
                case "/throttle/burstLimit", "/throttle/rateLimit" -> {
                    requireAddOrReplace(op, path);
                    Throttle current = throttle == null ? new Throttle(0, 0.0) : throttle;
                    throttle = path.endsWith("burstLimit")
                            ? new Throttle(numericBurstLimit(value), current.rateLimit())
                            : new Throttle(current.burstLimit(), numericRateLimit(value));
                }
                default -> throw invalidPatchPath(op, path);
            }
        }

        private void applyQuota(String op, String path, String value) {
            if ("/quota".equals(path)) {
                if (!"remove".equals(op)) {
                    throw invalidPatchPath(op, path);
                }
                hasQuota = false;
                quotaLimit = null;
                quotaOffset = null;
                quotaPeriod = null;
                return;
            }
            requireAddOrReplace(op, path);
            switch (path) {
                case "/quota/limit" -> quotaLimit = value;
                case "/quota/offset" -> {
                    // Without a value API Gateway refuses the op rather than resetting the offset to 0.
                    if (value == null) {
                        throw new AwsException(BAD_REQUEST, QUOTA_OFFSET_NOT_NUMERIC, 400);
                    }
                    quotaOffset = value;
                }
                case "/quota/period" -> quotaPeriod = value;
                default -> throw invalidPatchPath(op, path);
            }
            hasQuota = true;
        }

        /** The index of the plan's stage named by {@code ref}, or API Gateway's 404 when the plan has none. */
        private int stageIndex(String[] ref) {
            int index = indexOf(stages, ref[0], ref[1]);
            if (index < 0) {
                throw new AwsException("NotFoundException", "Invalid API Stage {api: " + ref[0] + ", stage: "
                        + ref[1] + "} specified for usageplan " + usagePlanId, 404);
            }
            return index;
        }
    }

    /**
     * A {@code /apiStages/<apiId>:<stage>/throttle} value: a JSON map of method key to limits, read as
     * API Gateway reads it, which is not how a create body's numbers are read. A burst limit takes a
     * JSON number in int range, truncated, or whole-number text; a rate limit takes a finite number or
     * numeric text. Anything else, an unknown member included, refuses the whole value. Every value is
     * read before any key is checked, then each key's method before its limits and their sign.
     */
    private static Map<String, Throttle> methodThrottles(String json) {
        JsonNode root;
        try {
            root = json == null ? null : JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw stageThrottleInvalid();
        }
        if (root == null || !root.isObject()) {
            throw stageThrottleInvalid();
        }
        Map<String, Throttle> read = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            JsonNode settings = entry.getValue();
            if (!settings.isNull() && !settings.isObject()) {
                throw stageThrottleInvalid();
            }
            read.put(entry.getKey(), settings.isNull() ? null : stageThrottle(settings));
        }
        Map<String, Throttle> methods = new LinkedHashMap<>();
        for (Map.Entry<String, Throttle> entry : read.entrySet()) {
            String[] resourcePathAndMethod = methodKey(entry.getKey());
            if (resourcePathAndMethod[1].isEmpty()) {
                throw new AwsException(BAD_REQUEST, "Invalid method " + methodRef(resourcePathAndMethod) + " specified",
                        400);
            }
            Throttle throttle = entry.getValue();
            if (throttle == null) {
                throw new AwsException(BAD_REQUEST,
                        "Invalid throttle setting specified for method " + methodRef(resourcePathAndMethod), 400);
            }
            if (throttle.burstLimit() < 0 || throttle.rateLimit() < 0) {
                throw new AwsException(BAD_REQUEST, THROTTLE_INVALID, 400);
            }
            methods.put(entry.getKey(), throttle);
        }
        return methods;
    }

    /** One method's limits in a stage throttle value, sign unchecked; a limit left out or null reads 0. */
    private static Throttle stageThrottle(JsonNode settings) {
        for (Map.Entry<String, JsonNode> member : settings.properties()) {
            if (!"burstLimit".equals(member.getKey()) && !"rateLimit".equals(member.getKey())) {
                throw stageThrottleInvalid();
            }
        }
        JsonNode burst = settings.path("burstLimit");
        JsonNode rate = settings.path("rateLimit");
        return new Throttle(burst.isMissingNode() || burst.isNull() ? 0 : stageBurstLimit(burst),
                rate.isMissingNode() || rate.isNull() ? 0.0 : stageRateLimit(rate));
    }

    private static int stageBurstLimit(JsonNode value) {
        if (value.isNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.textValue());
            } catch (NumberFormatException e) {
                throw stageThrottleInvalid();
            }
        }
        throw stageThrottleInvalid();
    }

    private static double stageRateLimit(JsonNode value) {
        double rate;
        if (value.isNumber()) {
            rate = value.doubleValue();
        } else if (value.isTextual()) {
            try {
                rate = Double.parseDouble(value.textValue());
            } catch (NumberFormatException e) {
                throw stageThrottleInvalid();
            }
        } else {
            throw stageThrottleInvalid();
        }
        // API Gateway answers a rate beyond the double range, such as 1e400, with a 500 InternalFailure, a
        // fault rather than a contract; Floci refuses it as an invalid stage throttle instead.
        if (!Double.isFinite(rate)) {
            throw stageThrottleInvalid();
        }
        return rate;
    }

    private static AwsException stageThrottleInvalid() {
        return new AwsException(BAD_REQUEST, STAGE_THROTTLE_INVALID, 400);
    }

    /** {@code apiId:stage} split in two, or API Gateway's 400 for anything else. */
    private static String[] stageRef(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", 2);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new AwsException(BAD_REQUEST, "Invalid API Stage specified", 400);
        }
        return parts;
    }

    private static void requireAddOrReplace(String op, String path) {
        if (!"add".equals(op) && !"replace".equals(op)) {
            throw invalidPatchPath(op, path);
        }
    }

    /**
     * UpdateUsagePlan's input-model violations, one per op outside the model's enum, each at its
     * 1-based index, as API Gateway lists them before its own op check. A missing or null op is not
     * one; {@link #requirePatchOp} refuses it.
     */
    public static List<String> opViolations(JsonNode patchOperations) {
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < patchOperations.size(); i++) {
            JsonNode op = patchOperations.get(i).path("op");
            if (op.isTextual() && !OP_ENUM.contains(op.textValue())) {
                violations.add(SmithyValidationErrors.notInEnum(op.textValue(),
                        "updateUsagePlanInput.patchOperations." + (i + 1) + ".member.op", OP_ENUM));
            }
        }
        return violations;
    }

    /** API Gateway refuses any op but add, remove and replace, a missing one included, before it reads the path. */
    public static void requirePatchOp(String op) {
        if (op == null || !PATCH_PATHS.containsKey(op)) {
            throw invalidPatchOp();
        }
    }

    private static AwsException invalidPatchOp() {
        return new AwsException(BAD_REQUEST, "Invalid patch operation specified. Must be 'add'|'remove'|'replace'", 400);
    }

    /** API Gateway's 400 for a path {@code op} does not allow, listing the paths it does. */
    public static AwsException invalidPatchPath(String op, String path) {
        String allowed = op == null ? null : PATCH_PATHS.get(op);
        if (allowed == null) {
            return invalidPatchOp();
        }
        // Two spaces after "path", as API Gateway writes it.
        return new AwsException(BAD_REQUEST,
                "Invalid patch path  '" + path + "' specified for op '" + op + "'. Must be one of: " + allowed, 400);
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
