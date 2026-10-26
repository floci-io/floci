package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Provisions {@code AWS::EC2::KeyPair}. The key name is the physical id and {@code Ref};
 * {@code Fn::GetAtt} exposes {@code KeyPairId} and {@code KeyFingerprint}.
 *
 * <p>Without {@code PublicKeyMaterial} the key is made by CreateKeyPair, of the {@code KeyType} and
 * in the {@code KeyFormat} the template asks for, and its private key is stored, as on AWS, in the
 * {@code SecureString} parameter {@code /ec2/keypair/<KeyPairId>}. With it the key is imported, its
 * type read from the material, so {@code KeyType} is ignored as AWS documents and {@code KeyFormat}
 * has no private key to format, and nothing is written to Parameter Store. Tags are applied once the
 * key exists and the parameter is written last, so a step that fails leaves only the key for the undo
 * to delete. A created key takes its parameter with it wherever it is deleted: on stack delete,
 * rollback and replacement cleanup alike.
 *
 * <p>Every property is create-only and {@code KeyName} is required, so the resource is always
 * custom-named. An update that changes another property under the same name is refused, as
 * CloudFormation refuses it; one that changes the name creates the new key and leaves the displaced
 * one to the {@link ReplacementCleanup} record.
 */
@ApplicationScoped
public class Ec2KeyPairCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2KeyPairCfnProvisioner.class);
    private static final String TYPE = "AWS::EC2::KeyPair";
    /** The registry schema's enums; EC2 itself takes the same values. */
    private static final List<String> KEY_TYPES = List.of("rsa", "ed25519");
    private static final List<String> KEY_FORMATS = List.of("pem", "ppk");
    /** The create-only properties the key was made from, so an update can tell whether any changed. */
    private static final String INPUTS_ATTR = "__FlociKeyPairInputs";
    /**
     * The inputs of the key a rename displaced, for a rollback of the rename to put back:
     * {@link ReplacementCleanup} restores only the public attributes. Empty when the displaced
     * resource had none, as a stub does.
     */
    private static final String PRIOR_INPUTS_ATTR = "__FlociKeyPairPriorInputs";
    /**
     * The keys CreateKeyPair made that the resource no longer names but still owes a delete, by
     * name to id: those a rename displaced and a replacement a rollback undoes. The cleanup deletes
     * them by name alone, so this keeps the id whose parameter goes too when the key is already gone.
     * An entry leaves once its delete succeeds.
     */
    private static final String CREATED_KEYS_ATTR = "__FlociKeyPairCreatedKeys";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Ec2Service ec2Service;
    private final SsmService ssmService;

    @Inject
    public Ec2KeyPairCfnProvisioner(Ec2Service ec2Service, SsmService ssmService) {
        this.ec2Service = ec2Service;
        this.ssmService = ssmService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        String name = resolveProperty(props, "KeyName", ctx);
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires KeyName.", 400);
        }
        String keyType = resolveProperty(props, "KeyType", ctx);
        String keyFormat = resolveProperty(props, "KeyFormat", ctx);
        String publicKeyMaterial = resolveProperty(props, "PublicKeyMaterial", ctx);
        requireAllowed("KeyType", keyType, KEY_TYPES);
        requireAllowed("KeyFormat", keyFormat, KEY_FORMATS);
        List<Tag> tags = Ec2Tags.validatedTags(ctx.resolveTags(props, "Tags"));
        ObjectNode inputs = inputs(name, keyType, keyFormat, publicKeyMaterial, tags);

        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        // A dispatcher stub created nothing, so a key that takes over its id is a create.
        if (ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore)) {
            if (madeFrom(readInputs(attributesBefore.get(INPUTS_ATTR), r), inputs)) {
                // No property changed, and a key has nothing that changes in place.
                ReplacementCleanup.record(r, ctx, attributesBefore);
                return;
            }
            if (name.equals(ctx.priorPhysicalId())) {
                throw CfnExceptions.customNamedReplacement(name);
            }
        }

        if (publicKeyMaterial != null && publicKeyMaterial.isEmpty()) {
            // What ImportKeyPair answers for an empty PublicKeyMaterial.
            throw new AwsException("MissingParameter", "The request must contain the parameter PublicKeyMaterial", 400);
        }
        KeyPair key = publicKeyMaterial == null
                ? ec2Service.createKeyPair(ctx.region(), name, keyType, keyFormat)
                : ec2Service.importKeyPair(ctx.region(), name, publicKeyMaterial);
        r.setPhysicalId(key.getKeyName());
        r.getAttributes().put("KeyPairId", key.getKeyPairId());
        r.getAttributes().put("KeyFingerprint", key.getKeyFingerprint());
        r.getAttributes().put(INPUTS_ATTR, inputs.toString());
        if (ctx.isUpdate()) {
            r.getAttributes().put(PRIOR_INPUTS_ATTR, attributesBefore.getOrDefault(INPUTS_ATTR, ""));
            String priorCreatedId = createdKeyId(attributesBefore, r);
            if (priorCreatedId != null) {
                Map<String, String> owed = createdKeys(r);
                owed.put(ctx.priorPhysicalId(), priorCreatedId);
                putCreatedKeys(r, owed);
            }
        }
        CfnRollback.guarded(r, ctx, true,
                () -> ec2Service.deleteKeyPair(ctx.region(), null, key.getKeyPairId()),
                () -> { },
                () -> {
                    if (!tags.isEmpty()) {
                        ec2Service.createTags(ctx.region(), List.of(key.getKeyPairId()), tags);
                    }
                    if (publicKeyMaterial == null) {
                        ssmService.putParameter(parameterName(key.getKeyPairId()), key.getKeyMaterial(),
                                "SecureString", null, false, ctx.region());
                    }
                });
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The resolved property, or null when it is absent or {@code AWS::NoValue}, directly or as the
     * {@code Fn::If} branch its condition chooses. Every other value is kept as resolved, an empty
     * string included, so a value a parameter supplies acts as the same value written literally.
     */
    private static String resolveProperty(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode value = ctx.engine().omitNoValue(props.get(name));
        return value.isMissingNode() ? null : ctx.engine().resolve(value);
    }

    private static void requireAllowed(String property, String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new AwsException("ValidationError",
                    TYPE + " " + property + " must be " + String.join(" or ", allowed) + ", got '" + value + "'", 400);
        }
    }

    /**
     * The create-only properties as resolved, null when absent; tags as a sorted map, because their
     * order is no change, and null when there are none.
     */
    private static ObjectNode inputs(String name, String keyType, String keyFormat, String publicKeyMaterial,
                                     List<Tag> tags) {
        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.put("KeyName", name);
        inputs.put("KeyType", keyType);
        inputs.put("KeyFormat", keyFormat);
        inputs.put("PublicKeyMaterial", publicKeyMaterial);
        if (tags.isEmpty()) {
            inputs.putNull("Tags");
        } else {
            Map<String, String> sortedTags = new TreeMap<>();
            tags.forEach(tag -> sortedTags.put(tag.getKey(), tag.getValue()));
            inputs.set("Tags", MAPPER.valueToTree(sortedTags));
        }
        return inputs;
    }

    /** The inputs a key was recorded with, or null when the resource carries none it can read. */
    private static JsonNode readInputs(String recorded, StackResource r) {
        if (recorded == null || recorded.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(recorded);
        } catch (JsonProcessingException e) {
            LOG.warnv("Unreadable key pair inputs on {0}, treating them as unknown: {1}",
                    r.getLogicalId(), e.getMessage());
            return null;
        }
    }

    /**
     * Whether a key recorded with {@code recorded} was made from {@code inputs}, compared property by
     * property; a property the record lacks counts as absent, so a record written before a property
     * was tracked still matches a template that leaves it out.
     */
    private static boolean madeFrom(JsonNode recorded, ObjectNode inputs) {
        if (recorded == null) {
            return false;
        }
        for (Map.Entry<String, JsonNode> input : inputs.properties()) {
            JsonNode was = recorded.path(input.getKey());
            if (!(was.isMissingNode() ? NullNode.getInstance() : was).equals(input.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String parameterName(String keyPairId) {
        return "/ec2/keypair/" + keyPairId;
    }

    private void deletePrivateKeyParameter(String keyPairId, String region) {
        String parameter = parameterName(keyPairId);
        CfnDeletes.safeDelete("EC2 key pair private key parameter", parameter,
                () -> ssmService.deleteParameter(parameter, region), "ParameterNotFound");
    }

    /**
     * Deletes the key the resource's name holds, as a Cloud Control DeleteResource names it by that
     * identifier alone, with its parameter first when CreateKeyPair made it. The name is all this
     * goes by, so once this resource's key was deleted outside the stack, a key made under the same
     * name since then is the one deleted. When the name no longer holds the key this resource
     * recorded, the recorded key's parameter is deleted as well, so no private key outlives its key.
     */
    @Override
    public void delete(StackResource resource, String region) {
        // A stub left from before this type had a provisioner names no key.
        if (CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            return;
        }
        deleteNamed(resource.getPhysicalId(), createdKeyId(resource.getAttributes(), resource), region);
    }

    /**
     * Deletes the key the name holds, which is all a displaced or rolled-back key is recorded by, and
     * first the parameter holding its private key when CreateKeyPair made it: an imported key has
     * none. A key or parameter that is already gone counts as deleted; any other failure keeps the
     * key, so a retry still finds it.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        deleteNamed(physicalId, null, region);
    }

    /**
     * Deletes the key {@code name} holds, and, when {@code createdKeyId} names a key CreateKeyPair made
     * for this resource that the name no longer holds, that key's parameter too.
     */
    private void deleteNamed(String name, String createdKeyId, String region) {
        KeyPair current = findKeyPair(name, region);
        if (current != null) {
            deleteKeyPair(current, region);
        }
        if (createdKeyId != null && (current == null || !createdKeyId.equals(current.getKeyPairId()))) {
            deletePrivateKeyParameter(createdKeyId, region);
        }
    }

    /** The recorded KeyPairId when the recorded inputs show CreateKeyPair made the key, else null. */
    private static String createdKeyId(Map<String, String> attributes, StackResource r) {
        String recordedId = attributes.get("KeyPairId");
        JsonNode inputs = readInputs(attributes.get(INPUTS_ATTR), r);
        return recordedId != null && inputs != null && !inputs.path("PublicKeyMaterial").isTextual()
                ? recordedId : null;
    }

    private static Map<String, String> createdKeys(StackResource r) {
        Map<String, String> owed = new TreeMap<>();
        JsonNode recorded = readInputs(r.getAttributes().get(CREATED_KEYS_ATTR), r);
        if (recorded != null) {
            recorded.properties().forEach(e -> owed.put(e.getKey(), e.getValue().asText()));
        }
        return owed;
    }

    private static void putCreatedKeys(StackResource r, Map<String, String> owed) {
        if (owed.isEmpty()) {
            r.getAttributes().remove(CREATED_KEYS_ATTR);
        } else {
            r.getAttributes().put(CREATED_KEYS_ATTR, MAPPER.valueToTree(owed).toString());
        }
    }

    /**
     * Deletes a key the cleanup names, with the parameter of the created key recorded under that
     * name, and drops the record once the delete succeeds.
     */
    private ReplacementCleanup.Deleter owedDeleter(StackResource r) {
        return (type, name, region) -> {
            Map<String, String> owed = createdKeys(r);
            deleteNamed(name, owed.get(name), region);
            if (owed.remove(name) != null) {
                putCreatedKeys(r, owed);
            }
        };
    }

    /** The key pair {@code name} holds, or null when there is none. */
    private KeyPair findKeyPair(String name, String region) {
        try {
            return ec2Service.describeKeyPairs(region, List.of(name), List.of()).getFirst();
        } catch (AwsException e) {
            if (!"InvalidKeyPair.NotFound".equals(e.getErrorCode())) {
                throw e;
            }
            return null;
        }
    }

    private void deleteKeyPair(KeyPair key, String region) {
        if (key.getKeyMaterial() != null) {
            deletePrivateKeyParameter(key.getKeyPairId(), region);
        }
        ec2Service.deleteKeyPair(region, null, key.getKeyPairId());
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
        resource.getAttributes().remove(PRIOR_INPUTS_ATTR);
        return ReplacementCleanup.complete(resource, owedDeleter(resource));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_INPUTS_ATTR);
        resource.getAttributes().remove(CREATED_KEYS_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A rename is undone through the cleanup record, which names the displaced key again and deletes
     * the replacement; the displaced key's inputs are put back here, even when that delete fails, as
     * the resource names the displaced key from then on. Any other update changed nothing, so there
     * is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String replacement = resource.getPhysicalId();
        String priorInputs = resource.getAttributes().get(PRIOR_INPUTS_ATTR);
        String replacementCreatedId = createdKeyId(resource.getAttributes(), resource);
        if (priorInputs != null && replacementCreatedId != null) {
            Map<String, String> owed = createdKeys(resource);
            owed.put(replacement, replacementCreatedId);
            putCreatedKeys(resource, owed);
        }
        try {
            ReplacementCleanup.rollback(resource, owedDeleter(resource));
        } finally {
            if (priorInputs != null && !replacement.equals(resource.getPhysicalId())) {
                resource.getAttributes().remove(PRIOR_INPUTS_ATTR);
                Map<String, String> owed = createdKeys(resource);
                if (owed.remove(resource.getPhysicalId()) != null) {
                    putCreatedKeys(resource, owed);
                }
                if (priorInputs.isEmpty()) {
                    resource.getAttributes().remove(INPUTS_ATTR);
                } else {
                    resource.getAttributes().put(INPUTS_ATTR, priorInputs);
                }
            }
        }
        return true;
    }
}
