package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
     * Lists all versions of a layer.
     */
    public List<LambdaLayerVersion> listLayerVersions(String region, String layerName) {
        return layerStore.listVersions(region, layerName);
    }

    /**
     * Lists all layers in a region (returns the latest version of each).
     */
    public List<LambdaLayerVersion> listLayers(String region) {
        return layerStore.listLayers(region);
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
        // resolveLayerByArn keys on region/name/version within the caller's own partition, so an
        // ARN naming another account would otherwise resolve to the caller's same-named layer.
        // No layer here can be shared cross-account (layer permissions are unimplemented), so a
        // foreign account is always a miss; this is where sharing would hook in if that changes.
        // resolveLayerByArn also drops the partition, so an ARN naming another one would
        // otherwise resolve to the local layer under a foreign-partition ARN. Floci emulates
        // the aws partition; the live service rejects the others outright.
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(layerVersionArn);
        if (parsed.partition() != null && !parsed.partition().isEmpty()
                && !"aws".equals(parsed.partition())) {
            throw new AwsException("InvalidParameterValueException",
                    "Invalid layer version " + layerVersionArn, 400);
        }
        String requestedAccount = AwsArnUtils.accountOrDefault(layerVersionArn, null);
        if (requestedAccount != null && !requestedAccount.equals(regionResolver.getAccountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "The resource you requested does not exist.", 404);
        }
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
