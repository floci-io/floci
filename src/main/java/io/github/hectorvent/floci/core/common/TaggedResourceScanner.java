package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Estate-wide view of every tagged resource floci holds, assembled by reading the persisted
 * model state of every service.
 *
 * <p>Resource Groups Tagging's {@code GetResources} is the one AWS API that must see resources
 * it does not own. Floci stores tags on each service's own model (an EC2 volume's tags live on
 * the {@code Volume}, an IAM role's on the {@code IamRole}), so a tagging service that only
 * reads its own store answers "no resources" for an estate full of tagged ones. Rather than ask
 * every service to write through to a second index — which is one hand-wire per service, and
 * silently wrong for any service that forgets — this scanner reads the stores that already
 * exist, through the one seam every service passes through: {@link StorageFactory}.
 *
 * <p>Each stored value is serialized to a JSON tree and inspected for two things: a tag
 * collection, and the resource's own ARN. Both are recognised structurally, so a service added
 * tomorrow is covered with no change here.
 *
 * <h2>Side tag-stores</h2>
 * Some services keep {@code ResourceId → tags} in a store separate from the resource's own model
 * instead of a {@code tags} field on it — Route53 keeps a hosted zone's tags in
 * {@code route53-tags.json} keyed {@code hostedzone/<id>}, and ELBv2, CodeDeploy, Config,
 * CloudFront and Transfer do the same. {@link StorageFactory.OwnedBackend#flatTagMapStore()}
 * marks a store whose registered value type is itself {@code Map<String, String>}; for those, the
 * whole node passed to {@link #collect} at depth zero {@code is} the tag collection rather than
 * containing one, so it is read directly with no field named {@code tags} required, and an ARN is
 * sought from the storage key alone: first as an ARN the key already is, then via
 * {@link ArnSynthesizer}.
 *
 * <h2>Picking the right ARN</h2>
 * A model often carries several ARN-valued fields, most of them references to other resources —
 * a Lambda function's execution role, an EC2 instance's instance profile. Attributing a
 * resource's tags to a referenced ARN would be worse than returning nothing, so the scanner only
 * accepts an ARN it can tie to the model itself:
 * <ol>
 *   <li>a field named {@code arn} or {@code resourceArn}; failing that,</li>
 *   <li>a field named {@code <base>Arn} whose {@code <base>} appears in the model's type name —
 *       {@code functionArn} on {@code LambdaFunction}, {@code clusterArn} on {@code EmrCluster},
 *       {@code deliveryStreamARN} on {@code DeliveryStreamDescription}. {@code roleArn} on
 *       {@code CreateClusterRequest} does not qualify, which is the point.</li>
 * </ol>
 * More than one match, or none, means no ARN, and the model is handed to the
 * {@link ArnSynthesizer} implementations instead.
 */
@ApplicationScoped
public class TaggedResourceScanner {

    private static final Logger LOG = Logger.getLogger(TaggedResourceScanner.class);

    /** How far into a stored value to look for nested tagged resources. */
    private static final int MAX_DEPTH = 3;

    /** Shortest {@code <base>} in a {@code <base>Arn} field name that may be matched to a type name. */
    private static final int MIN_BASE_LENGTH = 3;

    private final StorageFactory storageFactory;
    private final ObjectMapper objectMapper;
    private final List<ArnSynthesizer> synthesizers;
    private final Instance<RequestContext> requestContextInstance;
    private final String defaultAccountId;

    @Inject
    public TaggedResourceScanner(StorageFactory storageFactory,
                                 ObjectMapper objectMapper,
                                 Instance<ArnSynthesizer> synthesizers,
                                 Instance<RequestContext> requestContextInstance,
                                 io.github.hectorvent.floci.config.EmulatorConfig config) {
        this.storageFactory = storageFactory;
        this.objectMapper = objectMapper;
        List<ArnSynthesizer> found = new ArrayList<>();
        if (synthesizers != null) {
            synthesizers.forEach(found::add);
        }
        this.synthesizers = List.copyOf(found);
        this.requestContextInstance = requestContextInstance;
        this.defaultAccountId = config == null ? "000000000000" : config.defaultAccountId();
    }

    /** Constructor for unit tests that supply their own collaborators. */
    public TaggedResourceScanner(StorageFactory storageFactory,
                                 ObjectMapper objectMapper,
                                 List<ArnSynthesizer> synthesizers,
                                 String defaultAccountId) {
        this.storageFactory = storageFactory;
        this.objectMapper = objectMapper;
        this.synthesizers = synthesizers == null ? List.of() : List.copyOf(synthesizers);
        this.requestContextInstance = null;
        this.defaultAccountId = defaultAccountId;
    }

    /**
     * Every tagged resource the calling account can see, keyed by ARN. Tags for the same ARN found
     * in more than one store are merged, later stores winning on a key collision.
     *
     * <p>Scoped to one account on purpose: {@code AccountAwareStorageBackend#keys()} returns only
     * the current credential's entries, the same window every other service reads through, so a
     * second account's tagged resources can never surface here.
     */
    public Map<String, Map<String, String>> scan() {
        Map<String, Map<String, String>> byArn = new LinkedHashMap<>();
        if (storageFactory == null) {
            return byArn;
        }
        String accountId = currentAccountId();
        for (StorageFactory.OwnedBackend owned : storageFactory.ownedBackends()) {
            AccountAwareStorageBackend<?> backend = owned.backend();
            java.util.Set<String> keys;
            try {
                keys = backend.keys();
            } catch (RuntimeException e) {
                LOG.debugv("Skipping store {0} in tagged-resource scan: {1}",
                        owned.fileName(), e.toString());
                continue;
            }
            for (String logicalKey : keys) {
                Object value;
                try {
                    value = backend.get(logicalKey).orElse(null);
                } catch (RuntimeException e) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.valueToTree(value);
                } catch (RuntimeException e) {
                    LOG.debugv("Skipping unserializable entry in {0}: {1}", owned.fileName(), e.toString());
                    continue;
                }
                collect(byArn, owned.serviceName(), owned.fileName(), logicalKey, accountId,
                        value.getClass().getSimpleName(), node, 0, owned.flatTagMapStore());
            }
        }
        return byArn;
    }

    /** Mirrors {@code AccountAwareStorageBackend}'s own account resolution. */
    private String currentAccountId() {
        if (requestContextInstance != null) {
            try {
                String accountId = requestContextInstance.get().getAccountId();
                if (accountId != null) {
                    return accountId;
                }
            } catch (ContextNotActiveException ignored) {
                // outside request scope — fall through to the default account
            }
        }
        return defaultAccountId;
    }

    private void collect(Map<String, Map<String, String>> byArn,
                         String serviceName,
                         String storeName,
                         String logicalKey,
                         String accountId,
                         String typeHint,
                         JsonNode node,
                         int depth,
                         boolean wholeNodeIsTags) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collect(byArn, serviceName, storeName, logicalKey, accountId, typeHint, element, depth + 1, false);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        Map<String, String> tags = wholeNodeIsTags ? flatTags(node) : extractTags(node);
        if (!tags.isEmpty()) {
            String arn = resolveArn(node, typeHint);
            if (arn == null) {
                arn = synthesize(serviceName, storeName, logicalKey, accountId, node);
            }
            if (arn == null && isArn(logicalKey)) {
                // The storage key is already the resource's ARN — true of every side tag-store
                // seen so far except Route53's, which needs a synthesizer instead.
                arn = logicalKey;
            }
            if (arn != null) {
                byArn.computeIfAbsent(arn, k -> new LinkedHashMap<>()).putAll(tags);
                // A model that resolved to its own ARN has been accounted for; its children are
                // sub-structures of it, not sibling resources.
                return;
            }
        }

        if (wholeNodeIsTags) {
            // Every field on this node was already read as a tag above; there is nothing else to
            // recurse into.
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode child = field.getValue();
            if (child.isContainerNode()) {
                collect(byArn, serviceName, storeName, logicalKey, accountId, field.getKey(), child, depth + 1, false);
            }
        }
    }

    private String synthesize(String serviceName, String storeName, String logicalKey,
                              String accountId, JsonNode node) {
        for (ArnSynthesizer synthesizer : synthesizers) {
            try {
                String arn = synthesizer.synthesize(serviceName, storeName, logicalKey, accountId, node)
                        .orElse(null);
                if (arn != null) {
                    return arn;
                }
            } catch (RuntimeException e) {
                LOG.debugv("ArnSynthesizer {0} failed for {1}: {2}",
                        synthesizer.getClass().getSimpleName(), logicalKey, e.toString());
            }
        }
        return null;
    }

    /**
     * Reads the tags off one model node. Three shapes appear across floci's services and all
     * three are accepted: a string-to-string map, a list of {@code {Key, Value}} objects, and a
     * list of single-entry maps.
     */
    public static Map<String, String> extractTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"tags".equalsIgnoreCase(field.getKey())) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonNode> tag = entries.next();
                    if (tag.getValue().isValueNode()) {
                        tags.put(tag.getKey(), tag.getValue().asText());
                    }
                }
            } else if (value.isArray()) {
                for (JsonNode element : value) {
                    if (!element.isObject()) {
                        continue;
                    }
                    JsonNode key = firstOf(element, "Key", "key");
                    JsonNode val = firstOf(element, "Value", "value");
                    if (key != null && key.isValueNode()) {
                        tags.put(key.asText(), val != null && val.isValueNode() ? val.asText() : "");
                    }
                }
            }
        }
        tags.remove("");
        return tags;
    }

    /**
     * Reads every field on {@code node} as a tag, for a store whose registered value type is
     * itself {@code Map<String, String>} (see {@link StorageFactory.OwnedBackend#flatTagMapStore()}).
     * Unlike {@link #extractTags}, there is no {@code tags} field to look for: the node's fields
     * are the tag keys and values directly.
     */
    static Map<String, String> flatTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isValueNode()) {
                tags.put(field.getKey(), field.getValue().asText());
            }
        }
        return tags;
    }

    private static JsonNode firstOf(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode found = node.get(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The ARN of the resource this node represents, or {@code null} when no field on the node can
     * be tied to the node's own identity. See the class comment for why a near-miss is discarded
     * rather than guessed at.
     */
    static String resolveArn(JsonNode node, String typeHint) {
        String direct = null;
        List<String> matched = new ArrayList<>();
        String hint = typeHint == null ? "" : typeHint.toLowerCase(Locale.ROOT);

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (!value.isTextual() || !isArn(value.asText())) {
                continue;
            }
            String name = field.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("arn") || lower.equals("resourcearn")) {
                if (direct == null) {
                    direct = value.asText();
                }
                continue;
            }
            if (!lower.endsWith("arn")) {
                continue;
            }
            String base = lower.substring(0, lower.length() - 3);
            if (base.length() >= MIN_BASE_LENGTH && hint.contains(base)) {
                matched.add(value.asText());
            }
        }
        if (direct != null) {
            return direct;
        }
        return matched.size() == 1 ? matched.get(0) : null;
    }

    private static boolean isArn(String candidate) {
        return candidate != null && candidate.startsWith("arn:") && candidate.split(":", 6).length >= 6;
    }
}
