package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.DeadlineCharSequence;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.ParameterStringFilter;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class SsmService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(SsmService.class);

    private static final String PUBLIC_PARAMETER_PREFIX = "/aws/service/";
    private static final DateTimeFormatter SOURCE_RESULT_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.US).withZone(ZoneOffset.UTC);
    static final String SECRET_REFERENCE_PREFIX = "/aws/reference/secretsmanager/";
    // Label is a ParameterStringFilter key too, but only GetParametersByPath accepts it.
    private static final Set<String> DESCRIBE_PARAMETERS_FILTER_KEYS =
            Set.of("Name", "Type", "KeyId", "Path", "Tier", "DataType");
    private static final String DEFAULT_SSM_KEY_ID = "alias/aws/ssm";
    private static final int STANDARD_TIER_MAX_VALUE_BYTES = 4096;
    private static final Set<String> PARAMETER_TIERS = Set.of("Standard", "Advanced", "Intelligent-Tiering");
    private static final int MAX_ALLOWED_PATTERN_LENGTH = 1024;
    private static final long ALLOWED_PATTERN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String TAG_KEY_REGEX = "^([\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*)$";
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile(TAG_KEY_REGEX);
    private static final int MAX_TAG_KEY_LENGTH = 128;
    /** The tail AWS appends to every rejected parameter name, whichever prefix the operation reserves. */
    private static final String PARAMETER_NAME_FORMAT_RULE = "If formed as a path, it can consist of sub-paths "
            + "divided by slash symbol; each sub-path can be formed as a mix of letters, numbers and the following "
            + "3 symbols .-_";
    /** A bare name, or a path of non-empty segments; AWS also lets a colon through here. */
    private static final Pattern UNLABEL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+|(/[A-Za-z0-9_.:-]+)+");

    /**
     * Account-default values for the service settings floci models. AWS rejects
     * unknown setting ids with ServiceSettingNotFound; so do we.
     */
    private static final Map<String, String> SERVICE_SETTING_DEFAULTS = Map.of(
            "/ssm/documents/console/public-sharing-permission", "Enable",
            "/ssm/parameter-store/default-parameter-tier", "Standard",
            "/ssm/parameter-store/high-throughput-enabled", "false",
            "/ssm/managed-instance/activation-tier", "standard"
    );

    private final StorageBackend<String, Parameter> parameterStore;
    private final StorageBackend<String, List<ParameterHistory>> historyStore;
    private final StorageBackend<String, List<String>> documentPermissionStore;
    private final StorageBackend<String, SsmDocument> documentStore;
    private final StorageBackend<String, ServiceSetting> serviceSettingStore;
    private final StorageBackend<String, SsmAssociation> associationStore;
    private final int maxParameterHistory;
    private final RegionResolver regionResolver;
    private final Ec2ImageCatalog imageCatalog;
    private final SecretsManagerService secretsManager;

    @Inject
    public SsmService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                      Ec2ImageCatalog imageCatalog, SecretsManagerService secretsManager) {
        this(
                storageFactory.create("ssm", "ssm-parameters.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-history.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-document-permissions.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-documents.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-service-settings.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-associations.json",
                        new TypeReference<>() {
                        }),
                config.services().ssm().maxParameterHistory(),
                regionResolver,
                imageCatalog,
                secretsManager
        );
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, documentPermissionStore, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000")); // partition-literal: test-shaped constructor default
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000")); // partition-literal: test-shaped constructor default
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               StorageBackend<String, SsmDocument> documentStore,
               StorageBackend<String, ServiceSetting> serviceSettingStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this(parameterStore, historyStore, documentPermissionStore, documentStore,
                serviceSettingStore, new InMemoryStorage<>(), maxParameterHistory, regionResolver);
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               StorageBackend<String, SsmDocument> documentStore,
               StorageBackend<String, ServiceSetting> serviceSettingStore,
               StorageBackend<String, SsmAssociation> associationStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this(parameterStore, historyStore, documentPermissionStore, documentStore,
                serviceSettingStore, associationStore, maxParameterHistory, regionResolver, null, null);
    }

    /**
     * Package-private constructor for testing without CDI. A null image catalog means no
     * public parameters are answered.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               StorageBackend<String, SsmDocument> documentStore,
               StorageBackend<String, ServiceSetting> serviceSettingStore,
               StorageBackend<String, SsmAssociation> associationStore,
               int maxParameterHistory, RegionResolver regionResolver,
               Ec2ImageCatalog imageCatalog, SecretsManagerService secretsManager) {
        this.parameterStore = parameterStore;
        this.historyStore = historyStore;
        this.documentPermissionStore = documentPermissionStore;
        this.documentStore = documentStore;
        this.serviceSettingStore = serviceSettingStore;
        this.associationStore = associationStore;
        this.maxParameterHistory = maxParameterHistory;
        this.regionResolver = regionResolver;
        this.imageCatalog = imageCatalog;
        this.secretsManager = secretsManager;
    }

    /**
     * Create or update a parameter.
     * Returns the version number.
     */
    public long putParameter(String name, String value, String type, String description, boolean overwrite, String region) {
        return putParameter(name, value, type, description, overwrite, null, region);
    }

    public long putParameter(String name, String value, String type, String description, boolean overwrite,
                             Map<String, String> tags, String region) {
        return putParameter(name, value, type, description, overwrite, tags, null, null, null, null, region);
    }

    public long putParameter(String name, String value, String type, String description,
                             boolean overwrite, Map<String, String> tags, String keyId, String allowedPattern,
                             String tier, List<JsonNode> policies, String region) {
        // Validation reads only the request, so it runs before the lock: an AllowedPattern match can take up
        // to ALLOWED_PATTERN_TIMEOUT_NANOS and must not stall other SSM writers.
        if (tier != null && !PARAMETER_TIERS.contains(tier)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + tier + "' at 'tier' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [Standard, Advanced, Intelligent-Tiering]", 400);
        }
        validateTagKeys(tags);
        rejectReservedName(name);

        if (overwrite && tags != null && !tags.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Invalid request: tags and overwrite can't be used together. To create a "
                            + "parameter with tags, please remove overwrite flag. To update tags for an "
                            + "existing parameter, please use AddTagsToResource or RemoveTagsFromResource.",
                    400);
        }

        // Terraform sends an empty AllowedPattern for every parameter; AWS treats it as no pattern.
        if (allowedPattern != null && allowedPattern.isEmpty()) {
            allowedPattern = null;
        }
        if (allowedPattern != null) {
            if (allowedPattern.length() > MAX_ALLOWED_PATTERN_LENGTH) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'allowedPattern' failed to satisfy constraint: "
                                + "Member must have length less than or equal to " + MAX_ALLOWED_PATTERN_LENGTH, 400);
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(allowedPattern);
            } catch (PatternSyntaxException e) {
                throw new AwsException("InvalidAllowedPatternException",
                        "The request doesn't meet the regular expression requirement.", 400);
            }
            boolean matches;
            try {
                // Both pattern and value come from the caller, so bound the backtracking a pattern can force.
                matches = pattern.matcher(new DeadlineCharSequence(value,
                        System.nanoTime() + ALLOWED_PATTERN_TIMEOUT_NANOS, "AllowedPattern timed out")).matches();
            } catch (IllegalStateException e) {
                throw new AwsException("InvalidAllowedPatternException",
                        "AllowedPattern took too long to evaluate against the parameter value.", 400);
            }
            if (!matches) {
                throw new AwsException("ParameterPatternMismatchException",
                        "Parameter value, cannot be validated against allowedPattern: " + allowedPattern, 400);
            }
        }

        // Same monitor as the other synchronized SSM writers: the version read and the write are one step.
        synchronized (this) {
            return storeParameter(name, value, type, description, overwrite, tags, keyId, allowedPattern,
                    tier, policies, region);
        }
    }

    private long storeParameter(String name, String value, String type, String description,
                                boolean overwrite, Map<String, String> tags, String keyId, String allowedPattern,
                                String tier, List<JsonNode> policies, String region) {
        String storageKey = regionKey(region, name);
        Parameter existing = parameterStore.get(storageKey).orElse(null);

        if (existing != null && !overwrite) {
            throw new AwsException("ParameterAlreadyExists",
                    "The parameter already exists. To overwrite this value, set the overwrite option in the request to true.",
                    400);
        }

        long version = (existing != null) ? existing.getVersion() + 1 : 1;

        Parameter parameter = new Parameter(name, value, type != null ? type : "String");
        parameter.setVersion(version);
        parameter.setDescription(description);
        if ("SecureString".equals(parameter.getType())) {
            parameter.setKeyId(keyId);
        }
        parameter.setAllowedPattern(allowedPattern);
        // AWS keeps existing policies until new ones, or an empty list, are sent.
        if (policies == null) {
            parameter.setPolicies(existing != null ? existing.getPolicies() : null);
        } else {
            parameter.setPolicies(policies.isEmpty() ? null : List.copyOf(policies));
        }
        if ("Intelligent-Tiering".equals(tier)) {
            boolean advanced = (existing != null && "Advanced".equals(tierOf(existing)))
                    || parameter.getPolicies() != null
                    || value.getBytes(StandardCharsets.UTF_8).length > STANDARD_TIER_MAX_VALUE_BYTES;
            tier = advanced ? "Advanced" : "Standard";
        }
        // AWS never moves an Advanced parameter back to Standard behind an overwrite that omits the tier
        // or asks for Intelligent-Tiering.
        parameter.setTier(tier != null ? tier : existing != null ? tierOf(existing) : "Standard");
        parameter.setArn(regionResolver.buildArn("ssm", region, "parameter" + name));
        parameter.setLastModifiedDate(Instant.now());

        if (existing != null && existing.getTags() != null) {
            parameter.setTags(new HashMap<>(existing.getTags()));
        } else if (tags != null && !tags.isEmpty()) {
            parameter.setTags(new HashMap<>(tags));
        }

        parameterStore.put(storageKey, parameter);
        addHistory(storageKey, parameter);

        LOG.infov("Put parameter: {0} in region {1} (version {2})", name, region, version);
        return version;
    }

    /** Reads on behalf of ECS task secrets, CodeBuild and CloudFormation ssm-secure, which decrypt. */
    public Parameter getParameter(String name, String region) {
        return getParameter(name, true, region);
    }

    public Parameter getParameter(String name, boolean withDecryption, String region) {
        if (name != null && name.startsWith(SECRET_REFERENCE_PREFIX)) {
            return secretReference(name, withDecryption, region);
        }
        return findParameter(name, region).orElseThrow(() -> {
            int separator = name == null ? -1 : name.lastIndexOf(':');
            if (separator > 0 && separator < name.length() - 1
                    && parameterStore.get(regionKey(region, name.substring(0, separator))).isPresent()) {
                return new AwsException("ParameterVersionNotFound",
                        "Systems Manager could not find version or label " + name.substring(separator + 1)
                                + " of " + name.substring(0, separator) + ". Verify the version and try again.", 400);
            }
            return new AwsException("ParameterNotFound", "Parameter " + name + " not found.", 400);
        });
    }

    /** A name AWS cannot answer, whatever the reason, is left out rather than failing the call. */
    public List<Parameter> getParameters(List<String> names, boolean withDecryption, String region) {
        List<Parameter> result = new ArrayList<>();
        for (String name : names) {
            try {
                result.add(getParameter(name, withDecryption, region));
            } catch (AwsException e) {
                LOG.debugv("GetParameters lists {0} as invalid: {1}", name, e.getMessage());
            }
        }
        return result;
    }

    /**
     * The resource IAM checks a reference read on: the secret's ARN, or for a secret that does not
     * exist, the ARN a GetSecretValue by that name is checked on, so a missing secret is refused too.
     * Null for a malformed name, which AWS rejects before it calls Secrets Manager.
     */
    String secretReferenceArn(String name, String region) {
        SecretReferenceName reference = SecretReferenceName.of(name);
        if (reference.malformed()) {
            return null;
        }
        String secretId = reference.secretId();
        try {
            return secretsManager.describeSecret(secretId, region).getArn();
        } catch (AwsException expected) {
            // No such secret: check the name instead, so the caller is refused either way.
            return regionResolver.buildArn("secretsmanager", region, "secret:" + secretId);
        }
    }

    /**
     * AWS answers {@code /aws/reference/secretsmanager/<secret-id>[:<version-id-or-label>]} with the
     * secret's value. The order of the checks and the error texts are what AWS sends, including the
     * missing slash and the "null" it prints for a name without a selector.
     */
    private Parameter secretReference(String name, boolean withDecryption, String region) {
        SecretReferenceName reference = SecretReferenceName.of(name);
        String secretId = reference.secretId();
        String selector = reference.selector();
        if (reference.malformed()) {
            throw new AwsException("ValidationException", "Invalid parameter name. Please use correct syntax "
                    + "for referencing a version/label  <name>:<version/label>", 400);
        }
        if (!withDecryption) {
            throw new AwsException("ValidationException",
                    "WithDecryption flag must be True for retrieving a Secret Manager secret.", 400);
        }
        Secret secret;
        SecretVersion version;
        try {
            secret = secretsManager.describeSecret(secretId, region);
            boolean byId = selector != null && secret.getVersions().containsKey(selector);
            // By the ARN just described, so a secret replaced in between is not found rather than read.
            // A miss still falls back to a secret named like the ARN's last part ("app-AbCdEf"), a
            // name AWS warns against, so that fallback is deliberately not guarded here.
            version = secretsManager.getSecretValue(secret.getArn(), byId ? selector : null,
                    byId ? null : selector, region);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw new AwsException("ParameterNotFound", "An error occurred (ParameterNotFound) when referencing "
                        + "Secrets Manager: Secret " + name.substring(1) + (selector == null ? "null" : "")
                        + " not found.", 400);
            }
            throw new AwsException("ValidationException", "Invalid Request to Secrets Manager", 400);
        }
        Parameter parameter = new Parameter(SECRET_REFERENCE_PREFIX + secretId, version.getSecretString(), "SecureString");
        parameter.setVersion(0);
        parameter.setArn(secret.getArn());
        parameter.setLastModifiedDate(version.getCreatedDate());
        parameter.setDataType(null);
        parameter.setSelector(selector == null ? null : ":" + selector);
        parameter.setSourceResult(sourceResult(secret, version));
        return parameter;
    }

    /** The secret id and the version id or staging label after the first colon, if any. */
    private record SecretReferenceName(String secretId, String selector) {
        static SecretReferenceName of(String name) {
            String reference = name.substring(SECRET_REFERENCE_PREFIX.length());
            int colon = reference.indexOf(':');
            return colon < 0 ? new SecretReferenceName(reference, null)
                    : new SecretReferenceName(reference.substring(0, colon), reference.substring(colon + 1));
        }

        /** A trailing colon or a second one, which AWS rejects before anything else. */
        boolean malformed() {
            return selector != null && (selector.isEmpty() || selector.contains(":"));
        }
    }

    /**
     * AWS's GetSecretValue result the way SSM serializes it: Gson's field names, order and default
     * US date format in UTC, with null fields left out.
     */
    private static String sourceResult(Secret secret, SecretVersion version) {
        ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put("ARN", secret.getArn())
                .put("name", secret.getName())
                .put("versionId", version.getVersionId());
        if (version.getSecretString() != null) {
            result.put("secretString", version.getSecretString());
        }
        // AWS dumps a binary secret's ByteBuffer internals here; left out until a caller reads them.
        if (version.getVersionStages() != null) {
            ArrayNode stages = result.putArray("versionStages");
            version.getVersionStages().forEach(stages::add);
        }
        return result.put("createdDate", SOURCE_RESULT_DATE.format(version.getCreatedDate())).toString();
    }

    /**
     * AWS reserves the {@code aws} and {@code ssm} namespaces, with or without a leading slash
     * and regardless of case, so an account can never write over a public parameter. The rule
     * keys on the first path segment rather than a bare prefix, so a name such as
     * {@code ssm-auto-stack-Param-ABC}, which CloudFormation generates for a stack whose name
     * starts with ssm, is still accepted.
     */
    private static void rejectReservedName(String name) {
        String bare = name == null ? "" : name.startsWith("/") ? name.substring(1) : name;
        String lower = bare.toLowerCase(Locale.ROOT);
        if (lower.equals("aws") || lower.equals("ssm") // partition-literal: reserved name prefix
                || lower.startsWith("aws/") || lower.startsWith("ssm/")) {
            throw new AwsException("ValidationException",
                    "Parameter name: can't be prefixed with \"aws\" or \"ssm\" (case-insensitive). "
                            + PARAMETER_NAME_FORMAT_RULE, 400);
        }
    }

    private Optional<Parameter> findParameter(String name, String region) {
        Optional<Parameter> stored = parameterStore.get(regionKey(region, name));
        if (stored.isPresent()) {
            return stored;
        }
        Optional<Parameter> selected = findSelectedVersion(name, region);
        if (selected.isPresent()) {
            return selected;
        }
        return publicParameter(name, region);
    }

    /**
     * A read may name a version or label as {@code name:version} or {@code name:label}. A
     * parameter name cannot contain a colon, so the last one always starts the selector. The
     * answer is a copy built from the history entry: it carries the base name and the selector
     * as AWS returns them, and nothing is written back.
     */
    private Optional<Parameter> findSelectedVersion(String name, String region) {
        int separator = name == null ? -1 : name.lastIndexOf(':');
        if (separator <= 0 || separator == name.length() - 1) {
            return Optional.empty();
        }
        String baseName = name.substring(0, separator);
        String selector = name.substring(separator + 1);
        String storageKey = regionKey(region, baseName);
        Optional<Parameter> current = parameterStore.get(storageKey);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        boolean isVersion = selector.chars().allMatch(Character::isDigit);
        // A label is unique across parameter versions because attaching it moves it from any prior version.
        return historyStore.get(storageKey).orElse(List.of()).stream()
                .filter(h -> isVersion
                        ? String.valueOf(h.getVersion()).equals(selector)
                        : h.getLabels() != null && h.getLabels().contains(selector))
                .max(Comparator.comparingLong(ParameterHistory::getVersion))
                .map(h -> {
                    Parameter parameter = new Parameter(baseName, h.getValue(), h.getType());
                    parameter.setVersion(h.getVersion());
                    parameter.setDescription(h.getDescription());
                    parameter.setLastModifiedDate(h.getLastModifiedDate());
                    parameter.setArn(current.get().getArn());
                    parameter.setDataType(current.get().getDataType());
                    parameter.setSelector(":" + selector);
                    return parameter;
                });
    }

    /**
     * AWS publishes read-only AMI id parameters under {@code /aws/service/} in every account
     * with no setup, so a read of one of those names is answered from the EC2 image catalog.
     * Nothing is written: like on AWS the parameter is not the account's own, so it never
     * shows up in DescribeParameters or GetParameterHistory. Any other name under the prefix
     * is still ParameterNotFound.
     */
    private Optional<Parameter> publicParameter(String name, String region) {
        if (imageCatalog == null || name == null || !name.startsWith(PUBLIC_PARAMETER_PREFIX)) {
            return Optional.empty();
        }
        return imageCatalog.findByPublicParameterName(name).map(image -> {
            Parameter parameter = new Parameter(name, image.imageId, "String");
            parameter.setArn(AwsArnUtils.Arn.of("ssm", region, "", "parameter" + name).toString());
            parameter.setLastModifiedDate(Instant.parse(image.creationDate));
            return parameter;
        });
    }

    public List<Parameter> getParametersByPath(String path, boolean recursive, String region) {
        String normalizedPath = path.endsWith("/") ? path : path + "/";
        String prefix = region + "::";

        List<Parameter> result = new ArrayList<>(parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            return underPath(key.substring(prefix.length()), normalizedPath, recursive);
        }));
        if (imageCatalog != null) {
            // The public names answer to the same path and Recursive rules as stored ones, so a
            // recursive query on an ancestor such as /aws lists them too.
            for (String name : imageCatalog.publicParameterNames()) {
                if (underPath(name, normalizedPath, recursive)) {
                    publicParameter(name, region).ifPresent(result::add);
                }
            }
        }
        return result;
    }

    private static boolean underPath(String paramName, String normalizedPath, boolean recursive) {
        if (!paramName.startsWith(normalizedPath)) {
            return false;
        }
        if (recursive) {
            return true;
        }
        return !paramName.substring(normalizedPath.length()).contains("/");
    }

    public synchronized void deleteParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        parameterStore.delete(storageKey);
        historyStore.delete(storageKey);
        LOG.infov("Deleted parameter: {0}", name);
    }

    public synchronized List<String> deleteParameters(List<String> names, String region) {
        List<String> deleted = new ArrayList<>();
        for (String name : names) {
            String storageKey = regionKey(region, name);
            if (parameterStore.get(storageKey).isPresent()) {
                parameterStore.delete(storageKey);
                historyStore.delete(storageKey);
                deleted.add(name);
            }
        }
        return deleted;
    }

    public List<ParameterHistory> getParameterHistory(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        return historyStore.get(storageKey).orElse(Collections.emptyList());
    }

    public List<Parameter> describeParameters(String region) {
        return describeParameters(List.of(), region);
    }

    /**
     * Lists the region's own parameters that pass every filter: filters combine with AND, the
     * values of one filter with OR. A key or option AWS rejects is an error rather than being
     * skipped, because dropping a filter silently widens the result.
     */
    public List<Parameter> describeParameters(List<ParameterStringFilter> filters, String region) {
        filters.forEach(SsmService::validateDescribeParametersFilter);
        String prefix = region + "::";
        return parameterStore.scan(key -> key.startsWith(prefix)).stream()
                .filter(p -> filters.stream().allMatch(f -> matchesParameterFilter(p, f)))
                .toList();
    }

    private static void validateDescribeParametersFilter(ParameterStringFilter filter) {
        String key = filter.key();
        boolean tag = key != null && key.startsWith("tag:") && key.length() > "tag:".length();
        // Set.of rejects a null lookup, so an absent key is checked before the membership test.
        if (!tag && (key == null || !DESCRIBE_PARAMETERS_FILTER_KEYS.contains(key))) {
            throw new AwsException("InvalidFilterKey", "The specified key isn't valid.", 400);
        }
        if (filter.option() != null) {
            Set<String> options = "Path".equals(key) ? Set.of("Recursive", "OneLevel")
                    : "Name".equals(key) ? Set.of("Equals", "BeginsWith", "Contains")
                    : Set.of("Equals", "BeginsWith");
            if (!options.contains(filter.option())) {
                throw new AwsException("InvalidFilterOption",
                        "The specified filter option isn't valid. Valid options are Equals and BeginsWith. "
                                + "For Path filter, valid options are Recursive and OneLevel.", 400);
            }
        }
        // Every key but a tag needs at least one value, and a path value must be absolute.
        boolean invalidValues = !tag && filter.values().isEmpty()
                || "Path".equals(key) && filter.values().stream().anyMatch(v -> !v.startsWith("/"));
        if (invalidValues) {
            throw new AwsException("InvalidFilterValue",
                    "The filter value isn't valid. Verify the value and try again.", 400);
        }
    }

    private static boolean matchesParameterFilter(Parameter parameter, ParameterStringFilter filter) {
        String key = filter.key();
        if (key.startsWith("tag:")) {
            String tagValue = parameter.getTags() == null ? null
                    : parameter.getTags().get(key.substring("tag:".length()));
            // A tag filter without values matches any parameter carrying the tag key.
            return tagValue != null && (filter.values().isEmpty() || matchesAny(tagValue, filter));
        }
        if ("Path".equals(key)) {
            boolean recursive = "Recursive".equals(filter.option());
            return filter.values().stream().anyMatch(path ->
                    underPath(parameter.getName(), path.endsWith("/") ? path : path + "/", recursive));
        }
        String actual = switch (key) {
            case "Name" -> parameter.getName();
            case "Type" -> parameter.getType();
            case "DataType" -> parameter.getDataType();
            case "Tier" -> tierOf(parameter);
            case "KeyId" -> keyIdOf(parameter);
            default -> null;
        };
        return actual != null && matchesAny(actual, filter);
    }

    /** Parameters stored before Floci kept a tier are Standard, as AWS defaults them. */
    static String tierOf(Parameter parameter) {
        return parameter.getTier() != null ? parameter.getTier() : "Standard";
    }

    /** A SecureString put without a KeyId is encrypted with the AWS managed key. */
    static String keyIdOf(Parameter parameter) {
        if (!"SecureString".equals(parameter.getType())) {
            return null;
        }
        return parameter.getKeyId() != null ? parameter.getKeyId() : DEFAULT_SSM_KEY_ID;
    }

    private static boolean matchesAny(String actual, ParameterStringFilter filter) {
        String option = filter.option() == null ? "Equals" : filter.option();
        return filter.values().stream().anyMatch(value -> switch (option) {
            case "BeginsWith" -> actual.startsWith(value);
            case "Contains" -> actual.contains(value);
            default -> actual.equals(value);
        });
    }

    public record LabelParameterVersionResult(long parameterVersion, List<String> invalidLabels) {}

    public synchronized LabelParameterVersionResult labelParameterVersion(String name, Long parameterVersion,
                                                             List<String> labels, String region) {
        validateLabelList(labels);

        String storageKey = regionKey(region, name);
        Parameter current = parameterStore.get(storageKey).orElseThrow(() ->
                new AwsException("ParameterNotFound", "Parameter " + name + " not found.", 400));

        long targetVersion = parameterVersion == null
                ? current.getVersion()
                : parameterVersion;

        List<ParameterHistory> existingHistory = historyStore.get(storageKey).orElse(List.of());
        List<ParameterHistory> updatedHistory = new ArrayList<>(existingHistory.size());

        ParameterHistory targetCopy = null;
        for (ParameterHistory h : existingHistory) {
            ParameterHistory copy = new ParameterHistory(h);
            if (copy.getVersion() == targetVersion) {
                targetCopy = copy;
            }
            updatedHistory.add(copy);
        }

        if (targetCopy == null) {
            throw new AwsException("ParameterVersionNotFound",
                    "Parameter version " + targetVersion + " not found.", 400);
        }

        List<String> invalidLabels = new ArrayList<>();
        List<String> validLabels = new ArrayList<>();
        if (labels != null) {
            for (String label : labels) {
                if (isValidLabel(label)) {
                    if (!validLabels.contains(label)) {
                        validLabels.add(label);
                    }
                } else {
                    if (!invalidLabels.contains(label)) {
                        invalidLabels.add(label);
                    }
                }
            }
        }

        List<String> targetLabels = targetCopy.getLabels() != null
                ? new ArrayList<>(targetCopy.getLabels())
                : new ArrayList<>();

        int newLabelCount = targetLabels.size();
        for (String validLabel : validLabels) {
            if (!targetLabels.contains(validLabel)) {
                newLabelCount++;
            }
        }
        if (newLabelCount > 10) {
            throw new AwsException("ParameterVersionLabelLimitExceeded",
                    "The parameter version already has the maximum number of labels (10).", 400);
        }

        for (ParameterHistory h : updatedHistory) {
            if (h.getVersion() != targetVersion && h.getLabels() != null) {
                List<String> otherLabels = new ArrayList<>(h.getLabels());
                if (otherLabels.removeAll(validLabels)) {
                    h.setLabels(otherLabels);
                }
            }
        }

        for (String validLabel : validLabels) {
            if (!targetLabels.contains(validLabel)) {
                targetLabels.add(validLabel);
            }
        }
        targetCopy.setLabels(targetLabels);

        historyStore.put(storageKey, updatedHistory);
        LOG.infov("Labeled parameter {0} version {1} with labels {2}", name, targetVersion, validLabels);
        return new LabelParameterVersionResult(targetVersion, invalidLabels);
    }

    public synchronized LabelParameterVersionResult labelParameterVersion(String name, long parameterVersion,
                                                             List<String> labels, String region) {
        return labelParameterVersion(name, Long.valueOf(parameterVersion), labels, region);
    }

    public record UnlabelParameterVersionResult(List<String> removedLabels, List<String> invalidLabels) {}

    /**
     * Detaches labels from one version. A label that is not on that version, including one
     * attached to a different version, is reported in {@code invalidLabels} and left in place.
     * As on AWS, {@code removedLabels} follows the order the labels are stored on the version,
     * {@code invalidLabels} the order of the request, and error messages echo the name as sent
     * although the lookup ignores surrounding whitespace.
     */
    public synchronized UnlabelParameterVersionResult unlabelParameterVersion(String name, long parameterVersion,
                                                                             List<String> labels, String region) {
        validateLabelList(labels);
        String trimmed = name.strip();
        if (AwsArnUtils.isArn(trimmed)) {
            throw new AwsException("ValidationException", "Parameter ARN is not supported for this operation.", 400);
        }
        String bare = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        String lower = bare.toLowerCase(Locale.ROOT);
        if (!UNLABEL_NAME_PATTERN.matcher(trimmed).matches() || lower.startsWith("ssm")) {
            throw new AwsException("ValidationException",
                    "Parameter name: can't be prefixed with \"ssm\" (case-insensitive). "
                            + PARAMETER_NAME_FORMAT_RULE, 400);
        }
        if (lower.startsWith("aws")) { // partition-literal: reserved parameter name prefix
            throw new AwsException("AccessDeniedException",
                    "No access to reserved parameter name: " + bare + ".", 400);
        }

        String storageKey = regionKey(region, trimmed);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound", "Parameter " + name + " not found.", 400);
        }

        List<ParameterHistory> existingHistory = historyStore.get(storageKey).orElse(List.of());
        List<ParameterHistory> updatedHistory = new ArrayList<>(existingHistory.size());
        ParameterHistory targetCopy = null;
        for (ParameterHistory h : existingHistory) {
            ParameterHistory copy = new ParameterHistory(h);
            if (copy.getVersion() == parameterVersion) {
                targetCopy = copy;
            }
            updatedHistory.add(copy);
        }
        if (targetCopy == null) {
            throw new AwsException("ParameterVersionNotFound",
                    "Systems Manager could not find version " + parameterVersion + " of " + name
                            + ". Verify the version and try again.", 400);
        }

        List<String> targetLabels = targetCopy.getLabels() != null
                ? new ArrayList<>(targetCopy.getLabels())
                : new ArrayList<>();
        Set<String> requested = new LinkedHashSet<>(labels);
        List<String> removedLabels = targetLabels.stream().filter(requested::contains).toList();
        List<String> invalidLabels = requested.stream().filter(l -> !targetLabels.contains(l)).toList();

        if (!removedLabels.isEmpty()) {
            targetLabels.removeAll(requested);
            targetCopy.setLabels(targetLabels);
            historyStore.put(storageKey, updatedHistory);
            LOG.infov("Removed labels {0} from parameter {1} version {2}", removedLabels, trimmed, parameterVersion);
        }
        return new UnlabelParameterVersionResult(removedLabels, invalidLabels);
    }

    private static void validateLabelList(List<String> labels) {
        String violation = labelListViolation(labels);
        if (violation != null) {
            throw validationErrors(List.of(violation));
        }
    }

    /**
     * The constraint a {@code ParameterLabelList} breaks, worded as AWS words it, or null. AWS
     * renders the list with Java's {@code List.toString}, so {@code [""]} reads {@code '[]'}.
     */
    static String labelListViolation(List<String> labels) {
        if (labels == null) {
            return "Value null at 'labels' failed to satisfy constraint: Member must not be null";
        }
        String prefix = "Value '" + labels + "' at 'labels' failed to satisfy constraint: ";
        if (labels.isEmpty()) {
            return prefix + "Member must have length greater than or equal to 1";
        }
        if (labels.size() > 10) {
            return prefix + "Member must have length less than or equal to 10";
        }
        boolean memberOutOfRange = labels.stream()
                .anyMatch(label -> label == null || label.isEmpty() || label.length() > 100);
        if (memberOutOfRange) {
            return prefix + "Member must satisfy constraint: [Member must have length less than or equal to 100, "
                    + "Member must have length greater than or equal to 1]";
        }
        return null;
    }

    static AwsException validationErrors(List<String> violations) {
        String count = violations.size() == 1
                ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return new AwsException("ValidationException", count + String.join("; ", violations), 400);
    }

    private static boolean isValidLabel(String label) {
        if (label == null || label.isEmpty() || label.length() > 100) {
            return false;
        }
        if (Character.isDigit(label.charAt(0))) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.startsWith("aws") || lower.startsWith("ssm")) {
            return false;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public void addTagsToResource(String resourceId, Map<String, String> tags, String region) {
        validateTagKeys(tags);
        String normalizedId = normalizeResourceId(resourceId);
        String storageKey = regionKey(region, normalizedId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() == null) {
            param.setTags(new HashMap<>());
        }
        param.getTags().putAll(tags);
        parameterStore.put(storageKey, param);
        LOG.debugv("Added tags to parameter: {0}", resourceId);
    }

    /**
     * The key constraint AWS checks before any lookup, naming the offending key by its 1-based
     * position in the request's {@code Tags} list.
     */
    public static void validateTagKeys(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        int position = 0;
        for (String key : tags.keySet()) {
            position++;
            String constraint;
            if (key.isEmpty()) {
                constraint = "Member must have length greater than or equal to 1";
            } else if (key.codePointCount(0, key.length()) > MAX_TAG_KEY_LENGTH) {
                constraint = "Member must have length less than or equal to " + MAX_TAG_KEY_LENGTH;
            } else if (!TAG_KEY_PATTERN.matcher(key).matches()) {
                constraint = "Member must satisfy regular expression pattern: " + TAG_KEY_REGEX;
            } else {
                continue;
            }
            throw new AwsException("ValidationException", "1 validation error detected: Value at 'tags."
                    + position + ".member.key' failed to satisfy constraint: " + constraint, 400);
        }
    }

    public Map<String, String> listTagsForResource(String resourceId, String region) {
        String normalizedId = normalizeResourceId(resourceId);
        String storageKey = regionKey(region, normalizedId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));
        return param.getTags() != null ? param.getTags() : Map.of();
    }

    public void removeTagsFromResource(String resourceId, List<String> tagKeys, String region) {
        String normalizedId = normalizeResourceId(resourceId);
        String storageKey = regionKey(region, normalizedId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() != null) {
            for (String key : tagKeys) {
                param.getTags().remove(key);
            }
            parameterStore.put(storageKey, param);
        }
        LOG.debugv("Removed tags from parameter: {0}", resourceId);
    }

    private static String normalizeResourceId(String resourceId) {
        if (resourceId != null && resourceId.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceId);
                if ("ssm".equals(arn.service())) {
                    String resource = arn.resource();
                    if (resource.startsWith("parameter/")) {
                        return resource.substring("parameter".length());
                    }
                    if (resource.startsWith("parameter")) {
                        return resource.substring("parameter".length());
                    }
                }
            } catch (IllegalArgumentException e) {
                // Not a valid ARN; fall through and use resourceId directly so callers
                // querying parameterStore produce the standard InvalidResourceId error.
                LOG.debugv("Failed to parse resourceId as ARN: {0}", resourceId);
            }
        }
        return resourceId;
    }

    // ──────────────────────── Documents and Share Permissions ────────────────
    // Documents live in documentStore; share state is kept alongside it in
    // documentPermissionStore so callers like LZA's Custom::SSMShareDocument handler
    // can round-trip ModifyDocumentPermission -> DescribeDocumentPermission.
    // Both permission operations resolve the document first, so an unknown document
    // raises InvalidDocument instead of silently minting or reporting share state for
    // a document that does not exist.

    public SsmDocument getDocument(String name, String region) {
        SsmDocument document = documentStore.get(regionKey(region, name))
                .orElseThrow(() -> new AwsException("InvalidDocument",
                        "Document " + name + " does not exist.", 400));
        if (document.getOwner() == null) {
            document.setOwner(regionResolver.getAccountId());
        }
        return document;
    }

    public synchronized SsmDocument createDocument(String name, String content, String documentType, String region) {
        String storageKey = regionKey(region, name);
        if (documentStore.get(storageKey).isPresent()) {
            throw new AwsException("DocumentAlreadyExists",
                    "Document " + name + " already exists.", 400);
        }
        SsmDocument document = new SsmDocument(name, content, documentType);
        document.setOwner(regionResolver.getAccountId());
        documentStore.put(storageKey, document);
        return document;
    }

    public SsmDocument updateDocument(String name, String content, String region) {
        String storageKey = regionKey(region, name);
        SsmDocument document = documentStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidDocument",
                        "Document " + name + " does not exist.", 400));
        if (Objects.equals(document.getContent(), content)) {
            throw new AwsException("DuplicateDocumentContent",
                    "The content of the association document matches another document. "
                            + "Change the content of the document and try again.", 400);
        }
        long newVersion = document.getDocumentVersion() + 1;
        document.setContent(content);
        document.setDocumentVersion(newVersion);
        document.getVersions().put(String.valueOf(newVersion), content);
        documentStore.put(storageKey, document);
        return document;
    }

    /**
     * Lists the accounts a document is shared with. AWS raises InvalidDocument for a
     * document that does not exist rather than returning an empty list, so the document
     * is resolved first.
     */
    public List<String> describeDocumentPermission(String name, String region) {
        getDocument(name, region);
        return documentPermissionStore.get(regionKey(region, name))
                .map(List::copyOf)
                .orElse(List.of());
    }

    /**
     * Shares (or un-shares) a document with other accounts.
     *
     * <p>Only the document's owner may share it. Ownership here <em>is</em> the storage
     * partition: {@code documentStore} is an {@code AccountAwareStorageBackend}, whose key
     * prefix is the caller's account from {@code RequestContext} — the same resolution
     * {@code RegionResolver.getAccountId()} performs — so {@link #getDocument} can only
     * resolve a document in the caller's own partition. A caller that does not own the
     * document therefore gets InvalidDocument, which is AWS's answer for a document the
     * caller cannot see. Deriving the check from the partition rather than a second stored
     * owner field keeps the guard and the storage scope from ever disagreeing.
     */
    public synchronized void modifyDocumentPermission(String name, List<String> accountIdsToAdd,
                                         List<String> accountIdsToRemove, String region) {
        getDocument(name, region);
        String storageKey = regionKey(region, name);
        List<String> accountIds = new ArrayList<>(
                documentPermissionStore.get(storageKey).orElse(List.of()));
        for (String accountId : accountIdsToAdd) {
            if (!accountIds.contains(accountId)) {
                accountIds.add(accountId);
            }
        }
        accountIds.removeAll(accountIdsToRemove);
        documentPermissionStore.put(storageKey, accountIds);
        LOG.debugv("Modified document permission for {0}: {1} account(s) shared", name, accountIds.size());
    }

    public List<SsmDocument> listDocuments(String region, Map<String, List<String>> filters) {
        String prefix = regionKey(region, "");
        List<SsmDocument> docs = documentStore.scan(k -> k.startsWith(prefix));
        String accountId = regionResolver.getAccountId();
        for (SsmDocument d : docs) {
            if (d.getOwner() == null) {
                d.setOwner(accountId);
            }
        }
        if (filters == null || filters.isEmpty()) {
            return docs;
        }

        List<String> nameFilters = findFilterValues(filters, "Name");
        List<String> typeFilters = findFilterValues(filters, "DocumentType");
        List<String> ownerFilters = findFilterValues(filters, "Owner");
        List<String> platformFilters = findFilterValues(filters, "PlatformTypes");

        return docs.stream()
                .filter(d -> nameFilters.isEmpty()
                        || nameFilters.contains(d.getName())
                        || nameFilters.stream().anyMatch(n -> !n.isEmpty() && d.getName() != null && d.getName().startsWith(n)))
                .filter(d -> typeFilters.isEmpty()
                        || typeFilters.stream().anyMatch(t -> t.equalsIgnoreCase(d.getDocumentType())))
                .filter(d -> ownerFilters.isEmpty()
                        || ownerFilters.stream().anyMatch(o -> matchesOwner(o, d.getOwner(), accountId)))
                .filter(d -> platformFilters.isEmpty()
                        || (d.getPlatformTypes() != null && platformFilters.stream()
                                .anyMatch(p -> d.getPlatformTypes().stream().anyMatch(p::equalsIgnoreCase))))
                .toList();
    }

    /**
     * Every document visible through {@code documentStore} already belongs to the caller's
     * account (it is an account-partitioned store), so "Self"/"Private"/"All" and the caller's own
     * account id all match every visible document; "Amazon"/"Public"/"ThirdParty" match none, since
     * no AWS-owned or other-account documents are ever visible here. Documents created before the
     * {@code Owner} field existed persist with a null owner; since the store already guarantees the
     * caller's own partition, such a document is normalized to the current caller's account ID so
     * that it both matches owner filters and serializes its effective owner in list responses.
     */
    private boolean matchesOwner(String filterValue, String documentOwner, String accountId) {
        if ("Self".equalsIgnoreCase(filterValue) || "Private".equalsIgnoreCase(filterValue)
                || "All".equalsIgnoreCase(filterValue)) {
            return true;
        }
        if ("Amazon".equalsIgnoreCase(filterValue) || "Public".equalsIgnoreCase(filterValue)
                || "ThirdParty".equalsIgnoreCase(filterValue)) {
            return false;
        }
        String effectiveOwner = documentOwner != null ? documentOwner : accountId;
        return filterValue.equals(effectiveOwner);
    }

    private List<String> findFilterValues(Map<String, List<String>> filters, String targetKey) {
        if (filters == null) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetKey) && entry.getValue() != null) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    /**
     * Rejects a {@code DocumentVersion} that cannot resolve on the target document.
     * Stored document versions are retained across updates, so numeric versions are accepted
     * when they exist in the document's version history; {@code $LATEST}/{@code $DEFAULT}
     * always resolve to the current version. {@link SsmDocument#hasVersion} also accepts a
     * numeric version that predates the retained history (see its javadoc) rather than rejecting
     * a version that legitimately existed just because its content was never captured.
     * Non-existent versions or malformed inputs throw {@code InvalidDocumentVersion}, matching
     * AWS SSM behavior.
     */
    private void validateDocumentVersion(SsmDocument document, String documentVersion) {
        if (documentVersion == null || documentVersion.isBlank()
                || "$LATEST".equals(documentVersion) || "$DEFAULT".equals(documentVersion)) {
            return;
        }
        if (!document.hasVersion(documentVersion)) {
            throw new AwsException("InvalidDocumentVersion",
                    "The document version is not valid or does not exist.", 400);
        }
    }

    // ──────────────────────── Associations ───────────────────────────────────

    public synchronized SsmAssociation createAssociation(
            String name,
            String associationName,
            String documentVersion,
            String instanceId,
            List<SsmAssociation.Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            String region) {
        return createAssociation(name, associationName, documentVersion, instanceId, targets, parameters,
                scheduleExpression, null, null, null, region);
    }

    public synchronized SsmAssociation createAssociation(
            String name,
            String associationName,
            String documentVersion,
            String instanceId,
            List<SsmAssociation.Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            String maxErrors,
            String maxConcurrency,
            String complianceSeverity,
            String region) {
        SsmDocument document = getDocument(name, region);
        validateDocumentVersion(document, documentVersion);

        if (instanceId != null && !instanceId.isBlank()) {
            boolean duplicate = listAssociations(region).stream()
                    .anyMatch(a -> Objects.equals(a.getName(), name) && Objects.equals(a.getInstanceId(), instanceId));
            if (duplicate) {
                throw new AwsException("AssociationAlreadyExists",
                        "An association already exists for instance " + instanceId + " and document " + name + ".", 400);
            }
        } else if (targets != null && !targets.isEmpty()) {
            boolean duplicate = listAssociations(region).stream()
                    .anyMatch(a -> Objects.equals(a.getName(), name) && Objects.equals(a.getTargets(), targets));
            if (duplicate) {
                throw new AwsException("AssociationAlreadyExists",
                        "An association already exists for document " + name + " with the given targets.", 400);
            }
        }

        String associationId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        SsmAssociation association = new SsmAssociation();
        association.setAssociationId(associationId);
        association.setAssociationName(associationName);
        association.setName(name);
        association.setDocumentVersion(documentVersion);
        association.setInstanceId(instanceId);
        association.setTargets(targets);
        association.setParameters(parameters);
        association.setScheduleExpression(scheduleExpression);
        association.setMaxErrors(maxErrors);
        association.setMaxConcurrency(maxConcurrency);
        association.setComplianceSeverity(complianceSeverity);
        association.setAssociationVersion("1");
        // Real SSM starts an association at "Pending" and transitions it to "Success"/"Failed" once
        // its execution engine runs it. This emulator has no execution engine, so it reports
        // "Success" immediately rather than modeling a state that would never change on its own.
        association.setStatus(new SsmAssociation.AssociationStatus("Success", "Success", now, null));
        association.setOverview(new SsmAssociation.AssociationOverview("Success", "Success"));
        association.setCreatedDate(now);
        association.setLastExecutionDate(now);

        associationStore.put(regionKey(region, associationId), association);
        LOG.infov("Created association {0} ({1}) for document {2} in region {3}",
                associationId, associationName, name, region);
        return association;
    }

    public synchronized SsmAssociation updateAssociation(
            String associationId,
            String associationName,
            String documentVersion,
            List<SsmAssociation.Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            String maxErrors,
            String maxConcurrency,
            String complianceSeverity,
            String region) {
        String storageKey = regionKey(region, associationId);
        SsmAssociation association = associationStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AssociationDoesNotExist",
                        "The specified association does not exist.", 400));

        if (documentVersion != null) {
            validateDocumentVersion(getDocument(association.getName(), region), documentVersion);
        }

        if (associationName != null) association.setAssociationName(associationName);
        if (documentVersion != null) association.setDocumentVersion(documentVersion);
        if (targets != null) association.setTargets(targets);
        if (parameters != null) association.setParameters(parameters);
        if (scheduleExpression != null) association.setScheduleExpression(scheduleExpression);
        if (maxErrors != null) association.setMaxErrors(maxErrors);
        if (maxConcurrency != null) association.setMaxConcurrency(maxConcurrency);
        if (complianceSeverity != null) association.setComplianceSeverity(complianceSeverity);

        long currentVersion = parseAssociationVersion(association.getAssociationVersion());
        association.setAssociationVersion(String.valueOf(currentVersion + 1));

        associationStore.put(storageKey, association);
        LOG.infov("Updated association {0} in region {1}", associationId, region);
        return association;
    }

    private static long parseAssociationVersion(String version) {
        try {
            return version == null ? 1 : Long.parseLong(version);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public List<SsmAssociation> listAssociations(String region) {
        return listAssociations(region, Map.of());
    }

    public List<SsmAssociation> listAssociations(String region, Map<String, List<String>> filters) {
        String prefix = regionKey(region, "");
        List<SsmAssociation> associations = associationStore.scan(k -> k.startsWith(prefix));
        if (filters == null || filters.isEmpty()) {
            return associations;
        }

        List<String> instanceIdFilters = findFilterValues(filters, "InstanceId");
        List<String> associationIdFilters = findFilterValues(filters, "AssociationId");
        List<String> nameFilters = findFilterValues(filters, "Name");
        List<String> associationNameFilters = findFilterValues(filters, "AssociationName");

        return associations.stream()
                .filter(a -> instanceIdFilters.isEmpty() || instanceIdFilters.contains(a.getInstanceId()))
                .filter(a -> associationIdFilters.isEmpty() || associationIdFilters.contains(a.getAssociationId()))
                .filter(a -> nameFilters.isEmpty() || nameFilters.contains(a.getName()))
                .filter(a -> associationNameFilters.isEmpty() || associationNameFilters.contains(a.getAssociationName()))
                .toList();
    }

    public SsmAssociation describeAssociation(String associationId, String name, String instanceId, String region) {
        if (associationId != null && !associationId.isBlank()) {
            return associationStore.get(regionKey(region, associationId))
                    .orElseThrow(() -> new AwsException("AssociationDoesNotExist",
                            "The specified association does not exist.", 400));
        }
        if (name != null && instanceId != null) {
            String prefix = regionKey(region, "");
            return associationStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(a -> Objects.equals(a.getName(), name) && Objects.equals(a.getInstanceId(), instanceId))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("AssociationDoesNotExist",
                            "The specified association does not exist.", 400));
        }
        throw new AwsException("ValidationException",
                "Either AssociationId or both Name and InstanceId must be specified.", 400);
    }

    public synchronized void deleteAssociation(String associationId, String name, String instanceId, String region) {
        SsmAssociation association = describeAssociation(associationId, name, instanceId, region);
        associationStore.delete(regionKey(region, association.getAssociationId()));
        LOG.infov("Deleted association {0} in region {1}", association.getAssociationId(), region);
    }

    // ──────────────────────────── Patch Baselines ────────────────────────────
    // AWS provides a fixed set of AWS-owned predefined patch baselines (one default per operating
    // system). These are static reference data, not customer state, so they live in-memory only.

    private static final List<PatchBaselineIdentity> PREDEFINED_BASELINES = buildPredefinedBaselines();

    private static List<PatchBaselineIdentity> buildPredefinedBaselines() {
        String[][] defs = {
                {"WINDOWS", "AWS-DefaultPatchBaseline", "Windows"},
                {"AMAZON_LINUX", "AWS-AmazonLinuxDefaultPatchBaseline", "Amazon Linux"},
                {"AMAZON_LINUX_2", "AWS-AmazonLinux2DefaultPatchBaseline", "Amazon Linux 2"},
                {"AMAZON_LINUX_2022", "AWS-AmazonLinux2022DefaultPatchBaseline", "Amazon Linux 2022"},
                {"AMAZON_LINUX_2023", "AWS-AmazonLinux2023DefaultPatchBaseline", "Amazon Linux 2023"},
                {"UBUNTU", "AWS-UbuntuDefaultPatchBaseline", "Ubuntu"},
                {"REDHAT_ENTERPRISE_LINUX", "AWS-RedHatDefaultPatchBaseline", "Red Hat Enterprise Linux"},
                {"SUSE", "AWS-SuseDefaultPatchBaseline", "SUSE Linux Enterprise Server"},
                {"CENTOS", "AWS-CentOSDefaultPatchBaseline", "CentOS"},
                {"ORACLE_LINUX", "AWS-OracleLinuxDefaultPatchBaseline", "Oracle Linux"},
                {"DEBIAN", "AWS-DebianDefaultPatchBaseline", "Debian Server"},
                {"MACOS", "AWS-MacOSDefaultPatchBaseline", "macOS"},
                {"RASPBIAN", "AWS-RaspbianDefaultPatchBaseline", "Raspbian"},
                {"ROCKY_LINUX", "AWS-RockyLinuxDefaultPatchBaseline", "Rocky Linux"},
                {"ALMA_LINUX", "AWS-AlmaLinuxDefaultPatchBaseline", "AlmaLinux"},
        };
        List<PatchBaselineIdentity> baselines = new ArrayList<>();
        for (String[] def : defs) {
            String os = def[0];
            String name = def[1];
            String description = "Default Patch Baseline for " + def[2] + " Provided by AWS.";
            baselines.add(new PatchBaselineIdentity(stableBaselineId(name), name, os, description, true));
        }
        return List.copyOf(baselines);
    }

    /** Deterministic AWS-style baseline id (pb-<17 hex>) derived from the baseline name. */
    private static String stableBaselineId(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        String hex = String.format("%016x", h & 0x0FFFFFFFFFFFFFFFL);
        return "pb-0" + hex;
    }

    /**
     * Return AWS-owned predefined patch baselines matching the given DescribePatchBaselines filters
     * (supported keys: OWNER, OPERATING_SYSTEM, NAME_PREFIX). There are no customer-owned baselines.
     */
    public List<PatchBaselineIdentity> describePatchBaselines(Map<String, List<String>> filters) {
        List<String> owners = filters.getOrDefault("OWNER", List.of());
        // OWNER=Self matches only customer-owned baselines, of which there are none.
        if (!owners.isEmpty() && !owners.contains("AWS") && !owners.contains("All")) {
            return List.of();
        }

        List<String> operatingSystems = filters.getOrDefault("OPERATING_SYSTEM", List.of());
        List<String> namePrefixes = filters.getOrDefault("NAME_PREFIX", List.of());

        return PREDEFINED_BASELINES.stream()
                .filter(b -> operatingSystems.isEmpty() || operatingSystems.contains(b.operatingSystem()))
                .filter(b -> namePrefixes.isEmpty()
                        || namePrefixes.stream().anyMatch(prefix -> b.baselineName().startsWith(prefix)))
                .toList();
    }

    /** Return the default patch baseline id for an operating system (defaults to WINDOWS). */
    public String getDefaultPatchBaseline(String operatingSystem) {
        String os = (operatingSystem == null || operatingSystem.isBlank()) ? "WINDOWS" : operatingSystem;
        return PREDEFINED_BASELINES.stream()
                .filter(b -> b.operatingSystem().equals(os))
                .findFirst()
                .map(PatchBaselineIdentity::baselineId)
                .orElseThrow(() -> new AwsException("DoesNotExistException",
                        "No default patch baseline exists for operating system " + os, 400));
    }

    /**
     * Read a service setting for the calling account. Never-customized settings
     * report their account default with status "Default".
     */
    public ServiceSetting getServiceSetting(String settingId, String region) {
        String defaultValue = requireKnownSetting(settingId);
        return serviceSettingStore.get(settingKey(region, settingId))
                .orElseGet(() -> defaultSetting(settingId, defaultValue, region));
    }

    public void updateServiceSetting(String settingId, String settingValue, String region) {
        requireKnownSetting(settingId);
        ServiceSetting setting = new ServiceSetting(settingId, settingValue,
                settingArn(settingId, region), "Customized",
                regionResolver.buildGlobalArn("iam", "root"));
        serviceSettingStore.put(settingKey(region, settingId), setting);
    }

    public ServiceSetting resetServiceSetting(String settingId, String region) {
        String defaultValue = requireKnownSetting(settingId);
        serviceSettingStore.delete(settingKey(region, settingId));
        return defaultSetting(settingId, defaultValue, region);
    }

    private String requireKnownSetting(String settingId) {
        String defaultValue = SERVICE_SETTING_DEFAULTS.get(settingId);
        if (defaultValue == null) {
            throw new AwsException("ServiceSettingNotFound",
                    "The specified service setting was not found: " + settingId, 400);
        }
        return defaultValue;
    }

    private ServiceSetting defaultSetting(String settingId, String defaultValue, String region) {
        return new ServiceSetting(settingId, defaultValue, settingArn(settingId, region),
                "Default", "System");
    }

    /**
     * Service settings are per-account per-region: LZA assumes a role into each
     * member account before updating, so the caller's resolved account scopes the key.
     */
    private String settingKey(String region, String settingId) {
        return regionResolver.getAccountId() + "::" + regionKey(region, settingId);
    }

    private String settingArn(String settingId, String region) {
        // Setting ids begin with "/", so concatenation yields .../servicesetting/ssm/...
        return AwsArnUtils.Arn.of("ssm", region, regionResolver.getAccountId(), "servicesetting" + settingId).toString();
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }

    private void addHistory(String storageKey, Parameter parameter) {
        List<ParameterHistory> history = historyStore.get(storageKey)
                .orElse(new ArrayList<>());

        history = new ArrayList<>(history);
        history.add(new ParameterHistory(parameter));

        while (history.size() > maxParameterHistory) {
            history.removeFirst();
        }

        historyStore.put(storageKey, history);
    }

    public synchronized void deleteDocument(String name, String region) {
        String storageKey = regionKey(region, name);
        if (!documentStore.get(storageKey).isPresent()) {
            throw new AwsException("InvalidDocument",
                    "Document " + name + " does not exist.", 400);
        }
        documentStore.delete(storageKey);
        documentPermissionStore.delete(storageKey);
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Parameter parameter : parameterStore.scan(k -> true)) {
            String arn = parameter.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "ssm:parameter", "ssm",
                    parsed.region(), parsed.accountId(),
                    parameter.getLastModifiedDate() != null ? parameter.getLastModifiedDate() : Instant.now(),
                    parameter.getTags() != null ? parameter.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("ssm:parameter", "ssm", true));
    }
}
