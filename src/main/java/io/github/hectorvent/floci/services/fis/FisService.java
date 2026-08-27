package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.fis.model.Experiment;
import io.github.hectorvent.floci.services.fis.model.ExperimentTemplate;
import io.github.hectorvent.floci.services.fis.model.IdempotencyRecord;
import io.github.hectorvent.floci.services.fis.model.ResolvedTarget;
import io.github.hectorvent.floci.services.fis.model.SafetyLever;
import io.github.hectorvent.floci.services.fis.model.TargetAccountConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful AWS Fault Injection Service control plane.
 *
 * <p>Experiments are intentionally management-plane only: Floci records valid FIS state
 * transitions and resolved target snapshots without applying faults to local resources.</p>
 */
@ApplicationScoped
public class FisService implements TagHandler {

    private static final String SERVICE = "fis";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TAGS = 50;
    private static final int MAX_ACTIONS = 20;
    private static final int MAX_PARALLEL_ACTIONS = 10;
    private static final int MAX_STOP_CONDITIONS = 5;
    private static final int MAX_TARGET_ACCOUNTS = 40;
    private static final int MAX_EXPERIMENT_TEMPLATES = 500;
    private static final int MAX_ACTIVE_EXPERIMENTS = 5;
    private static final Set<String> TERMINAL_EXPERIMENT_STATES =
            Set.of("completed", "stopped", "failed", "cancelled");
    private static final Set<String> TEMPLATE_OPTION_FIELDS =
            Set.of("accountTargeting", "emptyTargetResolutionMode");
    private static final Set<String> UPDATE_TEMPLATE_FIELDS = Set.of(
            "actions", "description", "experimentOptions", "experimentReportConfiguration",
            "logConfiguration", "roleArn", "stopConditions", "targets");
    private static final Pattern COUNT_SELECTION = Pattern.compile("COUNT\\(([1-9][0-9]*)\\)");
    private static final Pattern PERCENT_SELECTION = Pattern.compile("PERCENT\\(([1-9][0-9]?|100)\\)");
    private static final Pattern TARGET_PARAMETER_VALUE =
            Pattern.compile("^[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]+$");
    private static final Pattern TAG_VALUE = Pattern.compile("^[A-Za-z0-9 _.:/=+\\-@]*$");

    private final FisStores stores;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final FisCatalog catalog;
    private final RandomGenerator random;

    @Inject
    public FisService(StorageFactory storageFactory, ObjectMapper mapper,
                      RegionResolver regionResolver, FisCatalog catalog) {
        this(new FisStores(storageFactory), mapper, regionResolver, catalog, new Random());
    }

    FisService(FisStores stores, ObjectMapper mapper,
               RegionResolver regionResolver, FisCatalog catalog) {
        this(stores, mapper, regionResolver, catalog, new Random());
    }

    FisService(FisStores stores, ObjectMapper mapper,
               RegionResolver regionResolver, FisCatalog catalog, RandomGenerator random) {
        this.stores = stores;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.catalog = catalog;
        this.random = random;
    }

    public synchronized ObjectNode createExperimentTemplate(String region, JsonNode request) {
        ObjectNode input = requireRequest(request);
        String clientToken = requireToken(input, "clientToken");
        ObjectNode existing = idempotentResponse(region, "create-template", clientToken, input);
        if (existing != null) {
            return existing;
        }
        validateCreateTemplate(input);
        if (copiedScan(stores.templates, templatePrefix(region)).size() >= MAX_EXPERIMENT_TEMPLATES) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The experiment template quota has been exceeded.", 402);
        }

        String id = newId(region, "template", "EXT");
        double timestamp = now();
        ObjectNode template = mapper.createObjectNode();
        template.put("id", id);
        template.put("arn", regionResolver.buildArn(SERVICE, region, "experiment-template/" + id));
        template.put("description", input.path("description").asText());
        template.set("targets", copyObject(input.path("targets")));
        template.set("actions", input.path("actions").deepCopy());
        template.set("stopConditions", input.path("stopConditions").deepCopy());
        template.put("creationTime", timestamp);
        template.put("lastUpdateTime", timestamp);
        template.put("roleArn", input.path("roleArn").asText());
        template.set("tags", tagsNode(readTagsInput(input.path("tags"))));
        template.set("experimentOptions", templateOptions(input.path("experimentOptions")));
        template.put("targetAccountConfigurationsCount", 0L);
        copyIfPresent(input, template, "logConfiguration");
        copyIfPresent(input, template, "experimentReportConfiguration");

        putTemplate(region, id, template);
        ObjectNode response = wrap("experimentTemplate", template);
        saveIdempotentResponse(region, "create-template", clientToken, input, response);
        return response;
    }

    public synchronized ObjectNode createTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId, JsonNode request) {
        ObjectNode input = requireRequest(request);
        requireAccountId(accountId);
        String token = optionalToken(input, "clientToken");
        String operation = "create-target-account:" + experimentTemplateId + ":" + accountId;
        ObjectNode existing = idempotentResponse(region, operation, token, input);
        if (existing != null) {
            return existing;
        }
        ObjectNode template = requireTemplate(region, experimentTemplateId);
        if (!"multi-account".equals(template.path("experimentOptions").path("accountTargeting").asText())) {
            throw conflict("Target account configurations require accountTargeting to be multi-account.");
        }
        if (stores.targetAccounts.get(targetAccountKey(region, experimentTemplateId, accountId)).isPresent()) {
            throw conflict("A target account configuration already exists for account " + accountId + ".");
        }
        if (targetAccountConfigurations(region, experimentTemplateId).size() >= MAX_TARGET_ACCOUNTS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The target account configuration quota has been exceeded.", 402);
        }
        String roleArn = requireArn(input, "roleArn");
        if (input.has("description")) {
            validateOptionalDescription(input, "description", true);
        }

        ObjectNode configuration = mapper.createObjectNode();
        configuration.put("accountId", accountId);
        configuration.put("roleArn", roleArn);
        if (input.has("description")) {
            configuration.put("description", input.path("description").asText());
        }
        putTargetAccountConfiguration(
                targetAccountKey(region, experimentTemplateId, accountId), configuration);
        updateTemplateTargetAccountCount(region, experimentTemplateId);
        ObjectNode response = wrap("targetAccountConfiguration", configuration);
        saveIdempotentResponse(region, operation, token, input, response);
        return response;
    }

    public synchronized ObjectNode deleteExperimentTemplate(String region, String id) {
        ObjectNode template = requireTemplate(region, id);
        stores.templates.delete(templateKey(region, id));
        deleteByPrefix(stores.targetAccounts, targetAccountPrefix(region, id));
        return wrap("experimentTemplate", template);
    }

    public synchronized ObjectNode deleteTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId) {
        requireTemplate(region, experimentTemplateId);
        ObjectNode configuration = requireTargetAccountConfiguration(region, experimentTemplateId, accountId);
        stores.targetAccounts.delete(targetAccountKey(region, experimentTemplateId, accountId));
        updateTemplateTargetAccountCount(region, experimentTemplateId);
        return wrap("targetAccountConfiguration", configuration);
    }

    public ObjectNode getAction(String region, String id) {
        ObjectNode action = catalog.action(region, id);
        if (action == null) {
            throw notFound("Action", id);
        }
        action.set("tags", actionTags(region, id));
        return wrap("action", action);
    }

    public ObjectNode getExperiment(String region, String id) {
        return wrap("experiment", requireExperiment(region, id));
    }

    public ObjectNode getExperimentTargetAccountConfiguration(
            String region, String experimentId, String accountId) {
        requireExperiment(region, experimentId);
        ObjectNode configuration = stores.targetAccounts
                .get(experimentTargetAccountKey(region, experimentId, accountId))
                .map(this::toObjectNode)
                .orElseThrow(() -> notFound("Experiment target account configuration", accountId));
        return wrap("targetAccountConfiguration", configuration);
    }

    public ObjectNode getExperimentTemplate(String region, String id) {
        return wrap("experimentTemplate", requireTemplate(region, id));
    }

    public synchronized ObjectNode getSafetyLever(String region, String id) {
        return wrap("safetyLever", requireSafetyLever(region, id));
    }

    public ObjectNode getTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId) {
        requireTemplate(region, experimentTemplateId);
        return wrap("targetAccountConfiguration",
                requireTargetAccountConfiguration(region, experimentTemplateId, accountId));
    }

    public ObjectNode getTargetResourceType(String region, String resourceType) {
        ObjectNode targetResourceType = catalog.targetResourceType(resourceType);
        if (targetResourceType == null) {
            throw notFound("Target resource type", resourceType);
        }
        return wrap("targetResourceType", targetResourceType);
    }

    public ObjectNode listActions(String region, Integer maxResults, String nextToken) {
        List<ObjectNode> actions = catalog.actionSummaries(region);
        actions.forEach(action -> action.set("tags", actionTags(region, action.path("id").asText())));
        return page("actions", actions, maxResults, nextToken, scope(region, "actions"),
                node -> node.path("id").asText());
    }

    public ObjectNode listExperimentResolvedTargets(
            String region, String experimentId, Integer maxResults, String nextToken, String targetName) {
        requireExperiment(region, experimentId);
        if (targetName != null) {
            validateName(targetName, "targetName");
        }
        List<ObjectNode> resolvedTargets =
                copiedScan(stores.resolvedTargets, resolvedTargetPrefix(region, experimentId));
        if (targetName != null) {
            resolvedTargets.removeIf(target -> !targetName.equals(target.path("targetName").asText()));
        }
        String filter = targetName == null ? "" : targetName;
        return page("resolvedTargets", resolvedTargets, maxResults, nextToken,
                scope(region, "resolved-targets:" + experimentId + ":" + filter), this::resolvedTargetCursor);
    }

    public ObjectNode listExperimentTargetAccountConfigurations(
            String region, String experimentId, String nextToken) {
        requireExperiment(region, experimentId);
        List<ObjectNode> configurations =
                copiedScan(stores.targetAccounts, experimentTargetAccountPrefix(region, experimentId));
        return page("targetAccountConfigurations", configurations, null, nextToken,
                scope(region, "experiment-target-accounts:" + experimentId),
                node -> node.path("accountId").asText());
    }

    public ObjectNode listExperimentTemplates(String region, Integer maxResults, String nextToken) {
        List<ObjectNode> summaries = copiedScan(stores.templates, templatePrefix(region)).stream()
                .map(this::templateSummary).toList();
        return page("experimentTemplates", summaries, maxResults, nextToken,
                scope(region, "templates"), node -> node.path("id").asText());
    }

    public ObjectNode listExperiments(
            String region, Integer maxResults, String nextToken, String experimentTemplateId) {
        List<ObjectNode> experiments = copiedScan(stores.experiments, experimentPrefix(region));
        if (experimentTemplateId != null) {
            validateName(experimentTemplateId, "experimentTemplateId");
            experiments.removeIf(experiment ->
                    !experimentTemplateId.equals(experiment.path("experimentTemplateId").asText()));
        }
        List<ObjectNode> summaries = experiments.stream().map(this::experimentSummary).toList();
        String filter = experimentTemplateId == null ? "" : experimentTemplateId;
        return page("experiments", summaries, maxResults, nextToken,
                scope(region, "experiments:" + filter), node -> node.path("id").asText());
    }

    public ObjectNode listTargetAccountConfigurations(
            String region, String experimentTemplateId, Integer maxResults, String nextToken) {
        requireTemplate(region, experimentTemplateId);
        return page("targetAccountConfigurations", targetAccountConfigurations(region, experimentTemplateId),
                maxResults, nextToken, scope(region, "target-accounts:" + experimentTemplateId),
                node -> node.path("accountId").asText());
    }

    public ObjectNode listTargetResourceTypes(String region, Integer maxResults, String nextToken) {
        return page("targetResourceTypes", catalog.targetResourceTypeSummaries(), maxResults, nextToken,
                scope(region, "target-resource-types"), node -> node.path("resourceType").asText());
    }

    public synchronized ObjectNode startExperiment(String region, JsonNode request) {
        ObjectNode input = requireRequest(request);
        String clientToken = requireToken(input, "clientToken");
        ObjectNode existing = idempotentResponse(region, "start-experiment", clientToken, input);
        if (existing != null) {
            return existing;
        }
        String templateId = requireText(input, "experimentTemplateId", 64);
        ObjectNode template = requireTemplate(region, templateId);
        List<ObjectNode> configurations = targetAccountConfigurations(region, templateId);
        if ("multi-account".equals(template.path("experimentOptions").path("accountTargeting").asText())
                && configurations.isEmpty()) {
            throw validation("A multi-account experiment requires at least one target account configuration.");
        }
        Map<String, String> tags = readTagsInput(input.path("tags"));
        String actionsMode = validateActionsMode(input.path("experimentOptions"));
        if (activeExperimentCount(region) >= MAX_ACTIVE_EXPERIMENTS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The active experiment quota has been exceeded.", 402);
        }

        String id = newId(region, "experiment", "EXP");
        double timestamp = now();
        ObjectNode experiment = mapper.createObjectNode();
        experiment.put("id", id);
        experiment.put("arn", regionResolver.buildArn(SERVICE, region, "experiment/" + id));
        experiment.put("experimentTemplateId", templateId);
        experiment.put("roleArn", template.path("roleArn").asText());
        experiment.put("creationTime", timestamp);
        experiment.set("targets", copyObject(template.path("targets")));
        experiment.set("stopConditions", template.path("stopConditions").deepCopy());
        experiment.set("tags", tagsNode(tags));
        ObjectNode options = copyObject(template.path("experimentOptions"));
        options.put("actionsMode", actionsMode);
        experiment.set("experimentOptions", options);
        copyIfPresent(template, experiment, "logConfiguration");
        copyIfPresent(template, experiment, "experimentReportConfiguration");

        experiment.put("targetAccountConfigurationsCount", configurations.size());
        configurations.forEach(configuration -> putTargetAccountConfiguration(
                experimentTargetAccountKey(region, id, configuration.path("accountId").asText()),
                configuration));
        Set<String> unresolvedTargets = persistResolvedTargets(region, id, experiment.path("targets"));

        ObjectNode safetyLever = requireSafetyLever(region, "default");
        boolean cancelled = "engaged".equals(safetyLever.path("state").path("status").asText());
        boolean skipAll = "skip-all".equals(actionsMode);
        boolean unresolvedUniqueIdentifier = unresolvedTargets.stream()
                .anyMatch(name -> experiment.path("targets").path(name).path("resourceArns").isArray());
        boolean failedResolution = !unresolvedTargets.isEmpty()
                && (unresolvedUniqueIdentifier
                || "fail".equals(options.path("emptyTargetResolutionMode").asText()));
        ObjectNode experimentActions = experiment.putObject("actions");
        template.path("actions").fields().forEachRemaining(entry -> {
            ObjectNode action = entry.getValue().deepCopy();
            boolean unresolvedActionTarget = usesTarget(action, unresolvedTargets);
            String status;
            String reason;
            if (cancelled) {
                status = "cancelled";
                reason = "The experiment was cancelled because the safety lever is engaged.";
            } else if (failedResolution) {
                status = unresolvedActionTarget ? "failed" : "cancelled";
                reason = unresolvedActionTarget
                        ? "The action target did not resolve to any resources."
                        : "The action was cancelled because target resolution failed.";
            } else if (skipAll) {
                status = "skipped";
                reason = "The action was skipped by the skip-all experiment option.";
            } else if (unresolvedActionTarget) {
                status = "skipped";
                reason = "The action was skipped because its target did not resolve to any resources.";
            } else if (action.path("startAfter").isArray() && !action.path("startAfter").isEmpty()) {
                status = "pending";
                reason = "The action is waiting for its dependencies.";
            } else {
                status = "running";
                reason = "The action is running in safe emulation mode.";
            }
            action.set("state", state(status, reason));
            if (!"pending".equals(status)) {
                action.put("startTime", timestamp);
            }
            if (cancelled || failedResolution || skipAll || unresolvedActionTarget) {
                action.put("endTime", timestamp);
            }
            experimentActions.set(entry.getKey(), action);
        });

        if (cancelled) {
            experiment.set("state", state("cancelled",
                    "The experiment was cancelled because the safety lever is engaged."));
            experiment.put("endTime", timestamp);
        } else if (failedResolution) {
            experiment.set("state", state("failed",
                    "The experiment failed because one or more targets did not resolve to any resources."));
            experiment.put("startTime", timestamp);
            experiment.put("endTime", timestamp);
        } else if (skipAll || allActionsHaveStatus(experimentActions, "skipped")) {
            experiment.set("state", state("completed",
                    "The experiment completed with every action skipped."));
            experiment.put("startTime", timestamp);
            experiment.put("endTime", timestamp);
        } else {
            experiment.set("state", state("running", "The experiment is running in safe emulation mode."));
            experiment.put("startTime", timestamp);
        }

        putExperiment(region, id, experiment);
        ObjectNode response = wrap("experiment", experiment);
        saveIdempotentResponse(region, "start-experiment", clientToken, input, response);
        return response;
    }

    public synchronized ObjectNode stopExperiment(String region, String id) {
        ObjectNode experiment = requireExperiment(region, id);
        String current = experiment.path("state").path("status").asText();
        if (!TERMINAL_EXPERIMENT_STATES.contains(current)) {
            stopExperimentNode(experiment, "The experiment was stopped by the user.");
            putExperiment(region, id, experiment);
        }
        return wrap("experiment", experiment);
    }

    public synchronized ObjectNode updateExperimentTemplate(String region, String id, JsonNode request) {
        ObjectNode input = requireRequest(request);
        ObjectNode template = requireTemplate(region, id);
        if (input.has("logConfiguration")) {
            validateLogConfiguration(input.path("logConfiguration"), false);
        }
        if (input.hasNonNull("experimentReportConfiguration")) {
            validateExperimentReportConfiguration(input.path("experimentReportConfiguration"));
        }
        boolean changed = false;
        for (String field : UPDATE_TEMPLATE_FIELDS) {
            if (!input.has(field)) {
                continue;
            }
            changed = true;
            if ("experimentOptions".equals(field)) {
                ObjectNode patch = requireObject(input.path(field), field);
                ObjectNode options = copyObject(template.path(field));
                patch.fields().forEachRemaining(entry -> {
                    if (!"emptyTargetResolutionMode".equals(entry.getKey())) {
                        throw validation("Only emptyTargetResolutionMode can be updated in experimentOptions.");
                    }
                    options.set(entry.getKey(), entry.getValue().deepCopy());
                });
                template.set(field, options);
            } else {
                template.set(field, input.path(field).deepCopy());
            }
        }
        if (!changed) {
            throw validation("At least one experiment template field must be supplied.");
        }
        validateStoredTemplate(template);
        template.put("lastUpdateTime", now());
        putTemplate(region, id, template);
        return wrap("experimentTemplate", template);
    }

    public synchronized ObjectNode updateSafetyLeverState(String region, String id, JsonNode request) {
        ObjectNode input = requireRequest(request);
        requireSafetyLever(region, id);
        ObjectNode stateInput = requireObject(input.path("state"), "state");
        String status = requireText(stateInput, "status", 64);
        JsonNode reasonNode = stateInput.path("reason");
        if (!reasonNode.isTextual()) {
            throw validation("reason must be a string.");
        }
        String reason = reasonNode.asText();
        if (!Set.of("engaged", "disengaged").contains(status)) {
            throw validation("Safety lever status must be engaged or disengaged.");
        }
        if ("engaged".equals(status)) {
            for (ObjectNode experiment : copiedScan(stores.experiments, experimentPrefix(region))) {
                String experimentStatus = experiment.path("state").path("status").asText();
                if (!TERMINAL_EXPERIMENT_STATES.contains(experimentStatus)) {
                    stopExperimentNode(experiment, "The experiment was stopped because the safety lever was engaged.");
                    putExperiment(region, experiment.path("id").asText(), experiment);
                }
            }
        }
        ObjectNode lever = safetyLever(region, status, reason);
        putSafetyLever(region, id, lever);
        return wrap("safetyLever", lever);
    }

    public synchronized ObjectNode updateTargetAccountConfiguration(
            String region, String experimentTemplateId, String accountId, JsonNode request) {
        ObjectNode input = requireRequest(request);
        requireTemplate(region, experimentTemplateId);
        ObjectNode configuration = requireTargetAccountConfiguration(region, experimentTemplateId, accountId);
        boolean changed = false;
        if (input.has("description")) {
            validateOptionalDescription(input, "description", true);
            configuration.put("description", input.path("description").asText());
            changed = true;
        }
        if (input.has("roleArn")) {
            configuration.put("roleArn", requireArn(input, "roleArn"));
            changed = true;
        }
        if (!changed) {
            throw validation("description or roleArn must be supplied.");
        }
        putTargetAccountConfiguration(
                targetAccountKey(region, experimentTemplateId, accountId), configuration);
        return wrap("targetAccountConfiguration", configuration);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public boolean allowEmptyTagKeys() {
        return true;
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        ResourceRef resource = parseTaggableArn(region, arn);
        return switch (resource.type()) {
            case "action" -> tagsMap(actionTags(region, resource.id()));
            case "experiment" -> tagsMap(requireExperiment(region, resource.id()).path("tags"));
            case "experiment-template" -> tagsMap(requireTemplate(region, resource.id()).path("tags"));
            default -> throw validation("The ARN does not identify a taggable AWS FIS resource.");
        };
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        validateTags(tags);
        ResourceRef resource = parseTaggableArn(region, arn);
        ObjectNode current = switch (resource.type()) {
            case "action" -> actionTags(region, resource.id());
            case "experiment" -> copyObject(requireExperiment(region, resource.id()).path("tags"));
            case "experiment-template" -> copyObject(requireTemplate(region, resource.id()).path("tags"));
            default -> throw validation("The ARN does not identify a taggable AWS FIS resource.");
        };
        tags.forEach(current::put);
        if (current.size() > MAX_TAGS) {
            throw validation("A resource can have at most 50 tags.");
        }
        saveResourceTags(region, resource, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        ResourceRef resource = parseTaggableArn(region, arn);
        ObjectNode current = switch (resource.type()) {
            case "action" -> actionTags(region, resource.id());
            case "experiment" -> copyObject(requireExperiment(region, resource.id()).path("tags"));
            case "experiment-template" -> copyObject(requireTemplate(region, resource.id()).path("tags"));
            default -> throw validation("The ARN does not identify a taggable AWS FIS resource.");
        };
        if (tagKeys == null || tagKeys.isEmpty()) {
            return;
        }
        tagKeys.forEach(this::validateTagKey);
        tagKeys.forEach(current::remove);
        saveResourceTags(region, resource, current);
    }

    private void validateCreateTemplate(ObjectNode input) {
        requireToken(input, "clientToken");
        validateOptionalDescription(input, "description", false);
        requireArn(input, "roleArn");
        readTagsInput(input.path("tags"));
        templateOptions(input.path("experimentOptions"));
        if (input.has("logConfiguration")) {
            validateLogConfiguration(input.path("logConfiguration"), true);
        }
        if (input.hasNonNull("experimentReportConfiguration")) {
            validateExperimentReportConfiguration(input.path("experimentReportConfiguration"));
        }
        ObjectNode candidate = mapper.createObjectNode();
        candidate.set("actions", input.path("actions").deepCopy());
        candidate.set("targets", copyObject(input.path("targets")));
        candidate.set("stopConditions", input.path("stopConditions").deepCopy());
        candidate.put("description", input.path("description").asText());
        candidate.put("roleArn", input.path("roleArn").asText());
        candidate.set("experimentOptions", templateOptions(input.path("experimentOptions")));
        validateStoredTemplate(candidate);
    }

    private void validateStoredTemplate(ObjectNode template) {
        validateOptionalDescription(template, "description", false);
        requireArn(template, "roleArn");
        ObjectNode targets = requireObject(template.path("targets"), "targets");
        validateTargets(targets);
        ObjectNode actions = requireObject(template.path("actions"), "actions");
        if (actions.isEmpty()) {
            throw validation("actions must contain at least one action.");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "An experiment template can contain at most 20 actions.", 402);
        }
        validateActions(actions, targets);
        JsonNode stopConditions = template.path("stopConditions");
        if (!stopConditions.isArray() || stopConditions.isEmpty()) {
            throw validation("stopConditions must contain at least one stop condition.");
        }
        if (stopConditions.size() > MAX_STOP_CONDITIONS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "An experiment template can contain at most 5 stop conditions.", 402);
        }
        for (JsonNode condition : stopConditions) {
            if (!condition.isObject()) {
                throw validation("Each stop condition must be an object.");
            }
            String source = requireText((ObjectNode) condition, "source", 64);
            if (!Set.of("none", "aws:cloudwatch:alarm").contains(source)) {
                throw validation("Unsupported stop condition source: " + source + ".");
            }
            if ("aws:cloudwatch:alarm".equals(source)) {
                if (!condition.hasNonNull("value")) {
                    throw validation("CloudWatch alarm stop conditions require a value.");
                }
            }
            if (condition.hasNonNull("value")) {
                JsonNode value = condition.path("value");
                if (!value.isTextual() || value.asText().length() < 20 || value.asText().length() > 2048) {
                    throw validation("Stop condition values must contain between 20 and 2048 characters.");
                }
            }
        }
        templateOptions(template.path("experimentOptions"));
    }

    private void validateTargets(ObjectNode targets) {
        targets.fields().forEachRemaining(entry -> {
            validateName(entry.getKey(), "target name");
            ObjectNode target = requireObject(entry.getValue(), "target " + entry.getKey());
            String resourceType = requireText(target, "resourceType", 128);
            ObjectNode resourceTypeDefinition = catalog.targetResourceType(resourceType);
            if (resourceTypeDefinition == null) {
                throw validation("Unsupported target resource type: " + resourceType + ".");
            }
            String selectionMode = requireText(target, "selectionMode", 64);
            validateSelectionMode(selectionMode);
            boolean arns = target.hasNonNull("resourceArns");
            boolean tags = target.hasNonNull("resourceTags");
            boolean filters = target.hasNonNull("filters");
            ObjectNode parameters = target.hasNonNull("parameters")
                    ? requireObject(target.path("parameters"), "target parameters")
                    : mapper.createObjectNode();
            boolean parameterSelector = !parameters.isEmpty();
            if (arns && tags) {
                throw validation("A target cannot specify both resourceArns and resourceTags.");
            }
            if (!arns && !tags && !filters && !parameterSelector) {
                throw validation(
                        "Each target must specify resourceArns, resourceTags, resource filters, or resource parameters.");
            }
            if ("aws:eks:pod".equals(resourceType) && (arns || tags || filters)) {
                throw validation("Targets of type aws:eks:pod must use resource parameters and do not support "
                        + "resourceArns, resourceTags, or filters.");
            }
            if (arns) {
                JsonNode resourceArns = target.path("resourceArns");
                if (!resourceArns.isArray() || resourceArns.isEmpty() || resourceArns.size() > 5) {
                    throw validation("resourceArns must contain between 1 and 5 ARNs.");
                }
                resourceArns.forEach(arn -> {
                    if (!arn.isTextual()
                            || arn.asText().length() < 20
                            || arn.asText().length() > 2048
                            || arn.asText().chars().anyMatch(Character::isWhitespace)) {
                        throw validation("Every resource ARN must contain between 20 and 2048 "
                                + "non-whitespace characters.");
                    }
                });
                if (filters) {
                    throw validation("A target cannot specify both resourceArns and filters.");
                }
            } else if (tags) {
                ObjectNode resourceTags = requireObject(target.path("resourceTags"), "resourceTags");
                if (resourceTags.isEmpty() || resourceTags.size() > 50) {
                    throw validation("resourceTags must contain between 1 and 50 tags.");
                }
                resourceTags.fields().forEachRemaining(tag -> {
                    if (tag.getKey().isEmpty() || tag.getKey().length() > 128
                            || !tag.getValue().isTextual() || tag.getValue().asText().length() > 256) {
                        throw validation("Resource tags must be string-to-string entries.");
                    }
                });
            }

            ObjectNode parameterDefinitions = (ObjectNode) resourceTypeDefinition.path("parameters");
            parameters.fields().forEachRemaining(parameter -> {
                if (!parameterDefinitions.has(parameter.getKey())) {
                    throw validation("Unsupported target parameter " + parameter.getKey()
                            + " for resource type " + resourceType + ".");
                }
                if (parameter.getKey().length() > 64 || !parameter.getValue().isTextual()) {
                    throw validation("Target parameters must be string-to-string entries.");
                }
                String value = parameter.getValue().asText();
                if (value.length() > 1024 || !TARGET_PARAMETER_VALUE.matcher(value).matches()) {
                    throw validation("Target parameter values must contain between 1 and 1024 valid characters.");
                }
            });
            parameterDefinitions.fields().forEachRemaining(parameter -> {
                if (parameter.getValue().path("required").asBoolean()
                        && !parameters.hasNonNull(parameter.getKey())) {
                    throw validation("Target resource type " + resourceType
                            + " requires parameter " + parameter.getKey() + ".");
                }
            });

            if (filters) {
                validateTargetFilters(target.path("filters"));
            }
        });
    }

    private void validateTargetFilters(JsonNode filters) {
        if (!filters.isArray() || filters.isEmpty()) {
            throw validation("filters must contain at least one filter.");
        }
        for (JsonNode filterNode : filters) {
            ObjectNode filter = requireObject(filterNode, "target filter");
            String path = requireText(filter, "path", 256);
            if (path.chars().anyMatch(Character::isWhitespace)) {
                throw validation("Filter paths must not contain whitespace.");
            }
            JsonNode values = filter.path("values");
            if (!values.isArray() || values.isEmpty()) {
                throw validation("Filter values must contain at least one value.");
            }
            values.forEach(value -> {
                if (!value.isTextual() || value.asText().isEmpty() || value.asText().length() > 128
                        || value.asText().chars().anyMatch(Character::isWhitespace)) {
                    throw validation("Filter values must be non-whitespace strings of at most 128 characters.");
                }
            });
        }
    }

    private void validateActions(ObjectNode actions, ObjectNode targets) {
        actions.fields().forEachRemaining(entry -> {
            String actionName = entry.getKey();
            validateName(actionName, "action name");
            ObjectNode action = requireObject(entry.getValue(), "action " + actionName);
            String actionId = requireText(action, "actionId", 128);
            ObjectNode actionDefinition = catalog.action(regionResolver.getRegion(), actionId);
            if (actionDefinition == null) {
                throw validation("Unsupported action ID: " + actionId + ".");
            }
            if (action.hasNonNull("description")) {
                JsonNode description = action.path("description");
                if (!description.isTextual()
                        || description.asText().isEmpty()
                        || description.asText().length() > 512) {
                    throw validation("Action descriptions must contain between 1 and 512 characters.");
                }
            }
            ObjectNode parameters = action.has("parameters")
                    ? requireObject(action.path("parameters"), "action parameters")
                    : mapper.createObjectNode();
            parameters.fields().forEachRemaining(parameter -> {
                validateName(parameter.getKey(), "action parameter name");
                if (!parameter.getValue().isTextual()
                        || parameter.getValue().asText().isEmpty()
                        || parameter.getValue().asText().length() > 1024
                        || parameter.getValue().asText().chars().anyMatch(Character::isWhitespace)) {
                    throw validation("Action parameters must contain between 1 and 1024 "
                            + "non-whitespace characters.");
                }
                if (!actionDefinition.path("parameters").has(parameter.getKey())) {
                    throw validation("Unsupported parameter " + parameter.getKey()
                            + " for action " + actionId + ".");
                }
            });
            actionDefinition.path("parameters").fields().forEachRemaining(parameter -> {
                if (parameter.getValue().path("required").asBoolean()
                        && !parameters.hasNonNull(parameter.getKey())) {
                    throw validation("Action " + actionId + " requires parameter " + parameter.getKey() + ".");
                }
            });
            ObjectNode actionTargets = action.has("targets")
                    ? requireObject(action.path("targets"), "action targets")
                    : mapper.createObjectNode();
            actionDefinition.path("targets").fields().forEachRemaining(target -> {
                String slot = target.getKey();
                if (!actionTargets.hasNonNull(slot) || !actionTargets.path(slot).isTextual()) {
                    throw validation("Action " + actionId + " requires target " + slot + ".");
                }
                String targetName = actionTargets.path(slot).asText();
                validateName(targetName, "action target reference");
                if (!targets.has(targetName)) {
                    throw validation("Action target " + targetName + " is not defined.");
                }
                String expectedType = target.getValue().path("resourceType").asText();
                if (!expectedType.equals(targets.path(targetName).path("resourceType").asText())) {
                    throw validation("Action target " + targetName + " must use resource type " + expectedType + ".");
                }
            });
            actionTargets.fields().forEachRemaining(target -> {
                validateName(target.getKey(), "action target name");
                if (!target.getValue().isTextual()) {
                    throw validation("Action target references must be strings.");
                }
                validateName(target.getValue().asText(), "action target reference");
                if (!targets.has(target.getValue().asText())) {
                    throw validation("Action target " + target.getValue().asText() + " is not defined.");
                }
                if (!actionDefinition.path("targets").has(target.getKey())) {
                    throw validation("Unsupported target " + target.getKey() + " for action " + actionId + ".");
                }
            });
            validateActionTargetRestrictions(actionId, actionTargets, targets);
            if (action.has("startAfter")) {
                JsonNode startAfter = action.path("startAfter");
                if (!startAfter.isArray()) {
                    throw validation("startAfter must be an array.");
                }
                startAfter.forEach(dependency -> {
                    if (!dependency.isTextual()) {
                        throw validation("startAfter entries must be strings.");
                    }
                    validateName(dependency.asText(), "startAfter action name");
                    if (!actions.has(dependency.asText()) || actionName.equals(dependency.asText())) {
                        throw validation("Invalid startAfter dependency for action " + actionName + ".");
                    }
                });
            }
        });
        validateAcyclicActions(actions);
        validateParallelActionQuota(actions);
    }

    private void validateParallelActionQuota(ObjectNode actions) {
        List<String> names = new ArrayList<>();
        actions.fieldNames().forEachRemaining(names::add);
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < names.size(); index++) {
            indexes.put(names.get(index), index);
        }

        boolean[][] precedes = new boolean[names.size()][names.size()];
        for (int actionIndex = 0; actionIndex < names.size(); actionIndex++) {
            JsonNode dependencies = actions.path(names.get(actionIndex)).path("startAfter");
            if (dependencies.isArray()) {
                for (JsonNode dependency : dependencies) {
                    precedes[indexes.get(dependency.asText())][actionIndex] = true;
                }
            }
        }
        for (int intermediate = 0; intermediate < names.size(); intermediate++) {
            for (int before = 0; before < names.size(); before++) {
                for (int after = 0; after < names.size(); after++) {
                    precedes[before][after] = precedes[before][after]
                            || (precedes[before][intermediate] && precedes[intermediate][after]);
                }
            }
        }

        int[] matchedBefore = new int[names.size()];
        Arrays.fill(matchedBefore, -1);
        int matches = 0;
        for (int before = 0; before < names.size(); before++) {
            if (augmentPrecedenceMatching(before, precedes, new boolean[names.size()], matchedBefore)) {
                matches++;
            }
        }
        if (names.size() - matches > MAX_PARALLEL_ACTIONS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "An experiment can run at most 10 actions in parallel.", 402);
        }
    }

    private boolean augmentPrecedenceMatching(
            int before, boolean[][] precedes, boolean[] visitedAfter, int[] matchedBefore) {
        for (int after = 0; after < precedes.length; after++) {
            if (!precedes[before][after] || visitedAfter[after]) {
                continue;
            }
            visitedAfter[after] = true;
            if (matchedBefore[after] == -1
                    || augmentPrecedenceMatching(matchedBefore[after], precedes, visitedAfter, matchedBefore)) {
                matchedBefore[after] = before;
                return true;
            }
        }
        return false;
    }

    private void validateActionTargetRestrictions(
            String actionId, ObjectNode actionTargets, ObjectNode targets) {
        if ("aws:ec2:api-insufficient-instance-capacity-error".equals(actionId)) {
            JsonNode target = targets.path(actionTargets.path("Roles").asText());
            JsonNode arns = target.path("resourceArns");
            if (!arns.isArray() || arns.isEmpty()
                    || target.hasNonNull("resourceTags")
                    || target.hasNonNull("filters")
                    || target.hasNonNull("parameters")) {
                throw validation("Action " + actionId
                        + " requires resource ARNs and does not support resource tags, filters, or parameters.");
            }
        }
        if ("aws:eks:inject-kubernetes-custom-resource".equals(actionId)) {
            JsonNode target = targets.path(actionTargets.path("Cluster").asText());
            JsonNode arns = target.path("resourceArns");
            if (!arns.isArray() || arns.size() != 1
                    || target.hasNonNull("resourceTags")
                    || target.hasNonNull("filters")
                    || target.hasNonNull("parameters")) {
                throw validation("Action " + actionId
                        + " requires exactly one resource ARN and does not support resource tags, filters, or parameters.");
            }
        }
        if ("aws:s3:bucket-pause-replication".equals(actionId)) {
            JsonNode target = targets.path(actionTargets.path("Buckets").asText());
            if (!target.path("resourceTags").isObject() || target.path("resourceTags").isEmpty()
                    || target.hasNonNull("resourceArns")
                    || target.hasNonNull("filters")
                    || target.hasNonNull("parameters")) {
                throw validation("Action " + actionId + " supports targeting by resource tags only.");
            }
        }
    }

    private void validateAcyclicActions(ObjectNode actions) {
        Map<String, Integer> states = new HashMap<>();
        actions.fieldNames().forEachRemaining(name -> visitAction(actions, name, states));
    }

    private void visitAction(ObjectNode actions, String name, Map<String, Integer> states) {
        int state = states.getOrDefault(name, 0);
        if (state == 1) {
            throw validation("Action startAfter dependencies must not contain a cycle.");
        }
        if (state == 2) {
            return;
        }
        states.put(name, 1);
        JsonNode dependencies = actions.path(name).path("startAfter");
        if (dependencies.isArray()) {
            dependencies.forEach(dependency -> visitAction(actions, dependency.asText(), states));
        }
        states.put(name, 2);
    }

    private ObjectNode templateOptions(JsonNode input) {
        ObjectNode options = mapper.createObjectNode();
        options.put("accountTargeting", "single-account");
        options.put("emptyTargetResolutionMode", "fail");
        if (input == null || input.isMissingNode() || input.isNull()) {
            return options;
        }
        ObjectNode supplied = requireObject(input, "experimentOptions");
        supplied.fields().forEachRemaining(entry -> {
            if (!TEMPLATE_OPTION_FIELDS.contains(entry.getKey()) || !entry.getValue().isTextual()) {
                throw validation("Invalid experiment option: " + entry.getKey() + ".");
            }
            options.put(entry.getKey(), entry.getValue().asText());
        });
        if (!Set.of("single-account", "multi-account").contains(options.path("accountTargeting").asText())) {
            throw validation("accountTargeting must be single-account or multi-account.");
        }
        if (!Set.of("fail", "skip").contains(options.path("emptyTargetResolutionMode").asText())) {
            throw validation("emptyTargetResolutionMode must be fail or skip.");
        }
        return options;
    }

    private void validateLogConfiguration(JsonNode input, boolean requireSchemaVersion) {
        ObjectNode configuration = requireObject(input, "logConfiguration");
        if (requireSchemaVersion && !configuration.hasNonNull("logSchemaVersion")) {
            throw validation("logConfiguration.logSchemaVersion is required.");
        }
        if (configuration.has("logSchemaVersion")) {
            JsonNode version = configuration.path("logSchemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt()) {
                throw validation("logConfiguration.logSchemaVersion must be an integer.");
            }
        }
        if (configuration.has("cloudWatchLogsConfiguration")) {
            ObjectNode cloudWatch = requireObject(
                    configuration.path("cloudWatchLogsConfiguration"),
                    "logConfiguration.cloudWatchLogsConfiguration");
            requireNonWhitespaceText(
                    cloudWatch, "logGroupArn", 20, 2048,
                    "logConfiguration.cloudWatchLogsConfiguration.logGroupArn");
        }
        if (configuration.has("s3Configuration")) {
            ObjectNode s3 = requireObject(
                    configuration.path("s3Configuration"),
                    "logConfiguration.s3Configuration");
            requireNonWhitespaceText(
                    s3, "bucketName", 3, 63,
                    "logConfiguration.s3Configuration.bucketName");
            if (s3.has("prefix")) {
                JsonNode prefix = s3.path("prefix");
                if (!prefix.isTextual() || prefix.asText().isEmpty() || prefix.asText().length() > 700) {
                    throw validation("logConfiguration.s3Configuration.prefix must contain between 1 and 700 "
                            + "characters.");
                }
            }
        }
    }

    private void validateExperimentReportConfiguration(JsonNode input) {
        ObjectNode configuration = requireObject(input, "experimentReportConfiguration");
        if (configuration.hasNonNull("preExperimentDuration")) {
            requireNonWhitespaceText(
                    configuration, "preExperimentDuration", 1, 32,
                    "experimentReportConfiguration.preExperimentDuration");
        }
        if (configuration.hasNonNull("postExperimentDuration")) {
            requireNonWhitespaceText(
                    configuration, "postExperimentDuration", 1, 32,
                    "experimentReportConfiguration.postExperimentDuration");
        }
        if (configuration.hasNonNull("outputs")) {
            ObjectNode outputs = requireObject(
                    configuration.path("outputs"), "experimentReportConfiguration.outputs");
            if (outputs.hasNonNull("s3Configuration")) {
                ObjectNode s3 = requireObject(
                        outputs.path("s3Configuration"),
                        "experimentReportConfiguration.outputs.s3Configuration");
                if (s3.hasNonNull("bucketName")) {
                    requireNonWhitespaceText(
                            s3, "bucketName", 3, 63,
                            "experimentReportConfiguration.outputs.s3Configuration.bucketName");
                }
                if (s3.hasNonNull("prefix")) {
                    requireNonWhitespaceText(
                            s3, "prefix", 1, 256,
                            "experimentReportConfiguration.outputs.s3Configuration.prefix");
                }
            }
        }
        if (configuration.hasNonNull("dataSources")) {
            ObjectNode dataSources = requireObject(
                    configuration.path("dataSources"), "experimentReportConfiguration.dataSources");
            if (dataSources.hasNonNull("cloudWatchDashboards")) {
                JsonNode dashboards = dataSources.path("cloudWatchDashboards");
                if (!dashboards.isArray()) {
                    throw validation("experimentReportConfiguration.dataSources.cloudWatchDashboards "
                            + "must be an array.");
                }
                for (JsonNode dashboardNode : dashboards) {
                    ObjectNode dashboard = requireObject(
                            dashboardNode,
                            "experimentReportConfiguration.dataSources.cloudWatchDashboards entry");
                    if (dashboard.hasNonNull("dashboardIdentifier")) {
                        requireNonWhitespaceText(
                                dashboard, "dashboardIdentifier", 1, 512,
                                "experimentReportConfiguration.dataSources.cloudWatchDashboards.dashboardIdentifier");
                    }
                }
            }
        }
    }

    private void requireNonWhitespaceText(
            ObjectNode input, String field, int minimumLength, int maximumLength, String displayName) {
        JsonNode value = input.path(field);
        if (!value.isTextual()
                || value.asText().length() < minimumLength
                || value.asText().length() > maximumLength
                || value.asText().chars().anyMatch(Character::isWhitespace)) {
            throw validation(displayName + " must contain between " + minimumLength + " and "
                    + maximumLength + " non-whitespace characters.");
        }
    }

    private String validateActionsMode(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "run-all";
        }
        ObjectNode options = requireObject(input, "experimentOptions");
        options.fieldNames().forEachRemaining(name -> {
            if (!"actionsMode".equals(name)) {
                throw validation("Invalid start experiment option: " + name + ".");
            }
        });
        String mode = options.path("actionsMode").asText("run-all");
        if (!Set.of("run-all", "skip-all").contains(mode)) {
            throw validation("actionsMode must be run-all or skip-all.");
        }
        return mode;
    }

    private Set<String> persistResolvedTargets(String region, String experimentId, JsonNode targets) {
        Set<String> unresolvedTargets = new HashSet<>();
        int index = 0;
        for (var iterator = targets.fields(); iterator.hasNext();) {
            var entry = iterator.next();
            String targetName = entry.getKey();
            JsonNode target = entry.getValue();
            String resourceType = target.path("resourceType").asText();
            int resolvedBeforeTarget = index;
            if (target.path("resourceArns").isArray()) {
                List<String> arns = new ArrayList<>();
                target.path("resourceArns").forEach(arn -> arns.add(arn.asText()));
                int count = selectedCount(target.path("selectionMode").asText(), arns.size());
                if (count < arns.size()) {
                    shuffle(arns);
                }
                for (String arn : arns.subList(0, Math.min(count, arns.size()))) {
                    ObjectNode resolved = resolvedTarget(targetName, resourceType);
                    resolved.putObject("targetInformation").put("arn", arn);
                    putResolvedTarget(region, experimentId, index++, resolved);
                }
            }
            if (index == resolvedBeforeTarget) {
                unresolvedTargets.add(targetName);
            }
        }
        return unresolvedTargets;
    }

    private void shuffle(List<String> values) {
        for (int index = values.size() - 1; index > 0; index--) {
            Collections.swap(values, index, random.nextInt(index + 1));
        }
    }

    private boolean usesTarget(ObjectNode action, Set<String> targetNames) {
        if (targetNames.isEmpty() || !action.path("targets").isObject()) {
            return false;
        }
        for (JsonNode targetName : action.path("targets")) {
            if (targetNames.contains(targetName.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean allActionsHaveStatus(ObjectNode actions, String status) {
        for (JsonNode action : actions) {
            if (!status.equals(action.path("state").path("status").asText())) {
                return false;
            }
        }
        return !actions.isEmpty();
    }

    private int selectedCount(String selectionMode, int total) {
        if ("ALL".equals(selectionMode)) {
            return total;
        }
        Matcher count = COUNT_SELECTION.matcher(selectionMode);
        if (count.matches()) {
            return Math.min(total, countSelectionValue(selectionMode, count));
        }
        Matcher percent = PERCENT_SELECTION.matcher(selectionMode);
        if (percent.matches()) {
            return Math.min(total, total * Integer.parseInt(percent.group(1)) / 100);
        }
        return total;
    }

    private void validateSelectionMode(String selectionMode) {
        if ("ALL".equals(selectionMode) || PERCENT_SELECTION.matcher(selectionMode).matches()) {
            return;
        }
        Matcher count = COUNT_SELECTION.matcher(selectionMode);
        if (count.matches()) {
            countSelectionValue(selectionMode, count);
            return;
        }
        throw validation("Invalid target selectionMode: " + selectionMode + ".");
    }

    private int countSelectionValue(String selectionMode, Matcher count) {
        BigInteger value = new BigInteger(count.group(1));
        if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw validation("Invalid target selectionMode: " + selectionMode + ".");
        }
        return value.intValue();
    }

    private ObjectNode resolvedTarget(String targetName, String resourceType) {
        ObjectNode target = mapper.createObjectNode();
        target.put("targetName", targetName);
        target.put("resourceType", resourceType);
        return target;
    }

    private void stopExperimentNode(ObjectNode experiment, String reason) {
        double timestamp = now();
        experiment.set("state", state("stopped", reason));
        experiment.put("endTime", timestamp);
        experiment.path("actions").forEach(actionNode -> {
            ObjectNode action = (ObjectNode) actionNode;
            String current = action.path("state").path("status").asText();
            if (Set.of("running", "initiating", "stopping").contains(current)) {
                action.set("state", state("stopped", reason));
                action.put("endTime", timestamp);
            } else if ("pending".equals(current)) {
                action.set("state", state("cancelled", reason));
                action.put("endTime", timestamp);
            }
        });
    }

    private synchronized ObjectNode requireSafetyLever(String region, String id) {
        if (!"default".equals(id)) {
            throw notFound("Safety lever", id);
        }
        return stores.safetyLevers.get(safetyLeverKey(region, id)).map(this::toObjectNode).orElseGet(() -> {
            ObjectNode lever = safetyLever(region, "disengaged", "The safety lever is disengaged.");
            putSafetyLever(region, id, lever);
            return lever;
        });
    }

    private ObjectNode safetyLever(String region, String status, String reason) {
        ObjectNode lever = mapper.createObjectNode();
        lever.put("id", "default");
        lever.put("arn", regionResolver.buildArn(SERVICE, region, "safety-lever/default"));
        lever.set("state", state(status, reason));
        return lever;
    }

    private ObjectNode state(String status, String reason) {
        ObjectNode state = mapper.createObjectNode();
        state.put("status", status);
        state.put("reason", reason);
        return state;
    }

    private ObjectNode templateSummary(ObjectNode template) {
        return select(template, "id", "arn", "description", "creationTime", "lastUpdateTime", "tags");
    }

    private ObjectNode experimentSummary(ObjectNode experiment) {
        return select(experiment, "id", "arn", "experimentTemplateId", "creationTime",
                "state", "tags", "experimentOptions");
    }

    private ObjectNode select(ObjectNode source, String... fields) {
        ObjectNode selected = mapper.createObjectNode();
        for (String field : fields) {
            if (source.has(field)) {
                selected.set(field, source.path(field).deepCopy());
            }
        }
        return selected;
    }

    private ObjectNode page(String field, List<ObjectNode> all, Integer maxResults, String nextToken,
                            String scope, Function<ObjectNode, String> cursorFunction) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_PAGE_SIZE)) {
            throw validation("maxResults must be between 1 and 100.");
        }
        int limit = maxResults == null ? MAX_PAGE_SIZE : maxResults;
        String after = decodeToken(nextToken, scope);
        List<ObjectNode> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(cursorFunction));
        int start = 0;
        if (after != null) {
            while (start < sorted.size() && cursorFunction.apply(sorted.get(start)).compareTo(after) <= 0) {
                start++;
            }
        }
        int end = Math.min(sorted.size(), start + limit);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode values = response.putArray(field);
        for (int i = start; i < end; i++) {
            values.add(sorted.get(i).deepCopy());
        }
        if (end < sorted.size() && end > start) {
            response.put("nextToken", encodeToken(scope, cursorFunction.apply(sorted.get(end - 1))));
        }
        return response;
    }

    private String encodeToken(String scope, String cursor) {
        String raw = scope + "\n" + cursor;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeToken(String token, String expectedScope) {
        if (token == null) {
            return null;
        }
        if (token.length() > 1024 || token.chars().anyMatch(Character::isWhitespace)) {
            throw validation("Invalid nextToken.");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = raw.indexOf('\n');
            if (separator < 0 || !expectedScope.equals(raw.substring(0, separator))
                    || separator == raw.length() - 1) {
                throw validation("Invalid nextToken.");
            }
            return raw.substring(separator + 1);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid nextToken.");
        }
    }

    private String scope(String region, String operation) {
        return regionResolver.getAccountId() + ":" + region + ":" + operation;
    }

    private String resolvedTargetCursor(ObjectNode target) {
        return target.path("targetName").asText() + "|" + target.path("resourceType").asText()
                + "|" + target.path("targetInformation").toString();
    }

    private ObjectNode idempotentResponse(String region, String operation, String token, ObjectNode request) {
        if (token == null) {
            return null;
        }
        IdempotencyRecord record = stores.idempotency
                .get(idempotencyKey(region, operation, token)).orElse(null);
        if (record == null) {
            return null;
        }
        if (!record.getRequest().equals(request)) {
            throw conflict("The clientToken was already used with different request parameters.");
        }
        return record.getResponse().deepCopy();
    }

    private void saveIdempotentResponse(String region, String operation, String token,
                                        ObjectNode request, ObjectNode response) {
        if (token == null) {
            return;
        }
        IdempotencyRecord record = new IdempotencyRecord(request.deepCopy(), response.deepCopy());
        stores.idempotency.put(idempotencyKey(region, operation, token), record);
    }

    private ResourceRef parseTaggableArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN: " + arn + ".");
        }
        if (!SERVICE.equals(parsed.service()) || !region.equals(parsed.region())
                || !regionResolver.getAccountId().equals(parsed.accountId())) {
            throw validation("The resource ARN does not belong to this AWS FIS account and region.");
        }
        int separator = parsed.resource().indexOf('/');
        if (separator <= 0 || separator == parsed.resource().length() - 1) {
            throw validation("Invalid AWS FIS resource ARN: " + arn + ".");
        }
        String type = parsed.resource().substring(0, separator);
        String id = parsed.resource().substring(separator + 1);
        if (!Set.of("action", "experiment", "experiment-template").contains(type)) {
            throw validation("The ARN does not identify a taggable AWS FIS resource.");
        }
        if ("action".equals(type) && !catalog.containsAction(id)) {
            throw notFound("Action", id);
        }
        return new ResourceRef(type, id);
    }

    private void saveResourceTags(String region, ResourceRef resource, ObjectNode tags) {
        switch (resource.type()) {
            case "action" -> {
                stores.actionTags.put(actionTagsKey(region, resource.id()), tagsMap(tags));
            }
            case "experiment" -> {
                ObjectNode experiment = requireExperiment(region, resource.id());
                experiment.set("tags", tags.deepCopy());
                putExperiment(region, resource.id(), experiment);
            }
            case "experiment-template" -> {
                ObjectNode template = requireTemplate(region, resource.id());
                template.set("tags", tags.deepCopy());
                template.put("lastUpdateTime", now());
                putTemplate(region, resource.id(), template);
            }
            default -> throw validation("The ARN does not identify a taggable AWS FIS resource.");
        }
    }

    private ObjectNode actionTags(String region, String actionId) {
        return stores.actionTags.get(actionTagsKey(region, actionId))
                .map(this::tagsNode).orElseGet(mapper::createObjectNode);
    }

    private Map<String, String> readTagsInput(JsonNode tags) {
        if (tags == null || tags.isMissingNode() || tags.isNull()) {
            return Map.of();
        }
        if (!tags.isObject()) {
            throw validation("tags must be a string-to-string map.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("Tag values must be strings.");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        });
        validateTags(result);
        return result;
    }

    private void validateTags(Map<String, String> tags) {
        if (tags == null) {
            throw validation("tags must be supplied.");
        }
        if (tags.size() > MAX_TAGS) {
            throw validation("A resource can have at most 50 tags.");
        }
        tags.forEach((key, value) -> {
            validateTagKey(key);
            if (value == null || value.length() > 256) {
                throw validation("Tag values can contain at most 256 characters.");
            }
            if (!TAG_VALUE.matcher(value).matches()) {
                throw validation("Tag values contain invalid characters.");
            }
        });
    }

    private void validateTagKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw validation("Tag keys must contain between 1 and 128 characters.");
        }
        if (key.regionMatches(true, 0, "aws:", 0, 4)) {
            throw validation("Tag keys must not use the reserved aws: prefix.");
        }
        if (!TAG_VALUE.matcher(key).matches()) {
            throw validation("Tag keys contain invalid characters.");
        }
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = mapper.createObjectNode();
        tags.forEach(node::put);
        return node;
    }

    private Map<String, String> tagsMap(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags != null && tags.isObject()) {
            tags.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        }
        return result;
    }

    private ObjectNode requireTemplate(String region, String id) {
        return stores.templates.get(templateKey(region, id)).map(this::toObjectNode)
                .orElseThrow(() -> notFound("Experiment template", id));
    }

    private ObjectNode requireExperiment(String region, String id) {
        return stores.experiments.get(experimentKey(region, id)).map(this::toObjectNode)
                .orElseThrow(() -> notFound("Experiment", id));
    }

    private ObjectNode requireTargetAccountConfiguration(String region, String templateId, String accountId) {
        return stores.targetAccounts.get(targetAccountKey(region, templateId, accountId))
                .map(this::toObjectNode)
                .orElseThrow(() -> notFound("Target account configuration", accountId));
    }

    private List<ObjectNode> targetAccountConfigurations(String region, String templateId) {
        return copiedScan(stores.targetAccounts, targetAccountPrefix(region, templateId));
    }

    private long activeExperimentCount(String region) {
        return copiedScan(stores.experiments, experimentPrefix(region)).stream()
                .filter(experiment -> !TERMINAL_EXPERIMENT_STATES.contains(
                        experiment.path("state").path("status").asText()))
                .count();
    }

    private <T> List<ObjectNode> copiedScan(StorageBackend<String, T> store, String prefix) {
        List<ObjectNode> result = new ArrayList<>();
        store.scan(key -> key.startsWith(prefix)).forEach(value -> result.add(toObjectNode(value)));
        return result;
    }

    private void updateTemplateTargetAccountCount(String region, String templateId) {
        ObjectNode template = requireTemplate(region, templateId);
        template.put("targetAccountConfigurationsCount", targetAccountConfigurations(region, templateId).size());
        template.put("lastUpdateTime", now());
        putTemplate(region, templateId, template);
    }

    private <T> void deleteByPrefix(StorageBackend<String, T> store, String prefix) {
        for (String key : new HashSet<>(store.keys())) {
            if (key.startsWith(prefix)) {
                store.delete(key);
            }
        }
    }

    private String newId(String region, String type, String prefix) {
        String id;
        do {
            id = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        } while (resourceIdExists(region, type, id));
        return id;
    }

    private boolean resourceIdExists(String region, String type, String id) {
        return switch (type) {
            case "template" -> stores.templates.get(templateKey(region, id)).isPresent();
            case "experiment" -> stores.experiments.get(experimentKey(region, id)).isPresent();
            default -> throw new IllegalArgumentException("Unsupported FIS resource type: " + type);
        };
    }

    private void putTemplate(String region, String id, ObjectNode template) {
        stores.templates.put(templateKey(region, id),
                mapper.convertValue(template, ExperimentTemplate.class));
    }

    private void putExperiment(String region, String id, ObjectNode experiment) {
        stores.experiments.put(experimentKey(region, id), mapper.convertValue(experiment, Experiment.class));
    }

    private void putTargetAccountConfiguration(String key, ObjectNode configuration) {
        stores.targetAccounts.put(key, mapper.convertValue(configuration, TargetAccountConfiguration.class));
    }

    private void putResolvedTarget(String region, String experimentId, int index, ObjectNode resolvedTarget) {
        stores.resolvedTargets.put(resolvedTargetKey(region, experimentId, index),
                mapper.convertValue(resolvedTarget, ResolvedTarget.class));
    }

    private void putSafetyLever(String region, String id, ObjectNode safetyLever) {
        stores.safetyLevers.put(safetyLeverKey(region, id),
                mapper.convertValue(safetyLever, SafetyLever.class));
    }

    private ObjectNode toObjectNode(Object value) {
        return mapper.valueToTree(value);
    }

    private ObjectNode requireRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw validation("Request body must be a JSON object.");
        }
        return (ObjectNode) request;
    }

    private ObjectNode requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw validation(name + " must be an object.");
        }
        return (ObjectNode) node;
    }

    private ObjectNode copyObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return mapper.createObjectNode();
        }
        return requireObject(node, "value").deepCopy();
    }

    private String requireText(ObjectNode input, String field, int maximumLength) {
        JsonNode value = input.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximumLength) {
            throw validation(field + " must be a non-empty string no longer than " + maximumLength + " characters.");
        }
        return value.asText();
    }

    private String requireToken(ObjectNode input, String field) {
        String token = requireText(input, field, 1024);
        if (token.chars().anyMatch(Character::isWhitespace)) {
            throw validation(field + " must contain between 1 and 1024 non-whitespace characters.");
        }
        return token;
    }

    private String optionalToken(ObjectNode input, String field) {
        return input.has(field) ? requireToken(input, field) : null;
    }

    private String requireArn(ObjectNode input, String field) {
        String arn = requireText(input, field, 2048);
        if (arn.length() < 20 || arn.chars().anyMatch(Character::isWhitespace)) {
            throw validation(field + " must be a valid ARN.");
        }
        try {
            AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation(field + " must be a valid ARN.");
        }
        return arn;
    }

    private void validateOptionalDescription(ObjectNode input, String field, boolean allowEmpty) {
        if (!input.has(field) && allowEmpty) {
            return;
        }
        JsonNode value = input.path(field);
        if (!value.isTextual() || value.asText().length() > 512
                || (!allowEmpty && value.asText().isEmpty())) {
            throw validation(field + " must be a " + (allowEmpty ? "" : "non-empty ")
                    + "string no longer than 512 characters.");
        }
    }

    private void validateName(String name, String field) {
        if (name == null || name.isEmpty() || name.length() > 64
                || name.chars().anyMatch(Character::isWhitespace)) {
            throw validation(field + " must contain between 1 and 64 non-whitespace characters.");
        }
    }

    private void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\S{12,48}")) {
            throw validation("accountId must contain between 12 and 48 non-whitespace characters.");
        }
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field) && !source.path(field).isNull()) {
            target.set(field, source.path(field).deepCopy());
        }
    }

    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode response = mapper.createObjectNode();
        response.set(field, value.deepCopy());
        return response;
    }

    private AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private AwsException notFound(String resource, String id) {
        return new AwsException("ResourceNotFoundException", resource + " " + id + " was not found.", 404);
    }

    private double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private String key(String region, String type, String id) {
        return region + "::" + type + "::" + id;
    }

    private String templateKey(String region, String id) {
        return key(region, "template", id);
    }

    private String templatePrefix(String region) {
        return key(region, "template", "");
    }

    private String experimentKey(String region, String id) {
        return key(region, "experiment", id);
    }

    private String experimentPrefix(String region) {
        return key(region, "experiment", "");
    }

    private String targetAccountKey(String region, String templateId, String accountId) {
        return key(region, "target-account", templateId + "::" + accountId);
    }

    private String targetAccountPrefix(String region, String templateId) {
        return key(region, "target-account", templateId + "::");
    }

    private String experimentTargetAccountKey(String region, String experimentId, String accountId) {
        return key(region, "experiment-target-account", experimentId + "::" + accountId);
    }

    private String experimentTargetAccountPrefix(String region, String experimentId) {
        return key(region, "experiment-target-account", experimentId + "::");
    }

    private String resolvedTargetKey(String region, String experimentId, int index) {
        return key(region, "resolved-target", experimentId + "::" + String.format("%08d", index));
    }

    private String resolvedTargetPrefix(String region, String experimentId) {
        return key(region, "resolved-target", experimentId + "::");
    }

    private String actionTagsKey(String region, String actionId) {
        return key(region, "action-tags", actionId);
    }

    private String safetyLeverKey(String region, String id) {
        return key(region, "safety-lever", id);
    }

    private String idempotencyKey(String region, String operation, String token) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return key(region, "idempotency", operation + "::" + encoded);
    }

    private record ResourceRef(String type, String id) {}
}
