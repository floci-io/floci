package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Business logic for Lambda Layer management.
 */
@ApplicationScoped
public class LambdaLayerService {

    private static final Logger LOG = Logger.getLogger(LambdaLayerService.class);

    /**
     * Arn constraint GetLayerVersionByArn enforces. Taken from the live service's own
     * ValidationException rather than the API reference, which publishes a laxer pattern and
     * omits the AWS-managed {@code awslayer} form entirely.
     */
    private static final String LAYER_VERSION_ARN_PATTERN =
            "((arn:(aws[a-zA-Z-]*)?:lambda:(eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\\d{1}"
                    + ":\\d{12}:layer:[a-zA-Z0-9-_]+:[0-9]+)"
                    + "|(arn:[a-zA-Z0-9-]+:lambda:::awslayer:[a-zA-Z0-9-_]+))";
    private static final Pattern LAYER_VERSION_ARN = Pattern.compile(LAYER_VERSION_ARN_PATTERN);
    private static final int MAX_LAYER_VERSION_ARN_LENGTH = 140;
    private static final int MAX_ITEMS_LIMIT = 50;

    private static final char MARKER_SEPARATOR = '.';

    /** Signing key for pagination markers, per process; see {@link #encodeMarker}. */
    private static final byte[] MARKER_KEY = newMarkerKey();

    private static final List<String> COMPATIBLE_ARCHITECTURES = List.of("x86_64", "arm64");

    /**
     * Runtime identifiers accepted for CompatibleRuntime, taken from the live service's own
     * ValidationException rather than the API reference: it accepts values the reference omits
     * (java8.al2023, java17.al2023, python3.15, nodejs26.x, the greengrass runtime ARNs) and
     * rejects everything else, case-sensitively. Rendered into the error message as-is, so the
     * order is fixed here; the live service's own ordering varies between responses.
     */
    private static final List<String> COMPATIBLE_RUNTIMES = List.of(
            "nodejs", "nodejs4.3", "nodejs4.3-edge", "nodejs6.10", "nodejs8.9", "nodejs8.10",
            "nodejs8.x", "nodejs10.x", "nodejs12.x", "nodejs14.x", "nodejs16.x", "nodejs18.x",
            "nodejs20.x", "nodejs22.x", "nodejs24.x", "nodejs26.x",
            "java8", "java8.al2", "java8.al2023", "java11", "java11.al2023", "java17",
            "java17.al2023", "java21", "java25",
            "python2.7", "python2.7-greengrass", "python3.4", "python3.6", "python3.7",
            "python3.8", "python3.9", "python3.10", "python3.11", "python3.12", "python3.13",
            "python3.14", "python3.15",
            "dotnetcore1.0", "dotnetcore2.0", "dotnetcore2.1", "dotnetcore3.1", "dotnet6",
            "dotnet8", "dotnet10",
            "ruby2.5", "ruby2.6", "ruby2.7", "ruby3.2", "ruby3.3", "ruby3.4", "ruby4.0",
            "go1.x", "go1.9", "provided", "provided.al2", "provided.al2023",
            "byol", "custom", "nasa",
            "arn:aws:greengrass:::runtime/function/executable",
            "arn:aws-cn:greengrass:::runtime/function/executable",
            "arn:aws-us-gov:greengrass:::runtime/function/executable");

    private final LambdaLayerStore layerStore;
    private final ZipExtractor zipExtractor;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;

    @Inject
    public LambdaLayerService(LambdaLayerStore layerStore,
                              ZipExtractor zipExtractor,
                              EmulatorConfig config,
                              RegionResolver regionResolver,
                              S3Service s3Service) {
        this.layerStore = layerStore;
        this.zipExtractor = zipExtractor;
        this.config = config;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
    }

    /**
     * Publishes a new layer version. Each call with the same layer name creates a new version.
     */
    @SuppressWarnings("unchecked")
    public LambdaLayerVersion publishLayerVersion(String region, String layerName, Map<String, Object> request) {
        if (layerName == null || layerName.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "LayerName is required", 400);
        }

        Map<String, Object> content = (Map<String, Object>) request.get("Content");
        if (content == null) {
            throw new AwsException("InvalidParameterValueException", "Content is required", 400);
        }

        String description = (String) request.get("Description");
        String licenseInfo = (String) request.get("LicenseInfo");

        List<String> compatibleRuntimes = request.get("CompatibleRuntimes") instanceof List
                ? (List<String>) request.get("CompatibleRuntimes") : null;
        List<String> compatibleArchitectures = request.get("CompatibleArchitectures") instanceof List
                ? (List<String>) request.get("CompatibleArchitectures") : null;

        // Resolve the zip content
        byte[] zipBytes = resolveLayerContent(content);

        // Determine the next version number
        long nextVersion = layerStore.getLatestVersion(region, layerName) + 1;

        // Extract the layer zip to disk
        Path layerPath = getLayerCodePath(layerName, nextVersion);
        try {
            zipExtractor.extractTo(zipBytes, layerPath);
        } catch (IOException e) {
            throw new AwsException("InvalidParameterValueException",
                    "Failed to extract layer archive: " + e.getMessage(), 400);
        }

        // Compute SHA-256
        String codeSha256 = computeSha256(zipBytes);

        // Build the layer version
        String accountId = regionResolver.getAccountId();
        String layerArn = AwsArnUtils.Arn.of("lambda", region, accountId, "layer:" + layerName).toString();
        String layerVersionArn = layerArn + ":" + nextVersion;

        LambdaLayerVersion layerVersion = new LambdaLayerVersion();
        layerVersion.setLayerName(layerName);
        layerVersion.setLayerArn(layerArn);
        layerVersion.setLayerVersionArn(layerVersionArn);
        layerVersion.setVersion(nextVersion);
        layerVersion.setDescription(description);
        layerVersion.setLicenseInfo(licenseInfo);
        layerVersion.setCompatibleRuntimes(compatibleRuntimes != null ? new ArrayList<>(compatibleRuntimes) : null);
        layerVersion.setCompatibleArchitectures(compatibleArchitectures != null ? new ArrayList<>(compatibleArchitectures) : null);
        layerVersion.setCreatedDate(DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)));
        layerVersion.setCodeSizeBytes(zipBytes.length);
        layerVersion.setCodeSha256(codeSha256);
        layerVersion.setCodeLocalPath(layerPath.toAbsolutePath().normalize().toString());

        // Store the archive before persisting the version so a load-bearing store failure
        // (kubernetes executor) fails the publish instead of leaving a version pods cannot use.
        boolean stored = storeLayerArchive(region, accountId, layerName, nextVersion, zipBytes);
        if (!stored && LambdaService.requiresStoredTasksObject(config)) {
            throw new AwsException("ServiceException",
                    "Could not store the layer archive for '" + layerName + "' v" + nextVersion
                            + " in Floci's S3, which the kubernetes Lambda executor needs. The "
                            + "layer version was not published.", 500);
        }
        layerVersion.setArchiveStored(stored);
        layerStore.save(region, layerVersion);
        LOG.infov("Published layer version: {0} v{1} in region {2}", layerName, nextVersion, region);
        return layerVersion;
    }

    /**
     * Keeps the exact layer archive in Floci's S3 so GetLayerVersion can serve a real
     * Content.Location and the kubernetes executor's init container can download it.
     * Best-effort unless the active Lambda executor requires stored tasks-bucket objects.
     */
    private boolean storeLayerArchive(String region, String accountId, String layerName,
                                      long version, byte[] zipBytes) {
        return LambdaService.putTasksObjectQuietly(s3Service, region,
                LambdaService.layerObjectKey(accountId, layerName, version), zipBytes,
                "layer archive for " + layerName + " v" + version);
    }

    /**
     * Returns information about a specific layer version.
     */
    public LambdaLayerVersion getLayerVersion(String region, String layerName, long versionNumber) {
        return layerStore.get(region, layerName, versionNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Layer version " + versionNumber + " for layer " + layerName + " not found.", 404));
    }

    /**
     * Lists the versions of a layer, honouring the documented CompatibleRuntime,
     * CompatibleArchitecture, MaxItems and Marker parameters. An unknown layer name is an
     * empty page, not a 404 — the live service answers 200 for a layer that never existed.
     */
    public PaginatedResult<LambdaLayerVersion> listLayerVersions(String region, String layerName,
                                                                 String compatibleRuntime,
                                                                 String compatibleArchitecture,
                                                                 String maxItems, String marker) {
        Integer limit = validateListParameters(maxItems, compatibleRuntime, compatibleArchitecture);
        List<LambdaLayerVersion> versions = layerStore.listVersions(region, layerName).stream()
                .filter(lv -> matchesFilters(lv, compatibleRuntime, compatibleArchitecture))
                .toList();
        // Zero-padded so the cursor's string ordering matches the numeric version ordering.
        return page(versions, lv -> String.format("%019d", lv.getVersion()), limit, marker);
    }

    /**
     * Lists the layers in a region, honouring the same four parameters. Under a filter,
     * LatestMatchingVersion is the newest version that matches rather than the newest overall,
     * and a layer with no matching version drops out of the list entirely.
     */
    public PaginatedResult<LambdaLayerVersion> listLayers(String region, String compatibleRuntime,
                                                          String compatibleArchitecture,
                                                          String maxItems, String marker) {
        Integer limit = validateListParameters(maxItems, compatibleRuntime, compatibleArchitecture);
        List<LambdaLayerVersion> latestMatching = layerStore.listAllVersions(region).stream()
                .filter(lv -> matchesFilters(lv, compatibleRuntime, compatibleArchitecture))
                .collect(Collectors.groupingBy(LambdaLayerVersion::getLayerName))
                .values().stream()
                .map(versions -> versions.stream()
                        .max(Comparator.comparingLong(LambdaLayerVersion::getVersion))
                        .orElseThrow())
                .sorted(Comparator.comparing(LambdaLayerVersion::getLayerName))
                .toList();
        return page(latestMatching, LambdaLayerVersion::getLayerName, limit, marker);
    }

    /**
     * A layer version matches a filter only by listing the value explicitly: one published
     * without CompatibleArchitectures is absent from both x86_64 and arm64 results rather than
     * defaulting to x86_64 the way a function's Architectures does.
     */
    private static boolean matchesFilters(LambdaLayerVersion lv, String compatibleRuntime,
                                          String compatibleArchitecture) {
        if (compatibleRuntime != null) {
            List<String> runtimes = lv.getCompatibleRuntimes();
            if (runtimes == null || !runtimes.contains(compatibleRuntime)) {
                return false;
            }
        }
        if (compatibleArchitecture != null) {
            List<String> architectures = lv.getCompatibleArchitectures();
            if (architectures == null || !architectures.contains(compatibleArchitecture)) {
                return false;
            }
        }
        return true;
    }

    private static <T> PaginatedResult<T> page(List<T> items, Function<T, String> cursorOf,
                                               Integer maxItems, String marker) {
        String after = decodeMarker(marker);
        List<T> remaining = after == null ? items
                : items.stream().filter(i -> cursorOf.apply(i).compareTo(after) > 0).toList();
        List<T> pageItems = remaining.stream()
                .limit(maxItems != null ? maxItems : MAX_ITEMS_LIMIT)
                .toList();
        String nextMarker = pageItems.size() < remaining.size()
                ? encodeMarker(cursorOf.apply(pageItems.get(pageItems.size() - 1)))
                : null;
        return new PaginatedResult<>(pageItems, nextMarker);
    }

    /**
     * Validates the three parseable list parameters and returns the effective MaxItems.
     *
     * <p>Mirrors the live service's ordering, measured rather than documented: a MaxItems that
     * is not an integer is a SerializationException that short-circuits everything else, while
     * range and enum failures accumulate into one ValidationException in a fixed member order
     * (maxItems, compatibleRuntime, compatibleArchitecture) regardless of query-string order.
     * An empty MaxItems is treated as omitted; a blank one is not.
     */
    private static Integer validateListParameters(String maxItems, String compatibleRuntime,
                                                  String compatibleArchitecture) {
        Integer parsed = parseMaxItems(maxItems);
        List<String> errors = new ArrayList<>();
        if (parsed != null && parsed < 1) {
            errors.add(constraintFailure(maxItems, "maxItems",
                    "Member must have value greater than or equal to 1"));
        } else if (parsed != null && parsed > MAX_ITEMS_LIMIT) {
            errors.add(constraintFailure(maxItems, "maxItems",
                    "Member must have value less than or equal to " + MAX_ITEMS_LIMIT));
        }
        if (compatibleRuntime != null && !COMPATIBLE_RUNTIMES.contains(compatibleRuntime)) {
            errors.add(constraintFailure(compatibleRuntime, "compatibleRuntime",
                    "Member must satisfy enum value set: " + COMPATIBLE_RUNTIMES));
        }
        if (compatibleArchitecture != null
                && !COMPATIBLE_ARCHITECTURES.contains(compatibleArchitecture)) {
            errors.add(constraintFailure(compatibleArchitecture, "compatibleArchitecture",
                    "Member must satisfy enum value set: " + COMPATIBLE_ARCHITECTURES));
        }
        if (!errors.isEmpty()) {
            throw new AwsException("ValidationException",
                    errors.size() + (errors.size() == 1 ? " validation error" : " validation errors")
                            + " detected: " + String.join("; ", errors), 400);
        }
        return parsed;
    }

    private static Integer parseMaxItems(String maxItems) {
        if (maxItems == null || maxItems.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(maxItems);
        } catch (NumberFormatException e) {
            throw new AwsException("SerializationException",
                    "'" + maxItems + "' can not be converted to Integer", 400);
        }
    }

    private static String constraintFailure(String value, String member, String requirement) {
        return "Value '" + value + "' at '" + member + "' failed to satisfy constraint: "
                + requirement;
    }

    /**
     * The live service's Marker is an encrypted token it rejects when it did not issue it.
     * Floci signs the cursor instead, with a key generated at startup: a fabricated or edited
     * marker fails the check, which is what makes an arbitrary string a 400 rather than a
     * silently skipped or empty page. A marker from an earlier run is stale for the same
     * reason, matching a client that replays an expired token against AWS.
     */
    private static String encodeMarker(String cursor) {
        String signed = cursor + MARKER_SEPARATOR + sign(cursor);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeMarker(String marker) {
        if (marker == null) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(marker), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalidPaginationKey();
        }
        int separator = decoded.lastIndexOf(MARKER_SEPARATOR);
        if (separator < 0) {
            throw invalidPaginationKey();
        }
        String cursor = decoded.substring(0, separator);
        byte[] presented = decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, sign(cursor).getBytes(StandardCharsets.UTF_8))) {
            throw invalidPaginationKey();
        }
        return cursor;
    }

    private static String sign(String cursor) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(MARKER_KEY, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(cursor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required to paginate layers", e);
        }
    }

    private static byte[] newMarkerKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static AwsException invalidPaginationKey() {
        return new AwsException("InvalidParameterValueException", "Invalid pagination key.", 400);
    }

    /**
     * Deletes a specific layer version.
     */
    public void deleteLayerVersion(String region, String layerName, long versionNumber) {
        LambdaLayerVersion lv = layerStore.get(region, layerName, versionNumber).orElse(null);
        if (lv == null) {
            // AWS returns 204 even if the layer version doesn't exist
            return;
        }

        // Delete the extracted code from disk
        if (lv.getCodeLocalPath() != null) {
            Path codePath = Path.of(lv.getCodeLocalPath());
            deleteDirectory(codePath);
        }

        layerStore.delete(region, layerName, versionNumber);
        deleteLayerArchive(region, lv);
        LOG.infov("Deleted layer version: {0} v{1} in region {2}", layerName, versionNumber, region);
    }

    private void deleteLayerArchive(String region, LambdaLayerVersion lv) {
        if (s3Service == null) {
            return;
        }
        try {
            var account = AwsArnUtils.accountOrDefault(lv.getLayerVersionArn(), "000000000000");
            s3Service.deleteObject(LambdaService.tasksBucketName(region),
                    LambdaService.layerObjectKey(account, lv.getLayerName(), lv.getVersion()));
        } catch (Exception e) {
            LOG.debugv("Could not delete stored layer archive for {0} v{1}: {2}",
                    lv.getLayerName(), lv.getVersion(), e.getMessage());
        }
    }

    /**
     * GetLayerVersionByArn. Arn is validated before lookup, so a malformed value is a 400
     * ValidationException and a well-formed but absent one a 404 — both as the live service
     * answers them.
     */
    public LambdaLayerVersion getLayerVersionByArn(String layerVersionArn) {
        if (layerVersionArn == null || layerVersionArn.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'arn' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (layerVersionArn.length() > MAX_LAYER_VERSION_ARN_LENGTH
                || !LAYER_VERSION_ARN.matcher(layerVersionArn).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + layerVersionArn + "' at 'arn' failed to satisfy"
                            + " constraint: Member must satisfy regular expression pattern: "
                            + LAYER_VERSION_ARN_PATTERN, 400);
        }
        // Floci emulates the aws partition; the live service rejects the others outright, and
        // with a different error than an unresolvable ARN, so this precedes the lookup.
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(layerVersionArn);
        if (parsed.partition() != null && !parsed.partition().isEmpty()
                && !"aws".equals(parsed.partition())) {
            throw new AwsException("InvalidParameterValueException",
                    "Invalid layer version " + layerVersionArn, 400);
        }
        // A foreign account is a miss rather than a substitution: resolveLayerByArn scopes the
        // lookup to the caller's own account. This is where cross-account sharing would hook in
        // if layer permissions were ever implemented.
        LambdaLayerVersion lv = resolveLayerByArn(layerVersionArn);
        if (lv == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The resource you requested does not exist.", 404);
        }
        return lv;
    }

    /**
     * Resolves a layer version ARN to its local code path.
     * Used by the container launcher to copy layer content into /opt.
     */
    public LambdaLayerVersion resolveLayerByArn(String layerVersionArn) {
        // ARN format: arn:aws:lambda:{region}:{account}:layer:{name}:{version}
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(layerVersionArn);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] resourceParts = arn.resource().split(":");
        if (resourceParts.length < 3 || !"layer".equals(resourceParts[0])) {
            return null;
        }
        if (isForeignLayerArn(arn)) {
            return null;
        }
        String region = arn.region();
        String layerName = resourceParts[1];
        long version;
        try {
            version = Long.parseLong(resourceParts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return layerStore.get(region, layerName, version).orElse(null);
    }

    /**
     * True when the ARN is a well-formed layer version ARN naming an account or partition other
     * than the caller's, so Floci cannot decide whether it exists. On the live service such a
     * layer resolves through its resource policy, measured against ap-southeast-1: an ARN in
     * another account whose name and version match a layer of the caller's own is
     * {@code AccessDeniedException}, never a substitution. Floci has no layer permission model
     * (see the Not Implemented list), so it treats every foreign ARN as unknowable rather than
     * resolving it inside the caller's own partition.
     */
    public boolean isForeignLayerArn(String layerVersionArn) {
        AwsArnUtils.Arn arn = parseLayerVersionArn(layerVersionArn);
        if (arn == null) {
            return false;
        }
        return isForeignLayerArn(arn);
    }

    /**
     * True when the ARN is a well-formed layer version ARN outside the {@code aws} partition.
     * Partitions are isolated, so such a layer is unreachable rather than merely unreadable, and
     * {@link #getLayerVersionByArn} already rejects it outright. Attaching one to a function has
     * to reject too, or Floci would persist an ARN its own lookup path calls invalid.
     */
    public boolean isForeignPartitionLayerArn(String layerVersionArn) {
        AwsArnUtils.Arn arn = parseLayerVersionArn(layerVersionArn);
        if (arn == null) {
            return false;
        }
        String partition = arn.partition();
        return partition != null && !partition.isEmpty() && !"aws".equals(partition);
    }

    private AwsArnUtils.Arn parseLayerVersionArn(String layerVersionArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(layerVersionArn);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] resourceParts = arn.resource().split(":");
        if (resourceParts.length < 3 || !"layer".equals(resourceParts[0])) {
            return null;
        }
        return arn;
    }

    private boolean isForeignLayerArn(AwsArnUtils.Arn arn) {
        String partition = arn.partition();
        if (partition != null && !partition.isEmpty() && !"aws".equals(partition)) {
            return true;
        }
        String account = arn.accountId();
        return account != null && !account.isEmpty() && !account.equals(regionResolver.getAccountId());
    }

    private byte[] resolveLayerContent(Map<String, Object> content) {
        String zipFileBase64 = (String) content.get("ZipFile");
        if (zipFileBase64 != null) {
            return Base64.getDecoder().decode(zipFileBase64);
        }

        String s3Bucket = (String) content.get("S3Bucket");
        String s3Key = (String) content.get("S3Key");
        if (s3Bucket != null && s3Key != null) {
            if (s3Service == null) {
                throw new AwsException("ServiceUnavailableException", "S3 service not available", 503);
            }
            try {
                S3Object obj = s3Service.getObject(s3Bucket, s3Key);
                return obj.getData();
            } catch (Exception e) {
                throw new AwsException("InvalidParameterValueException",
                        "Unable to fetch layer content from s3://" + s3Bucket + "/" + s3Key + ": " + e.getMessage(), 400);
            }
        }

        throw new AwsException("InvalidParameterValueException",
                "Layer content must include either ZipFile or S3Bucket/S3Key", 400);
    }

    private Path getLayerCodePath(String layerName, long version) {
        String sanitized = layerName.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return Path.of(config.services().lambda().codePath())
                .resolve("layers")
                .resolve(sanitized)
                .resolve(String.valueOf(version));
    }

    private String computeSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOG.warnv("Failed to delete {0}: {1}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warnv("Failed to delete layer directory {0}: {1}", dir, e.getMessage());
        }
    }
}
