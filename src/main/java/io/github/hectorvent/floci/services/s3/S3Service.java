package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.model.*;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class S3Service {

    private static final Logger LOG = Logger.getLogger(S3Service.class);

    private final StorageBackend<String, Bucket> bucketStore;
    private final StorageBackend<String, S3Object> objectStore;
    private final Path dataRoot;
    private final ConcurrentHashMap<String, MultipartUpload> multipartUploads = new ConcurrentHashMap<>();

    private final SqsService sqsService;
    private final SnsService snsService;
    private final RegionResolver regionResolver;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    @Inject
    public S3Service(StorageFactory storageFactory, EmulatorConfig config,
                     SqsService sqsService, SnsService snsService, RegionResolver regionResolver,
                     ObjectMapper objectMapper) {
        this(
                storageFactory.create("s3", "s3-buckets.json",
                        new TypeReference<Map<String, Bucket>>() {
                        }),
                storageFactory.create("s3", "s3-objects.json",
                        new TypeReference<Map<String, S3Object>>() {
                        }),
                Path.of(config.storage().persistentPath()).resolve("s3"),
                sqsService, snsService, regionResolver, config.effectiveBaseUrl(), objectMapper
        );
    }

    /**
     * Package-private constructor for testing.
     */
    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              Path dataRoot) {
        this(bucketStore, objectStore, dataRoot, null, null, null, "http://localhost:4566",
                new ObjectMapper());
    }

    private S3Service(StorageBackend<String, Bucket> bucketStore,
                      StorageBackend<String, S3Object> objectStore,
                      Path dataRoot, SqsService sqsService, SnsService snsService,
                      RegionResolver regionResolver, String baseUrl, ObjectMapper objectMapper) {
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
        this.dataRoot = dataRoot;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.regionResolver = regionResolver;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(dataRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create S3 data directory: " + dataRoot, e);
        }
    }

    public Bucket createBucket(String bucketName, String region) {
        var existing = bucketStore.get(bucketName);
        if (existing.isPresent()) {
            throw new AwsException("BucketAlreadyOwnedByYou",
                    "Your previous request to create the named bucket succeeded and you already own it.", 409);
        }

        Bucket bucket = new Bucket(bucketName);
        bucket.setRegion(region);
        bucketStore.put(bucketName, bucket);
        LOG.infov("Created bucket: {0} in region: {1}", bucketName, region);
        return bucket;
    }

    public void deleteBucket(String bucketName) {
        ensureBucketExists(bucketName);

        // Check if bucket is empty
        List<S3Object> objects = listObjects(bucketName, null, null, 1);
        if (!objects.isEmpty()) {
            throw new AwsException("BucketNotEmpty",
                    "The bucket you tried to delete is not empty.", 409);
        }

        bucketStore.delete(bucketName);
        deleteDirectory(dataRoot.resolve(bucketName));
        LOG.infov("Deleted bucket: {0}", bucketName);
    }

    public List<Bucket> listBuckets() {
        return bucketStore.scan(key -> true);
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata) {
        return putObject(bucketName, key, data, contentType, metadata, null, null, null, null);
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata,
                              String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        return putObject(bucketName, key, data, contentType, metadata, null,
                objectLockMode, retainUntilDate, legalHoldStatus);
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata, String storageClass,
                              String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        S3Object object = storeObject(bucketName, key, data, contentType, metadata, storageClass, null, null,
                objectLockMode, retainUntilDate, legalHoldStatus);
        fireNotifications(bucketName, key, "ObjectCreated:Put", object);
        return object;
    }

    /**
     * Store object without firing notifications (used internally by completeMultipartUpload).
     */
    private S3Object storeObject(String bucketName, String key, byte[] data,
                                 String contentType, Map<String, String> metadata) {
        return storeObject(bucketName, key, data, contentType, metadata, null, null, null,
                null, null, null);
    }

    private S3Object storeObject(String bucketName, String key, byte[] data,
                                 String contentType, Map<String, String> metadata, String storageClass,
                                 S3Checksum checksum, List<Part> parts,
                                 String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));

        S3Object object = new S3Object(bucketName, key, data, contentType);
        if (metadata != null) {
            object.getMetadata().putAll(metadata);
        }
        object.setStorageClass(ObjectAttributeName.normalizeStorageClass(storageClass));
        object.setChecksum(checksum != null ? copyChecksum(checksum) : buildChecksum(data, parts, false));
        object.setParts(copyParts(parts));

        if (bucket.isVersioningEnabled()) {
            String versionId = UUID.randomUUID().toString();
            object.setVersionId(versionId);
            object.setLatest(true);

            // Check lock protection on the current latest before overwriting
            String latestKey = objectKey(bucketName, key);
            objectStore.get(latestKey).ifPresent(prev -> {
                if (prev.isLatest() && !prev.isDeleteMarker() && bucket.isObjectLockEnabled()) {
                    checkLockProtection(prev, false);
                }
                if (prev.getVersionId() != null) {
                    prev.setLatest(false);
                    objectStore.put(versionedKey(bucketName, key, prev.getVersionId()), prev);
                }
            });

            // Apply lock fields from request or bucket default
            applyObjectLock(object, bucket, objectLockMode, retainUntilDate, legalHoldStatus);

            // Store versioned copy and update latest pointer
            objectStore.put(versionedKey(bucketName, key, versionId), object);
            objectStore.put(latestKey, object);
            writeFile(bucketName, key, data);
            writeVersionedFile(bucketName, key, versionId, data);
            LOG.debugv("Put versioned object: {0}/{1} v={2} ({3} bytes)", bucketName, key, versionId, data.length);
        } else {
            // Check lock protection on the existing object before overwriting
            if (bucket.isObjectLockEnabled()) {
                objectStore.get(objectKey(bucketName, key)).ifPresent(prev -> {
                    if (!prev.isDeleteMarker()) {
                        checkLockProtection(prev, false);
                    }
                });
            }

            // Apply lock fields from request or bucket default
            applyObjectLock(object, bucket, objectLockMode, retainUntilDate, legalHoldStatus);

            objectStore.put(objectKey(bucketName, key), object);
            writeFile(bucketName, key, data);
            LOG.debugv("Put object: {0}/{1} ({2} bytes)", bucketName, key, data.length);
        }
        return object;
    }

    private void applyObjectLock(S3Object object, Bucket bucket,
                                 String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        if (objectLockMode != null) {
            object.setObjectLockMode(objectLockMode);
            object.setRetainUntilDate(retainUntilDate);
        } else if (bucket.isObjectLockEnabled() && bucket.getDefaultRetention() != null) {
            ObjectLockRetention def = bucket.getDefaultRetention();
            object.setObjectLockMode(def.mode());
            long days = "Years".equals(def.unit()) ? (long) def.value() * 365 : def.value();
            object.setRetainUntilDate(Instant.now().plusSeconds(days * 86400L));
        }
        if (legalHoldStatus != null) {
            object.setLegalHoldStatus(legalHoldStatus);
        }
    }

    private void checkLockProtection(S3Object obj, boolean bypassGovernance) {
        if ("ON".equals(obj.getLegalHoldStatus())) {
            throw new AwsException("AccessDenied", "Object has an active legal hold", 403);
        }
        if (obj.getRetainUntilDate() != null && Instant.now().isBefore(obj.getRetainUntilDate())) {
            if ("COMPLIANCE".equals(obj.getObjectLockMode())) {
                throw new AwsException("AccessDenied", "Object is protected by COMPLIANCE retention", 403);
            }
            if ("GOVERNANCE".equals(obj.getObjectLockMode()) && !bypassGovernance) {
                throw new AwsException("AccessDenied", "Object is protected by GOVERNANCE retention", 403);
            }
        }
    }

    public S3Object getObject(String bucketName, String key) {
        return getObject(bucketName, key, null);
    }

    public S3Object getObject(String bucketName, String key, String versionId) {
        S3Object obj = getObjectMetadata(bucketName, key, versionId);

        // Read from versioned file if available, otherwise from latest
        if (versionId != null) {
            obj.setData(readVersionedFile(bucketName, key, versionId));
        } else {
            obj.setData(readFile(bucketName, key));
        }
        return obj;
    }

    public S3Object headObject(String bucketName, String key) {
        return headObject(bucketName, key, null);
    }

    public S3Object headObject(String bucketName, String key, String versionId) {
        return getObjectMetadata(bucketName, key, versionId);
    }

    public S3Object getObjectMetadata(String bucketName, String key, String versionId) {
        S3Object copy = copyObject(getStoredObject(bucketName, key, versionId));
        copy.setData(null);
        return copy;
    }

    public GetObjectAttributesResult getObjectAttributes(String bucketName, String key, String versionId,
                                                         Set<ObjectAttributeName> attributes,
                                                         Integer maxParts, Integer partNumberMarker) {
        S3Object object = getObjectMetadata(bucketName, key, versionId);

        GetObjectAttributesResult result = new GetObjectAttributesResult();
        result.setLastModified(object.getLastModified());
        result.setVersionId(object.getVersionId());

        if (attributes.contains(ObjectAttributeName.E_TAG)) {
            result.setETag(object.getETag());
        }
        if (attributes.contains(ObjectAttributeName.STORAGE_CLASS)) {
            result.setStorageClass(object.getStorageClass());
        }
        if (attributes.contains(ObjectAttributeName.OBJECT_SIZE)) {
            result.setObjectSize(object.getSize());
        }
        if (attributes.contains(ObjectAttributeName.CHECKSUM)) {
            result.setChecksum(copyChecksum(object.getChecksum()));
        }
        if (attributes.contains(ObjectAttributeName.OBJECT_PARTS)) {
            result.setObjectParts(buildObjectParts(object, maxParts, partNumberMarker));
        }

        return result;
    }

    private S3Object getStoredObject(String bucketName, String key, String versionId) {
        ensureBucketExists(bucketName);

        String storeKey = versionId != null ? versionedKey(bucketName, key, versionId) : objectKey(bucketName, key);
        S3Object object = objectStore.get(storeKey)
                .orElseThrow(() -> versionId != null
                        ? new AwsException("NoSuchVersion", "The specified version does not exist.", 404)
                        : new AwsException("NoSuchKey", "The specified key does not exist.", 404));
        if (object.isDeleteMarker()) {
            throw new AwsException("NoSuchKey", "The specified key does not exist.", 404);
        }
        return object;
    }

    private GetObjectAttributesParts buildObjectParts(S3Object object, Integer maxParts, Integer partNumberMarker) {
        List<Part> sortedParts = new ArrayList<>(copyParts(object.getParts()));
        sortedParts.sort(Comparator.comparingInt(Part::getPartNumber));

        int max = (maxParts == null || maxParts <= 0) ? 1000 : maxParts;
        int marker = Math.max(partNumberMarker != null ? partNumberMarker : 0, 0);

        List<Part> visibleParts = sortedParts.stream()
                .filter(part -> part.getPartNumber() > marker)
                .toList();
        List<Part> returnedParts = visibleParts.stream().limit(max).toList();

        GetObjectAttributesParts result = new GetObjectAttributesParts();
        result.setMaxParts(max);
        result.setPartNumberMarker(marker);
        result.setParts(returnedParts);
        result.setPartsCount(sortedParts.size());
        result.setTruncated(visibleParts.size() > returnedParts.size());
        result.setNextPartNumberMarker(returnedParts.isEmpty()
                ? marker
                : returnedParts.get(returnedParts.size() - 1).getPartNumber());
        return result;
    }

    public S3Object deleteObject(String bucketName, String key) {
        return deleteObject(bucketName, key, null, false);
    }

    public S3Object deleteObject(String bucketName, String key, String versionId) {
        return deleteObject(bucketName, key, versionId, false);
    }

    public S3Object deleteObject(String bucketName, String key, String versionId, boolean bypassGovernance) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));

        if (bucket.isVersioningEnabled() && versionId == null) {
            // Check lock on current latest before placing a delete marker
            objectStore.get(objectKey(bucketName, key)).ifPresent(prev -> {
                if (!prev.isDeleteMarker()) {
                    checkLockProtection(prev, bypassGovernance);
                }
            });

            // Create a delete marker instead of actually deleting
            S3Object deleteMarker = new S3Object(bucketName, key, new byte[0], null);
            String markerId = UUID.randomUUID().toString();
            deleteMarker.setVersionId(markerId);
            deleteMarker.setDeleteMarker(true);
            deleteMarker.setLatest(true);

            // Mark previous latest as not latest
            objectStore.get(objectKey(bucketName, key)).ifPresent(prev -> {
                if (prev.getVersionId() != null) {
                    prev.setLatest(false);
                    objectStore.put(versionedKey(bucketName, key, prev.getVersionId()), prev);
                }
            });

            objectStore.put(versionedKey(bucketName, key, markerId), deleteMarker);
            objectStore.put(objectKey(bucketName, key), deleteMarker);
            LOG.debugv("Created delete marker: {0}/{1} v={2}", bucketName, key, markerId);
            fireNotifications(bucketName, key, "ObjectRemoved:DeleteMarkerCreated", deleteMarker);
            return deleteMarker;
        } else if (versionId != null) {
            // Check lock on the specific version before permanent deletion
            objectStore.get(versionedKey(bucketName, key, versionId)).ifPresent(obj -> {
                if (!obj.isDeleteMarker()) {
                    checkLockProtection(obj, bypassGovernance);
                }
            });
            // Permanently delete a specific version
            objectStore.delete(versionedKey(bucketName, key, versionId));
            LOG.debugv("Permanently deleted version: {0}/{1} v={2}", bucketName, key, versionId);
            return null;
        } else {
            // Check lock on the non-versioned object before delete
            objectStore.get(objectKey(bucketName, key)).ifPresent(obj -> {
                if (!obj.isDeleteMarker()) {
                    checkLockProtection(obj, bypassGovernance);
                }
            });
            // Non-versioned delete
            objectStore.delete(objectKey(bucketName, key));
            deleteFile(bucketName, key);
            LOG.debugv("Deleted object: {0}/{1}", bucketName, key);
            fireNotifications(bucketName, key, "ObjectRemoved:Delete", null);
            return null;
        }
    }

    public record ListObjectsResult(List<S3Object> objects, List<String> commonPrefixes) {}

    public List<S3Object> listObjects(String bucketName, String prefix, String delimiter, int maxKeys) {
        return listObjectsWithPrefixes(bucketName, prefix, delimiter, maxKeys).objects();
    }

    public ListObjectsResult listObjectsWithPrefixes(String bucketName, String prefix, String delimiter, int maxKeys) {
        ensureBucketExists(bucketName);

        String keyPrefix = bucketName + "/";
        String fullPrefix = prefix != null ? keyPrefix + prefix : keyPrefix;

        // Filter out versioned entries (contain #v#) and delete markers
        List<S3Object> allObjects = objectStore.scan(key ->
                        key.startsWith(fullPrefix) && !key.contains("#v#"))
                .stream()
                .filter(obj -> !obj.isDeleteMarker())
                .toList();
        allObjects = new ArrayList<>(allObjects);

        // see https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-prefixes.html
        List<String> commonPrefixes = List.of();

        if (delimiter != null && !delimiter.isEmpty()) {
            
            if (maxKeys > 0) {
                List<S3Object> limitedObjects = new ArrayList<>();
                List<String> limitedPrefixes = new ArrayList<>();
                int count = 0;
                int commonPrefixCount = 0;
                int directObjectCount = 0;
                while (count < maxKeys && (directObjectCount < allObjects.size() || commonPrefixCount < commonPrefixes.size())) {
                    String objectKey = directObjectCount < allObjects.size() ? allObjects.get(directObjectCount).getKey() : null;
                    String prefixKey = commonPrefixCount < commonPrefixes.size() ? commonPrefixes.get(commonPrefixCount) : null;
                    if (objectKey != null && (prefixKey == null || objectKey.compareTo(prefixKey) <= 0)) {
                        limitedObjects.add(allObjects.get(directObjectCount++));
                    } else {
                        limitedPrefixes.add(commonPrefixes.get(commonPrefixCount++));
                    }
                    count++;
                }
                allObjects = limitedObjects;
                commonPrefixes = limitedPrefixes;
            }
            Collections.sort(commonPrefixes);
        }

        allObjects.sort(Comparator.comparing(S3Object::getKey));

        if (maxKeys > 0 && allObjects.size() > maxKeys) {
            allObjects = allObjects.subList(0, maxKeys);
        }

        return new ListObjectsResult(allObjects, commonPrefixes);
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey) {
        return copyObject(sourceBucket, sourceKey, destBucket, destKey,
                null, null, null, null);
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey,
                               String metadataDirective, Map<String, String> replacementMetadata,
                               String storageClass, String contentType) {
        S3Object source = getObject(sourceBucket, sourceKey);
        ensureBucketExists(destBucket);

        boolean replaceMetadata = "REPLACE".equalsIgnoreCase(metadataDirective);
        Map<String, String> metadata = replaceMetadata ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getMetadata());
        if (replaceMetadata && replacementMetadata != null) {
            metadata.putAll(replacementMetadata);
        }

        String effectiveContentType = replaceMetadata && contentType != null ? contentType : source.getContentType();
        String effectiveStorageClass = storageClass != null ? storageClass : source.getStorageClass();
        S3Object copy = storeObject(destBucket, destKey, source.getData(), effectiveContentType, metadata,
                effectiveStorageClass, source.getChecksum(), source.getParts(), null, null, null);
        copy.setETag(source.getETag());
        LOG.debugv("Copied object: {0}/{1} -> {2}/{3}", sourceBucket, sourceKey, destBucket, destKey);
        fireNotifications(destBucket, destKey, "ObjectCreated:Copy", copy);
        return copy;
    }

    // --- Versioning Operations ---

    public void putBucketVersioning(String bucketName, String status) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        if (!"Enabled".equals(status) && !"Suspended".equals(status)) {
            throw new AwsException("MalformedXML",
                    "Versioning status must be 'Enabled' or 'Suspended'.", 400);
        }
        bucket.setVersioningStatus(status);
        bucketStore.put(bucketName, bucket);
        LOG.infov("Set versioning for bucket {0}: {1}", bucketName, status);
    }

    public String getBucketVersioning(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        return bucket.getVersioningStatus();
    }

    public List<S3Object> listObjectVersions(String bucketName, String prefix, int maxKeys) {
        ensureBucketExists(bucketName);

        String versionPrefix = bucketName + "/";
        String fullPrefix = prefix != null ? versionPrefix + prefix : versionPrefix;

        // Scan for versioned entries (contain #v#)
        List<S3Object> versions = new ArrayList<>(objectStore.scan(key ->
                key.startsWith(fullPrefix) && key.contains("#v#")));

        // Sort by key, then by lastModified descending
        versions.sort((a, b) -> {
            int keyCompare = a.getKey().compareTo(b.getKey());
            if (keyCompare != 0) return keyCompare;
            return b.getLastModified().compareTo(a.getLastModified());
        });

        if (maxKeys > 0 && versions.size() > maxKeys) {
            versions = versions.subList(0, maxKeys);
        }
        return versions;
    }

    // --- Head Bucket / Bucket Location ---

    public void headBucket(String bucketName) {
        ensureBucketExists(bucketName);
    }

    public String getBucketRegion(String bucketName) {
        ensureBucketExists(bucketName);
        return bucketStore.get(bucketName).map(Bucket::getRegion).orElse(null);
    }

    // --- Batch Delete ---

    public record DeleteResult(String key, String versionId, boolean deleteMarker, String deleteMarkerVersionId) {
    }

    public record DeleteError(String key, String code, String message) {
    }

    public record DeleteObjectsResult(List<DeleteResult> deleted, List<DeleteError> errors) {
    }

    public DeleteObjectsResult deleteObjects(String bucketName, List<String> keys) {
        ensureBucketExists(bucketName);
        List<DeleteResult> deleted = new ArrayList<>();
        List<DeleteError> errors = new ArrayList<>();
        for (String key : keys) {
            try {
                S3Object result = deleteObject(bucketName, key);
                if (result != null && result.isDeleteMarker()) {
                    deleted.add(new DeleteResult(key, null, true, result.getVersionId()));
                } else {
                    deleted.add(new DeleteResult(key, null, false, null));
                }
            } catch (Exception e) {
                errors.add(new DeleteError(key, "InternalError", e.getMessage()));
            }
        }
        return new DeleteObjectsResult(deleted, errors);
    }

    // --- Object Tagging ---

    public void putObjectTagging(String bucketName, String key, Map<String, String> tags) {
        ensureBucketExists(bucketName);
        S3Object obj = objectStore.get(objectKey(bucketName, key))
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
        obj.setTags(tags != null ? tags : new java.util.HashMap<>());
        objectStore.put(objectKey(bucketName, key), obj);
        LOG.debugv("Put tags on object: {0}/{1}", bucketName, key);
    }

    public Map<String, String> getObjectTagging(String bucketName, String key) {
        ensureBucketExists(bucketName);
        S3Object obj = objectStore.get(objectKey(bucketName, key))
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
        return obj.getTags() != null ? obj.getTags() : Map.of();
    }

    public void deleteObjectTagging(String bucketName, String key) {
        ensureBucketExists(bucketName);
        S3Object obj = objectStore.get(objectKey(bucketName, key))
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
        obj.setTags(new java.util.HashMap<>());
        objectStore.put(objectKey(bucketName, key), obj);
        LOG.debugv("Deleted tags from object: {0}/{1}", bucketName, key);
    }

    // --- Bucket Tagging ---

    public void putBucketTagging(String bucketName, Map<String, String> tags) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setTags(tags != null ? tags : new java.util.HashMap<>());
        bucketStore.put(bucketName, bucket);
        LOG.debugv("Put tags on bucket: {0}", bucketName);
    }

    public Map<String, String> getBucketTagging(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        return bucket.getTags() != null ? bucket.getTags() : Map.of();
    }

    public void deleteBucketTagging(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setTags(new java.util.HashMap<>());
        bucketStore.put(bucketName, bucket);
        LOG.debugv("Deleted tags from bucket: {0}", bucketName);
    }

    // --- Object Lock Configuration ---

    public void setBucketObjectLockEnabled(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setBucketObjectLockEnabled();
        bucketStore.put(bucketName, bucket);
        LOG.infov("Enabled Object Lock for bucket: {0}", bucketName);
    }

    public void putObjectLockConfiguration(String bucketName, String mode, String unit, int value) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setBucketObjectLockEnabled();
        if (mode != null && unit != null && value > 0) {
            bucket.setDefaultRetention(new ObjectLockRetention(mode, unit, value));
        } else {
            bucket.setDefaultRetention(null);
        }
        bucketStore.put(bucketName, bucket);
        LOG.infov("Set Object Lock configuration for bucket: {0}, mode={1}, unit={2}, value={3}",
                bucketName, mode, unit, value);
    }

    public ObjectLockRetention getObjectLockConfiguration(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        return bucket.getDefaultRetention();
    }

    public void putObjectRetention(String bucketName, String key, String versionId,
                                   String mode, Instant retainUntil, boolean bypassGovernance) {
        ensureBucketExists(bucketName);
        String storeKey = versionId != null
                ? versionedKey(bucketName, key, versionId)
                : objectKey(bucketName, key);
        S3Object obj = objectStore.get(storeKey)
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));

        // COMPLIANCE mode: retainUntil cannot be shortened
        if ("COMPLIANCE".equals(obj.getObjectLockMode())
                && obj.getRetainUntilDate() != null
                && retainUntil != null
                && retainUntil.isBefore(obj.getRetainUntilDate())) {
            throw new AwsException("AccessDenied",
                    "COMPLIANCE retention period cannot be shortened", 403);
        }

        // Check bypass permission for existing governance lock when shortening/removing
        if ("GOVERNANCE".equals(obj.getObjectLockMode())
                && obj.getRetainUntilDate() != null
                && Instant.now().isBefore(obj.getRetainUntilDate())
                && !bypassGovernance) {
            if (retainUntil == null || retainUntil.isBefore(obj.getRetainUntilDate())) {
                throw new AwsException("AccessDenied",
                        "Object is protected by GOVERNANCE retention", 403);
            }
        }

        obj.setObjectLockMode(mode);
        obj.setRetainUntilDate(retainUntil);
        objectStore.put(storeKey, obj);
        LOG.debugv("Set retention on {0}/{1}: mode={2}, until={3}", bucketName, key, mode, retainUntil);
    }

    public S3Object getObjectRetention(String bucketName, String key, String versionId) {
        ensureBucketExists(bucketName);
        String storeKey = versionId != null
                ? versionedKey(bucketName, key, versionId)
                : objectKey(bucketName, key);
        return objectStore.get(storeKey)
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
    }

    public void putObjectLegalHold(String bucketName, String key, String versionId, String status) {
        ensureBucketExists(bucketName);
        String storeKey = versionId != null
                ? versionedKey(bucketName, key, versionId)
                : objectKey(bucketName, key);
        S3Object obj = objectStore.get(storeKey)
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
        obj.setLegalHoldStatus(status);
        objectStore.put(storeKey, obj);
        LOG.debugv("Set legal hold on {0}/{1}: {2}", bucketName, key, status);
    }

    public S3Object getObjectLegalHold(String bucketName, String key, String versionId) {
        ensureBucketExists(bucketName);
        String storeKey = versionId != null
                ? versionedKey(bucketName, key, versionId)
                : objectKey(bucketName, key);
        return objectStore.get(storeKey)
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
    }

    // --- Multipart Upload Operations ---

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType) {
        return initiateMultipartUpload(bucket, key, contentType, null, null);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass) {
        ensureBucketExists(bucket);
        MultipartUpload upload = new MultipartUpload(bucket, key, contentType);
        if (metadata != null) {
            upload.getMetadata().putAll(metadata);
        }
        upload.setStorageClass(ObjectAttributeName.normalizeStorageClass(storageClass));

        try {
            Files.createDirectories(dataRoot.resolve(".multipart").resolve(upload.getUploadId()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create multipart temp directory", e);
        }

        multipartUploads.put(upload.getUploadId(), upload);
        LOG.infov("Initiated multipart upload: {0}/{1}, uploadId={2}", bucket, key, upload.getUploadId());
        return upload;
    }

    public String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data) {
        MultipartUpload upload = multipartUploads.get(uploadId);
        if (upload == null || !upload.getBucket().equals(bucket) || !upload.getKey().equals(key)) {
            throw new AwsException("NoSuchUpload",
                    "The specified multipart upload does not exist.", 404);
        }
        if (partNumber < 1 || partNumber > 10000) {
            throw new AwsException("InvalidArgument",
                    "Part number must be between 1 and 10000.", 400);
        }

        // Write part to temp directory
        Path partPath = dataRoot.resolve(".multipart").resolve(uploadId).resolve(String.valueOf(partNumber));
        try {
            Files.write(partPath, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write multipart part", e);
        }

        String eTag = computeETag(data);
        Part part = new Part(partNumber, eTag, data.length);
        part.setChecksum(buildChecksum(data, List.of(part), true));
        upload.getParts().put(partNumber, part);
        LOG.debugv("Uploaded part {0} for upload {1} ({2} bytes)", partNumber, uploadId, data.length);
        return eTag;
    }

    public S3Object completeMultipartUpload(String bucket, String key, String uploadId, List<Integer> partNumbers) {
        MultipartUpload upload = multipartUploads.get(uploadId);
        if (upload == null || !upload.getBucket().equals(bucket) || !upload.getKey().equals(key)) {
            throw new AwsException("NoSuchUpload",
                    "The specified multipart upload does not exist.", 404);
        }

        // Verify all requested parts exist
        for (int num : partNumbers) {
            if (!upload.getParts().containsKey(num)) {
                throw new AwsException("InvalidPart",
                        "One or more of the specified parts could not be found. Part " + num + " is missing.", 400);
            }
        }

        // Concatenate parts in order
        try {
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            MessageDigest md = MessageDigest.getInstance("MD5");

            for (int num : partNumbers) {
                Path partPath = dataRoot.resolve(".multipart").resolve(uploadId).resolve(String.valueOf(num));
                byte[] partData = Files.readAllBytes(partPath);
                combined.write(partData);
                // For composite ETag: hash each part's MD5
                md.update(computeETagBytes(partData));
            }

            byte[] allData = combined.toByteArray();

            // Composite ETag: MD5 of concatenated part MD5s, suffixed with part count
            String compositeETag = "\"" + bytesToHex(md.digest()) + "-" + partNumbers.size() + "\"";

            List<Part> completedParts = partNumbers.stream()
                    .map(num -> copyPart(upload.getParts().get(num)))
                    .toList();
            S3Checksum checksum = buildChecksum(allData, completedParts, true);
            S3Object object = storeObject(bucket, key, allData, upload.getContentType(), upload.getMetadata(),
                    upload.getStorageClass(), checksum, completedParts, null, null, null);
            // Override the ETag with the composite multipart ETag
            object.setETag(compositeETag);
            objectStore.put(objectKey(bucket, key), object);

            // Cleanup
            cleanupMultipart(uploadId);
            LOG.infov("Completed multipart upload: {0}/{1}, uploadId={2}, parts={3}",
                    bucket, key, uploadId, partNumbers.size());
            fireNotifications(bucket, key, "ObjectCreated:CompleteMultipartUpload", object);
            return object;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read multipart parts", e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        MultipartUpload upload = multipartUploads.get(uploadId);
        if (upload == null || !upload.getBucket().equals(bucket) || !upload.getKey().equals(key)) {
            throw new AwsException("NoSuchUpload",
                    "The specified multipart upload does not exist.", 404);
        }
        cleanupMultipart(uploadId);
        LOG.infov("Aborted multipart upload: {0}/{1}, uploadId={2}", bucket, key, uploadId);
    }

    public List<MultipartUpload> listMultipartUploads(String bucket) {
        ensureBucketExists(bucket);
        return multipartUploads.values().stream()
                .filter(u -> u.getBucket().equals(bucket))
                .toList();
    }

    // --- Notification Configuration ---

    public void putBucketNotificationConfiguration(String bucketName, NotificationConfiguration config) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setNotificationConfiguration(config);
        bucketStore.put(bucketName, bucket);
        LOG.infov("Set notification configuration for bucket: {0}", bucketName);
    }

    public NotificationConfiguration getBucketNotificationConfiguration(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        NotificationConfiguration config = bucket.getNotificationConfiguration();
        return config != null ? config : new NotificationConfiguration();
    }

    // ──────────────────────────── Policy, CORS, Lifecycle, ACL ────────────────────────────

    public String getBucketPolicy(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getPolicy() == null) {
            throw new AwsException("NoSuchBucketPolicy", "The bucket policy does not exist", 404);
        }
        return bucket.getPolicy();
    }

    public void putBucketPolicy(String bucketName, String policy) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setPolicy(policy);
        bucketStore.put(bucketName, bucket);
    }

    public void deleteBucketPolicy(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setPolicy(null);
        bucketStore.put(bucketName, bucket);
    }

    public String getBucketCors(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getCorsConfiguration() == null) {
            throw new AwsException("NoSuchCORSConfiguration", "The CORS configuration does not exist", 404);
        }
        return bucket.getCorsConfiguration();
    }

    public void putBucketCors(String bucketName, String cors) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setCorsConfiguration(cors);
        bucketStore.put(bucketName, bucket);
    }

    public void deleteBucketCors(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setCorsConfiguration(null);
        bucketStore.put(bucketName, bucket);
    }

    public String getBucketLifecycle(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getLifecycleConfiguration() == null) {
            throw new AwsException("NoSuchLifecycleConfiguration", "The lifecycle configuration does not exist", 404);
        }
        return bucket.getLifecycleConfiguration();
    }

    public void putBucketLifecycle(String bucketName, String lifecycle) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setLifecycleConfiguration(lifecycle);
        bucketStore.put(bucketName, bucket);
    }

    public void deleteBucketLifecycle(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setLifecycleConfiguration(null);
        bucketStore.put(bucketName, bucket);
    }

    public String getBucketAcl(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        return bucket.getAcl() != null ? bucket.getAcl() : defaultAclXml("000000000000", "floci");
    }

    public void putBucketAcl(String bucketName, String acl) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setAcl(acl);
        bucketStore.put(bucketName, bucket);
    }

    public String getObjectAcl(String bucketName, String key, String versionId) {
        S3Object obj = getObject(bucketName, key, versionId);
        return obj.getAcl() != null ? obj.getAcl() : defaultAclXml("000000000000", "floci");
    }

    public void putObjectAcl(String bucketName, String key, String versionId, String acl) {
        S3Object obj = getObject(bucketName, key, versionId);
        obj.setAcl(acl);
        String storeKey = (versionId != null) ? versionedKey(bucketName, key, versionId) : objectKey(bucketName, key);
        objectStore.put(storeKey, obj);
    }

    public String getBucketEncryption(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getEncryptionConfiguration() == null) {
            throw new AwsException("ServerSideEncryptionConfigurationNotFoundError",
                    "The server side encryption configuration was not found", 404);
        }
        return bucket.getEncryptionConfiguration();
    }

    public void putBucketEncryption(String bucketName, String encryptionXml) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setEncryptionConfiguration(encryptionXml);
        bucketStore.put(bucketName, bucket);
    }

    public void deleteBucketEncryption(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setEncryptionConfiguration(null);
        bucketStore.put(bucketName, bucket);
    }

    public void restoreObject(String bucketName, String key, String versionId, String restoreXml) {
        // Validation only - stub implementation
        getObject(bucketName, key, versionId);
        LOG.infov("Restored object: {0}/{1} (stub)", bucketName, key);
    }

    private String defaultAclXml(String id, String displayName) {
        return new XmlBuilder()
                .start("AccessControlPolicy")
                  .start("Owner")
                    .elem("ID", id)
                    .elem("DisplayName", displayName)
                  .end("Owner")
                  .start("AccessControlList")
                    .start("Grant")
                      .raw("<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">")
                        .elem("ID", id)
                        .elem("DisplayName", displayName)
                      .raw("</Grantee>")
                      .elem("Permission", "FULL_CONTROL")
                    .end("Grant")
                  .end("AccessControlList")
                .end("AccessControlPolicy")
                .build();
    }

    private void fireNotifications(String bucketName, String key, String eventName, S3Object obj) {
        if (sqsService == null && snsService == null) {
            return;
        }
        Bucket bucket = bucketStore.get(bucketName).orElse(null);
        if (bucket == null) {
            return;
        }
        NotificationConfiguration config = bucket.getNotificationConfiguration();
        if (config == null || config.isEmpty()) {
            return;
        }

        String region = regionResolver != null ? regionResolver.getDefaultRegion() : "us-east-1";
        String eventJson = buildS3EventJson(bucketName, key, eventName, obj, region);

        for (QueueNotification qn : config.getQueueConfigurations()) {
            if (qn.events().stream().anyMatch(p -> matchesEvent(p, eventName))) {
                try {
                    sqsService.sendMessage(sqsUrlFromArn(qn.queueArn()), eventJson, 0);
                    LOG.debugv("Fired S3 event {0} to SQS {1}", eventName, qn.queueArn());
                } catch (Exception e) {
                    LOG.warnv("Failed to deliver S3 event to SQS {0}: {1}", qn.queueArn(), e.getMessage());
                }
            }
        }

        for (TopicNotification tn : config.getTopicConfigurations()) {
            if (tn.events().stream().anyMatch(p -> matchesEvent(p, eventName))) {
                try {
                    snsService.publish(tn.topicArn(), null, eventJson, "Amazon S3 Notification", region);
                    LOG.debugv("Fired S3 event {0} to SNS {1}", eventName, tn.topicArn());
                } catch (Exception e) {
                    LOG.warnv("Failed to deliver S3 event to SNS {0}: {1}", tn.topicArn(), e.getMessage());
                }
            }
        }
    }

    private boolean matchesEvent(String pattern, String eventName) {
        String full = "s3:" + eventName;
        if (pattern.endsWith("*")) {
            return full.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return full.equals(pattern);
    }

    private String sqsUrlFromArn(String arn) {
        if (arn.split(":").length < 6) return arn;
        return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
    }

    private String buildS3EventJson(String bucketName, String key, String eventName,
                                    S3Object obj, String region) {
        try {
            String eventTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            long size = obj != null ? obj.getSize() : 0;
            String eTag = obj != null && obj.getETag() != null ? obj.getETag().replace("\"", "") : "";
            String requestId = UUID.randomUUID().toString();

            ObjectNode bucketNode = objectMapper.createObjectNode();
            bucketNode.put("name", bucketName);
            bucketNode.put("arn", "arn:aws:s3:::" + bucketName);

            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("key", key);
            objectNode.put("size", size);
            objectNode.put("eTag", eTag);

            ObjectNode s3Node = objectMapper.createObjectNode();
            s3Node.put("s3SchemaVersion", "1.0");
            s3Node.put("configurationId", "emulator");
            s3Node.set("bucket", bucketNode);
            s3Node.set("object", objectNode);

            ObjectNode record = objectMapper.createObjectNode();
            record.put("eventVersion", "2.1");
            record.put("eventSource", "aws:s3");
            record.put("awsRegion", region);
            record.put("eventTime", eventTime);
            record.put("eventName", eventName);
            record.putObject("userIdentity").put("principalId", "AWS:EMULATOR");
            record.putObject("requestParameters").put("sourceIPAddress", "127.0.0.1");
            record.putObject("responseElements").put("x-amz-request-id", requestId);
            record.set("s3", s3Node);

            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("Records").add(record);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    private void cleanupMultipart(String uploadId) {
        multipartUploads.remove(uploadId);
        deleteDirectory(dataRoot.resolve(".multipart").resolve(uploadId));
    }

    private static S3Checksum buildChecksum(byte[] data, List<Part> parts, boolean multipartUpload) {
        S3Checksum checksum = new S3Checksum();
        checksum.setChecksumSHA1(S3Checksum.sha1Base64(data));
        checksum.setChecksumSHA256(S3Checksum.sha256Base64(data));
        checksum.setChecksumType(multipartUpload || (parts != null && parts.size() > 1)
                ? "COMPOSITE"
                : "FULL_OBJECT");
        return checksum;
    }

    private static S3Object copyObject(S3Object source) {
        S3Object copy = new S3Object();
        copy.setBucketName(source.getBucketName());
        copy.setKey(source.getKey());
        copy.setData(source.getData() != null ? Arrays.copyOf(source.getData(), source.getData().length) : null);
        copy.setMetadata(new HashMap<>(source.getMetadata()));
        copy.setContentType(source.getContentType());
        copy.setSize(source.getSize());
        copy.setLastModified(source.getLastModified());
        copy.setETag(source.getETag());
        copy.setStorageClass(source.getStorageClass());
        copy.setChecksum(copyChecksum(source.getChecksum()));
        copy.setParts(copyParts(source.getParts()));
        copy.setVersionId(source.getVersionId());
        copy.setDeleteMarker(source.isDeleteMarker());
        copy.setLatest(source.isLatest());
        copy.setTags(new HashMap<>(source.getTags()));
        copy.setObjectLockMode(source.getObjectLockMode());
        copy.setRetainUntilDate(source.getRetainUntilDate());
        copy.setLegalHoldStatus(source.getLegalHoldStatus());
        copy.setAcl(source.getAcl());
        return copy;
    }

    private static S3Checksum copyChecksum(S3Checksum source) {
        if (source == null) {
            return null;
        }
        S3Checksum copy = new S3Checksum();
        copy.setChecksumCRC32(source.getChecksumCRC32());
        copy.setChecksumCRC32C(source.getChecksumCRC32C());
        copy.setChecksumCRC64NVME(source.getChecksumCRC64NVME());
        copy.setChecksumSHA1(source.getChecksumSHA1());
        copy.setChecksumSHA256(source.getChecksumSHA256());
        copy.setChecksumType(source.getChecksumType());
        return copy;
    }

    private static List<Part> copyParts(List<Part> sourceParts) {
        if (sourceParts == null) {
            return new ArrayList<>();
        }
        return sourceParts.stream().map(S3Service::copyPart).toList();
    }

    private static Part copyPart(Part source) {
        if (source == null) {
            return null;
        }
        Part copy = new Part();
        copy.setPartNumber(source.getPartNumber());
        copy.setETag(source.getETag());
        copy.setSize(source.getSize());
        copy.setChecksum(copyChecksum(source.getChecksum()));
        copy.setLastModified(source.getLastModified());
        return copy;
    }

    private static String computeETag(byte[] data) {
        return "\"" + bytesToHex(computeETagBytes(data)) + "\"";
    }

    private static byte[] computeETagBytes(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void ensureBucketExists(String bucketName) {
        if (bucketStore.get(bucketName).isEmpty()) {
            throw new AwsException("NoSuchBucket",
                    "The specified bucket does not exist.", 404);
        }
    }

    private String objectKey(String bucketName, String key) {
        return bucketName + "/" + key;
    }

    private String versionedKey(String bucketName, String key, String versionId) {
        return bucketName + "/" + key + "#v#" + versionId;
    }

    private Path resolveObjectPath(String bucketName, String key) {
        return dataRoot.resolve(bucketName).resolve(key);
    }

    private Path resolveVersionedPath(String bucketName, String key, String versionId) {
        return dataRoot.resolve(".versions").resolve(bucketName).resolve(key).resolve(versionId);
    }

    private void writeVersionedFile(String bucketName, String key, String versionId, byte[] data) {
        try {
            Path filePath = resolveVersionedPath(bucketName, key, versionId);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write versioned S3 object file", e);
        }
    }

    private byte[] readVersionedFile(String bucketName, String key, String versionId) {
        try {
            return Files.readAllBytes(resolveVersionedPath(bucketName, key, versionId));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read versioned S3 object file", e);
        }
    }

    private void writeFile(String bucketName, String key, byte[] data) {
        try {
            Path filePath = resolveObjectPath(bucketName, key);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write S3 object file", e);
        }
    }

    private byte[] readFile(String bucketName, String key) {
        try {
            return Files.readAllBytes(resolveObjectPath(bucketName, key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read S3 object file", e);
        }
    }

    private void deleteFile(String bucketName, String key) {
        try {
            Files.deleteIfExists(resolveObjectPath(bucketName, key));
        } catch (IOException e) {
            LOG.errorv(e, "Failed to delete S3 object file: {0}/{1}", bucketName, key);
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.errorv(e, "Failed to delete: {0}", path);
                }
            });
        } catch (IOException e) {
            LOG.errorv(e, "Failed to delete directory: {0}", dir);
        }
    }
}
