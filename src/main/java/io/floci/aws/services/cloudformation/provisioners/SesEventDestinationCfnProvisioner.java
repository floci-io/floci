package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.ses.SesConfigurationSetService;
import io.floci.aws.services.ses.model.EventDestination;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class SesEventDestinationCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::SES::ConfigurationSetEventDestination";
    private static final String SET_ATTR = "__FlociSesEventDestinationConfigurationSet";
    private static final String NAME_MODE_ATTR = "__FlociSesEventDestinationNameMode";
    private static final String UPDATE_ATTR = "__FlociSesEventDestinationUpdate";
    private static final String CLEANUP_ATTR = "__FlociSesEventDestinationCleanup";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SesConfigurationSetService configurationSets;

    @Inject
    public SesEventDestinationCfnProvisioner(SesConfigurationSetService configurationSets) {
        this.configurationSets = configurationSets;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        if (resource.getAttributes().containsKey(UPDATE_ATTR)) {
            rollbackUpdate(resource);
            ctx = ctx.withPriorPhysicalId(resource.getPhysicalId());
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(properties);
        if (resolved == null || !resolved.isObject()) {
            throw invalid("Properties must be an object.");
        }
        if (resolved.has("Id")) {
            throw invalid("Unknown property: Id");
        }
        rejectNullDestinationBlocks(resolved.path("EventDestination"));
        JsonNode props = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(TYPE, resolved);
        String set = requiredText(props, "ConfigurationSetName");
        JsonNode definition = props.path("EventDestination");
        if (!definition.isObject()) {
            throw invalid("EventDestination must be an object.");
        }
        String explicitName = optionalText(definition, "Name");
        Address prior = priorAddress(resource, ctx);
        String name = explicitName != null && !explicitName.isBlank() ? explicitName
                : prior != null && "generated".equals(resource.getAttributes().get(NAME_MODE_ATTR))
                ? prior.name() : ctx.generatePhysicalName(resource.getLogicalId(), 64, false);
        Address target = new Address(set, name, ctx.region());
        EventDestination destination = destination(definition, name);

        deletePending(resource);
        boolean replacement = prior == null || !prior.equals(target);
        ObjectNode snapshot = ctx.isUpdate() ? snapshot(resource, prior, target, ctx) : null;
        if (snapshot != null && !replacement) {
            EventDestination previous = find(prior);
            snapshot.set("previous", MAPPER.valueToTree(previous));
            resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
            configurationSets.updateEventDestination(set, name, destination, ctx.accountId(), ctx.region(), true);
        } else {
            if (snapshot != null) {
                resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
            }
            configurationSets.createEventDestination(set, name, destination, ctx.accountId(), ctx.region(), true);
            if (snapshot != null) {
                snapshot.put("created", true);
                resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
                if (prior != null) {
                    CfnLifecycleRecords.setCleanup(resource, CLEANUP_ATTR, address(prior), true);
                }
            }
        }
        resource.setPhysicalId(name);
        resource.getAttributes().put("Id", name);
        resource.getAttributes().put(SET_ATTR, set);
        resource.getAttributes().put(NAME_MODE_ATTR, explicitName == null || explicitName.isBlank()
                ? "generated" : "explicit");
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
    }

    private Address priorAddress(StackResource resource, ProvisionContext ctx) {
        if (!ctx.isUpdate()) {
            return null;
        }
        String set = resource.getAttributes().get(SET_ATTR);
        if (set == null) {
            if (!CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), resource.getAttributes())) {
                throw new IllegalStateException("Missing SES event destination address for "
                        + resource.getLogicalId());
            }
            return null;
        }
        return new Address(set, ctx.priorPhysicalId(), ctx.region());
    }

    private EventDestination find(Address address) {
        return configurationSets.getEventDestinations(address.set(), address.region()).stream()
                .filter(destination -> address.name().equals(destination.getName()))
                .findFirst().orElseThrow(() -> new AwsException("NotFoundException",
                        "SES event destination " + address.name() + " does not exist.", 404));
    }

    private static EventDestination destination(JsonNode definition, String name) {
        ObjectNode normalized = ((ObjectNode) definition).deepCopy();
        if (normalized.get("SnsDestination") instanceof ObjectNode sns) {
            rename(sns, "TopicARN", "TopicArn");
        }
        if (normalized.get("KinesisFirehoseDestination") instanceof ObjectNode firehose) {
            rename(firehose, "IAMRoleARN", "IamRoleArn");
            rename(firehose, "DeliveryStreamARN", "DeliveryStreamArn");
        }
        try {
            EventDestination destination = MAPPER.treeToValue(normalized, EventDestination.class);
            destination.setName(name);
            return destination;
        } catch (JsonProcessingException failure) {
            throw invalid("Invalid EventDestination: " + failure.getOriginalMessage());
        }
    }

    private static void rejectNullDestinationBlocks(JsonNode definition) {
        if (!definition.isObject()) {
            return;
        }
        for (String key : new String[] {"SnsDestination", "KinesisFirehoseDestination",
                "EventBridgeDestination", "CloudWatchDestination"}) {
            if (definition.path(key).isNull()) {
                throw invalid(key + " must be an object.");
            }
        }
        if (definition.path("CloudWatchDestination").path("DimensionConfigurations").isNull()) {
            throw invalid("DimensionConfigurations must be an array.");
        }
        JsonNode dimensions = definition.path("CloudWatchDestination").path("DimensionConfigurations");
        if (dimensions.isArray()) {
            for (JsonNode dimension : dimensions) {
                if (dimension.isNull()) {
                    throw invalid("DimensionConfigurations entries must be objects.");
                }
            }
        }
    }

    private static void rename(ObjectNode node, String from, String to) {
        JsonNode value = node.remove(from);
        if (value != null) {
            node.set(to, value);
        }
    }

    private static ObjectNode snapshot(StackResource resource, Address prior, Address target, ProvisionContext ctx) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("physicalId", resource.getPhysicalId());
        snapshot.put("accountId", ctx.accountId());
        snapshot.put("replacement", prior == null || !prior.equals(target));
        snapshot.set("attributes", MAPPER.valueToTree(resource.getAttributes()));
        snapshot.set("target", address(target));
        if (prior != null) {
            snapshot.set("prior", address(prior));
        }
        return snapshot;
    }

    private record Address(String set, String name, String region) { }

    private static ObjectNode address(Address address) {
        return MAPPER.createObjectNode().put("set", address.set()).put("name", address.name())
                .put("region", address.region());
    }

    private static Address address(JsonNode node) {
        return new Address(node.path("set").asText(), node.path("name").asText(),
                node.path("region").asText());
    }

    private void delete(Address address) {
        CfnDeletes.safeDelete("SES event destination", address.name(),
                () -> configurationSets.deleteEventDestination(address.set(), address.name(), address.region()),
                "NotFoundException", "ConfigurationSetDoesNotExist");
    }

    @Override
    public void delete(StackResource resource, String region) {
        ObjectNode snapshot = read(resource, UPDATE_ATTR);
        if (snapshot != null && snapshot.path("replacement").asBoolean()
                && snapshot.path("created").asBoolean()) {
            delete(address(snapshot.path("target")));
        }
        if (snapshot != null && snapshot.has("prior")) {
            delete(address(snapshot.path("prior")));
        } else if (snapshot == null && resource.getAttributes().containsKey(SET_ATTR)) {
            delete(new Address(resource.getAttributes().get(SET_ATTR), resource.getPhysicalId(), region));
        }
        deletePending(resource);
        resource.getAttributes().remove(UPDATE_ATTR);
    }

    private void deletePending(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        CfnLifecycleRecords.deletePending(resource, CLEANUP_ATTR, pending, true,
                record -> delete(address(record)));
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, UPDATE_ATTR);
        if (snapshot == null) {
            return true;
        }
        if (snapshot.path("replacement").asBoolean()) {
            if (snapshot.path("created").asBoolean()) {
                delete(address(snapshot.path("target")));
            }
            resource.getAttributes().remove(CLEANUP_ATTR);
        } else {
            Address prior = address(snapshot.path("prior"));
            try {
                EventDestination previous = MAPPER.treeToValue(snapshot.path("previous"), EventDestination.class);
                configurationSets.updateEventDestination(prior.set(), prior.name(), previous,
                        snapshot.path("accountId").asText(), prior.region(), true);
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Invalid SES event destination update snapshot", failure);
            }
        }
        resource.setPhysicalId(snapshot.path("physicalId").asText(null));
        resource.getAttributes().clear();
        snapshot.path("attributes").fields().forEachRemaining(entry ->
                resource.getAttributes().put(entry.getKey(), entry.getValue().asText()));
        return true;
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_ATTR);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return "UPDATE_FAILED".equals(resource.getStatus()) && retainsFailedUpdateState(resource);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return resource.getAttributes().containsKey(CLEANUP_ATTR);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        return CfnLifecycleRecords.cleanupPhysicalId(resource, pending);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if (requiresUpdateRecovery(resource)) {
            return new UpdateCleanupResult(true, false, resource.getPhysicalId(), 0,
                    "SES event destination rollback is pending.");
        }
        return completePending(resource);
    }

    private UpdateCleanupResult completePending(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        return CfnLifecycleRecords.completePending(resource, CLEANUP_ATTR, UPDATE_ATTR, pending, true,
                record -> delete(address(record)));
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        if (pending == null) {
            return UpdateCleanupResult.notApplicable();
        }
        return completePending(resource);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_ATTR);
    }

    private static ObjectNode read(StackResource resource, String attribute) {
        return CfnLifecycleRecords.read(resource, attribute,
                "Invalid SES event destination lifecycle record",
                "SES event destination lifecycle record must be an object");
    }

    private static String requiredText(JsonNode node, String key) {
        String value = optionalText(node, key);
        if (value == null || value.isBlank()) {
            throw invalid(key + " must be a nonempty string.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(key + " must be a string.");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }
}
