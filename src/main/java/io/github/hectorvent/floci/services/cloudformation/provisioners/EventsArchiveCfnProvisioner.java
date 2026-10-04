package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.Archive;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provisions {@code AWS::Events::Archive}. Rules, buses and bus policies belong to
 * {@link EventsCfnProvisioner}; this class takes only the EventBridge service so the test fixture
 * can wire it from that service.
 *
 * <p>The archive name is the physical id and {@code Ref}, generated when the template gives none
 * and kept across updates; {@code Fn::GetAtt Arn} is the archive ARN. The name and the source are
 * create-only. An update that keeps both changes the archive in place through UpdateArchive, sending
 * only the properties the template declares, so a property dropped from the template keeps its
 * stored value, and keeps the description, pattern and retention it replaces on the resource so a
 * failed stack update can put them back. An update that changes either, or drops an explicit name
 * for a generated one, creates the new archive and leaves the displaced one to the
 * {@link ReplacementCleanup} record; a replacement that would keep an explicit name is refused, as
 * CloudFormation refuses it for any custom-named resource, and so is declaring a generated name
 * explicitly.
 */
@ApplicationScoped
public class EventsArchiveCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Events::Archive";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ARCHIVE_NAME_MAX_LENGTH = 48;
    /** The registry schema's {@code ArchiveName} constraint: its pattern within lengths 1 to 48. */
    private static final Pattern ARCHIVE_NAME = Pattern.compile("[.\\-_A-Za-z0-9]{1,48}");
    /**
     * Records whether the archive's name came from the template or was generated, so a later
     * update can tell an explicit name being dropped, which replaces the archive on AWS, from an
     * unnamed archive keeping the name it was given. Read back off the stored attributes.
     */
    private static final String NAME_MODE_ATTR = "FlociArchiveNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";

    private final EventBridgeService eventBridgeService;

    @Inject
    public EventsArchiveCfnProvisioner(EventBridgeService eventBridgeService) {
        this.eventBridgeService = eventBridgeService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String explicitName = resolvePresent(props, "ArchiveName", ctx);
        boolean hasExplicitName = explicitName != null;
        if (hasExplicitName && !ARCHIVE_NAME.matcher(explicitName).matches()) {
            throw new AwsException("ValidationError", TYPE + " ArchiveName must be 1 to 48 characters from "
                    + "[.\\-_A-Za-z0-9], got " + explicitName, 400);
        }
        String sourceArn = ctx.resolveOptional(props, "SourceArn");
        if (sourceArn == null || sourceArn.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires SourceArn", 400);
        }
        String description = resolvePresent(props, "Description", ctx);
        // The pattern is a string on the wire; a template writes it as a JSON object, so blank can
        // only be AWS::NoValue, which the engine resolves to an empty string.
        String eventPattern = props == null ? null
                : ctx.engine().resolveJsonAttribute(props.path("EventPattern"));
        if (eventPattern != null && eventPattern.isBlank()) {
            eventPattern = null;
        }
        Integer retentionDays = retentionDays(props, ctx);

        // A stack from before this type had a provisioner holds the dispatcher's stub here, which
        // names no archive and never recorded a name mode, so the archive is created as on a first deploy.
        boolean updatesArchive = ctx.isUpdate() && attributesBefore.containsKey(NAME_MODE_ATTR);
        Archive prior = updatesArchive
                ? eventBridgeService.describeArchive(ctx.priorPhysicalId(), ctx.region())
                : null;
        boolean sourceChanged = prior != null && !sourceArn.equals(prior.getEventSourceArn());
        boolean nameWasGenerated = NAME_MODE_GENERATED.equals(attributesBefore.get(NAME_MODE_ATTR));
        if ((sourceChanged || nameWasGenerated) && hasExplicitName && explicitName.equals(ctx.priorPhysicalId())) {
            throw new AwsException("ValidationError",
                    "CloudFormation cannot update a stack when a custom-named resource requires "
                            + "replacing. Rename " + explicitName + " and update the stack again.", 400);
        }
        String name = physicalName(r, ctx, explicitName, hasExplicitName, updatesArchive, sourceChanged);

        Archive archive;
        if (updatesArchive && ctx.reusesPriorEntity(name)) {
            snapshotBeforeUpdate(r, prior, ctx.region());
            archive = eventBridgeService.updateArchive(name, description, eventPattern, retentionDays, ctx.region());
        } else {
            archive = eventBridgeService.createArchive(name, sourceArn, description, eventPattern,
                    retentionDays == null ? 0 : retentionDays, ctx.region());
        }
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", archive.getArchiveArn());
        r.getAttributes().put(NAME_MODE_ATTR, hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The resolved property, or null when it is absent or an intrinsic resolved to AWS::NoValue,
     * which the engine yields as an empty string. A literal string is kept as written.
     */
    private static String resolvePresent(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        boolean literal = props != null && props.path(name).isTextual();
        return literal || (value != null && !value.isBlank()) ? value : null;
    }

    /** The template's retention, or null when it gives none so an update leaves the stored one alone. */
    private static Integer retentionDays(JsonNode props, ProvisionContext ctx) {
        String raw = ctx.resolveOptional(props, "RetentionDays");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    TYPE + " RetentionDays must be an integer, got " + raw, 400);
        }
    }

    /**
     * The template's name when it gives one; otherwise the generated name the archive already has,
     * unless the previous name was explicit or the source changed, since either replaces the
     * archive on AWS, or the prior resource names no archive; and a fresh generated name otherwise.
     */
    private static String physicalName(StackResource r, ProvisionContext ctx, String explicitName,
                                       boolean hasExplicitName, boolean updatesArchive, boolean sourceChanged) {
        if (hasExplicitName) {
            return explicitName;
        }
        boolean explicitNameRemoved = ctx.isUpdate()
                && NAME_MODE_EXPLICIT.equals(r.getAttributes().get(NAME_MODE_ATTR));
        if (updatesArchive && !explicitNameRemoved && !sourceChanged) {
            return ctx.priorPhysicalId();
        }
        return ctx.generatePhysicalName(r.getLogicalId(), ARCHIVE_NAME_MAX_LENGTH, false);
    }

    /**
     * Keeps the description, pattern and retention the archive has before an in-place update
     * changes them, so {@link #rollbackUpdate} can put them back.
     */
    private static void snapshotBeforeUpdate(StackResource r, Archive current, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", current.getArchiveName());
        snapshot.put("description", current.getDescription());
        snapshot.put("eventPattern", current.getEventPattern());
        snapshot.put("retentionDays", current.getRetentionDays());
        r.getAttributes().put(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("EventBridge archive", physicalId,
                () -> eventBridgeService.deleteArchive(physicalId, region),
                "ResourceNotFoundException");
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it. UpdateArchive leaves an omitted field alone, so a field the snapshot holds
     * no value for is sent empty to clear what the update set. With neither on the resource the
     * provision failed before it changed anything, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the archive update snapshot for "
                    + resource.getLogicalId(), e);
        }
        eventBridgeService.updateArchive(snapshot.path("name").asText(),
                textOrEmpty(snapshot.path("description")),
                textOrEmpty(snapshot.path("eventPattern")),
                snapshot.path("retentionDays").asInt(),
                snapshot.path("region").asText());
        resource.getAttributes().remove(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private static String textOrEmpty(JsonNode value) {
        return value.isTextual() ? value.textValue() : "";
    }
}
