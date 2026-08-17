package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.TaggedResourceScanner;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cloud Control {@code ListResources} for the types nobody hand-wrote a lister for.
 *
 * <p>{@code CloudControlService.listResources} answers from a switch: a case per type, each one
 * calling that service's own read API and shaping the result. That is exact for the types it
 * covers and returns an empty list for every other type floci can create — a call that succeeds
 * while enumerating nothing, which reads to a client as "the estate is empty" rather than "this
 * emulator does not list that". A round trip over floci's 610 creatable types found 7 answering.
 *
 * <p>Adding a case per type does not close that: the AWS provider grows types faster than the
 * switch can. So this lister reads the stores that already exist, through the seam every service
 * passes through — {@link StorageFactory} — the same way
 * {@link TaggedResourceScanner} backs Resource Groups Tagging's {@code GetResources}. Nothing
 * here names a resource type.
 *
 * <h2>Which stored models are of the requested type</h2>
 * A type name is {@code AWS::<Service>::<Type>}. The service segment selects the stores (floci
 * names each store after its service; {@link #STORE_ALIASES} carries the handful whose AWS
 * spelling differs), and the stored value's own class name selects the entries: {@code Type}
 * exactly, or a class whose name ends with it after a service-flavoured prefix
 * ({@code EcsCluster} for {@code AWS::ECS::Cluster}) or a {@code Model} suffix
 * ({@code EcsServiceModel} for {@code AWS::ECS::Service}). A model that matches neither is left
 * out rather than guessed at — listing someone else's resource under this type is worse than
 * listing nothing.
 *
 * <h2>Which field is the identifier</h2>
 * Cloud Control identifies a resource by its type's primaryIdentifier, and a client round-trips
 * it: create returns a physical id, the following list has to name the same string. The
 * candidates are tried most-specific first — {@code <type>Arn}, then a bare {@code arn}, then
 * {@code <type>Id}, {@code <type>Name}, {@code id}, {@code name} — because a model routinely
 * carries several of them and the one qualified by the type name is the one that identifies it.
 * A model with none is skipped: a listing entry with no identifier is exactly the shape a
 * discovery client reports as an error.
 */
@ApplicationScoped
public class CloudControlStoreLister {

    private static final Logger LOG = Logger.getLogger(CloudControlStoreLister.class);

    /** AWS service segment (lowercased) → floci store service name, where the two differ. */
    private static final Map<String, String> STORE_ALIASES = Map.ofEntries(
            Map.entry("logs", "cloudwatchlogs"),
            Map.entry("events", "eventbridge"),
            Map.entry("cloudwatch", "cloudwatchmetrics"),
            Map.entry("elasticloadbalancingv2", "elbv2"),
            Map.entry("servicediscovery", "cloudmap"),
            Map.entry("kinesisfirehose", "firehose"),
            Map.entry("aps", "amp"),
            Map.entry("opensearchservice", "opensearch"),
            Map.entry("elasticsearch", "opensearch"),
            Map.entry("amazonmq", "amazonmq"),
            Map.entry("mq", "amazonmq"),
            Map.entry("stepfunctions", "stepfunctions"),
            Map.entry("resourcegroups", "tagging"),
            Map.entry("applicationautoscaling", "autoscaling"),
            Map.entry("autoscalingplans", "autoscaling"));

    /**
     * Suffixes a model class may carry that its AWS type name does not: floci stores a DynamoDB
     * table as {@code TableDefinition} and a Firehose stream as {@code DeliveryStreamDescription}.
     * Only tried after the class name has failed to match as written.
     */
    private static final List<String> MODEL_SUFFIXES =
            List.of("Model", "Definition", "Description", "Details", "Entry", "State");

    /** Upper bound on one listing, so a store nobody expected to be large cannot stall a call. */
    private static final int MAX_ENTRIES = 5000;

    private final StorageFactory storageFactory;
    private final ObjectMapper mapper;

    @Inject
    public CloudControlStoreLister(StorageFactory storageFactory, ObjectMapper mapper) {
        this.storageFactory = storageFactory;
        this.mapper = mapper;
    }

    /**
     * Every resource of {@code typeName} the calling account holds in {@code region}, read from
     * the owning service's own store. An unrecognised service segment, or a service with no
     * store, yields an empty list — the same answer the caller had before.
     */
    public List<CloudControlService.ResourceDescription> list(String region, String typeName) {
        if (storageFactory == null || typeName == null) {
            return List.of();
        }
        String[] segments = typeName.split("::");
        if (segments.length != 3) {
            return List.of();
        }
        String storeService = storeService(segments[1]);
        String bareType = segments[2];
        // Strict first: a class named exactly for the type wins outright. The suffix rule
        // (TableDefinition for Table) only runs when nothing matched as written, so a service
        // holding both an EcsTask and a TaskDefinition cannot answer AWS::ECS::Task with both.
        List<CloudControlService.ResourceDescription> found = scan(region, storeService, bareType, false);
        return found.isEmpty() ? scan(region, storeService, bareType, true) : found;
    }

    private List<CloudControlService.ResourceDescription> scan(String region, String storeService,
                                                               String bareType, boolean lenient) {
        List<CloudControlService.ResourceDescription> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StorageFactory.OwnedBackend owned : storageFactory.ownedBackends()) {
            if (!storeService.equals(owned.serviceName())) {
                continue;
            }
            AccountAwareStorageBackend<?> backend = owned.backend();
            Set<String> keys;
            try {
                keys = backend.keys();
            } catch (RuntimeException e) {
                LOG.debugv("Skipping store {0} for {1}: {2}", owned.fileName(), bareType, e.toString());
                continue;
            }
            for (String key : keys) {
                if (found.size() >= MAX_ENTRIES) {
                    return found;
                }
                Object value;
                try {
                    value = backend.get(key).orElse(null);
                } catch (RuntimeException e) {
                    continue;
                }
                String className = value == null ? null : value.getClass().getSimpleName();
                if (className == null
                        || !(lenient ? isOfType(className, bareType, storeService)
                                     : matches(className, bareType, storeService))) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.valueToTree(value);
                } catch (RuntimeException e) {
                    LOG.debugv("Skipping unserializable entry in {0}: {1}", owned.fileName(), e.toString());
                    continue;
                }
                if (node == null || !node.isObject()) {
                    continue;
                }
                String identifier = identifier(node, bareType);
                if (identifier == null || !inRegion(node, identifier, region)) {
                    continue;
                }
                if (!seen.add(identifier)) {
                    continue;
                }
                found.add(new CloudControlService.ResourceDescription(identifier, properties(node)));
            }
        }
        return found;
    }

    private static String storeService(String awsService) {
        String lower = awsService.toLowerCase(Locale.ROOT);
        return STORE_ALIASES.getOrDefault(lower, lower);
    }

    /**
     * Whether a stored model's class name says it is the requested type. Exact wins; otherwise the
     * class may carry a {@code Model} suffix and a prefix, and the prefix has to be the service's
     * own name. Anything looser attributes one type's resources to another: {@code
     * DaemonTaskDefinition} ends with {@code Definition} and {@code ContainerInstance} ends with
     * {@code Instance}, and neither is the type a client asking for those would mean.
     */
    static boolean isOfType(String className, String bareType, String storeService) {
        if (className == null || bareType == null) {
            return false;
        }
        if (matches(className, bareType, storeService)) {
            return true;
        }
        // The raw name is tried first on purpose: TaskDefinition IS AWS::ECS::TaskDefinition, and
        // stripping its suffix ahead of the exact check would lose it.
        for (String suffix : MODEL_SUFFIXES) {
            if (className.length() > suffix.length() && className.endsWith(suffix)
                    && matches(className.substring(0, className.length() - suffix.length()),
                            bareType, storeService)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String candidate, String bareType, String storeService) {
        if (candidate.equalsIgnoreCase(bareType)) {
            return true;
        }
        if (candidate.length() <= bareType.length()
                || !candidate.regionMatches(true, candidate.length() - bareType.length(),
                        bareType, 0, bareType.length())) {
            return false;
        }
        return candidate.substring(0, candidate.length() - bareType.length())
                .equalsIgnoreCase(storeService);
    }

    /** The model's primary identifier, or null when nothing on it identifies the model itself. */
    static String identifier(JsonNode node, String bareType) {
        String camel = Character.toLowerCase(bareType.charAt(0)) + bareType.substring(1);
        String[] candidates = {
            camel + "Arn", camel + "ARN", "arn", "resourceArn",
            camel + "Id", camel + "Identifier", camel + "Name",
            "id", "identifier", "name",
        };
        for (String candidate : candidates) {
            String value = textField(node, candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String textField(JsonNode node, String name) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equalsIgnoreCase(name)) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Floci keeps one store per service across every region, so a listing has to exclude the
     * regions the caller did not ask about. An ARN carries its region; a model may carry one in a
     * field. Neither present means the resource is not region-scoped, and it is listed.
     */
    static boolean inRegion(JsonNode node, String identifier, String region) {
        if (region == null || region.isBlank()) {
            return true;
        }
        if (identifier.startsWith("arn:")) {
            String[] parts = identifier.split(":", 6);
            if (parts.length >= 4 && !parts[3].isBlank()) {
                return region.equals(parts[3]);
            }
        }
        String declared = textField(node, "region");
        return declared == null || region.equals(declared);
    }

    /**
     * The model as a Cloud Control resource model. Cloud Control reports CFN property names, which
     * are floci's camelCase field names capitalised; tags are re-shaped into the CFN
     * {@code [{Key, Value}]} list whatever the model stored them as, because a tag-reading client
     * has only this listing to read them from.
     */
    private String properties(JsonNode node) {
        ObjectNode out = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if ("tags".equalsIgnoreCase(field.getKey())) {
                continue;
            }
            out.set(pascal(field.getKey()), pascalKeys(value));
        }
        Map<String, String> tags = TaggedResourceScanner.extractTags(node);
        if (!tags.isEmpty()) {
            ArrayNode list = out.putArray("Tags");
            tags.forEach((k, v) -> list.addObject().put("Key", k).put("Value", v == null ? "" : v));
        }
        try {
            return mapper.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private JsonNode pascalKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue() == null || field.getValue().isNull()) {
                    continue;
                }
                out.set(pascal(field.getKey()), pascalKeys(field.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode element : node) {
                out.add(pascalKeys(element));
            }
            return out;
        }
        return node;
    }

    private static String pascal(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
