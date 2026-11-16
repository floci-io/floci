package io.floci.aws.services.xray;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.xray.model.SamplingRule;
import io.floci.aws.services.xray.model.Trace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The X-Ray trace store: the segments a client sent, grouped by trace, read back as they were sent.
 *
 * <p>A segment document is AWS's own JSON and Floci keeps it verbatim, because {@code BatchGetTraces}
 * must answer exactly what {@code PutTraceSegments} accepted. Only the identifiers and timing are
 * parsed, for retention and for the summaries and service graph computed from the documents.
 *
 * <p>A local emulator runs for days, so the store is bounded: at most {@link #MAX_TRACES} traces and
 * nothing older than {@link #RETENTION_SECONDS}, the same 30 day window AWS keeps, with the oldest
 * trace evicted first.
 */
@ApplicationScoped
public class XrayService implements Resettable {

    static final String SERVICE = "xray";

    private static final Logger LOG = Logger.getLogger(XrayService.class);
    private static final Pattern TRACE_ID = Pattern.compile("(1-)?[0-9a-f]{8}-[0-9a-f]{24}|[0-9a-f]{32}");
    private static final Pattern SEGMENT_ID = Pattern.compile("[0-9a-f]{16}");
    private static final int MAX_TRACES = 10_000;
    private static final long RETENTION_SECONDS = 30L * 24 * 60 * 60;
    private static final int MAX_DOCUMENTS_PER_CALL = 100;
    private static final int MAX_TRACE_IDS_PER_CALL = 5;
    /** AWS caps a summaries page at 100 trace summaries. */
    private static final int MAX_SUMMARIES_PAGE = 100;

    private final AccountAwareStorageBackend<Trace> traces;
    private final AccountAwareStorageBackend<SamplingRule> rules;
    /**
     * A monitor per Region. The stores are read-modify-write over a documented-heap backend, so two
     * services posting segments of the same trace would otherwise drop one, and two creates of the
     * same rule would both pass the duplicate check. Keyed by Region because that is the scope both
     * operations are counted in.
     */
    private final ConcurrentHashMap<String, Object> regionLocks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public XrayService(StorageFactory storageFactory) {
        this(storageFactory.create(SERVICE, "xray-traces.json",
                        new TypeReference<Map<String, Trace>>() {}),
                storageFactory.create(SERVICE, "xray-sampling-rules.json",
                        new TypeReference<Map<String, SamplingRule>>() {}));
    }

    XrayService(AccountAwareStorageBackend<Trace> traces, AccountAwareStorageBackend<SamplingRule> rules) {
        this.traces = traces;
        this.rules = rules;
    }

    /** One document X-Ray refused, with the code and message it reports for it. */
    public record UnprocessedSegment(String id, String errorCode, String message) {
    }

    /** The traces a caller asked for, and the ids that named nothing. */
    public record BatchGetResult(List<Trace> traces, List<String> unprocessedIds) {
    }

    // ------------------------------------------------------------------ ingest

    public List<UnprocessedSegment> putTraceSegments(List<String> documents, String region) {
        if (documents == null || documents.isEmpty()) {
            throw validation("TraceSegmentDocuments is required.");
        }
        if (documents.size() > MAX_DOCUMENTS_PER_CALL) {
            throw validation("A PutTraceSegments request carries at most " + MAX_DOCUMENTS_PER_CALL
                    + " segments, got " + documents.size() + ".");
        }
        List<UnprocessedSegment> unprocessed = new ArrayList<>();
        synchronized (lockFor(region)) {
            storeDocuments(documents, region, unprocessed);
        }
        LOG.debugv("Stored {0} of {1} segments", documents.size() - unprocessed.size(), documents.size());
        return unprocessed;
    }

    private void storeDocuments(List<String> documents, String region, List<UnprocessedSegment> unprocessed) {
        for (String document : documents) {
            JsonNode node;
            try {
                node = objectMapper.readTree(document);
            } catch (Exception e) {
                unprocessed.add(new UnprocessedSegment("", "InvalidSegment",
                        "The segment document is not valid JSON."));
                continue;
            }
            String problem = validationProblem(node);
            if (problem != null) {
                unprocessed.add(new UnprocessedSegment(node.path("id").asText(""), "InvalidSegment", problem));
                continue;
            }
            store(node, document, region);
        }
        evict(region);
    }

    private static String validationProblem(JsonNode node) {
        if (!node.isObject()) {
            return "The segment document must be a JSON object.";
        }
        String traceId = node.path("trace_id").asText("");
        if (!TRACE_ID.matcher(traceId).matches()) {
            return "trace_id must be a 96 bit hex identifier, got " + traceId + ".";
        }
        String id = node.path("id").asText("");
        if (!SEGMENT_ID.matcher(id).matches()) {
            return "id must be a 64 bit hex identifier, got " + id + ".";
        }
        if (node.path("name").asText("").isEmpty()) {
            return "name is required.";
        }
        if (!node.path("start_time").isNumber()) {
            return "start_time must be a number of epoch seconds.";
        }
        // A segment still in flight has no end_time yet, which AWS accepts: refusing it would make
        // every long-running subsegment unrecordable.
        if (node.has("end_time")) {
            if (!node.path("end_time").isNumber()) {
                return "end_time must be a number of epoch seconds.";
            }
            if (node.path("end_time").asDouble() < node.path("start_time").asDouble()) {
                return "end_time is before start_time.";
            }
        }
        return null;
    }

    private void store(JsonNode document, String raw, String region) {
        String traceId = document.path("trace_id").asText();
        double start = document.path("start_time").asDouble();
        double end = document.has("end_time") ? document.path("end_time").asDouble() : start;
        Trace trace = traces.get(storageKey(region, traceId)).orElse(null);
        if (trace == null) {
            trace = new Trace();
            trace.setId(traceId);
            trace.setStartTime(start);
            trace.setEndTime(end);
        } else {
            trace.setStartTime(Math.min(trace.getStartTime(), start));
            trace.setEndTime(Math.max(trace.getEndTime(), end));
        }
        // The list a reader holds must not change under it: summaries and BatchGetTraces walk the
        // documents of a stored trace while segments keep arriving, so this builds the new list
        // and swaps it in rather than editing the one already published.
        String segmentId = document.path("id").asText();
        List<String> documents = new ArrayList<>();
        for (String stored : trace.getDocuments()) {
            if (!segmentId.equals(segmentIdOf(stored))) {
                documents.add(stored);
            }
        }
        documents.add(raw);
        trace.setDocuments(documents);
        if (document.path("fault").asBoolean(false)) {
            trace.setFault(true);
        }
        if (document.path("error").asBoolean(false)) {
            trace.setError(true);
        }
        if (document.path("throttle").asBoolean(false)) {
            trace.setThrottle(true);
        }
        traces.put(storageKey(region, traceId), trace);
    }

    // ------------------------------------------------------------------- read

    public BatchGetResult batchGetTraces(List<String> traceIds, String region) {
        if (traceIds == null || traceIds.isEmpty()) {
            throw validation("TraceIds is required.");
        }
        if (traceIds.size() > MAX_TRACE_IDS_PER_CALL) {
            throw validation("A BatchGetTraces request carries at most " + MAX_TRACE_IDS_PER_CALL
                    + " trace ids, got " + traceIds.size() + ".");
        }
        long oldest = Instant.now().getEpochSecond() - RETENTION_SECONDS;
        List<Trace> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String traceId : traceIds) {
            Trace trace = traces.get(storageKey(region, traceId)).orElse(null);
            if (trace == null || trace.getEndTime() < oldest) {
                missing.add(traceId);
                continue;
            }
            found.add(trace);
        }
        return new BatchGetResult(found, missing);
    }

    /** The traces a service holds in a region, newest first, for the summaries and the graph. */
    public List<Trace> traces(String region) {
        long oldest = Instant.now().getEpochSecond() - RETENTION_SECONDS;
        List<Trace> kept = new ArrayList<>();
        for (Trace trace : traces.scan(key -> key.startsWith(region + "::"))) {
            if (trace.getEndTime() >= oldest) {
                kept.add(trace);
            }
        }
        kept.sort(Comparator.comparingDouble(Trace::getStartTime).reversed());
        return kept;
    }

    @Override
    public void clear() {
        traces.clear();
    }


    // --------------------------------------------------------------- summaries

    /** One trace as {@code GetTraceSummaries} reports it. */
    public record TraceSummary(String id, double startTime, double endTime, double duration,
                               boolean hasError, boolean hasFault, boolean hasThrottle,
                               Integer httpStatus, String httpMethod, String httpUrl,
                               List<String> services, Map<String, Object> annotations,
                               String entryPoint) {
    }

    /** A page of summaries, with the counts AWS reports beside them. */
    public record SummaryPage(List<TraceSummary> items, String nextToken, long approximateTime,
                              int processedCount) {
    }

    public SummaryPage traceSummaries(double startTime, double endTime, String filterExpression,
                                      Integer maxResults, String nextToken, String region) {
        requireWindow(startTime, endTime);
        Predicate<XrayFilterExpression.Facts> filter = XrayFilterExpression.compile(filterExpression);
        List<TraceSummary> matching = new ArrayList<>();
        int processed = 0;
        for (Trace trace : traces(region)) {
            if (trace.getEndTime() < startTime || trace.getStartTime() > endTime) {
                continue;
            }
            processed++;
            TraceSummary summary = summarize(trace);
            if (filter.test(factsOf(summary))) {
                matching.add(summary);
            }
        }
        PaginatedResult<TraceSummary> page = Pagination.paginate(matching, TraceSummary::id,
                maxResults, nextToken, MAX_SUMMARIES_PAGE, "InvalidRequestException");
        return new SummaryPage(page.items(), page.nextToken(),
                Instant.now().getEpochSecond(), processed);
    }

    /** The service graph over a window, the operation a console draws. */
    public List<XrayGraph.Node> serviceGraph(double startTime, double endTime, String region) {
        requireWindow(startTime, endTime);
        List<List<XraySegments.Node>> perTrace = new ArrayList<>();
        for (Trace trace : traces(region)) {
            if (trace.getEndTime() < startTime || trace.getStartTime() > endTime) {
                continue;
            }
            perTrace.add(nodesOf(trace));
        }
        return XrayGraph.build(perTrace);
    }

    /** The same graph, for named traces: AWS answers a window-free view of those traces. */
    public List<XrayGraph.Node> traceGraph(List<String> traceIds, String region) {
        if (traceIds == null || traceIds.isEmpty()) {
            throw validation("TraceIds is required.");
        }
        long oldest = Instant.now().getEpochSecond() - RETENTION_SECONDS;
        List<List<XraySegments.Node>> perTrace = new ArrayList<>();
        for (String traceId : traceIds) {
            traces.get(storageKey(region, traceId))
                    .filter(trace -> trace.getEndTime() >= oldest)
                    .ifPresent(trace -> perTrace.add(nodesOf(trace)));
        }
        return XrayGraph.build(perTrace);
    }

    private TraceSummary summarize(Trace trace) {
        List<XraySegments.Node> nodes = nodesOf(trace);
        Set<String> services = new LinkedHashSet<>();
        Integer httpStatus = null;
        String httpMethod = null;
        String httpUrl = null;
        String entryPoint = null;
        Map<String, Object> annotations = Map.of();
        for (XraySegments.Node node : nodes) {
            if (!node.name().isEmpty()) {
                services.add(node.name());
            }
            if (httpStatus == null && node.httpStatus() != null) {
                httpStatus = node.httpStatus();
                httpMethod = node.httpMethod();
                httpUrl = node.httpUrl();
            }
            if (entryPoint == null && node.parentId().isEmpty()) {
                entryPoint = node.name();
                annotations = node.annotations();
            }
        }
        return new TraceSummary(trace.getId(), trace.getStartTime(), trace.getEndTime(),
                trace.getEndTime() - trace.getStartTime(), trace.isError(), trace.isFault(),
                trace.isThrottle(), httpStatus, httpMethod, httpUrl, List.copyOf(services),
                annotations, entryPoint);
    }

    private static XrayFilterExpression.Facts factsOf(TraceSummary summary) {
        return new XrayFilterExpression.Facts(Set.copyOf(summary.services()), summary.hasError(),
                summary.hasFault(), summary.hasThrottle(), summary.duration(), summary.httpStatus(),
                summary.annotations());
    }

    private List<XraySegments.Node> nodesOf(Trace trace) {
        List<XraySegments.Node> nodes = new ArrayList<>();
        for (String document : trace.getDocuments()) {
            nodes.addAll(XraySegments.flatten(document, this::readNode));
        }
        return nodes;
    }

    /** The id a stored document carries, so a client retrying a segment replaces it instead of adding it. */
    private String segmentIdOf(String document) {
        try {
            return objectMapper.readTree(document).path("id").asText("");
        } catch (Exception expected) {
            return "";
        }
    }

    private JsonNode readNode(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static void requireWindow(double startTime, double endTime) {
        if (endTime <= startTime) {
            throw validation("EndTime must be later than StartTime.");
        }
    }

    // ---------------------------------------------------------------- sampling

    /** The rule AWS always serves, and the one a daemon samples at until a rule matches. */
    static final String DEFAULT_RULE_NAME = "Default";
    /** AWS allows twenty-five sampling rules per Region, the default one excluded. */
    private static final int MAX_SAMPLING_RULES = 25;
    /** The daemon asks for targets every 10 seconds, so that is the quota's lifetime. */
    private static final int SAMPLING_INTERVAL_SECONDS = 10;

    /** One target the daemon should sample at, for a client and a rule. */
    public record Target(String ruleName, Double fixedRate, Integer reservoirQuota,
                         long reservoirQuotaTtl, int interval) {
    }

    /** A statistics document the daemon sent for a rule Floci does not have. */
    public record UnprocessedTarget(String ruleName, String errorCode, String message) {
    }

    /** The answer to {@code GetSamplingTargets}. */
    public record Targets(List<Target> documents, List<UnprocessedTarget> unprocessed,
                          long lastRuleModification) {
    }

    public List<SamplingRule> samplingRules(String region) {
        List<SamplingRule> all = new ArrayList<>(rules.scan(key -> key.startsWith(region + "::")));
        all.add(defaultRule(region));
        all.sort(Comparator.comparing(SamplingRule::getPriority)
                .thenComparing(SamplingRule::getRuleName));
        return all;
    }

    public SamplingRule createSamplingRule(JsonNode request, String region) {
        requireObject(request, "Request payload");
        synchronized (lockFor(region)) {
            return createSamplingRuleLocked(request, region);
        }
    }

    private SamplingRule createSamplingRuleLocked(JsonNode request, String region) {
        SamplingRule requested = parseRule(requireObject(request.path("SamplingRule"), "SamplingRule"));
        String key = storageKey(region, requested.getRuleName());
        if (DEFAULT_RULE_NAME.equals(requested.getRuleName())) {
            throw validation("Sampling rule name " + DEFAULT_RULE_NAME + " is reserved.");
        }
        if (rules.get(key).isPresent()) {
            throw validation("Sampling rule " + requested.getRuleName() + " already exists.");
        }
        if (rules.scan(stored -> stored.startsWith(region + "::")).size() >= MAX_SAMPLING_RULES) {
            throw new AwsException("RuleLimitExceededException",
                    "A Region supports at most " + MAX_SAMPLING_RULES + " sampling rules, and this one"
                            + " already has that many.", 400);
        }
        long now = Instant.now().getEpochSecond();
        String arn = samplingRuleArn(requested.getRuleName(), region);
        requested.setRuleArn(arn);
        if (requested.getResourceArn() == null) {
            requested.setResourceArn(arn);
        }
        requested.setCreatedAt(now);
        requested.setModifiedAt(now);
        rules.put(key, requested);
        LOG.debugv("Created sampling rule {0}", requested.getRuleName());
        return requested;
    }

    public SamplingRule updateSamplingRule(JsonNode request, String region) {
        requireObject(request, "Request payload");
        synchronized (lockFor(region)) {
            return updateSamplingRuleLocked(request, region);
        }
    }

    private SamplingRule updateSamplingRuleLocked(JsonNode request, String region) {
        JsonNode update = requireObject(request.path("SamplingRuleUpdate"), "SamplingRuleUpdate");
        SamplingRule rule = loadRuleForUpdate(update, region);

        // Every supplied member is validated before any is applied: a rejected update must leave
        // the rule exactly as it was, not halfway between the old and the new values.
        Integer priority = update.has("Priority") ? requiredInt(update, "Priority") : null;
        Double fixedRate = update.has("FixedRate") ? requiredRate(update, "FixedRate") : null;
        Integer reservoirSize = update.has("ReservoirSize") ? requiredInt(update, "ReservoirSize") : null;
        Map<String, String> attributes =
                update.has("Attributes") ? optionalStringMap(update, "Attributes") : null;
        Map<String, String> matchers = new LinkedHashMap<>();
        for (String member : new String[] {"ServiceName", "ServiceType", "Host", "HTTPMethod", "URLPath"}) {
            if (update.has(member)) {
                matchers.put(member, requiredText(update, member));
            }
        }

        SamplingRule updated = rule.copy();
        if (priority != null) {
            updated.setPriority(priority);
        }
        if (fixedRate != null) {
            updated.setFixedRate(fixedRate);
        }
        if (reservoirSize != null) {
            updated.setReservoirSize(reservoirSize);
        }
        matchers.forEach((member, value) -> setMatcher(updated, member, value));
        if (attributes != null) {
            updated.setAttributes(attributes);
        }
        updated.setModifiedAt(Instant.now().getEpochSecond());
        rules.put(storageKey(region, updated.getRuleName()), updated);
        return updated;
    }

    /** The stored rule an update names, refusing the synthesised Default one it cannot change. */
    private SamplingRule loadRuleForUpdate(JsonNode update, String region) {
        String name = ruleName(update);
        if (DEFAULT_RULE_NAME.equals(name)) {
            throw validation("The " + DEFAULT_RULE_NAME + " sampling rule cannot be updated.");
        }
        return rules.get(storageKey(region, name)).orElseThrow(
                () -> validation("Sampling rule " + name + " does not exist."));
    }

    private static String ruleName(JsonNode request) {
        String name = request.path("RuleName").asText("");
        if (!name.isEmpty()) {
            return name;
        }
        String arn = request.path("RuleARN").asText("");
        if (arn.isEmpty()) {
            throw validation("RuleName or RuleARN is required.");
        }
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    public SamplingRule deleteSamplingRule(JsonNode request, String region) {
        requireObject(request, "Request payload");
        synchronized (lockFor(region)) {
            return deleteSamplingRuleLocked(request, region);
        }
    }

    private SamplingRule deleteSamplingRuleLocked(JsonNode request, String region) {
        SamplingRule rule = requireRule(request, region);
        if (DEFAULT_RULE_NAME.equals(rule.getRuleName())) {
            throw validation("The " + DEFAULT_RULE_NAME + " sampling rule cannot be deleted.");
        }
        rules.delete(storageKey(region, rule.getRuleName()));
        LOG.debugv("Deleted sampling rule {0}", rule.getRuleName());
        return rule;
    }

    public Targets samplingTargets(JsonNode request, String region) {
        requireObject(request, "Request payload");
        JsonNode documents = request.path("SamplingStatisticsDocuments");
        if (!documents.isArray()) {
            throw validation("SamplingStatisticsDocuments is required.");
        }
        long now = Instant.now().getEpochSecond();
        List<Target> targets = new ArrayList<>();
        List<UnprocessedTarget> unprocessed = new ArrayList<>();
        // The timestamp covers every rule of the Region: it tells the daemon whether to refetch
        // the whole set, so a rule it did not name changing still has to move it.
        long lastModification = rules.scan(key -> key.startsWith(region + "::")).stream()
                .mapToLong(SamplingRule::getModifiedAt).max().orElse(0L);
        for (JsonNode document : documents) {
            String ruleName = document.path("RuleName").asText("");
            if (ruleName.isEmpty()) {
                throw validation("SamplingStatisticsDocuments entries need a RuleName.");
            }
            SamplingRule rule = rules.get(storageKey(region, ruleName)).orElse(null);
            if (rule == null && DEFAULT_RULE_NAME.equals(ruleName)) {
                rule = defaultRule(region);
            }
            if (rule == null) {
                unprocessed.add(new UnprocessedTarget(ruleName, "RuleNotFound",
                        "Sampling rule " + ruleName + " does not exist."));
                continue;
            }
            targets.add(new Target(ruleName, rule.getFixedRate(),
                    Math.max(0, rule.getReservoirSize()), now + SAMPLING_INTERVAL_SECONDS,
                    SAMPLING_INTERVAL_SECONDS));
        }
        return new Targets(targets, unprocessed, lastModification);
    }

    public int putTelemetryRecords(JsonNode request, String region) {
        requireObject(request, "Request payload");
        JsonNode records = request.path("TelemetryRecords");
        if (!records.isArray()) {
            throw validation("TelemetryRecords is required.");
        }
        LOG.debugv("Accepted {0} telemetry records from {1}", records.size(),
                request.path("Hostname").asText("an unnamed host"));
        return records.size();
    }

    /** The rule AWS serves in every Region, so a daemon always has something to sample at. */
    private SamplingRule defaultRule(String region) {
        long now = Instant.now().getEpochSecond();
        SamplingRule rule = new SamplingRule();
        rule.setRuleName(DEFAULT_RULE_NAME);
        rule.setRuleArn(samplingRuleArn(DEFAULT_RULE_NAME, region));
        rule.setResourceArn(rule.getRuleArn());
        rule.setPriority(10000);
        rule.setFixedRate(0.05);
        rule.setReservoirSize(1);
        rule.setServiceName("*");
        rule.setServiceType("*");
        rule.setHost("*");
        rule.setHttpMethod("*");
        rule.setUrlPath("*");
        rule.setVersion(1);
        rule.setCreatedAt(now);
        rule.setModifiedAt(now);
        return rule;
    }

    private String samplingRuleArn(String ruleName, String region) {
        return AwsArnUtils.Arn.of(SERVICE, region, rules.accountId(),
                "sampling-rule/" + ruleName).toString();
    }

    private SamplingRule parseRule(JsonNode rule) {
        SamplingRule parsed = new SamplingRule();
        parsed.setRuleName(requiredText(rule, "RuleName"));
        parsed.setPriority(requiredInt(rule, "Priority"));
        parsed.setFixedRate(requiredRate(rule, "FixedRate"));
        parsed.setReservoirSize(requiredInt(rule, "ReservoirSize"));
        parsed.setServiceName(requiredText(rule, "ServiceName"));
        parsed.setServiceType(requiredText(rule, "ServiceType"));
        parsed.setHost(requiredText(rule, "Host"));
        parsed.setHttpMethod(requiredText(rule, "HTTPMethod"));
        parsed.setUrlPath(requiredText(rule, "URLPath"));
        parsed.setVersion(requiredInt(rule, "Version"));
        parsed.setAttributes(optionalStringMap(rule, "Attributes"));
        if (rule.has("ResourceARN")) {
            parsed.setResourceArn(requiredText(rule, "ResourceARN"));
        }
        return parsed;
    }

    private SamplingRule requireRule(JsonNode request, String region) {
        String ruleName = ruleName(request);
        if (DEFAULT_RULE_NAME.equals(ruleName)) {
            return defaultRule(region);
        }
        return rules.get(storageKey(region, ruleName)).orElseThrow(
                () -> validation("Sampling rule " + ruleName + " does not exist."));
    }

    private static void setMatcher(SamplingRule rule, String member, String value) {
        switch (member) {
            case "ServiceName" -> rule.setServiceName(value);
            case "ServiceType" -> rule.setServiceType(value);
            case "Host" -> rule.setHost(value);
            case "HTTPMethod" -> rule.setHttpMethod(value);
            case "URLPath" -> rule.setUrlPath(value);
            default -> throw new IllegalArgumentException("not a matcher: " + member);
        }
    }

    private static int requiredInt(JsonNode node, String member) {
        JsonNode value = node.path(member);
        if (!value.isNumber()) {
            throw validation(member + " must be a number.");
        }
        return value.asInt();
    }

    private static double requiredRate(JsonNode node, String member) {
        JsonNode value = node.path(member);
        if (!value.isNumber() || value.asDouble() < 0.0 || value.asDouble() > 1.0) {
            throw validation(member + " must be a number between 0 and 1.");
        }
        return value.asDouble();
    }

    // ---------------------------------------------------------------- helpers

    private void evict(String region) {
        List<Trace> all = new ArrayList<>(traces.scan(key -> key.startsWith(region + "::")));
        long oldest = Instant.now().getEpochSecond() - RETENTION_SECONDS;
        List<Trace> expired = all.stream().filter(trace -> trace.getEndTime() < oldest).toList();
        expired.forEach(trace -> traces.delete(storageKey(region, trace.getId())));
        all.removeAll(expired);
        if (all.size() <= MAX_TRACES) {
            return;
        }
        all.sort(Comparator.comparingDouble(Trace::getStartTime));
        int excess = all.size() - MAX_TRACES;
        for (int i = 0; i < excess; i++) {
            traces.delete(storageKey(region, all.get(i).getId()));
        }
        LOG.debugv("Evicted {0} traces to stay under {1}", excess, MAX_TRACES);
    }

    private Object lockFor(String region) {
        return regionLocks.computeIfAbsent(region, ignored -> new Object());
    }

    private static String storageKey(String region, String traceId) {
        return region + "::" + traceId;
    }

    /** A segment document as a node, for the readers that need to walk it. */
    JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (Exception e) {
            throw validation("The stored segment document is not valid JSON.");
        }
    }

    static ObjectNode objectNode() {
        return new ObjectMapper().createObjectNode();
    }

    private static JsonNode requireObject(JsonNode node, String member) {
        if (!node.isObject()) {
            throw validation(member + " is required.");
        }
        return node;
    }

    private static String requiredText(JsonNode node, String member) {
        String value = node.path(member).asText("");
        if (value.isEmpty()) {
            throw validation(member + " is required.");
        }
        return value;
    }

    private static Map<String, String> optionalStringMap(JsonNode node, String member) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode object = node.path(member);
        if (object.isObject()) {
            object.fields().forEachRemaining(entry -> values.put(entry.getKey(),
                    entry.getValue().asText()));
        }
        return values;
    }

    private static AwsException validation(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
