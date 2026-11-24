package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emrserverless.EmrServerlessJobRunService;
import io.floci.aws.services.emrserverless.EmrServerlessService;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CloudFormation provisioning for {@code AWS::EMRServerless::Application}, through the services the EMR Serverless
 * API itself uses. {@code Ref} is the application id; {@code Fn::GetAtt} gives {@code ApplicationId} and {@code Arn}.
 *
 * <p>It does what AWS's resource handler does (aws-cloudformation-resource-providers-emrserverless): the properties are
 * read as the handler's model validation reads them, a create sends them to CreateApplication, and an update sends
 * UpdateApplication the settings the template changed, then drives the application's tags to the template's, removing
 * any other. A setting the template drops is left as it is, as the handler sends null for it, but for
 * {@code InitialCapacity}, which it sends as an empty map, and so clears. {@code Name} and {@code Type} are
 * create-only, so a change to either replaces the application. Neither path starts or stops the application: an update
 * a STARTED application cannot take fails as UpdateApplication refuses it, and so does the delete of one.
 */
@ApplicationScoped
public class EmrServerlessCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::EMRServerless::Application";
    /** What an in-place update is about to change, for {@link #rollbackUpdate} to put back. */
    static final String SNAPSHOT_ATTR = "__FlociEmrServerlessApplicationUpdateSnapshot";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String INITIAL_CAPACITY = "InitialCapacity";
    /** The settings UpdateApplication takes, which CreateApplication takes too, by their template names. */
    private static final List<String> SETTINGS = List.of("ReleaseLabel", "Architecture", "InitialCapacity",
            "MaximumCapacity", "AutoStartConfiguration", "AutoStopConfiguration", "NetworkConfiguration",
            "ImageConfiguration", "WorkerTypeSpecifications", "MonitoringConfiguration", "RuntimeConfiguration",
            "SchedulerConfiguration", "InteractiveConfiguration", "IdentityCenterConfiguration");
    /** The settings an empty value clears, which is how a rollback takes back one the application did not hold. */
    private static final Set<String> CLEARED_WHEN_EMPTY =
            Set.of("initialCapacity", "imageConfiguration", "workerTypeSpecifications");
    private static final String SCHEDULER = "schedulerConfiguration";
    /** The registry schema's {@code Architecture} enum, which model validation holds a template to. */
    /** The schema's read-only properties, which a user cannot set. */
    private static final List<String> READ_ONLY = List.of("ApplicationId", "Arn");

    private final EmrServerlessService applications;
    private final EmrServerlessJobRunService jobRuns;

    @Inject
    public EmrServerlessCfnProvisioner(EmrServerlessService applications, EmrServerlessJobRunService jobRuns) {
        this.applications = applications;
        this.jobRuns = jobRuns;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    /**
     * An in-place update whose own undo failed leaves its snapshot on the attempted resource, which the engine discards
     * for the previous one: carried over, it lets ContinueUpdateRollback try the restore again.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        CfnRollback.carryFailedUndoSnapshot(previous, attempted, SNAPSHOT_ATTR);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement or a stub migration is undone through the cleanup record, and an in-place update from the snapshot
     * taken before it. The snapshot is spent only once the restore succeeded, so one that throws leaves it for
     * ContinueUpdateRollback to retry.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot == null) {
            return false;
        }
        restore(snapshot, true, true);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(TYPE, props);
        checkTemplate(template);
        Map<String, String> tags = tags(template);
        // A stack from before this type had a provisioner, or deployed while EMR Serverless was disabled, holds the
        // dispatcher's stub, which names no application: taking over its id is a create.
        boolean stub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        PropertyChanges changes = ctx.isUpdate() && !stub ? ctx.changes(TYPE, props) : null;
        if (changes != null && !changes.requiresReplacement()) {
            updateInPlace(r, ctx, template, changes, tags);
        } else {
            CreateApplicationRequest request = MAPPER.convertValue(settings(template, SETTINGS),
                    CreateApplicationRequest.class);
            request.setName(template.path("Name").textValue());
            request.setType(template.path("Type").textValue());
            request.setTags(tags.isEmpty() ? null : tags);
            request.setClientToken(UUID.randomUUID().toString());
            publish(r, applications.createApplication(request));
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * What model validation checks that the vendored shapes do not hold: the {@code Architecture} enum, in the schema
     * validator's wording. Also the read-only properties, which the schema guide says a user cannot set, refused as
     * AWS's handler for EMR on EKS's virtual cluster refuses its {@code Arn}.
     */
    private static void checkTemplate(JsonNode template) {
        String architecture = template.path("Architecture").textValue();
        if (architecture != null && !EmrServerlessService.ARCHITECTURES.contains(architecture)) {
            throw CfnPropertyTypes.modelValidation("#/Architecture: " + architecture + " is not a valid enum value)");
        }
        for (String readOnly : READ_ONLY) {
            if (template.has(readOnly)) {
                throw new AwsException("InvalidRequest",
                        readOnly + " is a ReadOnly property which cannot be specified in the template", 400);
            }
        }
    }

    /**
     * The update AWS's handler makes, from the application the prior physical id names: it fails, as the handler's
     * check of the application does, when that one is gone. The application's settings and tags as they are now are
     * snapshotted first, so a later resource's failure puts them back, and a failure here puts back what it had changed
     * before it propagates: nothing, when UpdateApplication refused the update.
     */
    private void updateInPlace(StackResource r, ProvisionContext ctx, JsonNode template, PropertyChanges changes,
                               Map<String, String> tags) {
        String id = ctx.priorPhysicalId();
        Application live = applications.findLive(id).orElseThrow(() -> new AwsException("NotFound",
                "Resource of type '" + TYPE + "' with identifier '" + id + "' was not found.", 404));
        ObjectNode update = updatedSettings(template, changes);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("applicationId", id);
        snapshot.put("arn", live.getArn());
        snapshot.set("settings", restoring(live, update));
        snapshot.set("tags", MAPPER.valueToTree(live.getTags() == null ? Map.of() : live.getTags()));
        publish(r, live);
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        AtomicBoolean settingsChanged = new AtomicBoolean();
        AtomicBoolean tagsTouched = new AtomicBoolean();
        CfnRollback.guarded(r, ctx, false, () -> { },
                () -> restore(snapshot, settingsChanged.get(), tagsTouched.get()), () -> {
                    if (!update.isEmpty()) {
                        jobRuns.updateApplication(id, MAPPER.convertValue(update, UpdateApplicationRequest.class));
                        settingsChanged.set(true);
                    }
                    tagsTouched.set(true);
                    reconcileTags(live.getArn(), tags);
                });
    }

    /**
     * The settings the template added or changed, in the API's shape, and an empty {@code initialCapacity} when it
     * dropped {@code InitialCapacity}, which AWS's handler sends whether the template has one or not.
     */
    private static ObjectNode updatedSettings(JsonNode template, PropertyChanges changes) {
        List<String> sent = new ArrayList<>();
        for (String name : SETTINGS) {
            PropertyChanges.Kind kind = changes.kindOf(name);
            if (kind == PropertyChanges.Kind.ADDED || kind == PropertyChanges.Kind.CHANGED) {
                sent.add(name);
            }
        }
        ObjectNode update = settings(template, sent);
        if (changes.kindOf(INITIAL_CAPACITY) == PropertyChanges.Kind.DROPPED) {
            update.putObject("initialCapacity");
        }
        return update;
    }

    /**
     * What puts back each setting {@code update} sends: the application's own value, or an empty one where that clears
     * a setting it did not hold. Any other setting it did not hold stays as the update left it, which is what AWS's
     * rollback, an update back to the previous template, does too: a scheduler configuration the update set where an
     * empty one had turned queuing off among them, as a STARTED application cannot take an empty one back.
     */
    private static ObjectNode restoring(Application live, ObjectNode update) {
        ObjectNode restore = MAPPER.createObjectNode();
        JsonNode held = MAPPER.valueToTree(live);
        update.fieldNames().forEachRemaining(member -> {
            JsonNode value = held.get(member);
            if (value != null && !value.isNull()) {
                restore.set(member, SCHEDULER.equals(member) ? heldScheduler(value, update.get(member)) : value);
            } else if (CLEARED_WHEN_EMPTY.contains(member)) {
                restore.putObject(member);
            }
        });
        return restore;
    }

    /**
     * The held scheduler configuration's members the update sent: a member it left out kept its value, and the one
     * change a STARTED application takes, {@code maxConcurrentRuns}, stays the one its restore makes. An update that
     * turned queuing off with an empty one is undone with every member.
     */
    private static JsonNode heldScheduler(JsonNode held, JsonNode sent) {
        if (sent.isEmpty()) {
            return held;
        }
        ObjectNode restore = MAPPER.createObjectNode();
        sent.fieldNames().forEachRemaining(member -> {
            if (held.has(member)) {
                restore.set(member, held.get(member));
            }
        });
        return restore;
    }

    /** Puts back the settings and the tags the snapshot holds, each when the update got as far as changing it. */
    private void restore(JsonNode snapshot, boolean settingsChanged, boolean tagsTouched) {
        String id = snapshot.path("applicationId").asText();
        JsonNode settings = snapshot.path("settings");
        if (settingsChanged && !settings.isEmpty()) {
            jobRuns.updateApplication(id, MAPPER.convertValue(settings, UpdateApplicationRequest.class));
        }
        if (tagsTouched) {
            Map<String, String> tags = new LinkedHashMap<>();
            snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
            reconcileTags(snapshot.path("arn").asText(), tags);
        }
    }

    /** Drives the application's tags to {@code desired}, removing every other, as AWS's handler does. */
    private void reconcileTags(String arn, Map<String, String> desired) {
        EmrServerlessService.ResourceArn resource = applications.resourceArn(arn);
        CfnRollback.reconcileTags(applications.listTags(resource).keySet(), desired,
                dropped -> applications.untagResource(resource, dropped),
                added -> applications.tagResource(resource, added));
    }

    /** The template's settings named in {@code names}, as the API names and shapes them. */
    private static ObjectNode settings(JsonNode template, List<String> names) {
        ObjectNode settings = MAPPER.createObjectNode();
        for (String name : names) {
            JsonNode value = template.get(name);
            if (value != null) {
                settings.set(apiName(name), apiShape(name, value));
            }
        }
        return settings;
    }

    /**
     * A template value in the API's shape: each member named as the API names it, the {@code Key}/{@code Value}
     * lists the template gives {@code InitialCapacity} and {@code LogTypeMap} (the API's {@code logTypes}) in as maps,
     * and the keys of the worker type and configuration property maps kept as they are.
     */
    private static JsonNode apiShape(String name, JsonNode value) {
        return switch (name) {
            case INITIAL_CAPACITY, "LogTypeMap" -> keyed(value);
            case "WorkerTypeSpecifications" -> byKey(value);
            case "Properties" -> value;
            default -> {
                if (value.isArray()) {
                    ArrayNode items = MAPPER.createArrayNode();
                    value.forEach(item -> items.add(apiShape("", item)));
                    yield items;
                }
                if (!value.isObject()) {
                    yield value;
                }
                ObjectNode shaped = MAPPER.createObjectNode();
                value.properties().forEach(member -> shaped.set(apiName(member.getKey()),
                        apiShape(member.getKey(), member.getValue())));
                yield shaped;
            }
        };
    }

    private static String apiName(String name) {
        return "LogTypeMap".equals(name) ? "logTypes" : CfnApiShapeConverter.lowerFirst(name);
    }

    private static ObjectNode keyed(JsonNode entries) {
        ObjectNode map = MAPPER.createObjectNode();
        entries.forEach(entry -> map.set(entry.path("Key").asText(), apiShape("", entry.path("Value"))));
        return map;
    }

    private static ObjectNode byKey(JsonNode object) {
        ObjectNode map = MAPPER.createObjectNode();
        object.properties().forEach(member -> map.set(member.getKey(), apiShape("", member.getValue())));
        return map;
    }

    private static Map<String, String> tags(JsonNode template) {
        Map<String, String> tags = new LinkedHashMap<>();
        template.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        return tags;
    }

    private static void publish(StackResource r, Application app) {
        r.setPhysicalId(app.getApplicationId());
        r.getAttributes().put("ApplicationId", app.getApplicationId());
        r.getAttributes().put("Arn", app.getArn());
    }

    /**
     * Deletes the application unless it is already gone, which a deleted one is, being left TERMINATED. One that is
     * not stopped or created fails, as DeleteApplication refuses it and AWS's handler does not stop it first.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        applications.whileLive(physicalId, app -> applications.deleteApplication(physicalId));
    }
}
