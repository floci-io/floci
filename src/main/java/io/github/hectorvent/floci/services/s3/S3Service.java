package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.model.*;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class S3Service implements Resettable, ResourceProvider {
    private String ownerId() {
        if (regionResolver != null) {
            return regionResolver.getAccountId();
        }
        if (bucketStore instanceof AccountAwareStorageBackend<?> aware) {
            return aware.accountId();
        }
        return "000000000000";
    }
    static final String DEFAULT_OWNER_DISPLAY_NAME = "floci";
    public static final String INTERNAL_BUCKET_PREFIX = "floci-internal-";
    public static final String REDSHIFT_SPECTRUM_SCRATCH_BUCKET =
            INTERNAL_BUCKET_PREFIX + "redshift-spectrum-scratch";
    public static final String INTERNAL_BUCKET_TAG_KEY = "floci:internal";
    public static final String REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE = "redshift-spectrum-scratch";
    private static final String AUTHENTICATED_USERS_GROUP_URI = "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";
    private static final String LOG_DELIVERY_GROUP_URI = "http://acs.amazonaws.com/groups/s3/LogDelivery";
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final Set<String> SUPPORTED_SERVER_SIDE_ENCRYPTION_VALUES = Set.of("AES256", "aws:kms", "aws:kms:dsse", "aws:fsx");
    private static final String SSE_C_ALGORITHM = "AES256";
    private static final int SSE_C_KEY_BYTES = 32;

    @FunctionalInterface
    interface LambdaInvoker {
        void invoke(String region, String functionName, byte[] payload, InvocationType type);
    }

    private static final Logger LOG = Logger.getLogger(S3Service.class);

    record RequestAuthorization(boolean signed, String accessKeyId, String sessionToken) {
        static RequestAuthorization unsigned() {
            return new RequestAuthorization(false, null, null);
        }
    }

    record SignedPrincipalResourcePolicyEvaluation(
            ResourcePolicyDecision decision,
            String resourceOwnerAccountId
    ) {
    }

    private final StorageBackend<String, Bucket> bucketStore;
    private final StorageBackend<String, S3Object> objectStore;
    private final StorageBackend<String, ObjectAnnotation> annotationStore;
    private final Path dataRoot;
    private final boolean inMemory;
    private final ConcurrentHashMap<String, byte[]> memoryDataStore = new ConcurrentHashMap<>();
    // Annotation payload bytes, keyed by physical key like memoryDataStore. Kept out of
    // annotationStore for the same reason object bodies are kept out of objectStore: every
    // backend serializes its whole map into a single document on each flush, so payloads
    // (up to 1 MiB each, up to 1,000 per object version) must not be inline.
    private final ConcurrentHashMap<String, byte[]> memoryAnnotationStore = new ConcurrentHashMap<>();
    // Guards disk writes/deletes against a racing legacy migration for the same path (see
    // copyLegacyFileIfPresent()). Fixed-size stripes keep memory bounded, unlike a per-path
    // map that would need reference counting to ever shrink safely.
    private static final int DISK_FILE_LOCK_STRIPES = 256;
    private final ReentrantLock[] diskFileLocks = newLockStripes(DISK_FILE_LOCK_STRIPES);
    // Bucket deletion must not sweep an upload while a multipart operation is creating or using it.
    // Read locks preserve parallel multipart writes; fair, fixed stripes avoid writer starvation
    // and retaining a lock for every bucket name ever seen.
    private final ReentrantReadWriteLock[] multipartBucketLocks = newMultipartBucketLocks(256);
    // A second, finer-grained lock prevents part writes, completion and abort from racing on
    // the same upload without serializing independent uploads in the bucket.
    private final ReentrantLock[] multipartUploadLocks = newLockStripes(256);

    private static ReentrantReadWriteLock[] newMultipartBucketLocks(int count) {
        ReentrantReadWriteLock[] locks = new ReentrantReadWriteLock[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new ReentrantReadWriteLock(true);
        }
        return locks;
    }

    private ReentrantReadWriteLock multipartBucketLock(String bucketName) {
        return multipartBucketLocks[Math.floorMod(bucketName.hashCode(), multipartBucketLocks.length)];
    }

    private static <T> T withLock(Lock lock, Supplier<T> operation) {
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private static void withLock(Lock lock, Runnable operation) {
        withLock(lock, () -> {
            operation.run();
            return null;
        });
    }

    private <T> T withMultipartBucketReadLock(String bucketName, Supplier<T> operation) {
        return withLock(multipartBucketLock(bucketName).readLock(), operation);
    }

    private void withMultipartBucketReadLock(String bucketName, Runnable operation) {
        withLock(multipartBucketLock(bucketName).readLock(), operation);
    }

    private void withMultipartBucketWriteLock(String bucketName, Runnable operation) {
        withLock(multipartBucketLock(bucketName).writeLock(), operation);
    }

    private ReentrantLock multipartUploadLock(String uploadId) {
        return multipartUploadLocks[Math.floorMod(uploadId.hashCode(), multipartUploadLocks.length)];
    }

    private <T> T withMultipartOperationLock(String bucketName, String uploadId, Supplier<T> operation) {
        return withMultipartBucketReadLock(bucketName, () -> withLock(multipartUploadLock(uploadId), operation));
    }

    private void withMultipartOperationLock(String bucketName, String uploadId, Runnable operation) {
        withMultipartBucketReadLock(bucketName, () -> withLock(multipartUploadLock(uploadId), operation));
    }

    private static ReentrantLock[] newLockStripes(int count) {
        ReentrantLock[] locks = new ReentrantLock[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }
    // Memory mode keeps an object in one byte array; this is the largest the JDK reliably allocates.
    private static final long MAX_IN_MEMORY_OBJECT_SIZE = Integer.MAX_VALUE - 8;
    // Directory under .multipart where a streamed part or object body is written before it is
    // published. Upload IDs are UUIDs, so it never collides with an upload's own directory, and the
    // sweep at startup and reset clears it with them.
    private static final String INCOMING_BODIES = ".incoming";
    // Upload ID to part bytes, keyed by each part's storage ID.
    private final ConcurrentHashMap<String, Map<String, byte[]>> memoryMultipartStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MultipartUpload> multipartUploads = new ConcurrentHashMap<>();
    // Account-level (S3 Control) Block Public Access config, one entry per AWS account.
    // Distinct from the bucket-level configuration held on each Bucket. Block Public Access is a
    // security control that LZA applies once per governed account, so it is StorageFactory-backed
    // like every other piece of S3 state rather than held in memory: a restart must not silently
    // drop it. Always addressed through the explicit *ForAccount overloads — the account comes
    // from the validated x-amz-account-id header, and the account namespace here is never global
    // (globalBucketNamespace widens bucket resolution only, never this).
    private final AccountAwareStorageBackend<String> accountPublicAccessBlockStore;
    private static final String ACCOUNT_PUBLIC_ACCESS_BLOCK_KEY = "publicAccessBlock";

    private final SqsService sqsService;
    private final SnsService snsService;
    private final LambdaService lambdaService;
    private final Instance<LambdaService> lambdaServiceProvider;
    private final LambdaInvoker lambdaInvoker;
    private final EventBridgeService eventBridgeService;
    private final Event<S3ObjectUpdatedEvent> s3UpdatedEvent;
    private final RegionResolver regionResolver;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final boolean enforceAuth;
    private final boolean enforceIam;
    private final IamService iamService;
    private final boolean globalBucketNamespace;
    private final IamPolicyEvaluator policyEvaluator;

    @Inject
    public S3Service(StorageFactory storageFactory, EmulatorConfig config,
                     SqsService sqsService, SnsService snsService,
                     Instance<LambdaService> lambdaServiceProvider,
                     EventBridgeService eventBridgeService,
                     Event<S3ObjectUpdatedEvent> s3UpdatedEvent,
                     RegionResolver regionResolver,
                     ObjectMapper objectMapper,
                     IamService iamService) {
        this(
                storageFactory.create("s3", "s3-buckets.json",
                        new TypeReference<Map<String, Bucket>>() {
                        }),
                storageFactory.create("s3", "s3-objects.json",
                        new TypeReference<Map<String, S3Object>>() {
                        }, WriteProfile.APPEND_HEAVY),
                storageFactory.create("s3", "s3-annotations.json",
                        new TypeReference<Map<String, ObjectAnnotation>>() {
                        }),
                storageFactory.create("s3", "s3-account-public-access-block.json",
                        new TypeReference<Map<String, String>>() {
                        }),
                Path.of(config.storage().persistentPath()).resolve("s3"),
                "memory".equals(config.storage().services().s3().mode().orElse(config.storage().mode())),
                sqsService, snsService, null, lambdaServiceProvider, null,
                eventBridgeService, s3UpdatedEvent,
                regionResolver,
                config.effectiveBaseUrl(), objectMapper,
                config.services().s3().enforceAuth(), config.services().iam().enforcementEnabled(), iamService,
                config.services().s3().globalBucketNamespace()
        );
    }

    /**
     * Package-private constructor for testing.
     */
    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              Path dataRoot, boolean inMemory) {
        this(bucketStore, objectStore, defaultAnnotationStore(), defaultAccountPublicAccessBlockStore(),
                dataRoot, inMemory, null, null, null, null, null, null, null,
                null, "http://localhost:4566", new ObjectMapper(), false, false, null, false);
    }

    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              Path dataRoot, boolean inMemory,
              boolean enforceAuth, IamService iamService) {
        this(bucketStore, objectStore, defaultAnnotationStore(), defaultAccountPublicAccessBlockStore(),
                dataRoot, inMemory, null, null, null, null, null, null, null,
                null, "http://localhost:4566", new ObjectMapper(), enforceAuth, false, iamService, false);
    }

    /** Package-private constructor for testing account-level Block Public Access persistence. */
    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              AccountAwareStorageBackend<String> accountPublicAccessBlockStore,
              Path dataRoot, boolean inMemory) {
        this(bucketStore, objectStore, defaultAnnotationStore(), accountPublicAccessBlockStore,
                dataRoot, inMemory, null, null, null, null, null, null, null,
                null, "http://localhost:4566", new ObjectMapper(), false, false, null, false);
    }

    /** Package-private constructor for testing the global-bucket-namespace resolution flag. */
    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              Path dataRoot, boolean inMemory, boolean globalBucketNamespace) {
        this(bucketStore, objectStore, defaultAnnotationStore(), defaultAccountPublicAccessBlockStore(),
                dataRoot, inMemory, null, null, null, null, null, null, null,
                null, "http://localhost:4566", new ObjectMapper(), false, false, null, globalBucketNamespace);
    }

    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              Path dataRoot, boolean inMemory,
              LambdaService lambdaService,
              RegionResolver regionResolver) {
        this(bucketStore, objectStore, defaultAnnotationStore(), defaultAccountPublicAccessBlockStore(),
                dataRoot, inMemory, null, null, lambdaService, null, null, null, null,
                regionResolver, "http://localhost:4566", new ObjectMapper(), false, false, null, false);
    }

    S3Service(StorageBackend<String, Bucket> bucketStore,
              StorageBackend<String, S3Object> objectStore,
              Path dataRoot, boolean inMemory,
              LambdaInvoker lambdaInvoker,
              RegionResolver regionResolver) {
        this(bucketStore, objectStore, defaultAnnotationStore(), defaultAccountPublicAccessBlockStore(),
                dataRoot, inMemory, null, null, null, null, lambdaInvoker, null, null,
                regionResolver, "http://localhost:4566", new ObjectMapper(), false, false, null, false);
    }

    /** In-memory account-level Block Public Access store for the package-private test constructors. */
    private static AccountAwareStorageBackend<String> defaultAccountPublicAccessBlockStore() {
        return AccountAwareStorageBackend.inMemory("000000000000");
    }

    /** In-memory annotation store for the package-private test constructors. */
    private static AccountAwareStorageBackend<ObjectAnnotation> defaultAnnotationStore() {
        return AccountAwareStorageBackend.inMemory("000000000000");
    }

    private S3Service(StorageBackend<String, Bucket> bucketStore,
                      StorageBackend<String, S3Object> objectStore,
                      AccountAwareStorageBackend<ObjectAnnotation> annotationStore,
                      AccountAwareStorageBackend<String> accountPublicAccessBlockStore,
                      Path dataRoot, boolean inMemory, SqsService sqsService, SnsService snsService,
                      LambdaService lambdaService,
                      Instance<LambdaService> lambdaServiceProvider,
                      LambdaInvoker lambdaInvoker,
                      EventBridgeService eventBridgeService,
                      Event<S3ObjectUpdatedEvent> s3UpdatedEvent,
                      RegionResolver regionResolver, String baseUrl, ObjectMapper objectMapper,
                      boolean enforceAuth, boolean enforceIam, IamService iamService,
                      boolean globalBucketNamespace) {
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
        this.annotationStore = annotationStore;
        this.accountPublicAccessBlockStore = accountPublicAccessBlockStore;
        this.dataRoot = dataRoot;
        this.inMemory = inMemory;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.lambdaService = lambdaService;
        this.lambdaServiceProvider = lambdaServiceProvider;
        this.lambdaInvoker = lambdaInvoker;
        this.eventBridgeService = eventBridgeService;
        this.s3UpdatedEvent = s3UpdatedEvent;
        this.regionResolver = regionResolver;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.enforceAuth = enforceAuth;
        this.enforceIam = enforceIam;
        this.iamService = iamService;
        this.globalBucketNamespace = globalBucketNamespace;
        this.policyEvaluator = new IamPolicyEvaluator(objectMapper);
        if (!inMemory) {
            try {
                Files.createDirectories(dataRoot);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create S3 data directory: " + dataRoot, e);
            }
            deleteMultipartFiles();
        }
    }

    public void clear() {
        memoryDataStore.clear();
        memoryAnnotationStore.clear();
        memoryMultipartStore.clear();
        multipartUploads.clear();
        if (!inMemory) {
            // The reset above erases the annotation metadata through storageFactory.clearAll(),
            // so detached .s3ann payload files become unreachable: sweep the annotation payload
            // root for every account partition (reset runs outside request context, so the
            // default account alone is not enough). Mirrors the metadata erase; the pre-existing
            // .s3data behavior is unchanged.
            deleteAnnotationPayloadRoots();
            deleteMultipartFiles();
        }
    }

    /**
     * Deletes the part and assembled files of every multipart upload. Uploads are tracked only in
     * memory, so at startup or after a reset these files belong to uploads that no longer exist, and
     * an assembly a crash interrupted can be as large as the object it was building.
     */
    private void deleteMultipartFiles() {
        Path multipartRoot = dataRoot.resolve(".multipart");
        if (!Files.isDirectory(multipartRoot)) {
            return;
        }
        try (Stream<Path> uploads = Files.list(multipartRoot)) {
            long count = uploads.count();
            if (count > 0) {
                LOG.infov("Removing the files of {0} multipart uploads that no longer exist under {1}", count, multipartRoot);
            }
        } catch (IOException e) {
            LOG.warnv(e, "Failed to list {0} before removing it", multipartRoot);
        }
        deleteDirectory(multipartRoot);
    }

    private void deleteAnnotationPayloadRoots() {
        Path accountsRoot = dataRoot.resolve(ACCOUNT_STORAGE_ROOT);
        if (!Files.isDirectory(accountsRoot)) {
            return;
        }
        try (var accounts = Files.list(accountsRoot)) {
            for (Path account : accounts.toList()) {
                Path annotationsRoot = account.resolve(ANNOTATION_STORAGE_ROOT);
                if (Files.isDirectory(annotationsRoot)) {
                    deleteDirectory(annotationsRoot);
                }
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to reset annotation payload files under {0}", accountsRoot);
        }
    }

    public Bucket createBucket(String bucketName, String region) {
        requirePathSafeBucketName(bucketName);
        var existing = bucketStore.get(bucketName);
        if (existing.isPresent()) {
            Bucket bucket = existing.get();
            if (isDefaultS3Region(bucket.getRegion()) && isDefaultS3Region(region)) {
                LOG.infov("Bucket already exists in default region, treating CreateBucket as idempotent: {0}", bucketName);
                return bucket;
            }
            throw new AwsException("BucketAlreadyOwnedByYou",
                    "Your previous request to create the named bucket succeeded and you already own it.", 409);
        }

        Bucket bucket = new Bucket(bucketName);
        bucket.setRegion(region);
        bucketStore.put(bucketName, bucket);
        LOG.infov("Created bucket: {0} in region: {1}", bucketName, region);
        return bucket;
    }

    private static boolean isDefaultS3Region(String region) {
        return region == null || region.isBlank() || "us-east-1".equalsIgnoreCase(region); // partition-literal: S3's idempotent CreateBucket rule is literally us-east-1 in every partition
    }

    public void deleteBucket(String bucketName) {
        withMultipartBucketWriteLock(bucketName, () -> {
            ensureBucketExists(bucketName);
            Bucket bucket = bucketStore.get(bucketName)
                    .orElseThrow(() -> new AwsException("NoSuchBucket",
                            "The specified bucket does not exist.", 404));

            // Multipart operations take the stripe before the bucket monitor, so a delete cannot
            // sweep their temporary parts or miss an upload created just after its scan.
            synchronized (bucket) {
                deleteBucketLocked(bucketName);
            }
            LOG.infov("Deleted bucket: {0}", bucketName);
        });
    }

    private void deleteBucketLocked(String bucketName) {
        // Check if bucket is empty
        List<S3Object> objects = listObjects(bucketName, null, null, 1);
        if (!objects.isEmpty()) {
            throw new AwsException("BucketNotEmpty",
                    "The bucket you tried to delete is not empty.", 409);
        }

        // Outstanding uploads belong to this bucket incarnation. Do not let a later
        // owner of the same name discover or complete them.
        for (MultipartUpload upload : multipartUploads.values()) {
            if (bucketName.equals(upload.getBucket()) && ownerId().equals(upload.getOwnerAccountId())) {
                cleanupMultipart(upload.getUploadId());
            }
        }

        bucketStore.delete(bucketName);
        deleteAllAnnotationsForBucket(bucketName);
        if (inMemory) {
            String prefix = ownerId() + "/" + bucketName + "/";
            memoryDataStore.keySet().removeIf(k -> k.startsWith(prefix));
            memoryAnnotationStore.keySet().removeIf(k -> k.startsWith(prefix));
        } else {
            deleteDirectory(bucketDirectory(dataRoot.resolve(ACCOUNT_STORAGE_ROOT).resolve(ownerId()),
                    bucketName));
            deleteDirectory(bucketDirectory(dataRoot.resolve(ACCOUNT_STORAGE_ROOT).resolve(ownerId())
                    .resolve(ANNOTATION_STORAGE_ROOT), bucketName));
        }
    }

    public List<Bucket> listBuckets() {
        return bucketStore.scan(key -> true).stream()
                .filter(bucket -> !isSpectrumScratchBucket(bucket))
                .toList();
    }

    /**
     * The Spectrum scratch bucket is told apart from a user bucket of the same name by the tag the
     * materializer puts on it, so a user's own bucket is never hidden or treated as scratch space.
     */
    private static boolean isSpectrumScratchBucket(Bucket bucket) {
        return REDSHIFT_SPECTRUM_SCRATCH_BUCKET.equals(bucket.getName())
                && bucket.getTags() != null
                && REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE.equals(bucket.getTags().get(INTERNAL_BUCKET_TAG_KEY));
    }

    public String getBucketOwnerAccountId(String bucketName) {
        return resolveBucketEntry(bucketName)
                .map(AccountAwareStorageBackend.OwnedEntry::account)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
    }

    public void putBucketLogging(String bucketName, String loggingConfigurationXml) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        if (loggingConfigurationXml == null || loggingConfigurationXml.isBlank()) {
            bucket.setLoggingConfiguration(null);
        } else {
            String targetBucket = XmlParser.extractFirst(loggingConfigurationXml, "TargetBucket", null);
            if (targetBucket == null) {
                bucket.setLoggingConfiguration(null);
            } else {
                bucket.setLoggingConfiguration(loggingConfigurationXml);
            }
        }

        bucketStore.put(bucketName, bucket);
    }

    public String getBucketLogging(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        if (bucket.getLoggingConfiguration() == null || bucket.getLoggingConfiguration().isBlank()) {
            return new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("BucketLoggingStatus", AwsNamespaces.S3)
                    .end("BucketLoggingStatus")
                    .build();
        }

        return bucket.getLoggingConfiguration();
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata) {
        return putObject(bucketName, key, data, contentType, metadata, new PutObjectOptions());
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata,
                              String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        return putObject(bucketName, key, data, contentType, metadata,
                new PutObjectOptions()
                        .withObjectLockMode(objectLockMode)
                        .withRetainUntilDate(retainUntilDate)
                        .withLegalHoldStatus(legalHoldStatus));
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata, String storageClass,
                              String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        return putObject(bucketName, key, data, contentType, metadata,
                new PutObjectOptions()
                        .withStorageClass(storageClass)
                        .withObjectLockMode(objectLockMode)
                        .withRetainUntilDate(retainUntilDate)
                        .withLegalHoldStatus(legalHoldStatus));
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata, String storageClass,
                              String contentEncoding,
                              String objectLockMode, Instant retainUntilDate, String legalHoldStatus,
                              String contentDisposition, String cacheControl, String serverSideEncryption, String acl) {
        return putObject(bucketName, key, data, contentType, metadata,
                new PutObjectOptions()
                        .withStorageClass(storageClass)
                        .withContentEncoding(contentEncoding)
                        .withObjectLockMode(objectLockMode)
                        .withRetainUntilDate(retainUntilDate)
                        .withLegalHoldStatus(legalHoldStatus)
                        .withContentDisposition(contentDisposition)
                        .withCacheControl(cacheControl)
                        .withServerSideEncryption(serverSideEncryption)
                        .withAcl(acl));
    }

    public S3Object putObject(String bucketName, String key, byte[] data,
                              String contentType, Map<String, String> metadata, PutObjectOptions options) {
        return createObject(bucketName, key, data, contentType, metadata, options, "ObjectCreated:Put");
    }

    /**
     * Stores an object streamed from {@code body}, checked against {@code checksums}, without holding
     * it in memory in the disk-backed modes. Everything the request alone decides is checked before
     * any of the body is read, so a write that cannot succeed fails without transferring it. The body
     * is staged on disk with its ETag and checksum worked out as it arrives, then moved into place
     * like an assembled multipart object.
     */
    S3Object putObject(String bucketName, String key, InputStream body, UploadChecksums checksums,
                       String contentType, Map<String, String> metadata, PutObjectOptions options) {
        PutObjectOptions effectiveOptions = options != null ? options : new PutObjectOptions();
        checksums.requireWellFormedContentMd5();
        AccountAwareStorageBackend.OwnedEntry<Bucket> checkedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        rejectConflictingServerSideEncryption(
                normalizeServerSideEncryption(effectiveOptions.getServerSideEncryption()),
                validateSseCustomerKey(effectiveOptions.getSseCustomerAlgorithm(),
                        effectiveOptions.getSseCustomerKey(), effectiveOptions.getSseCustomerKeyMd5()));
        ChecksumAlgorithm declared = ChecksumAlgorithm.fromWireValue(effectiveOptions.getChecksumAlgorithm());
        // Checked again under the bucket lock when the object is stored; this only spares a transfer.
        checkWritePreconditions(bucketName, key, effectiveOptions.getIfMatch(), effectiveOptions.getIfNoneMatch());
        if (inMemory) {
            byte[] data = readVerified(body, checksums, "PutObject of " + bucketName + "/" + key);
            S3Object object = storeObject(checkedBucket, bucketName, key, new BytesBody(data),
                    contentType, metadata, null, null, effectiveOptions, null);
            fireNotifications(bucketName, key, "ObjectCreated:Put", object);
            return object;
        }

        // A checksum the client sent is stored as sent; otherwise the declared algorithm's, or CRC64NVME.
        ChecksumAlgorithm computed = effectiveOptions.getClientChecksum() != null ? null
                : declared != null ? declared : ChecksumAlgorithm.CRC64NVME;
        Set<ChecksumAlgorithm> algorithms = EnumSet.noneOf(ChecksumAlgorithm.class);
        algorithms.addAll(checksums.algorithms());
        if (computed != null) {
            algorithms.add(computed);
        }
        DigestingInputStream digests = new DigestingInputStream(body, algorithms);
        Path staged = stageBody(digests);
        try {
            checksums.verify(digests.md5(), digests::checksum);
            S3Checksum checksum = null;
            if (computed != null) {
                checksum = new S3Checksum();
                checksum.setValueFor(computed, digests.checksum(computed));
                checksum.setChecksumType(ChecksumType.FULL_OBJECT);
            }
            S3Object object = storeObject(checkedBucket, bucketName, key, new AssembledBody(staged, digests.size()),
                    contentType, metadata, checksum, null, effectiveOptions, digests.eTag());
            fireNotifications(bucketName, key, "ObjectCreated:Put", object);
            return object;
        } finally {
            deleteQuietly(staged, "staged body of an object that was not stored");
        }
    }

    public S3Object postObject(String bucketName, String key, byte[] data,
                               String contentType, Map<String, String> metadata) {
        return postObject(bucketName, key, data, contentType, metadata, new PutObjectOptions());
    }

    public S3Object postObject(String bucketName, String key, byte[] data,
                               String contentType, Map<String, String> metadata, PutObjectOptions options) {
        return createObject(bucketName, key, data, contentType, metadata, options, "ObjectCreated:Post");
    }

    private S3Object createObject(String bucketName, String key, byte[] data,
                                  String contentType, Map<String, String> metadata,
                                  PutObjectOptions options, String eventName) {
        S3Object object = storeObject(bucketName, key, data, contentType, metadata, null, null, options);
        fireNotifications(bucketName, key, eventName, object);
        return object;
    }

    /**
     * Store object without firing notifications (used internally by completeMultipartUpload).
     */
    private S3Object storeObject(String bucketName, String key, byte[] data,
                                 String contentType, Map<String, String> metadata) {
        return storeObject(bucketName, key, data, contentType, metadata, null, null, new PutObjectOptions());
    }

    private S3Object storeObject(String bucketName, String key, byte[] data,
                                 String contentType, Map<String, String> metadata, String storageClass,
                                 S3Checksum checksum, List<Part> parts,
                                 String objectLockMode, Instant retainUntilDate, String legalHoldStatus) {
        return storeObject(bucketName, key, data, contentType, metadata, checksum, parts,
                new PutObjectOptions()
                        .withStorageClass(storageClass)
                        .withObjectLockMode(objectLockMode)
                        .withRetainUntilDate(retainUntilDate)
                        .withLegalHoldStatus(legalHoldStatus));
    }

    private S3Object storeObject(String bucketName, String key, byte[] data,
                                 String contentType, Map<String, String> metadata,
                                 S3Checksum checksum, List<Part> parts, PutObjectOptions options) {
        return storeObject(bucketName, key, data, contentType, metadata, checksum, parts, options, null);
    }

    private S3Object storeObject(String bucketName, String key, byte[] data,
                                 String contentType, Map<String, String> metadata,
                                 S3Checksum checksum, List<Part> parts, PutObjectOptions options, String eTag) {
        return storeObject(bucketName, key, new BytesBody(data), contentType, metadata, checksum, parts, options, eTag);
    }

    private S3Object storeObject(String bucketName, String key, ObjectBody body,
                                 String contentType, Map<String, String> metadata,
                                 S3Checksum checksum, List<Part> parts, PutObjectOptions options, String eTag) {
        return storeObject(null, bucketName, key, body, contentType, metadata, checksum, parts, options, eTag);
    }

    /**
     * Stores the object, and with {@code checkedBucket} given, only in that bucket: an upload checked
     * against a bucket that was deleted while its body arrived fails with NoSuchBucket rather than
     * landing in a bucket created under the same name since, whose policy it was never checked
     * against. A bucket is created once and keeps its instance until it is deleted, so the instance
     * tells one bucket of a name from another.
     */
    private S3Object storeObject(AccountAwareStorageBackend.OwnedEntry<Bucket> checkedBucket,
                                 String bucketName, String key, ObjectBody body,
                                 String contentType, Map<String, String> metadata,
                                 S3Checksum checksum, List<Part> parts, PutObjectOptions options, String eTag) {
        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        Bucket bucket = ownedBucket.value();
        synchronized (bucket) {
            // Rechecked under the bucket's monitor, which deleting the bucket also holds.
            if (checkedBucket != null && resolveBucketEntry(bucketName)
                    .map(current -> current.value() != checkedBucket.value())
                    .orElse(true)) {
                throw new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404);
            }
            return storeObjectInternal(ownedBucket.account(), bucket, bucketName, key, body,
                    contentType, metadata, checksum, parts, options, eTag);
        }
    }

    /**
     * The body of an object being stored: bytes already in memory, or a file on disk, which is moved
     * into place instead of being read back.
     */
    private sealed interface ObjectBody permits BytesBody, AssembledBody {
        long size();
    }

    private record BytesBody(byte[] data) implements ObjectBody {
        @Override
        public long size() {
            return data.length;
        }
    }

    /**
     * A body already on disk that is moved into place: an assembled multipart object, the pinned file
     * of a copy source, or a streamed PutObject body. It is stored with an ETag and checksum worked
     * out beforehand, since computing either here would mean reading the whole file under the bucket
     * lock.
     */
    private record AssembledBody(Path file, long size) implements ObjectBody { }

    private S3Object storeObjectInternal(String bucketOwnerAccount, Bucket bucket,
                                         String bucketName, String key, ObjectBody body,
                                         String contentType, Map<String, String> metadata,
                                         S3Checksum checksum, List<Part> parts, PutObjectOptions options,
                                         String eTag) {
        PutObjectOptions effectiveOptions = options != null ? options : new PutObjectOptions();
        String normalizedServerSideEncryption = normalizeServerSideEncryption(effectiveOptions.getServerSideEncryption());
        SseCustomerKey sseCustomerKey = validateSseCustomerKey(effectiveOptions.getSseCustomerAlgorithm(), effectiveOptions.getSseCustomerKey(), effectiveOptions.getSseCustomerKeyMd5());
        rejectConflictingServerSideEncryption(normalizedServerSideEncryption, sseCustomerKey);
        checkWritePreconditions(bucketName, key, effectiveOptions.getIfMatch(), effectiveOptions.getIfNoneMatch());

        byte[] data = body instanceof BytesBody bytes ? bytes.data() : null;
        S3Object object = new S3Object(bucketName, key, body.size(), contentType,
                eTag != null ? eTag : computeETag(data));
        object.setData(data);
        if (metadata != null) {
            object.getMetadata().putAll(metadata);
        }
        object.setStorageClass(ObjectAttributeName.normalizeStorageClass(effectiveOptions.getStorageClass()));
        ChecksumAlgorithm validatedChecksumAlgorithm = ChecksumAlgorithm.fromWireValue(effectiveOptions.getChecksumAlgorithm());
        S3Checksum resolvedChecksum = checksum != null ? copyChecksum(checksum)
                : effectiveOptions.getClientChecksum() != null ? copyChecksum(effectiveOptions.getClientChecksum())
                : S3Checksum.fullObject(validatedChecksumAlgorithm, data);
        object.setChecksum(resolvedChecksum);
        object.setParts(copyParts(parts));
        object.setContentEncoding(effectiveOptions.getContentEncoding());
        object.setContentDisposition(effectiveOptions.getContentDisposition());
        object.setCacheControl(effectiveOptions.getCacheControl());
        object.setServerSideEncryption(normalizedServerSideEncryption);
        object.setSseKmsKeyId("aws:kms".equals(normalizedServerSideEncryption)
                ? effectiveOptions.getSseKmsKeyId()
                : null);
        if (sseCustomerKey != null) {
            object.setSseCustomerAlgorithm(sseCustomerKey.algorithm());
            object.setSseCustomerKeyMd5(sseCustomerKey.keyMd5());
        }
        String objectAcl = resolveObjectAclXml(bucketOwnerAccount,
                effectiveOptions.getAcl(), effectiveOptions.getGrantRead(),
                effectiveOptions.getGrantWrite(), effectiveOptions.getGrantFullControl(),
                effectiveOptions.getGrantReadAcp(), effectiveOptions.getGrantWriteAcp());
        // BlockPublicAcls fails a PutObject that carries a public ACL. The bucket and its owner
        // are already resolved here, so the settings are read without a second lookup.
        if (objectAcl != null && S3AclPublicAccessEvaluator.aclIsPublic(objectAcl)
                && blockPublicAccessFor(bucket, bucketOwnerAccount).blockPublicAcls()) {
            LOG.debugv("BlockPublicAcls rejected a public ACL on PutObject {0}/{1}", bucketName, key);
            throw accessDeniedException(bucketName, key);
        }
        object.setAcl(objectAcl);
        if (effectiveOptions.getTagging() != null && !effectiveOptions.getTagging().isEmpty()) {
            object.setTags(new HashMap<>(effectiveOptions.getTagging()));
        }

        if (bucket.isVersioningEnabled()) {
            String versionId = UUID.randomUUID().toString();
            object.setVersionId(versionId);
            object.setLatest(true);
            // Doubles as this object's dataGeneration token (see the field's javadoc on
            // S3Object) - reusing versionId needs no extra random value.
            object.setDataGeneration(versionId);

            // Check lock protection on the current latest before overwriting
            String latestKey = objectKey(bucketName, key);
            // A pre-versioning object being replaced: its annotations were keyed at the plain
            // object key and would be left unreachable by the new version. Cleanup is deferred
            // until the replacement body is on disk, so a failed write does not drop them.
            boolean[] dropPreVersioningAnnotations = {false};
            resolveObjectForAccount(bucketOwnerAccount, latestKey).ifPresent(prev -> {
                if (prev.isLatest() && !prev.isDeleteMarker() && bucket.isObjectLockEnabled() && prev.getVersionId() == null) {
                    checkLockProtection(prev, false);
                }
                if (prev.getVersionId() != null) {
                    prev.setLatest(false);
                    putObjectForAccount(bucketOwnerAccount,
                            versionedKey(bucketName, key, prev.getVersionId()), prev);
                } else {
                    dropPreVersioningAnnotations[0] = true;
                }
            });

            // Apply lock fields from request or bucket default
            applyObjectLock(object, bucket,
                    effectiveOptions.getObjectLockMode(),
                    effectiveOptions.getRetainUntilDate(),
                    effectiveOptions.getLegalHoldStatus());

            // Write the body before publishing metadata: getObject's optimistic read (see
            // getLatestObject) relies on a generation only ever becoming visible in objectStore
            // once its file is fully on disk, or a reader could see the new dataGeneration and
            // still read the previous write's bytes underneath it. Write the fresh, not-yet-
            // referenced versioned file first and the shared canonical file last: if the
            // versioned write fails, the canonical file - which unlocked GETs already associate
            // with the still-unpublished previous generation - is never touched, so a concurrent
            // GET can't observe corrupted "latest" bytes paired with the old metadata.
            if (body instanceof AssembledBody assembled) {
                Path versionedPath = resolveVersionedPath(bucketOwnerAccount, bucketName, key, versionId);
                moveIntoPlace(assembled.file(), versionedPath);
                linkIntoPlace(versionedPath, resolveObjectPath(bucketOwnerAccount, bucketName, key));
            } else {
                writeVersionedFile(bucketOwnerAccount, bucketName, key, versionId, data);
                writeFile(bucketOwnerAccount, bucketName, key, data);
            }
            // Deferred pre-versioning annotation cleanup: only after the replacement body is on
            // disk, so a failed write keeps the old body and its annotations together.
            if (dropPreVersioningAnnotations[0]) {
                deleteAllAnnotationsFor(annotationParentKey(bucketName, key, null));
            }
            // Release the cached payload before publishing: once objectStore.put makes this
            // instance visible to other threads, a concurrent getObject can hold a reference to
            // it (copyObject reads getData() without any lock) and race this null-out otherwise.
            object.setData(null);
            // Store versioned copy and update latest pointer
            putObjectForAccount(bucketOwnerAccount, versionedKey(bucketName, key, versionId), object);
            putObjectForAccount(bucketOwnerAccount, latestKey, object);
            LOG.debugv("Put versioned object: {0}/{1} v={2} ({3} bytes)", bucketName, key, versionId, body.size());
        } else {
            S3Object prev = resolveObjectForAccount(
                    bucketOwnerAccount, objectKey(bucketName, key)).orElse(null);
            // Check lock protection on the existing object before overwriting
            if (bucket.isObjectLockEnabled() && prev != null && !prev.isDeleteMarker()) {
                checkLockProtection(prev, false);
            }

            // Apply lock fields from request or bucket default
            applyObjectLock(object, bucket,
                    effectiveOptions.getObjectLockMode(),
                    effectiveOptions.getRetainUntilDate(),
                    effectiveOptions.getLegalHoldStatus());

            // A fresh per-write token, compared by getObject's optimistic read against a
            // later re-read of this same field to detect a concurrent overwrite - see the
            // dataGeneration javadoc on S3Object.
            object.setDataGeneration(UUID.randomUUID().toString());

            // Write the body before publishing metadata - see the comment in the versioned
            // branch above; the same ordering requirement applies here.
            if (body instanceof AssembledBody assembled) {
                moveIntoPlace(assembled.file(), resolveObjectPath(bucketOwnerAccount, bucketName, key));
            } else {
                writeFile(bucketOwnerAccount, bucketName, key, data);
            }
            // An overwrite replaces the object's annotations (AWS drops them on overwrite).
            // The cleanup runs only after the body write succeeds, so a failed PUT keeps the
            // old body together with its annotations; and before the new metadata is published,
            // so the replacement never appears annotated.
            deleteAllAnnotationsFor(annotationParentKey(bucketName, key, null));
            // Release the cached payload before publishing - see the comment in the versioned
            // branch above; the same race applies here.
            object.setData(null);
            putObjectForAccount(bucketOwnerAccount, objectKey(bucketName, key), object);
            LOG.debugv("Put object: {0}/{1} ({2} bytes)", bucketName, key, body.size());
        }
        return object;
    }

    private void checkWritePreconditions(String bucketName, String key, String ifMatch, String ifNoneMatch) {
        if (ifMatch == null && ifNoneMatch == null) {
            return;
        }

        S3Object existing;
        try {
            existing = headObject(bucketName, key);
        }
        catch (AwsException e) {
            if ("NoSuchKey".equals(e.getErrorCode()) && ifMatch == null) {
                return;
            }
            throw e;
        }

        if (ifMatch != null && !eTagMatches(ifMatch, existing.getETag())) {
            throw new S3PreconditionFailedException("If-Match");
        }
        if (ifNoneMatch != null && eTagMatches(ifNoneMatch, existing.getETag())) {
            throw new S3PreconditionFailedException("If-None-Match");
        }
    }

    private boolean eTagMatches(String headerValue, String eTag) {
        String normalizedETag = normalizeEntityTag(eTag);
        for (String candidate : headerValue.split(",")) {
            String normalizedCandidate = normalizeEntityTag(candidate);
            if ("*".equals(normalizedCandidate) || normalizedCandidate.equals(normalizedETag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the {@code x-amz-copy-source-if-*} preconditions to the source of a copy. The pairing
     * rules are the ones S3 documents for CopyObject: a matching {@code if-match} makes
     * {@code if-unmodified-since} irrelevant, and a matching {@code if-none-match} fails whatever
     * {@code if-modified-since} says. Unlike a conditional GET, every failure is a 412, never a 304.
     * Dates compare at second precision, the resolution of an HTTP date and of Last-Modified. S3
     * documents no {@code <Condition>} value for these, so the error carries none, as a failed
     * conditional GET does here.
     */
    private void checkCopySourcePreconditions(S3Object source, CopySourceConditions conditions) {
        if (conditions == null) {
            return;
        }
        Instant lastModified = source.getLastModified().truncatedTo(ChronoUnit.SECONDS);
        if (conditions.ifMatch() != null && !eTagMatches(conditions.ifMatch(), source.getETag())) {
            throw new S3PreconditionFailedException(null);
        }
        if (conditions.ifUnmodifiedSince() != null && conditions.ifMatch() == null
                && lastModified.isAfter(conditions.ifUnmodifiedSince())) {
            throw new S3PreconditionFailedException(null);
        }
        if (conditions.ifNoneMatch() != null && eTagMatches(conditions.ifNoneMatch(), source.getETag())) {
            throw new S3PreconditionFailedException(null);
        }
        if (conditions.ifModifiedSince() != null && conditions.ifNoneMatch() == null
                && !lastModified.isAfter(conditions.ifModifiedSince())) {
            throw new S3PreconditionFailedException(null);
        }
    }

    static String normalizeEntityTag(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    public void authorizeListBucket(String bucketName, RequestAuthorization authorization) {
        authorizeBucketRead(bucketName, "s3:ListBucket", authorization);
    }

    public void authorizeGetObject(String bucketName, String key, String versionId, RequestAuthorization authorization) {
        String action = versionId != null ? "s3:GetObjectVersion" : "s3:GetObject";
        if (enforceAuth && versionId == null && isUnsignedRequest(authorization) && !readableObjectExists(bucketName, key)) {
            authorizeMissingObjectRead(bucketName, authorization);
            return;
        }
        authorizeObjectRead(bucketName, key, versionId, action, authorization);
    }

    public void authorizeAnonymousGetObject(String bucketName, String key) {
        authorizeGetObject(bucketName, key, null, RequestAuthorization.unsigned());
    }

    /** Authorize an unsigned {@code s3:ListBucket}; see {@link #authorizeAnonymousGetObject}. */
    public void authorizeAnonymousListBucket(String bucketName) {
        authorizeListBucket(bucketName, RequestAuthorization.unsigned());
    }

    /** Authorize an unsigned {@code s3:PutObject}; see {@link #authorizeAnonymousGetObject}. */
    public void authorizeAnonymousPutObject(String bucketName, String key) {
        authorizePutObject(bucketName, key, RequestAuthorization.unsigned());
    }

    /** Authorize an unsigned {@code s3:DeleteObject}; see {@link #authorizeAnonymousGetObject}. */
    public void authorizeAnonymousDeleteObject(String bucketName, String key) {
        authorizeDeleteObject(bucketName, key, null, RequestAuthorization.unsigned());
    }

    /**
     * Authorize {@code s3:GetObject} as a signed principal (an IAM role session's access key),
     * reusing the identity-policy + resource-policy evaluation a genuine SigV4 request goes
     * through. Used for Redshift {@code COPY ... IAM_ROLE '<arn>'}.
     */
    public void authorizeSignedGetObject(String accessKeyId, String sessionToken, String bucketName, String key) {
        authorizeGetObject(bucketName, key, null, new RequestAuthorization(true, accessKeyId, sessionToken));
    }

    /** Authorize a signed {@code s3:PutObject}; see {@link #authorizeSignedGetObject}. */
    public void authorizeSignedPutObject(String accessKeyId, String sessionToken, String bucketName, String key) {
        authorizePutObject(bucketName, key, new RequestAuthorization(true, accessKeyId, sessionToken));
    }

    /** Authorize a signed {@code s3:ListBucket}; see {@link #authorizeSignedGetObject}. */
    public void authorizeSignedListBucket(String accessKeyId, String sessionToken, String bucketName) {
        authorizeListBucket(bucketName, new RequestAuthorization(true, accessKeyId, sessionToken));
    }

    /** Authorize a signed {@code s3:DeleteObject}; see {@link #authorizeSignedGetObject}. */
    public void authorizeSignedDeleteObject(String accessKeyId, String sessionToken, String bucketName, String key) {
        authorizeDeleteObject(bucketName, key, null, new RequestAuthorization(true, accessKeyId, sessionToken));
    }

    public void authorizeCloudFrontOacGetObject(
            String bucketName, String key, String distributionArn) {
        authorizeCloudFrontGetObject(
                bucketName,
                key,
                "Service",
                ServicePrincipals.of("cloudfront"),
                Map.of("AWS:SourceArn", distributionArn),
                null);
    }

    public void authorizeCloudFrontOaiGetObject(
            String bucketName, String key, String originAccessIdentityId,
            String canonicalUserId) {
        authorizeCloudFrontGetObject(
                bucketName,
                key,
                "AWS",
                regionResolver.buildGlobalArn("iam", "cloudfront",
                        "user/CloudFront Origin Access Identity " + originAccessIdentityId),
                Map.of(),
                canonicalUserId);
    }

    public void authorizeCloudFrontViewerGetObject(
            String bucketName, String key, String viewerAuthorization) {
        RequestAuthorization authorization =
                S3RequestAuthorizationParser.parseIfRequired(
                        enforceAuth, viewerAuthorization, new MultivaluedHashMap<>());
        authorizeGetObject(bucketName, key, null, authorization);
    }

    private void authorizeCloudFrontGetObject(
            String bucketName,
            String key,
            String principalType,
            String principalValue,
            Map<String, String> context,
            String canonicalUserId) {
        if (!enforceAuth) {
            return;
        }
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() ->
                        new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        String resourceArn = S3PublicAccessEvaluator.objectArn(bucketPartition(bucketName), bucketName, key);
        S3PublicAccessEvaluator.PublicAccessDecision decision =
                S3PublicAccessEvaluator.principalPolicyDecision(
                        objectMapper,
                        bucket.getPolicy(),
                        principalType,
                        principalValue,
                        "s3:GetObject",
                        resourceArn,
                        context);
        if (decision == S3PublicAccessEvaluator.PublicAccessDecision.DENY) {
            throw new AwsException("AccessDenied", "Access Denied", 403);
        }
        if (decision == S3PublicAccessEvaluator.PublicAccessDecision.ALLOW
                || canonicalUserObjectAclAllowsRead(bucketName, key, canonicalUserId)) {
            return;
        }
        throw new AwsException("AccessDenied", "Access Denied", 403);
    }

    void authorizeBucketRead(String bucketName, String action, RequestAuthorization authorization) {
        String bucketArn = S3PublicAccessEvaluator.bucketArn(bucketPartition(bucketName), bucketName);
        authorizeS3Read(bucketName, null, null, action, bucketArn, authorization);
    }

    /**
     * CreateBucket is never anonymous on AWS: there is no bucket policy to consult yet, so an
     * unsigned request is denied outright and a signed one only needs a known access key.
     */
    void authorizeCreateBucket(RequestAuthorization authorization) {
        if (!enforceAuth) {
            return;
        }
        authorizeSignedRequest(authorization);
        if (isUnsignedRequest(authorization)) {
            throw new AwsException("AccessDenied", "Access Denied", 403);
        }
    }

    void authorizeBucketWrite(String bucketName, String action, RequestAuthorization authorization) {
        if (!enforceAuth) {
            return;
        }

        authorizeSignedRequest(authorization);
        RequestAuthorization requestAuthorization = authorization != null
                ? authorization
                : RequestAuthorization.unsigned();
        if (requestAuthorization.signed()) {
            String bucketArn = S3PublicAccessEvaluator.bucketArn(bucketPartition(bucketName), bucketName);
            authorizeSignedBucketPolicy(bucketName, null, action, bucketArn, requestAuthorization);
            return;
        }

        Bucket bucket = resolveBucket(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        // AWS lets only an identity in the bucket owner's account manage the bucket policy; the
        // policy itself can never grant PutBucketPolicy or DeleteBucketPolicy to an anonymous caller.
        if (isBucketPolicyAction(action)) {
            throw accessDeniedException(bucketName, null);
        }

        String bucketArn = S3PublicAccessEvaluator.bucketArn(bucketPartition(bucketName), bucketName);
        S3PublicAccessEvaluator.PublicAccessDecision policyDecision =
                S3PublicAccessEvaluator.publicPolicyDecision(objectMapper, bucket.getPolicy(), action, bucketArn);
        if (policyDecision == S3PublicAccessEvaluator.PublicAccessDecision.DENY) {
            throw accessDeniedException(bucketName, null);
        }
        if (policyDecision == S3PublicAccessEvaluator.PublicAccessDecision.ALLOW) {
            return;
        }

        throw accessDeniedException(bucketName, null);
    }

    void authorizeObjectRead(String bucketName, String key, String versionId, String action, RequestAuthorization authorization) {
        String objectArn = S3PublicAccessEvaluator.objectArn(bucketPartition(bucketName), bucketName, key);
        authorizeS3Read(bucketName, key, versionId, action, objectArn, authorization);
    }

    void authorizePutObject(String bucketName, String key, RequestAuthorization authorization) {
        authorizeObjectWrite(bucketName, key, "s3:PutObject", authorization);
    }

    void authorizeDeleteObject(String bucketName, String key, String versionId, RequestAuthorization authorization) {
        String action = versionId != null ? "s3:DeleteObjectVersion" : "s3:DeleteObject";
        authorizeObjectWrite(bucketName, key, action, authorization);
    }

    void authorizeObjectWrite(String bucketName, String key, String action, RequestAuthorization authorization) {
        if (!enforceAuth) {
            return;
        }

        authorizeSignedRequest(authorization);
        RequestAuthorization requestAuthorization = authorization != null
                ? authorization
                : RequestAuthorization.unsigned();
        if (requestAuthorization.signed()) {
            String objectArn = S3PublicAccessEvaluator.objectArn(bucketPartition(bucketName), bucketName, key);
            authorizeSignedBucketPolicy(bucketName, key, action, objectArn, requestAuthorization);
            return;
        }

        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        Bucket bucket = ownedBucket.value();
        S3BlockPublicAccessSettings blockPublicAccess =
                blockPublicAccessFor(bucket, ownedBucket.account());

        String objectArn = S3PublicAccessEvaluator.objectArn(bucketPartition(bucketName), bucketName, key);
        S3PublicAccessEvaluator.PublicAccessDecision policyDecision =
                S3PublicAccessEvaluator.publicPolicyDecision(objectMapper, bucket.getPolicy(), action, objectArn);
        if (policyDecision == S3PublicAccessEvaluator.PublicAccessDecision.DENY) {
            throw accessDeniedException(bucketName, key);
        }
        if (policyDecision == S3PublicAccessEvaluator.PublicAccessDecision.ALLOW) {
            if (restrictsPublicPolicy(blockPublicAccess, bucket)) {
                LOG.debugv("RestrictPublicBuckets withheld anonymous {0} on bucket {1}", action, bucketName);
                throw accessDeniedException(bucketName, key);
            }
            return;
        }
        if (!blockPublicAccess.ignorePublicAcls() && isObjectCreationAction(action)
                && !readableObjectExists(bucketName, key) && publicBucketAclAllowsWrite(bucket)) {
            return;
        }

        throw accessDeniedException(bucketName, key);
    }

    /**
     * Checks only credential validity, not per-resource authorization. Batch callers use this to
     * fail the whole request on a bad access key, rather than the same InvalidAccessKeyId
     * surfacing as a per-resource error on every item.
     */
    void authorizeSignedRequest(RequestAuthorization authorization) {
        if (!enforceAuth) {
            return;
        }
        RequestAuthorization requestAuthorization = authorization != null
                ? authorization
                : RequestAuthorization.unsigned();
        if (requestAuthorization.signed() && !isKnownAccessKey(requestAuthorization)) {
            throw new AwsException("InvalidAccessKeyId",
                    "The AWS Access Key Id you provided does not exist in our records.", 403);
        }
    }

    private boolean publicBucketAclAllowsWrite(Bucket bucket) {
        return Optional.ofNullable(bucket.getAcl())
                .map(S3AclPublicAccessEvaluator::aclAllowsPublicWrite)
                .orElse(false);
    }

    public boolean isAuthEnforced() {
        return enforceAuth;
    }

    private void authorizeS3Read(String bucketName, String key, String versionId, String action, String resourceArn, RequestAuthorization authorization) {
        if (!enforceAuth) {
            return;
        }

        RequestAuthorization requestAuthorization = authorization != null
                ? authorization
                : RequestAuthorization.unsigned();

        if (requestAuthorization.signed()) {
            authorizeSignedBucketPolicy(bucketName, key, action, resourceArn, requestAuthorization);
            return;
        }

        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        Bucket bucket = ownedBucket.value();
        S3BlockPublicAccessSettings blockPublicAccess =
                blockPublicAccessFor(bucket, ownedBucket.account());

        S3PublicAccessEvaluator.PublicAccessDecision policyDecision =
                S3PublicAccessEvaluator.publicPolicyDecision(objectMapper, bucket.getPolicy(), action, resourceArn);
        if (policyDecision == S3PublicAccessEvaluator.PublicAccessDecision.DENY) {
            throw accessDeniedException(bucketName, key);
        }
        if (policyDecision == S3PublicAccessEvaluator.PublicAccessDecision.ALLOW) {
            if (restrictsPublicPolicy(blockPublicAccess, bucket)) {
                LOG.debugv("RestrictPublicBuckets withheld anonymous {0} on bucket {1}", action, bucketName);
                throw accessDeniedException(bucketName, key);
            }
            return;
        }
        if (!blockPublicAccess.ignorePublicAcls()) {
            if (key != null && isObjectDataReadAction(action) && publicObjectAclAllowsRead(bucketName, key, versionId)) {
                return;
            }
            if (key == null && "s3:ListBucket".equals(action) && publicBucketAclAllowsRead(bucket)) {
                return;
            }
        }

        throw accessDeniedException(bucketName, key);
    }

    /**
     * {@code RestrictPublicBuckets} confines a bucket whose policy is public to the owner
     * account, so the public statement stops granting anything to an anonymous or cross-account
     * caller. One public statement makes the whole policy public, which is why the status is
     * evaluated over the policy rather than over the statement that happened to match.
     */
    private boolean restrictsPublicPolicy(S3BlockPublicAccessSettings settings, Bucket bucket) {
        return settings.restrictPublicBuckets()
                && S3PublicAccessEvaluator.policyIsPublic(objectMapper, bucket.getPolicy());
    }

    private void authorizeSignedBucketPolicy(
            String bucketName,
            String key,
            String action,
            String resourceArn,
            RequestAuthorization authorization) {
        if (!isKnownAccessKey(authorization)) {
            throw new AwsException("InvalidAccessKeyId",
                    "The AWS Access Key Id you provided does not exist in our records.", 403);
        }

        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucketEntry = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException(
                        "NoSuchBucket", "The specified bucket does not exist.", 404));
        Bucket bucket = ownedBucketEntry.value();
        String bucketOwner = ownedBucketEntry.account();

        CallerArns caller = resolveCaller(authorization.accessKeyId()).orElse(null);
        String principalArn = caller == null ? null : caller.callerArn();
        boolean sameAccountAsOwner = isSameAccountAsBucketOwner(authorization.accessKeyId(), principalArn, bucketOwner);

        if (isBucketPolicyAction(action) && !sameAccountAsOwner) {
            throw accessDeniedException(bucketName, key);
        }

        // RestrictPublicBuckets blocks cross-account access derived from a public bucket policy,
        // including the non-public delegation a statement naming a specific account would grant.
        // AWS exempts AWS service principals; this path is only ever reached by an IAM principal,
        // so there is nothing to exempt here.
        if (!sameAccountAsOwner && restrictsPublicPolicy(blockPublicAccessFor(bucket, bucketOwner), bucket)) {
            LOG.debugv("RestrictPublicBuckets withheld cross-account {0} on bucket {1}", action, bucketName);
            throw accessDeniedException(bucketName, key);
        }

        String policy = bucket.getPolicy();
        if (policy == null || policy.isBlank()) {
            if (sameAccountAsOwner) {
                return;
            }
            throw accessDeniedException(bucketName, key);
        }

        ResourcePolicyDecision decision = policyEvaluator.evaluateResourcePolicyFor(
                List.of(policy),
                RequestPrincipal.caller(caller),
                action,
                resourceArn,
                bucketPolicyContext(caller));

        if (decision == ResourcePolicyDecision.EXPLICIT_DENY) {
            throw accessDeniedException(bucketName, key);
        }
        if (decision == ResourcePolicyDecision.ALLOW || decision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER) {
            return;
        }
        if (sameAccountAsOwner) {
            return;
        }
        throw accessDeniedException(bucketName, key);
    }

    /**
     * The signing caller's two ARNs: the caller ARN a bucket policy's {@code Principal} is matched
     * against, and the request's {@code aws:PrincipalArn}, which for a role session is the role's ARN,
     * path included, rather than the session's. A key IAM does not know is treated as its account's
     * root, which is both.
     */
    private Optional<CallerArns> resolveCaller(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return Optional.empty();
        }
        if (iamService != null) {
            Optional<CallerArns> callerArns = iamService.resolveCallerArns(accessKeyId);
            if (callerArns.isPresent()) {
                return callerArns;
            }
        }
        String account = accessKeyId.matches("\\d{12}") ? accessKeyId : ownerId();
        String rootArn = regionResolver.buildGlobalArn("iam", account, "root");
        return Optional.of(new CallerArns(rootArn, rootArn));
    }

    /**
     * The request context a bucket policy is evaluated with here: the caller is an IAM principal,
     * never a service, and for a role session {@code aws:PrincipalArn} is the role's ARN.
     */
    private static Map<String, List<String>> bucketPolicyContext(CallerArns caller) {
        return caller != null
                ? Map.of("aws:PrincipalArn", List.of(caller.principalArn()),
                        "aws:PrincipalIsAWSService", List.of("false"))
                : Map.of("aws:PrincipalIsAWSService", List.of("false"));
    }

    private boolean isSameAccountAsBucketOwner(String accessKeyId, String principalArn, String bucketOwnerAccount) {
        String owner = bucketOwnerAccount != null ? bucketOwnerAccount : ownerId();
        return (LEGACY_ACCESS_KEY_ID.equals(accessKeyId) && owner.equals(ownerId()))
                || (accessKeyId != null && accessKeyId.equals(owner))
                || (principalArn != null && owner.equals(extractAccountId(principalArn)));
    }

    private static String extractAccountId(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String account = AwsArnUtils.parse(arn).accountId();
        return account.matches("\\d{12}") ? account : null;
    }

    private static AwsException accessDeniedException(String bucketName, String key) {
        String resourcePath = key != null ? "/" + bucketName + "/" + key : "/" + bucketName;
        return new AwsException("AccessDenied", "Access Denied", 403, Map.of("Resource", resourcePath));
    }

    SignedPrincipalResourcePolicyEvaluation signedPrincipalResourcePolicyDecision(
            String bucketName,
            String action,
            String resourceArn,
            RequestAuthorization authorization) {
        if (authorization == null || !authorization.signed()
                || LEGACY_ACCESS_KEY_ID.equals(authorization.accessKeyId()) || iamService == null) {
            return new SignedPrincipalResourcePolicyEvaluation(ResourcePolicyDecision.NEUTRAL, null);
        }

        Optional<CallerArns> caller = resolveCaller(authorization.accessKeyId());
        if (caller.isEmpty()) {
            return new SignedPrincipalResourcePolicyEvaluation(ResourcePolicyDecision.NEUTRAL, null);
        }

        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException(
                        "NoSuchBucket", "The specified bucket does not exist.", 404));
        String policy = ownedBucket.value().getPolicy();
        ResourcePolicyDecision decision = policy == null || policy.isBlank()
                ? ResourcePolicyDecision.NEUTRAL
                : policyEvaluator.evaluateResourcePolicyFor(
                        List.of(policy),
                        RequestPrincipal.caller(caller.get()),
                        action,
                        resourceArn,
                        bucketPolicyContext(caller.get()));
        return new SignedPrincipalResourcePolicyEvaluation(decision, ownedBucket.account());
    }

    private boolean readableObjectExists(String bucketName, String key) {
        ensureBucketExists(bucketName);
        return resolveObject(objectKey(bucketName, key))
                .filter(object -> !object.isDeleteMarker())
                .isPresent();
    }

    private void authorizeMissingObjectRead(String bucketName, RequestAuthorization authorization) {
        authorizeListBucket(bucketName, authorization);
    }

    private static boolean isObjectDataReadAction(String action) {
        return "s3:GetObject".equals(action) || "s3:GetObjectVersion".equals(action);
    }

    /**
     * A bucket ACL WRITE grant to a non-owner only authorizes creating a new object; per AWS's
     * ACL documentation it "denies non-owners the ability to overwrite or delete existing
     * objects." Floci has no per-object ownership model, so this is approximated as: only
     * PutObject on a key that doesn't exist yet counts as creation.
     */
    private static boolean isObjectCreationAction(String action) {
        return "s3:PutObject".equals(action);
    }

    private static boolean isBucketPolicyAction(String action) {
        return "s3:PutBucketPolicy".equals(action) || "s3:DeleteBucketPolicy".equals(action);
    }

    private static boolean isUnsignedRequest(RequestAuthorization authorization) {
        return authorization == null || !authorization.signed();
    }

    private boolean isKnownAccessKey(RequestAuthorization authorization) {
        String accessKeyId = authorization != null ? authorization.accessKeyId() : null;
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return false;
        }
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return true;
        }
        return iamService != null
                && iamService.findSecretKey(accessKeyId, authorization.sessionToken()).isPresent();
    }

    private boolean publicBucketAclAllowsRead(Bucket bucket) {
        return Optional.ofNullable(bucket.getAcl())
                .map(S3AclPublicAccessEvaluator::aclAllowsPublicRead)
                .orElse(false);
    }

    private boolean publicObjectAclAllowsRead(String bucketName, String key, String versionId) {
        String storeKey = versionId != null ? versionedKey(bucketName, key, versionId) : objectKey(bucketName, key);
        return objectStore.get(storeKey)
                .filter(object -> !object.isDeleteMarker())
                .map(S3Object::getAcl)
                .map(S3AclPublicAccessEvaluator::aclAllowsPublicRead)
                .orElse(false);
    }

    private boolean canonicalUserObjectAclAllowsRead(
            String bucketName, String key, String canonicalUserId) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            return false;
        }
        return objectStore.get(objectKey(bucketName, key))
                .filter(object -> !object.isDeleteMarker())
                .map(S3Object::getAcl)
                .map(acl -> {
                    try {
                        return S3AclPolicy.parse(acl).grants().stream()
                                .anyMatch(grant ->
                                        grant.allowsCanonicalUserRead(canonicalUserId));
                    } catch (S3AclPolicy.AclParseException e) {
                        LOG.debugv(e, "Failed to parse S3 ACL for CloudFront OAI access");
                        return false;
                    }
                })
                .orElse(false);
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
        if ("null".equals(versionId)) {
            String bucketOwnerAccount = resolveBucketEntry(bucketName)
                    .orElseThrow(() -> new AwsException("NoSuchBucket",
                            "The specified bucket does not exist.", 404))
                    .account();
            S3Object obj = getObjectMetadata(bucketName, key, versionId);
            obj.setData(readFile(bucketOwnerAccount, bucketName, key));
            return obj;
        }
        if (versionId != null) {
            // An explicit version's file is immutable once written (see storeObjectInternal) and
            // never reused by a later PUT, so this pairing can never race a concurrent overwrite.
            String bucketOwnerAccount = resolveBucketEntry(bucketName)
                    .orElseThrow(() -> new AwsException("NoSuchBucket",
                            "The specified bucket does not exist.", 404))
                    .account();
            S3Object obj = getObjectMetadata(bucketName, key, versionId);
            obj.setData(readVersionedFile(bucketOwnerAccount, bucketName, key, versionId));
            return obj;
        }
        return getLatestObject(bucketName, key);
    }

    /**
     * Reads the "latest" object without locking against a concurrent overwrite for the
     * (potentially slow) metadata read or file read, while still never pairing one write's
     * metadata with another write's bytes. storeObjectInternal stamps every write with a fresh,
     * random dataGeneration and, critically, always finishes writing the file for that generation
     * before publishing metadata that names it (see the write-ordering comment in
     * storeObjectInternal) - so a generation can only become visible in objectStore once its
     * bytes are already on disk. This is a seqlock-style optimistic read: read the metadata, read
     * the file, then re-read the metadata's dataGeneration under the bucket monitor and compare it
     * to the first read. That re-read can only happen either fully before or fully after any
     * single write's monitor-held publish, so an unchanged token proves no write completed while
     * this read was in flight; combined with the write-before-publish ordering, that also proves
     * the file read - which happened after the first metadata read, and therefore after that
     * generation's file was already written - cannot have observed an earlier, stale generation's
     * bytes. A change (or a concurrent delete) means an overwrite landed mid-read, so the whole
     * read is retried. Objects written before this scheme existed have no dataGeneration recorded;
     * since nothing can concurrently overwrite a key without immediately stamping one, a null
     * token is only ever observed when untouched, and untouched means nothing to race against.
     */
    private S3Object getLatestObject(String bucketName, String key) {
        Snapshot<byte[]> snapshot = readLatestSnapshot(bucketName, key,
                account -> readFile(account, bucketName, key), data -> { });
        snapshot.object().setData(snapshot.body());
        return snapshot.object();
    }

    private record Snapshot<B>(S3Object object, B body) { }

    /**
     * The optimistic read described on {@link #getLatestObject}, for any body type: {@code readBody}
     * gets the bucket owner's account and {@code discard} releases a body read during a race.
     */
    private <B> Snapshot<B> readLatestSnapshot(String bucketName, String key,
                                               Function<String, B> readBody, Consumer<B> discard) {
        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        Bucket bucket = ownedBucket.value();
        String bucketOwnerAccount = ownedBucket.account();
        String storeKey = objectKey(bucketName, key);
        // A genuine race only ever needs a retry or two; this bound exists so a resolution bug
        // (the recheck disagreeing with getObjectMetadata about where this key lives) fails loudly
        // with a clear error instead of spinning forever re-reading the file and exhausting the heap.
        for (int attempt = 0; attempt < 10_000; attempt++) {
            S3Object obj = getObjectMetadata(bucketName, key, null);
            B body = readBody.apply(bucketOwnerAccount);
            synchronized (bucket) {
                S3Object current = resolveObjectForAccount(bucketOwnerAccount, storeKey).orElse(null);
                if (current != null && !current.isDeleteMarker()
                        && Objects.equals(current.getDataGeneration(), obj.getDataGeneration())) {
                    return new Snapshot<>(obj, body);
                }
            }
            discard.accept(body);
            // A concurrent overwrite (or delete) landed mid-read; retry against the new state.
        }
        throw new IllegalStateException(
                "getObject retry limit exceeded for " + bucketName + "/" + key
                        + " - the object is either under sustained concurrent overwrite or the "
                        + "metadata/data resolution paths disagree about where this key lives");
    }

    /** An object's metadata and an open stream over the body that matches it. */
    public record ObjectRead(S3Object object, InputStream body) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    /**
     * Like {@link #getObject}, but the body is an open stream instead of a byte array, so serving
     * a large object does not load it into the heap. Every write replaces the object file with an
     * atomic move, so a file opened for the matching generation keeps returning that generation's
     * bytes even if the key is overwritten while the stream is read. The caller closes the result.
     */
    public ObjectRead openObject(String bucketName, String key, String versionId) {
        if (inMemory) {
            S3Object obj = getObject(bucketName, key, versionId);
            return new ObjectRead(obj, new ByteArrayInputStream(obj.getData()));
        }
        if (versionId == null) {
            Snapshot<FileChannel> snapshot = readLatestSnapshot(bucketName, key,
                    account -> openForRead(resolveObjectPathForRead(account, bucketName, key)),
                    S3Service::closeQuietly);
            return objectRead(snapshot.object(), snapshot.body());
        }
        String bucketOwnerAccount = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404))
                .account();
        S3Object obj = getObjectMetadata(bucketName, key, versionId);
        Path path = "null".equals(versionId)
                ? resolveObjectPathForRead(bucketOwnerAccount, bucketName, key)
                : resolveVersionedPathForRead(bucketOwnerAccount, bucketName, key, versionId);
        return objectRead(obj, openForRead(path));
    }

    private static ObjectRead objectRead(S3Object obj, FileChannel channel) {
        try {
            long fileSize = channel.size();
            if (fileSize != obj.getSize()) {
                throw new IllegalStateException("S3 object file for " + obj.getBucketName() + "/"
                        + obj.getKey() + " has " + fileSize + " bytes but metadata declares "
                        + obj.getSize() + "; serving it would corrupt the response framing");
            }
        } catch (IOException e) {
            closeQuietly(channel);
            throw new UncheckedIOException("Failed to read S3 object file size", e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
        return new ObjectRead(obj, Channels.newInputStream(channel));
    }

    private static FileChannel openForRead(Path path) {
        try {
            return FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open S3 object file", e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing was read from it; closing is best effort.
        }
    }

    public S3Object headObject(String bucketName, String key) {
        return headObject(bucketName, key, null);
    }

    public S3Object headObject(String bucketName, String key, String versionId) {
        return getObjectMetadata(bucketName, key, versionId);
    }

    /** Returns true when the (latest-version) object exists, without throwing on a miss. */
    public boolean objectExists(String bucketName, String key) {
        try {
            getObjectMetadata(bucketName, key, null);
            return true;
        } catch (AwsException e) {
            // Only a genuine miss means "does not exist"; surface any other storage error (e.g.
            // NoSuchBucket) instead of masking it as absent, which would let callers act on a wrong
            // answer (e.g. issue a website redirect that hides the real failure).
            if ("NoSuchKey".equals(e.getErrorCode()) || "NoSuchVersion".equals(e.getErrorCode())) {
                return false;
            }
            throw e;
        }
    }

    public InputStream openObjectStream(String bucketName, String key, String versionId) {
        getObjectMetadata(bucketName, key, versionId);
        String bucketOwnerAccount = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404))
                .account();
        if (inMemory) {
            byte[] data = versionId != null && !"null".equals(versionId)
                    ? memoryDataStore.get(physicalVersionedKey(bucketOwnerAccount, bucketName, key, versionId))
                    : memoryDataStore.get(physicalKey(bucketOwnerAccount, bucketName, key));
            if (data == null) {
                throw new IllegalStateException("S3 object data is missing for " + bucketName + "/" + key);
            }
            return new ByteArrayInputStream(data);
        }
        try {
            Path path = versionId != null && !"null".equals(versionId)
                    ? resolveVersionedPathForRead(bucketOwnerAccount, bucketName, key, versionId)
                    : resolveObjectPathForRead(bucketOwnerAccount, bucketName, key);
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open S3 object stream", e);
        }
    }

    public S3Object getObjectMetadata(String bucketName, String key, String versionId) {
        return copyObject(getStoredObject(bucketName, key, "null".equals(versionId) ? null : versionId));
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
            result.setChecksum(object.getChecksum() == null ? null : object.getChecksum().forObjectAttributes());
        }
        if (attributes.contains(ObjectAttributeName.OBJECT_PARTS) && object.getParts() != null && !object.getParts().isEmpty()) {
            result.setObjectParts(buildObjectParts(object, maxParts, partNumberMarker));
        }

        return result;
    }

    private S3Object getStoredObject(String bucketName, String key, String versionId) {
        return getStoredObjectEntry(bucketName, key, versionId).value();
    }

    private AccountAwareStorageBackend.OwnedEntry<S3Object> getStoredObjectEntry(
            String bucketName, String key, String versionId) {
        AccountAwareStorageBackend.OwnedEntry<Bucket> ownedBucket = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));

        String storeKey = versionId != null ? versionedKey(bucketName, key, versionId) : objectKey(bucketName, key);
        S3Object object = resolveObjectForAccount(ownedBucket.account(), storeKey)
                .orElseThrow(() -> versionId != null
                        ? new AwsException("NoSuchVersion", "The specified version does not exist.", 404)
                        : new AwsException("NoSuchKey", "The specified key does not exist.", 404));
        if (object.isDeleteMarker()) {
            throw new AwsException("NoSuchKey", "The specified key does not exist.", 404);
        }
        return new AccountAwareStorageBackend.OwnedEntry<>(ownedBucket.account(), object);
    }

    // AWS lists part-level checksums only for composite objects; a full-object multipart object
    // reports its part count alone.
    private GetObjectAttributesParts buildObjectParts(S3Object object, Integer maxParts, Integer partNumberMarker) {
        List<Part> sortedParts = new ArrayList<>(copyParts(object.getParts()));
        sortedParts.sort(Comparator.comparingInt(Part::getPartNumber));
        if (object.getChecksum() == null || object.getChecksum().getChecksumType() != ChecksumType.COMPOSITE) {
            GetObjectAttributesParts countOnly = new GetObjectAttributesParts();
            countOnly.setPartsCount(sortedParts.size());
            return countOnly;
        }

        int max = (maxParts == null || maxParts <= 0) ? 1000 : maxParts;
        int marker = Math.max(partNumberMarker != null ? partNumberMarker : 0, 0);

        List<Part> visibleParts = sortedParts.stream()
                .filter(part -> part.getPartNumber() > marker)
                .toList();
        List<Part> returnedParts = visibleParts.stream().limit(max).toList();

        GetObjectAttributesParts result = new GetObjectAttributesParts();
        result.setPartChecksumsAvailable(true);
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
        return deleteObject(bucketName, key, versionId, bypassGovernance, null);
    }

    /**
     * Deletes an object, first checking an {@code If-Match} precondition when one is given.
     *
     * <p>As on S3, the precondition is evaluated against the current version of the key even
     * when {@code versionId} addresses an older one: a missing key, or a current delete marker,
     * answers {@code NoSuchKey}, and an ETag that does not match answers {@code 412}.
     */
    public S3Object deleteObject(String bucketName, String key, String versionId, boolean bypassGovernance,
                                 String ifMatch) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));

        // The bucket monitor serializes this against PutObject's annotation cleanup and the
        // annotation subresource writes, which hold the same monitor (storeObjectInternal
        // already runs under it via storeObject). The precondition is checked under it too,
        // so a concurrent overwrite cannot land between the check and the delete.
        synchronized (bucket) {
            checkDeletePrecondition(bucketName, key, ifMatch);
            return deleteObjectLocked(bucket, bucketName, key, versionId, bypassGovernance);
        }
    }

    // A current delete marker answers 404 NoSuchKey, the same as a missing key. That is deliberate:
    // the S3 conditional-deletes guide says "If the latest version of the object is a delete marker,
    // the object doesn't exist and the DeleteObject API will fail and return a 412 Precondition
    // Failed response", but real S3 (general purpose bucket, ap-southeast-2, measured 2026-09-07)
    // answered 404 NoSuchKey for If-Match: * over a current delete marker. Follow the measurement.
    private void checkDeletePrecondition(String bucketName, String key, String ifMatch) {
        if (ifMatch == null) {
            return;
        }
        S3Object current = objectStore.get(objectKey(bucketName, key)).orElse(null);
        if (current == null || current.isDeleteMarker()) {
            throw new AwsException("NoSuchKey", "The specified key does not exist.", 404);
        }
        if (!eTagMatches(ifMatch, current.getETag())) {
            throw new S3PreconditionFailedException("If-Match");
        }
    }

    private S3Object deleteObjectLocked(Bucket bucket, String bucketName, String key,
                                        String versionId, boolean bypassGovernance) {
        if (bucket.isVersioningEnabled() && versionId == null) {
            // Check lock on current latest before placing a delete marker
            objectStore.get(objectKey(bucketName, key)).ifPresent(prev -> {
                if (!prev.isDeleteMarker() && prev.getVersionId() == null) {
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
                } else {
                    // The marker replaces a pre-versioning object: no versioned entry ever
                    // existed, so its annotations become unreachable and are removed here
                    // (they are permanent, as on AWS).
                    deleteAllAnnotationsFor(annotationParentKey(bucketName, key, null));
                }
            });

            objectStore.put(versionedKey(bucketName, key, markerId), deleteMarker);
            objectStore.put(objectKey(bucketName, key), deleteMarker);
            LOG.debugv("Created delete marker: {0}/{1} v={2}", bucketName, key, markerId);
            fireNotifications(bucketName, key, "ObjectRemoved:DeleteMarkerCreated", deleteMarker);
            return deleteMarker;
        } else if ("null".equals(versionId)) {
            // A null version is stored at the plain object key until a versioned write replaces it.
            // Treat the literal request value as that null version, not as a versioned key named
            // "null". A delete marker is not the null version and must remain untouched.
            S3Object existing = objectStore.get(objectKey(bucketName, key)).orElse(null);
            if (existing == null || existing.isDeleteMarker() || existing.getVersionId() != null) {
                return null;
            }
            checkLockProtection(existing, bypassGovernance);
            objectStore.delete(objectKey(bucketName, key));
            deleteFile(bucketName, key);
            deleteAllAnnotationsFor(annotationParentKey(bucketName, key, null));
            LOG.debugv("Permanently deleted null version: {0}/{1}", bucketName, key);
            fireNotifications(bucketName, key, "ObjectRemoved:Delete", null);
            return existing;
        } else if (versionId != null) {
            // Get the specific version before permanent deletion
            S3Object toDelete = objectStore.get(versionedKey(bucketName, key, versionId)).orElse(null);
            if (toDelete != null && !toDelete.isDeleteMarker()) {
                checkLockProtection(toDelete, bypassGovernance);
            }
            // Permanently delete a specific version (metadata + file data)
            objectStore.delete(versionedKey(bucketName, key, versionId));
            deleteVersionedFile(bucketName, key, versionId);
            deleteAllAnnotationsFor(annotationParentKey(bucketName, key, versionId));
            LOG.debugv("Permanently deleted version: {0}/{1} v={2}", bucketName, key, versionId);
            // Promote the next most-recent version when the deleted one was the latest
            String latestKey = objectKey(bucketName, key);
            objectStore.get(latestKey).ifPresent(latest -> {
                if (versionId.equals(latest.getVersionId())) {
                    String vPrefix = versionedKey(bucketName, key, "");
                    List<S3Object> remaining = objectStore.scan(k -> k.startsWith(vPrefix));
                    if (remaining.isEmpty()) {
                        objectStore.delete(latestKey);
                        deleteFile(bucketName, key);
                    } else {
                        S3Object newLatest = remaining.stream()
                                .max(Comparator.comparing(S3Object::getLastModified))
                                .orElseThrow();
                        // The promoted version's body becomes current before its metadata is
                        // published, the order storeObjectInternal writes in, so a concurrent GET
                        // never pairs the promoted version with the deleted version's bytes.
                        // Delete markers have no versioned file.
                        if (!newLatest.isDeleteMarker()) {
                            promoteVersionedFile(bucketName, key, newLatest.getVersionId());
                        }
                        newLatest.setLatest(true);
                        objectStore.put(versionedKey(bucketName, key, newLatest.getVersionId()), newLatest);
                        objectStore.put(latestKey, newLatest);
                        if (newLatest.isDeleteMarker()) {
                            deleteFile(bucketName, key);
                        }
                    }
                }
            });
            if (toDelete != null) {
                fireNotifications(bucketName, key, "ObjectRemoved:Delete", toDelete);
            }
            return toDelete;
        } else {
            S3Object existing = objectStore.get(objectKey(bucketName, key)).orElse(null);
            // Check lock on the non-versioned object before delete
            if (existing != null && !existing.isDeleteMarker()) {
                checkLockProtection(existing, bypassGovernance);
            }
            // Non-versioned delete
            objectStore.delete(objectKey(bucketName, key));
            deleteFile(bucketName, key);
            deleteAllAnnotationsFor(annotationParentKey(bucketName, key, null));
            LOG.debugv("Deleted object: {0}/{1}", bucketName, key);
            fireNotifications(bucketName, key, "ObjectRemoved:Delete", null);
            return null;
        }
    }

    /**
     * Returns whether the requested object version is currently protected by an active
     * GOVERNANCE retention period. The bypass permission is only relevant for those versions;
     * an {@code x-amz-bypass-governance-retention} header on an otherwise unprotected batch
     * entry must not make that entry require {@code s3:BypassGovernanceRetention}.
     */
    public boolean isGovernanceRetentionActive(String bucketName, String key, String versionId) {
        ensureBucketExists(bucketName);
        S3Object object = (versionId != null
                ? objectStore.get(versionedKey(bucketName, key, versionId))
                : objectStore.get(objectKey(bucketName, key)))
                .orElse(null);
        return object != null
                && !object.isDeleteMarker()
                && "GOVERNANCE".equals(object.getObjectLockMode())
                && object.getRetainUntilDate() != null
                && Instant.now().isBefore(object.getRetainUntilDate());
    }

    public record ListObjectsResult(List<S3Object> objects, List<String> commonPrefixes, boolean isTruncated, String nextContinuationToken) {}

    public List<S3Object> listObjects(String bucketName, String prefix, String delimiter, int maxKeys) {
        return listObjectsWithPrefixes(bucketName, prefix, delimiter, maxKeys, null, null).objects();
    }

    public ListObjectsResult listObjectsWithPrefixes(String bucketName, String prefix, String delimiter, int maxKeys) {
        return listObjectsWithPrefixes(bucketName, prefix, delimiter, maxKeys, null, null);
    }

    public ListObjectsResult listObjectsWithPrefixes(String bucketName, String prefix, String delimiter, int maxKeys,
                                                     String continuationToken, String startAfter) {
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
            Set<String> prefixSet = new LinkedHashSet<>();
            List<S3Object> directObjects = new ArrayList<>();

            for (S3Object obj : allObjects) {
                String remainder = obj.getKey().substring(prefix != null ? prefix.length() : 0);
                int delimIdx = remainder.indexOf(delimiter);
                if (delimIdx >= 0) {
                    String cp = (prefix != null ? prefix : "") + remainder.substring(0, delimIdx + delimiter.length());
                    prefixSet.add(cp);
                } else {
                    directObjects.add(obj);
                }
            }

            allObjects = directObjects;
            commonPrefixes = new ArrayList<>(prefixSet);
            Collections.sort(commonPrefixes);
        }

        allObjects.sort(Comparator.comparing(S3Object::getKey));

        // Apply continuation-token / start-after filter.
        // continuation-token takes precedence; it encodes the last key seen on a previous page.
        String filterKey = continuationToken != null ? continuationToken : startAfter;
        if (filterKey != null) {
            final String fk = filterKey;
            allObjects = allObjects.stream()
                    .filter(o -> o.getKey().compareTo(fk) > 0)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            commonPrefixes = commonPrefixes.stream()
                    .filter(cp -> cp.compareTo(fk) > 0)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        // S3 counts both direct objects and common prefixes.
        // Each common prefix group (e.g. "docs/") uses one entry regardless of
        // how many keys it contains. Merge both sorted lists lexicographically
        // and stop at maxKeys to try to match S3 ListObjectsV2 behavior.
        // see https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html
        boolean isTruncated = false;
        String nextContinuationToken = null;
        if (maxKeys >= 0) {
            List<S3Object> limitedObjects = new ArrayList<>();
            List<String> limitedPrefixes = new ArrayList<>();
            int count = 0;
            int directObjectCount = 0;
            int commonPrefixCount = 0;
            String lastEmittedKey = null;
            while (count < maxKeys && (directObjectCount < allObjects.size() || commonPrefixCount < commonPrefixes.size())) {
                String objectKey = directObjectCount < allObjects.size() ? allObjects.get(directObjectCount).getKey() : null;
                String prefixKey = commonPrefixCount < commonPrefixes.size() ? commonPrefixes.get(commonPrefixCount) : null;
                if (objectKey != null && (prefixKey == null || objectKey.compareTo(prefixKey) <= 0)) {
                    limitedObjects.add(allObjects.get(directObjectCount++));
                    lastEmittedKey = objectKey;
                } else {
                    limitedPrefixes.add(commonPrefixes.get(commonPrefixCount++));
                    lastEmittedKey = prefixKey;
                }
                count++;
            }
            // max-keys=0 is a valid request for an empty page: AWS answers it with IsTruncated=false
            // and no continuation token even when the bucket has more objects.
            isTruncated = maxKeys > 0
                    && (directObjectCount < allObjects.size() || commonPrefixCount < commonPrefixes.size());
            if (isTruncated) {
                nextContinuationToken = lastEmittedKey;
            }
            allObjects = limitedObjects;
            commonPrefixes = limitedPrefixes;
        }

        return new ListObjectsResult(allObjects, commonPrefixes, isTruncated, nextContinuationToken);
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey) {
        return copyObject(sourceBucket, sourceKey, destBucket, destKey, new CopyObjectOptions());
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey, String versionId) {
        return copyObject(sourceBucket, sourceKey, destBucket, destKey, null, new CopyObjectOptions());
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey,
                               String metadataDirective, Map<String, String> replacementMetadata,
                               String storageClass, String contentType) {
        return copyObject(sourceBucket, sourceKey, destBucket, destKey,
                new CopyObjectOptions()
                        .withMetadataDirective(metadataDirective)
                        .withReplacementMetadata(replacementMetadata)
                        .withStorageClass(storageClass)
                        .withContentType(contentType));
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey,
                               String metadataDirective, Map<String, String> replacementMetadata,
                               String storageClass, String contentType, String contentEncoding,
                               String contentDisposition, String cacheControl, String serverSideEncryption, String acl) {
        return copyObject(sourceBucket, sourceKey, destBucket, destKey,
                new CopyObjectOptions()
                        .withMetadataDirective(metadataDirective)
                        .withReplacementMetadata(replacementMetadata)
                        .withStorageClass(storageClass)
                        .withContentType(contentType)
                        .withContentEncoding(contentEncoding)
                        .withContentDisposition(contentDisposition)
                        .withCacheControl(cacheControl)
                        .withServerSideEncryption(serverSideEncryption)
                        .withAcl(acl));
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey, String versionId,
                               String metadataDirective, Map<String, String> replacementMetadata,
                               String storageClass, String contentType, String contentEncoding,
                               String contentDisposition, String cacheControl, String serverSideEncryption, String acl) {
        return copyObject(sourceBucket, sourceKey, destBucket, destKey, versionId,
                new CopyObjectOptions()
                        .withMetadataDirective(metadataDirective)
                        .withReplacementMetadata(replacementMetadata)
                        .withStorageClass(storageClass)
                        .withContentType(contentType)
                        .withContentEncoding(contentEncoding)
                        .withContentDisposition(contentDisposition)
                        .withCacheControl(cacheControl)
                        .withServerSideEncryption(serverSideEncryption)
                        .withAcl(acl));
    }
    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey, String versionId, CopyObjectOptions options)
    {
        CopyObjectOptions effectiveOptions = options != null ? options : new CopyObjectOptions();
        return copyPinnedSource(sourceBucket, sourceKey, destBucket, destKey,
                pinCopySource(sourceBucket, sourceKey, versionId), effectiveOptions);
    }

    public S3Object copyObject(String sourceBucket, String sourceKey,
                               String destBucket, String destKey, CopyObjectOptions options) {
        CopyObjectOptions effectiveOptions = options != null ? options : new CopyObjectOptions();
        return copyPinnedSource(sourceBucket, sourceKey, destBucket, destKey,
                pinCopySource(sourceBucket, sourceKey, null), effectiveOptions);
    }

    private S3Object copyPinnedSource(String sourceBucket, String sourceKey, String destBucket, String destKey,
                                      CopySource source, CopyObjectOptions effectiveOptions) {
        try {
            checkCopySourcePreconditions(source.object(), effectiveOptions.getCopySourceConditions());
            validateSseCustomerAccess(source.object(),
                    effectiveOptions.getCopySourceSseCustomerAlgorithm(),
                    effectiveOptions.getCopySourceSseCustomerKey(),
                    effectiveOptions.getCopySourceSseCustomerKeyMd5());
            requireCopyableSize(source.object());
            return copyS3Object(sourceBucket, sourceKey, destBucket, destKey, source, effectiveOptions);
        } finally {
            // A stored copy moved its pinned file into place, so this only removes one a failed
            // copy left behind.
            if (source.pinnedFile() != null) {
                deleteQuietly(source.pinnedFile(), "pinned copy source that was not stored");
            }
        }
    }

    // S3 copies at most 5 GiB in one CopyObject; a larger object is copied with UploadPartCopy.
    static final long MAX_COPY_OBJECT_SOURCE_SIZE = 5L * 1024 * 1024 * 1024;

    static void requireCopyableSize(S3Object source) {
        if (source.getSize() > MAX_COPY_OBJECT_SOURCE_SIZE) {
            throw new AwsException("InvalidRequest",
                    "The specified copy source is larger than the maximum allowable size for a copy source: "
                            + MAX_COPY_OBJECT_SOURCE_SIZE, 400);
        }
    }

    /**
     * The source of a copy, held for the length of the copy: its metadata and, in disk modes, a
     * temporary hard link to the file generation that metadata describes. The link keeps those
     * bytes even if the source is overwritten meanwhile, since an object file is only ever replaced
     * by a rename, and the copy can store the file without reading it into memory. In memory mode
     * the bytes come with the object, as before.
     */
    private record CopySource(S3Object object, Path pinnedFile) { }

    /** A copy's body, with the ETag and checksum to store it under (null to compute them from bytes). */
    private record CopyBody(ObjectBody body, String eTag, S3Checksum checksum) { }

    private static final Pattern SINGLE_PART_ETAG = Pattern.compile("[0-9a-f]{32}");

    private CopySource pinCopySource(String bucketName, String key, String versionId) {
        // The size limit is checked on the metadata before anything is pinned: without hard links,
        // pinning copies the file, and a source too large to copy must not be duplicated to refuse it.
        requireCopyableSize(getObjectMetadata(bucketName, key, versionId));
        if (inMemory) {
            return new CopySource(getObject(bucketName, key, versionId), null);
        }
        if (versionId == null) {
            Snapshot<Path> snapshot = readLatestSnapshot(bucketName, key,
                    account -> pinFile(resolveObjectPathForRead(account, bucketName, key)),
                    pinned -> deleteQuietly(pinned, "pinned copy source of a read that raced an overwrite"));
            return checkedCopySource(snapshot.object(), snapshot.body());
        }
        String bucketOwnerAccount = resolveBucketEntry(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404))
                .account();
        S3Object obj = getObjectMetadata(bucketName, key, versionId);
        Path path = "null".equals(versionId)
                ? resolveObjectPathForRead(bucketOwnerAccount, bucketName, key)
                : resolveVersionedPathForRead(bucketOwnerAccount, bucketName, key, versionId);
        return checkedCopySource(obj, pinFile(path));
    }

    private CopySource checkedCopySource(S3Object obj, Path pinned) {
        long fileSize;
        try {
            fileSize = Files.size(pinned);
        } catch (IOException e) {
            deleteQuietly(pinned, "pinned copy source whose size could not be read");
            throw new UncheckedIOException("Failed to read the copy source", e);
        }
        if (fileSize != obj.getSize()) {
            deleteQuietly(pinned, "pinned copy source whose size does not match its metadata");
            throw new IllegalStateException("S3 object file for " + obj.getBucketName() + "/"
                    + obj.getKey() + " has " + fileSize + " bytes but metadata declares " + obj.getSize());
        }
        return new CopySource(obj, pinned);
    }

    /**
     * A temporary hard link to {@code file} beside it, or a copy where the filesystem has no hard
     * links. A failed pin removes whatever it created, since the caller never gets its path.
     */
    private Path pinFile(Path file) {
        Path pinned = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try {
                Files.createLink(pinned, file);
            } catch (UnsupportedOperationException | FileSystemException e) {
                LOG.debugv(e, "No hard link for {0}, copying it to pin a copy source", file);
                Files.copy(file, pinned);
            }
            return pinned;
        } catch (IOException e) {
            deleteQuietly(pinned, "partly pinned copy source");
            throw new UncheckedIOException("Failed to pin the copy source", e);
        }
    }

    /**
     * The copy's body, ETag and checksum. In disk modes the pinned file is the body, and it is read,
     * once, only for what cannot carry over from the source: a single-part source's ETag is the MD5
     * of its bytes and stays, while a multipart one's has to be computed; the source's checksum
     * stays unless a new or composite one has to become a full-object checksum.
     */
    private CopyBody copyBody(CopySource source, S3Checksum effectiveChecksum, ChecksumAlgorithm copyChecksumAlgorithm) {
        S3Object object = source.object();
        if (source.pinnedFile() == null) {
            return new CopyBody(new BytesBody(object.getData()), null, effectiveChecksum);
        }
        boolean needsETag = !SINGLE_PART_ETAG.matcher(stripSurroundingQuotes(object.getETag())).matches();
        S3Checksum.Calculator calculator = effectiveChecksum == null ? S3Checksum.calculator(copyChecksumAlgorithm) : null;
        String eTag = object.getETag();
        if (needsETag || calculator != null) {
            String md5ETag = hashFile(source.pinnedFile(), needsETag, calculator);
            if (needsETag) {
                eTag = md5ETag;
            }
        }
        return new CopyBody(new AssembledBody(source.pinnedFile(), object.getSize()), eTag,
                calculator != null ? calculator.fullObject() : effectiveChecksum);
    }

    /** Reads {@code file} once, feeding {@code checksum} if given; returns its MD5 ETag when {@code md5}. */
    private static String hashFile(Path file, boolean md5, S3Checksum.Calculator checksum) {
        try {
            MessageDigest digest = md5 ? MessageDigest.getInstance("MD5") : null;
            byte[] buffer = new byte[1 << 20];
            try (InputStream in = Files.newInputStream(file)) {
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    if (digest != null) {
                        digest.update(buffer, 0, read);
                    }
                    if (checksum != null) {
                        checksum.update(buffer, 0, read);
                    }
                }
            }
            return digest == null ? null : "\"" + bytesToHex(digest.digest()) + "\"";
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the copy source", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
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

    public record ListVersionsResult(List<S3Object> versions, List<String> commonPrefixes, boolean isTruncated,
                                     String nextKeyMarker, String nextVersionIdMarker) {}

    public ListVersionsResult listObjectVersions(String bucketName, String prefix, int maxKeys, String keyMarker) {
        return listObjectVersions(bucketName, prefix, null, maxKeys, keyMarker, null);
    }

    /**
     * Lists every version and delete marker under {@code prefix}, optionally grouped by {@code delimiter}.
     *
     * <p>{@code maxKeys} bounds the number of entries in the response, where each {@code Version}, each
     * {@code DeleteMarker} and each {@code CommonPrefixes} group counts as one entry, exactly as AWS counts
     * them. A page may therefore end part-way through the versions of a single key, which is why a truncated
     * response carries both {@code NextKeyMarker} and {@code NextVersionIdMarker}: the pair identifies the
     * last entry returned, and the next request resumes at the entry immediately after it.
     *
     * @param versionIdMarker version id of the last entry of the previous page; only meaningful together with
     *                        {@code keyMarker}, and ignored when the key it names holds no such version
     */
    public ListVersionsResult listObjectVersions(String bucketName, String prefix, String delimiter, int maxKeys,
                                                 String keyMarker, String versionIdMarker) {
        ensureBucketExists(bucketName);

        String versionPrefix = bucketName + "/";
        String fullPrefix = prefix != null ? versionPrefix + prefix : versionPrefix;

        // Scan for versioned entries (contain #v#)
        List<S3Object> versions = new ArrayList<>(objectStore.scan(key ->
                key.startsWith(fullPrefix) && key.contains("#v#")));

        // Also include non-versioned objects (no #v# in storage key, versionId == null).
        // These are objects uploaded when versioning was disabled or before versioning was enabled.
        // Versioned latest-pointer entries (also stored at the plain key) are excluded because
        // they have a non-null versionId; their #v# entry is already captured above.
        objectStore.scan(key -> key.startsWith(fullPrefix) && !key.contains("#v#"))
                .stream()
                .filter(obj -> obj.getVersionId() == null)
                .forEach(versions::add);

        List<String> commonPrefixes = List.of();
        if (delimiter != null && !delimiter.isEmpty()) {
            Set<String> prefixSet = new LinkedHashSet<>();
            List<S3Object> directVersions = new ArrayList<>();

            for (S3Object obj : versions) {
                String remainder = obj.getKey().substring(prefix != null ? prefix.length() : 0);
                int delimIdx = remainder.indexOf(delimiter);
                if (delimIdx >= 0) {
                    String cp = (prefix != null ? prefix : "") + remainder.substring(0, delimIdx + delimiter.length());
                    prefixSet.add(cp);
                } else {
                    directVersions.add(obj);
                }
            }

            versions = directVersions;
            commonPrefixes = new ArrayList<>(prefixSet);
            Collections.sort(commonPrefixes);
        }

        // Sort by key, then by lastModified descending
        versions.sort((a, b) -> {
            int keyCompare = a.getKey().compareTo(b.getKey());
            if (keyCompare != 0) return keyCompare;
            return b.getLastModified().compareTo(a.getLastModified());
        });

        // Apply the marker filter. Without a version-id-marker the marker is an exclusive lower bound on the
        // key; with one, the previous page stopped inside keyMarker, so the versions of that key that follow
        // the named version are still owed to the caller.
        if (keyMarker != null && !keyMarker.isEmpty()) {
            final String km = keyMarker;
            commonPrefixes = commonPrefixes.stream()
                    .filter(cp -> cp.compareTo(km) > 0)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            if (versionIdMarker != null && !versionIdMarker.isEmpty()) {
                List<S3Object> remaining = new ArrayList<>();
                boolean afterMarker = false;
                for (S3Object v : versions) {
                    int keyCompare = v.getKey().compareTo(km);
                    if (keyCompare < 0) {
                        continue;
                    }
                    if (keyCompare > 0 || afterMarker) {
                        remaining.add(v);
                    } else if (versionIdMarker.equals(reportedVersionId(v))) {
                        afterMarker = true;
                    }
                }
                versions = remaining;
            } else {
                versions = versions.stream()
                        .filter(v -> v.getKey().compareTo(km) > 0)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
        }

        boolean isTruncated = false;
        String nextKeyMarker = null;
        String nextVersionIdMarker = null;
        if (maxKeys >= 0) {
            List<S3Object> pageVersions = new ArrayList<>();
            List<String> pagePrefixes = new ArrayList<>();
            int vIdx = 0;
            int cpIdx = 0;

            // Merge versions and common prefixes in key order, one response entry at a time, so that
            // maxKeys bounds Version/DeleteMarker entries as well as CommonPrefixes groups.
            while (pageVersions.size() + pagePrefixes.size() < maxKeys
                    && (vIdx < versions.size() || cpIdx < commonPrefixes.size())) {
                String vKey = vIdx < versions.size() ? versions.get(vIdx).getKey() : null;
                String cpKey = cpIdx < commonPrefixes.size() ? commonPrefixes.get(cpIdx) : null;

                if (vKey != null && (cpKey == null || vKey.compareTo(cpKey) <= 0)) {
                    S3Object version = versions.get(vIdx++);
                    pageVersions.add(version);
                    nextKeyMarker = version.getKey();
                    nextVersionIdMarker = reportedVersionId(version);
                } else {
                    String commonPrefix = commonPrefixes.get(cpIdx++);
                    pagePrefixes.add(commonPrefix);
                    nextKeyMarker = commonPrefix;
                    nextVersionIdMarker = null;
                }
            }

            isTruncated = maxKeys > 0 && (vIdx < versions.size() || cpIdx < commonPrefixes.size());
            if (!isTruncated) {
                nextKeyMarker = null;
                nextVersionIdMarker = null;
            }
            versions = pageVersions;
            commonPrefixes = pagePrefixes;
        }
        return new ListVersionsResult(versions, commonPrefixes, isTruncated, nextKeyMarker, nextVersionIdMarker);
    }

    /**
     * Version id as it appears in a {@code ListObjectVersions} response: objects stored while versioning was
     * off have no version id, and AWS reports those as the literal string {@code "null"}. Markers echoed back
     * by a client therefore have to be compared against that same rendering.
     */
    private static String reportedVersionId(S3Object object) {
        return object.getVersionId() != null ? object.getVersionId() : "null";
    }

    // --- Head Bucket / Bucket Location ---

    public void headBucket(String bucketName) {
        ensureBucketExists(bucketName);
    }

    public String getBucketRegion(String bucketName) {
        ensureBucketExists(bucketName);
        return resolveBucket(bucketName).map(Bucket::getRegion).orElse(null);
    }

    // --- Batch Delete ---

    public record DeleteResult(String key, String versionId, boolean deleteMarker, String deleteMarkerVersionId) {
    }

    public record DeleteError(String key, String code, String message) {
    }

    public record DeleteObjectsResult(List<DeleteResult> deleted, List<DeleteError> errors) {
    }

    public DeleteObjectsResult deleteObjects(String bucketName, List<XmlParser.KeyVersion> entries) {
        return deleteObjects(bucketName, entries, false);
    }

    public DeleteObjectsResult deleteObjects(String bucketName, List<XmlParser.KeyVersion> entries,
                                             boolean bypassGovernance) {
        ensureBucketExists(bucketName);
        List<DeleteResult> deleted = new ArrayList<>();
        List<DeleteError> errors = new ArrayList<>();
        for (XmlParser.KeyVersion entry : entries) {
            try {
                S3Object result = deleteObject(bucketName, entry.key(), entry.versionId(), bypassGovernance,
                        entry.eTag());
                if (result != null && result.isDeleteMarker()) {
                    deleted.add(new DeleteResult(entry.key(), entry.versionId(), true, result.getVersionId()));
                } else {
                    deleted.add(new DeleteResult(entry.key(), entry.versionId(), false, null));
                }
            } catch (AwsException e) {
                errors.add(new DeleteError(entry.key(), e.getErrorCode(), e.getMessage()));
            } catch (Exception e) {
                errors.add(new DeleteError(entry.key(), "InternalError", e.getMessage()));
            }
        }
        return new DeleteObjectsResult(deleted, errors);
    }

    // --- Object Tagging ---

    public void putObjectTagging(String bucketName, String key, Map<String, String> tags) {
        AccountAwareStorageBackend.OwnedEntry<S3Object> ownedObject =
                getStoredObjectEntry(bucketName, key, null);
        S3Object obj = ownedObject.value();
        obj.setTags(tags != null ? tags : new java.util.HashMap<>());
        putObjectMetadataForAccount(ownedObject.account(), bucketName, key, obj);
        LOG.debugv("Put tags on object: {0}/{1}", bucketName, key);
    }

    public Map<String, String> getObjectTagging(String bucketName, String key) {
        return getObjectTagging(bucketName, key, null);
    }

    /** Tags of one version, or of the current version when {@code versionId} is null or "null". */
    public Map<String, String> getObjectTagging(String bucketName, String key, String versionId) {
        S3Object obj = getStoredObject(bucketName, key, "null".equals(versionId) ? null : versionId);
        return obj.getTags() != null ? obj.getTags() : Map.of();
    }

    public void deleteObjectTagging(String bucketName, String key) {
        AccountAwareStorageBackend.OwnedEntry<S3Object> ownedObject =
                getStoredObjectEntry(bucketName, key, null);
        S3Object obj = ownedObject.value();
        obj.setTags(new java.util.HashMap<>());
        putObjectMetadataForAccount(ownedObject.account(), bucketName, key, obj);
        LOG.debugv("Deleted tags from object: {0}/{1}", bucketName, key);
    }

    // --- Object Annotations ---

    public static final int MAX_ANNOTATIONS_PER_VERSION = 1_000;
    public static final int MAX_ANNOTATION_RESULTS = 1_000;
    private static final int MAX_ANNOTATION_NAME_BYTES = 512;
    private static final int MAX_ANNOTATION_PAYLOAD_BYTES = 1_048_576;
    // '@' can never occur in a valid annotation name, so this separator cannot be produced by a
    // name itself. Object keys CAN contain '@' and '#', which is why annotationParentKey
    // URL-encodes the key before appending the separators.
    private static final String ANNOTATION_SEPARATOR = "@ann@";
    private static final String ANNOTATION_DATA_SUFFIX = ".s3ann";
    private static final String ANNOTATION_STORAGE_ROOT = ".annotations";

    /** maxAnnotationResults carries the effective limit (default applied) so callers echo the value the service enforced. */
    public record ListObjectAnnotationsResult(List<ObjectAnnotation> annotations, boolean isTruncated,
                                              String nextContinuationToken, int maxAnnotationResults) {}

    public ObjectAnnotation putObjectAnnotation(String bucketName, String key, String annotationName,
                                                String versionId, byte[] payload, String ifMatch,
                                                ChecksumAlgorithm checksumAlgorithm) {
        Bucket bucket = requireBucket(bucketName);
        S3Object[] notificationTarget = {null};
        ObjectAnnotation annotation;
        synchronized (bucket) {
            versionId = normalizeNullVersionId(versionId);
            S3Object parent = resolveParentObject(bucketName, key, versionId);
            // Symmetric with deleteObjectAnnotation: an annotation put must not add or replace
            // an annotation on a retention-protected version, or the delete path's protection
            // is circumvented by a re-put.
            checkLockProtection(parent, false);
            if (parent.getSseCustomerAlgorithm() != null) {
                // AWS rejects annotations on SSE-C encrypted objects.
                throw new AwsException("InvalidRequest",
                        "Server-side encryption with customer-provided keys is not supported for annotations.", 400);
            }
            if (ifMatch != null && !eTagMatches(ifMatch, parent.getETag())) {
                throw new S3PreconditionFailedException("If-Match");
            }
            validateAnnotationName(annotationName);
            validateAnnotationPayload(payload);

            String parentKey = parentStoreKey(bucketName, key, versionId, parent);
            String storeKey = annotationStoreKey(parentKey, annotationName);
            // Account-scoped, like every annotation write: in globalBucketNamespace mode a
            // cross-account probe would let the caller bypass the per-version limit by reading
            // another account's entry, while the put itself lands in the caller's partition.
            boolean isUpdate = annotationStore.get(storeKey).isPresent();
            // Check-then-act against concurrent annotation puts is safe: this method holds the
            // bucket monitor, the same one storeObject's overwrite cleanup holds.
            if (!isUpdate && countAnnotations(parentKey) >= MAX_ANNOTATIONS_PER_VERSION) {
                throw new AwsException("AnnotationLimitExceeded",
                        "The maximum number of annotations for this object version has been reached.", 400);
            }

            ChecksumAlgorithm algorithm = checksumAlgorithm != null ? checksumAlgorithm : ChecksumAlgorithm.CRC64NVME;
            annotation = new ObjectAnnotation(bucketName, key, parent.getVersionId(),
                    annotationName, payload.length, S3Object.computeETag(payload), Instant.now(),
                    algorithm.name(), algorithm.compute(payload));
            annotation.setServerSideEncryption(parent.getServerSideEncryption());

            // Write the payload before publishing metadata, mirroring storeObjectInternal's
            // write-before-publish ordering.
            writeAnnotationPayload(annotation, payload);
            annotationStore.put(storeKey, annotation);
            LOG.debugv("Put annotation {0} on object: {1}/{2}", annotationName, bucketName, key);
            notificationTarget[0] = parent;
        }
        // Fired outside the bucket monitor (the storeObject callers' pattern): a slow SQS/SNS/
        // Lambda delivery must not block every other write and annotation op on the bucket.
        fireNotifications(bucketName, key, "ObjectAnnotation:Put", notificationTarget[0]);
        return annotation;
    }

    public ObjectAnnotation getObjectAnnotation(String bucketName, String key, String annotationName,
                                                String versionId) {
        Bucket bucket = requireBucket(bucketName);
        synchronized (bucket) {
            versionId = normalizeNullVersionId(versionId);
            S3Object parent = resolveParentObject(bucketName, key, versionId);
            validateAnnotationName(annotationName);
            String storeKey = annotationStoreKey(parentStoreKey(bucketName, key, versionId, parent), annotationName);
            return annotationStore.get(storeKey)
                    .orElseThrow(() -> new AwsException("NoSuchAnnotation",
                            "The specified annotation does not exist.", 404));
        }
    }

    public byte[] readObjectAnnotationPayload(ObjectAnnotation annotation) {
        byte[] payload = readAnnotationPayload(annotation);
        if (payload == null) {
            throw new AwsException("NoSuchAnnotation",
                    "The specified annotation does not exist.", 404);
        }
        return payload;
    }

    public ListObjectAnnotationsResult listObjectAnnotations(String bucketName, String key,
                                                             String annotationPrefix,
                                                             Integer maxAnnotationResults,
                                                             String continuationToken, String versionId) {
        Bucket bucket = requireBucket(bucketName);
        synchronized (bucket) {
            versionId = normalizeNullVersionId(versionId);
            S3Object parent = resolveParentObject(bucketName, key, versionId);
            int limit = maxAnnotationResults != null ? maxAnnotationResults : MAX_ANNOTATION_RESULTS;
            if (limit < 1 || limit > MAX_ANNOTATION_RESULTS) {
                throw new AwsException("InvalidArgument",
                        "max-annotation-results must be between 1 and 1000.", 400);
            }
            validateAnnotationPrefix(annotationPrefix);
            String startAfter = decodeAnnotationContinuationToken(continuationToken);

            String parentKey = parentStoreKey(bucketName, key, versionId, parent);
            List<ObjectAnnotation> matches = annotationStore.scan(k -> k.startsWith(parentKey + ANNOTATION_SEPARATOR))
                    .stream()
                    .filter(a -> annotationPrefix == null || a.getAnnotationName().startsWith(annotationPrefix))
                    .sorted(Comparator.comparing(ObjectAnnotation::getAnnotationName))
                    .toList();
            if (startAfter != null) {
                matches = matches.stream()
                        .filter(a -> a.getAnnotationName().compareTo(startAfter) > 0)
                        .toList();
            }
            boolean truncated = matches.size() > limit;
            List<ObjectAnnotation> page = truncated ? new ArrayList<>(matches.subList(0, limit)) : matches;
            String nextToken = truncated ? encodeAnnotationContinuationToken(page.get(page.size() - 1).getAnnotationName()) : null;
            return new ListObjectAnnotationsResult(page, truncated, nextToken, limit);
        }
    }

    /** Returns the parent object's versionId (null in non-versioned buckets) for the response header. */
    public String deleteObjectAnnotation(String bucketName, String key, String annotationName,
                                         String versionId, String ifMatch, boolean bypassGovernance) {
        Bucket bucket = requireBucket(bucketName);
        S3Object[] notificationTarget = {null};
        String parentVersionId;
        synchronized (bucket) {
            versionId = normalizeNullVersionId(versionId);
            S3Object parent = resolveParentObject(bucketName, key, versionId);
            // Deleting an annotation on a locked version follows DeleteObject's rules: governance
            // retention needs x-amz-bypass-governance-retention, compliance and legal hold always block.
            checkLockProtection(parent, bypassGovernance);
            if (ifMatch != null && !eTagMatches(ifMatch, parent.getETag())) {
                throw new S3PreconditionFailedException("If-Match");
            }
            validateAnnotationName(annotationName);
            String parentKey = parentStoreKey(bucketName, key, versionId, parent);
            String storeKey = annotationStoreKey(parentKey, annotationName);
            // Account-scoped existence check, like the objectStore delete path: a cross-account
            // probe would report another account's annotation, which this delete must not remove.
            ObjectAnnotation existing = annotationStore.get(storeKey).orElse(null);
            if (existing == null) {
                // Deleting a nonexistent annotation is not an error (idempotent), but the parent
                // object still had to exist and pass its precondition check above.
                return parent.getVersionId();
            }
            annotationStore.delete(storeKey);
            deleteAnnotationPayload(existing);
            LOG.debugv("Deleted annotation {0} from object: {1}/{2}", annotationName, bucketName, key);
            parentVersionId = parent.getVersionId();
            notificationTarget[0] = parent;
        }
        // Fired outside the bucket monitor, as in putObjectAnnotation.
        fireNotifications(bucketName, key, "ObjectAnnotation:Delete", notificationTarget[0]);
        return parentVersionId;
    }

    /**
     * ListObjectVersions reports pre-versioning objects with the literal VersionId {@code "null"};
     * a version-echoing client sends it back. Treat it as a request for the pre-versioning entry
     * at the plain object key.
     */
    private static String normalizeNullVersionId(String versionId) {
        return "null".equals(versionId) ? null : versionId;
    }

    /** Removes every annotation attached to one object version (metadata + payload). */
    private void deleteAllAnnotationsFor(String parentKey) {
        for (ObjectAnnotation annotation : annotationStore.scan(k -> k.startsWith(parentKey + ANNOTATION_SEPARATOR))) {
            annotationStore.delete(annotationStoreKey(parentKey, annotation.getAnnotationName()));
            deleteAnnotationPayload(annotation);
        }
    }

    /** Removes every annotation in a bucket (metadata + payload); used by DeleteBucket. */
    private void deleteAllAnnotationsForBucket(String bucketName) {
        for (ObjectAnnotation annotation : annotationStore.scan(k -> k.startsWith(bucketName + "/"))) {
            String parentKey = parentKeyOf(annotation);
            annotationStore.delete(annotationStoreKey(parentKey, annotation.getAnnotationName()));
            deleteAnnotationPayload(annotation);
        }
    }

    private int countAnnotations(String parentKey) {
        return (int) annotationStore.scan(k -> k.startsWith(parentKey + ANNOTATION_SEPARATOR)).size();
    }

    private String annotationStoreKey(String parentKey, String annotationName) {
        return parentKey + ANNOTATION_SEPARATOR + annotationName;
    }

    /**
     * Resolves the annotation-store key of the object version an annotation request targets.
     * An absent versionId means the current latest object: the latest entry's own versionId when
     * the bucket is versioned, otherwise the plain object key. The delete-marker check lives in
     * {@link #resolveParentObject}.
     */
    private String parentStoreKey(String bucketName, String key, String versionId, S3Object parent) {
        if (versionId != null) {
            return annotationParentKey(bucketName, key, versionId);
        }
        return parent.getVersionId() != null
                ? annotationParentKey(bucketName, key, parent.getVersionId())
                : annotationParentKey(bucketName, key, null);
    }

    /**
     * Builds the annotation identity for one object version. Unlike the objectStore key scheme,
     * this must be injective: {@code '@'} and {@code "#v#"} mark the separators, and an S3 object
     * key may contain any character, so the key is URL-encoded first. Encoded keys never contain
     * '@', '#' or bare '%', so no other object's identity can forge these separators or extend
     * another object's scan prefix.
     */
    private String annotationParentKey(String bucketName, String key, String versionId) {
        return bucketName + "/" + annotationIdentity(key, versionId);
    }

    private String annotationIdentity(String key, String versionId) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        return versionId != null ? encodedKey + "#v#" + versionId : encodedKey;
    }

    /** Resolves the object a subresource request targets; a delete-marker latest reads as absent. */
    private S3Object resolveParentObject(String bucketName, String key, String versionId) {
        S3Object object = resolveObject(versionId != null
                        ? versionedKey(bucketName, key, versionId)
                        : objectKey(bucketName, key))
                .orElseThrow(() -> new AwsException("NoSuchKey",
                        "The specified key does not exist.", 404));
        if (object.isDeleteMarker()) {
            throw new AwsException("NoSuchKey", "The specified key does not exist.", 404);
        }
        return object;
    }

    private void validateAnnotationName(String annotationName) {
        if (annotationName == null || annotationName.isBlank()) {
            throw new AwsException("InvalidAnnotationName",
                    "The annotation name must not be empty or consist only of whitespace.", 400);
        }
        if (annotationName.getBytes(StandardCharsets.UTF_8).length > MAX_ANNOTATION_NAME_BYTES) {
            throw new AwsException("AnnotationNameTooLong",
                    "The annotation name exceeds the maximum length of 512 bytes.", 400);
        }
        for (int i = 0; i < annotationName.length(); ) {
            int codePoint = annotationName.codePointAt(i);
            if (!isAllowedAnnotationNameCodePoint(codePoint)) {
                throw new AwsException("InvalidAnnotationName",
                        "The annotation name contains invalid characters.", 400);
            }
            i += Character.charCount(codePoint);
        }
        String lowercased = annotationName.toLowerCase(Locale.ROOT);
        if (lowercased.startsWith("aws") || lowercased.startsWith("s3")) {
            throw new AwsException("InvalidAnnotationName",
                    "Annotation names must not start with 'aws' or 's3'.", 400);
        }
    }

    private static boolean isAllowedAnnotationNameCodePoint(int codePoint) {
        return Character.isLetter(codePoint) || Character.isDigit(codePoint)
                || codePoint == '_' || codePoint == '.' || codePoint == '-';
    }

    private void validateAnnotationPrefix(String annotationPrefix) {
        if (annotationPrefix == null || annotationPrefix.isEmpty()) {
            return;
        }
        for (int i = 0; i < annotationPrefix.length(); ) {
            int codePoint = annotationPrefix.codePointAt(i);
            if (!isAllowedAnnotationNameCodePoint(codePoint)) {
                throw new AwsException("InvalidPrefix",
                        "The annotation prefix you provided is invalid.", 400);
            }
            i += Character.charCount(codePoint);
        }
    }

    private void validateAnnotationPayload(byte[] payload) {
        if (payload == null || payload.length < 1) {
            throw new AwsException("InvalidRequest",
                    "The annotation payload must be between 1 byte and 1 MiB in size.", 400);
        }
        if (payload.length > MAX_ANNOTATION_PAYLOAD_BYTES) {
            throw new AwsException("InvalidRequest",
                    "The annotation payload exceeds the maximum size of 1 MiB.", 400);
        }
        if (!ObjectAnnotation.isValidUtf8(payload)) {
            throw new AwsException("UnsupportedMediaType",
                    "The annotation payload is not valid UTF-8 encoded text.", 415);
        }
    }

    private String encodeAnnotationContinuationToken(String lastAnnotationName) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(lastAnnotationName.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeAnnotationContinuationToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgument", "The continuation token you provided is invalid.", 400);
        }
    }

    // Annotation payload bytes live outside the annotation store, the same way object bodies
    // live outside s3-objects.json: in memoryAnnotationStore in memory mode, .s3ann files on disk.

    private String physicalAnnotationKey(String parentKey, String annotationName) {
        return ownerId() + "/" + annotationStoreKey(parentKey, annotationName);
    }

    private Path resolveAnnotationPath(String bucketName, String key, String versionId, String annotationName) {
        Path bucketDir = bucketDirectory(dataRoot.resolve(ACCOUNT_STORAGE_ROOT).resolve(ownerId())
                .resolve(ANNOTATION_STORAGE_ROOT), bucketName);
        // Both directory components are SHA-256 hex of our own injective identity (the object key
        // is URL-encoded inside it), so the path is bounded in length, filesystem-safe, and
        // collision-free across object keys that contain '#v#', '@', or path-like characters.
        // Cleanup is metadata-driven, so the mapping never needs to be reversed.
        // Every path component below bucketDir is SHA-256 hex, so no traversal is possible.
        Path parentDir = bucketDir.resolve(sha256Hex(annotationIdentity(key, versionId)));
        return parentDir.resolve(sha256Hex(annotationName) + ANNOTATION_DATA_SUFFIX);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private void writeAnnotationPayload(ObjectAnnotation annotation, byte[] payload) {
        if (inMemory) {
            memoryAnnotationStore.put(physicalAnnotationKey(
                    parentKeyOf(annotation), annotation.getAnnotationName()), payload);
            return;
        }
        Path filePath = resolveAnnotationPath(annotation.getBucketName(), annotation.getKey(),
                annotation.getVersionId(), annotation.getAnnotationName());
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            atomicWrite(filePath, payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write S3 annotation payload file", e);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the payload bytes, or {@code null} when the payload is gone while its metadata survived. */
    private byte[] readAnnotationPayload(ObjectAnnotation annotation) {
        if (inMemory) {
            return memoryAnnotationStore.get(physicalAnnotationKey(
                    parentKeyOf(annotation), annotation.getAnnotationName()));
        }
        Path filePath = resolveAnnotationPath(annotation.getBucketName(), annotation.getKey(),
                annotation.getVersionId(), annotation.getAnnotationName());
        // The same lock writeAnnotationPayload and deleteAnnotationPayload hold: without it a
        // concurrent delete between the existence check and the read surfaces as an
        // UncheckedIOException (HTTP 500) instead of the intended NoSuchAnnotation (404).
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            if (!Files.exists(filePath)) {
                return null;
            }
            return Files.readAllBytes(filePath);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read S3 annotation payload file", e);
        } finally {
            lock.unlock();
        }
    }

    private void deleteAnnotationPayload(ObjectAnnotation annotation) {
        if (inMemory) {
            memoryAnnotationStore.remove(physicalAnnotationKey(
                    parentKeyOf(annotation), annotation.getAnnotationName()));
            return;
        }
        Path filePath = resolveAnnotationPath(annotation.getBucketName(), annotation.getKey(),
                annotation.getVersionId(), annotation.getAnnotationName());
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to delete S3 annotation payload file for {0}/{1} / {2}",
                    annotation.getBucketName(), annotation.getKey(), annotation.getAnnotationName());
        } finally {
            lock.unlock();
        }
    }

    private String parentKeyOf(ObjectAnnotation annotation) {
        return annotationParentKey(annotation.getBucketName(), annotation.getKey(), annotation.getVersionId());
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

    public void putBucketWebsite(String bucketName, WebsiteConfiguration config) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setWebsiteConfiguration(config);
        bucketStore.put(bucketName, bucket);
        LOG.infov("Set website configuration for bucket: {0}", bucketName);
    }

    public WebsiteConfiguration getBucketWebsite(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        if (bucket.getWebsiteConfiguration() == null) {
            throw new AwsException("NoSuchWebsiteConfiguration", "The specified bucket does not have a website configuration.", 404);
        }
        return bucket.getWebsiteConfiguration();
    }

    public void deleteBucketWebsite(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setWebsiteConfiguration(null);
        bucketStore.put(bucketName, bucket);
        LOG.infov("Deleted website configuration for bucket: {0}", bucketName);
    }

    /**
     * What a website-endpoint request resolves to. The service decides <em>what</em> to serve;
     * the controller decides how to render it as HTTP. Keeping the decision here means the policy
     * is unit-testable without standing up the HTTP layer.
     */
    public sealed interface WebsiteResolution {

        /** Serve {@code object} (already read and authorized) as the response body. */
        record ServeObject(String key, S3Object object) implements WebsiteResolution {}

        /**
         * The request names a "folder" that only exists as a prefix with an index document
         * beneath it: redirect to the slash-terminated form so the page's relative asset URLs
         * resolve against the right base. The target is built by the caller, which is the only
         * layer that knows the raw request path.
         */
        record RedirectToDirectory() implements WebsiteResolution {}

        /** Serve the bucket's custom error document with {@code status}. */
        record ErrorDocument(S3Object object, int status) implements WebsiteResolution {}

        /** No usable custom error document: render S3's built-in error page with {@code status}. */
        record DefaultError(int status) implements WebsiteResolution {}

        /** Not a website request — fall through to the normal object path. */
        record NotAWebsite() implements WebsiteResolution {}
    }

    /**
     * Resolve a request against a bucket's website configuration.
     * <p>
     * {@code directoryRequest} is the caller's answer to "did the client ask for a directory?" —
     * the routing layer strips the trailing slash from the object key, so only the caller can see
     * the slash that distinguishes {@code /docs/} (serve {@code docs/index.html}) from
     * {@code /docs} (redirect to {@code /docs/}). The site root is always a directory request.
     * <p>
     * Returns {@link WebsiteResolution.NotAWebsite} when the request should be served by the
     * normal object path — an exact object hit, or a bucket with no website configuration. The
     * index read is authorized (a no-op unless S3 auth enforcement is enabled), matching the
     * object-serving path.
     */
    public WebsiteResolution resolveWebsiteRequest(String bucket, String key, boolean directoryRequest,
                                                   RequestAuthorization authorization) {
        WebsiteConfiguration cfg;
        try {
            cfg = getBucketWebsite(bucket);
        } catch (AwsException e) {
            // Only "no website configuration" means fall through to normal handling; a real error
            // (e.g. NoSuchBucket) must propagate rather than be masked as "not a website".
            if (!"NoSuchWebsiteConfiguration".equals(e.getErrorCode())) {
                throw e;
            }
            return new WebsiteResolution.NotAWebsite();
        }
        String index = cfg.getIndexDocument();
        if (index == null) {
            return new WebsiteResolution.NotAWebsite();
        }
        boolean directory = key.isEmpty() || directoryRequest;
        String prefix = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;

        if (directory) {
            String indexKey = prefix.isEmpty() ? index : prefix + "/" + index;
            try {
                authorizeGetObject(bucket, indexKey, null, authorization);
                // Metadata only: the controller fetches the body atomically itself when the
                // request actually needs one (GET), so HEAD website requests never load it.
                return new WebsiteResolution.ServeObject(indexKey, headObject(bucket, indexKey, null));
            } catch (AwsException e) {
                if (!isWebsiteErrorDocumentTrigger(e)) {
                    throw e;
                }
                return resolveErrorDocument(bucket, cfg, authorization, e.getHttpStatus());
            }
        }
        // Not slash-terminated: an exact object is served by the normal path; a prefix that exists
        // only as a "folder" (an index document lives beneath it) redirects to the slash-terminated
        // form, matching real S3.
        if (!objectExists(bucket, prefix) && objectExists(bucket, prefix + "/" + index)) {
            return new WebsiteResolution.RedirectToDirectory();
        }
        return new WebsiteResolution.NotAWebsite();
    }

    /**
     * Resolve the error response for a website request that already failed, so a website endpoint
     * answers with the bucket's error document rather than S3's REST XML.
     * <p>
     * Returns {@link WebsiteResolution.NotAWebsite} when the bucket has no website configuration.
     * Any other failure propagates, so the caller renders the real error instead of hiding it
     * behind an error document.
     */
    public WebsiteResolution resolveWebsiteError(String bucket, RequestAuthorization authorization, int status) {
        try {
            return resolveErrorDocument(bucket, getBucketWebsite(bucket), authorization, status);
        } catch (AwsException e) {
            if (!"NoSuchWebsiteConfiguration".equals(e.getErrorCode())) {
                throw e;
            }
            return new WebsiteResolution.NotAWebsite();
        }
    }

    private WebsiteResolution resolveErrorDocument(String bucket, WebsiteConfiguration cfg,
                                                   RequestAuthorization authorization, int status) {
        int responseStatus = status == 403 ? 403 : 404;
        if (cfg.getErrorDocument() == null) {
            return new WebsiteResolution.DefaultError(responseStatus);
        }
        try {
            authorizeGetObject(bucket, cfg.getErrorDocument(), null, authorization);
            return new WebsiteResolution.ErrorDocument(getObject(bucket, cfg.getErrorDocument()), responseStatus);
        } catch (AwsException e) {
            if (!isWebsiteErrorDocumentTrigger(e)) {
                throw e;
            }
            return new WebsiteResolution.DefaultError(responseStatus);
        }
    }

    /**
     * Whether a failure should be answered with the bucket's website error document rather than
     * S3's REST XML error. Callers use this to decide whether the website error path is worth
     * attempting at all.
     */
    public static boolean isWebsiteErrorDocumentTrigger(AwsException e) {
        return "NoSuchKey".equals(e.getErrorCode()) || "AccessDenied".equals(e.getErrorCode());
    }

    public void deleteBucketTagging(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        bucket.setTags(new java.util.HashMap<>());
        bucketStore.put(bucketName, bucket);
        LOG.debugv("Deleted tags from bucket: {0}", bucketName);
    }

    // --- Metrics Configurations ---

    private static final String S3_XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    /**
     * Stores a CloudWatch request metrics configuration under {@code id}, replacing any
     * configuration already stored under it. floci records the configuration and returns it; no
     * metrics are produced from it.
     */
    public void putBucketMetricsConfiguration(String bucketName, String id, String innerXml) {
        Bucket bucket = requireBucket(bucketName);
        // Read-modify-write of the bucket record, so it takes the same monitor as the other
        // bucket-scoped mutations: without it two concurrent puts of different ids both start from
        // the same map and one of the configurations is lost.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getMetricsConfigurations() != null
                    ? new java.util.LinkedHashMap<>(bucket.getMetricsConfigurations())
                    : new java.util.LinkedHashMap<>();
            configurations.put(id, innerXml);
            bucket.setMetricsConfigurations(configurations);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Put metrics configuration {0} on bucket: {1}", id, bucketName);
    }

    public String getBucketMetricsConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        String innerXml = bucket.getMetricsConfigurations() == null
                ? null : bucket.getMetricsConfigurations().get(id);
        if (innerXml == null) {
            throw noSuchMetricsConfiguration();
        }
        return S3_XML_DECLARATION + new XmlBuilder()
                .start("MetricsConfiguration", AwsNamespaces.S3)
                .raw(innerXml)
                .end("MetricsConfiguration")
                .build();
    }

    /**
     * Lists every metrics configuration on the bucket. AWS pages these with a continuation token
     * once there are more than 100; floci returns them all in one unpaged response, ordered by id
     * so that the listing is stable.
     */
    public String listBucketMetricsConfigurations(String bucketName) {
        Bucket bucket = requireBucket(bucketName);
        Map<String, String> configurations = bucket.getMetricsConfigurations() != null
                ? bucket.getMetricsConfigurations() : Map.of();

        XmlBuilder xml = new XmlBuilder().start("ListMetricsConfigurationsResult", AwsNamespaces.S3);
        configurations.keySet().stream().sorted().forEach(id -> xml
                .start("MetricsConfiguration")
                .raw(configurations.get(id))
                .end("MetricsConfiguration"));
        return S3_XML_DECLARATION + xml
                .elem("IsTruncated", false)
                .end("ListMetricsConfigurationsResult")
                .build();
    }

    public void deleteBucketMetricsConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        // Same monitor as the put: the existence check and the write have to be one step, or a
        // concurrent put of another id is dropped by the write that follows it.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getMetricsConfigurations();
            if (configurations == null || !configurations.containsKey(id)) {
                throw noSuchMetricsConfiguration();
            }
            Map<String, String> remaining = new java.util.LinkedHashMap<>(configurations);
            remaining.remove(id);
            bucket.setMetricsConfigurations(remaining);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Deleted metrics configuration {0} from bucket: {1}", id, bucketName);
    }

    private Bucket requireBucket(String bucketName) {
        return bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
    }

    /**
     * Re-reads the bucket under its monitor and checks it is still the same record. Presence alone
     * is not enough: a bucket deleted and recreated under the same name leaves a different record
     * in the store, and writing the resolved one back would replace the new bucket with the old
     * one's state.
     */
    private void requireSameRecord(String bucketName, Bucket resolved) {
        if (bucketStore.get(bucketName).orElse(null) != resolved) {
            throw new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404);
        }
    }

    private static AwsException noSuchMetricsConfiguration() {
        return new AwsException("NoSuchConfiguration", "The specified configuration does not exist.", 404);
    }

    // --- Intelligent-Tiering Configurations ---

    /**
     * Stores an Intelligent-Tiering configuration under {@code id}, replacing any configuration
     * already stored under it. floci records the configuration and returns it; no objects are
     * transitioned between tiers because of it.
     */
    public void putBucketIntelligentTieringConfiguration(String bucketName, String id, String innerXml) {
        Bucket bucket = requireBucket(bucketName);
        // Read-modify-write of the bucket record, so it takes the same monitor as the other
        // bucket-scoped mutations: without it two concurrent puts of different ids both start from
        // the same map and one of the configurations is lost.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getIntelligentTieringConfigurations() != null
                    ? new java.util.LinkedHashMap<>(bucket.getIntelligentTieringConfigurations())
                    : new java.util.LinkedHashMap<>();
            configurations.put(id, innerXml);
            bucket.setIntelligentTieringConfigurations(configurations);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Put intelligent-tiering configuration {0} on bucket: {1}", id, bucketName);
    }

    public String getBucketIntelligentTieringConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        String innerXml = bucket.getIntelligentTieringConfigurations() == null
                ? null : bucket.getIntelligentTieringConfigurations().get(id);
        if (innerXml == null) {
            throw noSuchIntelligentTieringConfiguration();
        }
        return S3_XML_DECLARATION + new XmlBuilder()
                .start("IntelligentTieringConfiguration", AwsNamespaces.S3)
                .raw(innerXml)
                .end("IntelligentTieringConfiguration")
                .build();
    }

    /**
     * Lists every Intelligent-Tiering configuration on the bucket. AWS pages these with a
     * continuation token; floci returns them all in one unpaged response, ordered by id so that
     * the listing is stable.
     */
    public String listBucketIntelligentTieringConfigurations(String bucketName) {
        Bucket bucket = requireBucket(bucketName);
        Map<String, String> configurations = bucket.getIntelligentTieringConfigurations() != null
                ? bucket.getIntelligentTieringConfigurations() : Map.of();

        XmlBuilder xml = new XmlBuilder().start("ListBucketIntelligentTieringConfigurationsResult",
                AwsNamespaces.S3);
        configurations.keySet().stream().sorted().forEach(id -> xml
                .start("IntelligentTieringConfiguration")
                .raw(configurations.get(id))
                .end("IntelligentTieringConfiguration"));
        return S3_XML_DECLARATION + xml
                .elem("IsTruncated", false)
                .end("ListBucketIntelligentTieringConfigurationsResult")
                .build();
    }

    public void deleteBucketIntelligentTieringConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        // Same monitor as the put: the existence check and the write have to be one step, or a
        // concurrent put of another id is dropped by the write that follows it.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getIntelligentTieringConfigurations();
            if (configurations == null || !configurations.containsKey(id)) {
                throw noSuchIntelligentTieringConfiguration();
            }
            Map<String, String> remaining = new java.util.LinkedHashMap<>(configurations);
            remaining.remove(id);
            bucket.setIntelligentTieringConfigurations(remaining);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Deleted intelligent-tiering configuration {0} from bucket: {1}", id, bucketName);
    }

    private static AwsException noSuchIntelligentTieringConfiguration() {
        return new AwsException("NoSuchConfiguration", "The specified configuration does not exist.", 404);
    }

    // --- Analytics Configurations ---

    /**
     * Stores an analytics configuration under {@code id}, replacing any configuration
     * already stored under it. floci records the configuration and returns it; nothing is
     * produced from it.
     */
    public void putBucketAnalyticsConfiguration(String bucketName, String id, String innerXml) {
        Bucket bucket = requireBucket(bucketName);
        // Read-modify-write of the bucket record, so it takes the same monitor as the other
        // bucket-scoped mutations: without it two concurrent puts of different ids both start from
        // the same map and one of the configurations is lost.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getAnalyticsConfigurations() != null
                    ? new java.util.LinkedHashMap<>(bucket.getAnalyticsConfigurations())
                    : new java.util.LinkedHashMap<>();
            configurations.put(id, innerXml);
            bucket.setAnalyticsConfigurations(configurations);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Put analytics configuration {0} on bucket: {1}", id, bucketName);
    }

    public String getBucketAnalyticsConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        String innerXml = bucket.getAnalyticsConfigurations() == null
                ? null : bucket.getAnalyticsConfigurations().get(id);
        if (innerXml == null) {
            throw noSuchAnalyticsConfiguration();
        }
        return S3_XML_DECLARATION + new XmlBuilder()
                .start("AnalyticsConfiguration", AwsNamespaces.S3)
                .raw(innerXml)
                .end("AnalyticsConfiguration")
                .build();
    }

    /**
     * Lists every analytics configuration on the bucket. AWS pages these with a continuation
     * token; floci returns them all in one unpaged response, ordered by id so that the listing is
     * stable.
     */
    public String listBucketAnalyticsConfigurations(String bucketName) {
        Bucket bucket = requireBucket(bucketName);
        Map<String, String> configurations = bucket.getAnalyticsConfigurations() != null
                ? bucket.getAnalyticsConfigurations() : Map.of();

        XmlBuilder xml = new XmlBuilder().start("ListBucketAnalyticsConfigurationResult", AwsNamespaces.S3);
        configurations.keySet().stream().sorted().forEach(id -> xml
                .start("AnalyticsConfiguration")
                .raw(configurations.get(id))
                .end("AnalyticsConfiguration"));
        return S3_XML_DECLARATION + xml
                .elem("IsTruncated", false)
                .end("ListBucketAnalyticsConfigurationResult")
                .build();
    }

    public void deleteBucketAnalyticsConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        // Same monitor as the put: the existence check and the write have to be one step, or a
        // concurrent put of another id is dropped by the write that follows it.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getAnalyticsConfigurations();
            if (configurations == null || !configurations.containsKey(id)) {
                throw noSuchAnalyticsConfiguration();
            }
            Map<String, String> remaining = new java.util.LinkedHashMap<>(configurations);
            remaining.remove(id);
            bucket.setAnalyticsConfigurations(remaining);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Deleted analytics configuration {0} from bucket: {1}", id, bucketName);
    }

    private static AwsException noSuchAnalyticsConfiguration() {
        return new AwsException("NoSuchConfiguration", "The specified configuration does not exist.", 404);
    }

    // --- Inventory Configurations ---

    /**
     * Stores an inventory configuration under {@code id}, replacing any configuration
     * already stored under it. floci records the configuration and returns it; nothing is
     * produced from it.
     */
    public void putBucketInventoryConfiguration(String bucketName, String id, String innerXml) {
        Bucket bucket = requireBucket(bucketName);
        // Read-modify-write of the bucket record, so it takes the same monitor as the other
        // bucket-scoped mutations: without it two concurrent puts of different ids both start from
        // the same map and one of the configurations is lost.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getInventoryConfigurations() != null
                    ? new java.util.LinkedHashMap<>(bucket.getInventoryConfigurations())
                    : new java.util.LinkedHashMap<>();
            configurations.put(id, innerXml);
            bucket.setInventoryConfigurations(configurations);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Put inventory configuration {0} on bucket: {1}", id, bucketName);
    }

    public String getBucketInventoryConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        String innerXml = bucket.getInventoryConfigurations() == null
                ? null : bucket.getInventoryConfigurations().get(id);
        if (innerXml == null) {
            throw noSuchInventoryConfiguration();
        }
        return S3_XML_DECLARATION + new XmlBuilder()
                .start("InventoryConfiguration", AwsNamespaces.S3)
                .raw(innerXml)
                .end("InventoryConfiguration")
                .build();
    }

    /**
     * Lists every inventory configuration on the bucket. AWS pages these with a continuation
     * token; floci returns them all in one unpaged response, ordered by id so that the listing is
     * stable.
     */
    public String listBucketInventoryConfigurations(String bucketName) {
        Bucket bucket = requireBucket(bucketName);
        Map<String, String> configurations = bucket.getInventoryConfigurations() != null
                ? bucket.getInventoryConfigurations() : Map.of();

        XmlBuilder xml = new XmlBuilder().start("ListInventoryConfigurationsResult", AwsNamespaces.S3);
        configurations.keySet().stream().sorted().forEach(id -> xml
                .start("InventoryConfiguration")
                .raw(configurations.get(id))
                .end("InventoryConfiguration"));
        return S3_XML_DECLARATION + xml
                .elem("IsTruncated", false)
                .end("ListInventoryConfigurationsResult")
                .build();
    }

    public void deleteBucketInventoryConfiguration(String bucketName, String id) {
        Bucket bucket = requireBucket(bucketName);
        // Same monitor as the put: the existence check and the write have to be one step, or a
        // concurrent put of another id is dropped by the write that follows it.
        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            Map<String, String> configurations = bucket.getInventoryConfigurations();
            if (configurations == null || !configurations.containsKey(id)) {
                throw noSuchInventoryConfiguration();
            }
            Map<String, String> remaining = new java.util.LinkedHashMap<>(configurations);
            remaining.remove(id);
            bucket.setInventoryConfigurations(remaining);
            bucketStore.put(bucketName, bucket);
        }
        LOG.debugv("Deleted inventory configuration {0} from bucket: {1}", id, bucketName);
    }

    private static AwsException noSuchInventoryConfiguration() {
        return new AwsException("NoSuchConfiguration", "The specified configuration does not exist.", 404);
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
        if (!bucket.isObjectLockEnabled()) {
            throw new AwsException("ObjectLockConfigurationNotFoundError",
                    "Object Lock configuration does not exist for this bucket", 404);
        }
        return bucket.getDefaultRetention();
    }

    public void putObjectRetention(String bucketName, String key, String versionId,
                                   String mode, Instant retainUntil, boolean bypassGovernance) {
        AccountAwareStorageBackend.OwnedEntry<S3Object> ownedObject =
                getStoredObjectEntry(bucketName, key, versionId);
        S3Object obj = ownedObject.value();

        boolean activeComplianceRetention = "COMPLIANCE".equals(obj.getObjectLockMode())
                && obj.getRetainUntilDate() != null
                && Instant.now().isBefore(obj.getRetainUntilDate());

        // Active COMPLIANCE mode cannot be changed or removed, even when the
        // retention date is unchanged or extended.
        if (activeComplianceRetention && !"COMPLIANCE".equals(mode)) {
            throw new AwsException("AccessDenied",
                    "COMPLIANCE retention mode cannot be changed", 403);
        }

        // Active COMPLIANCE mode: retainUntil cannot be shortened.
        if (activeComplianceRetention
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
        putObjectMetadataForAccount(ownedObject.account(), bucketName, key, obj);
        LOG.debugv("Set retention on {0}/{1}: mode={2}, until={3}", bucketName, key, mode, retainUntil);
    }

    public S3Object getObjectRetention(String bucketName, String key, String versionId) {
        return getStoredObject(bucketName, key, versionId);
    }

    public void putObjectLegalHold(String bucketName, String key, String versionId, String status) {
        AccountAwareStorageBackend.OwnedEntry<S3Object> ownedObject =
                getStoredObjectEntry(bucketName, key, versionId);
        S3Object obj = ownedObject.value();
        obj.setLegalHoldStatus(status);
        putObjectMetadataForAccount(ownedObject.account(), bucketName, key, obj);
        LOG.debugv("Set legal hold on {0}/{1}: {2}", bucketName, key, status);
    }

    public S3Object getObjectLegalHold(String bucketName, String key, String versionId) {
        return getStoredObject(bucketName, key, versionId);
    }

    // --- Multipart Upload Operations ---

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType) {
        return initiateMultipartUpload(bucket, key, contentType, null, null, null, null, null);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass) {
        return initiateMultipartUpload(bucket, key, contentType, metadata, storageClass, null, null, null);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass,
                                                   String contentDisposition, String serverSideEncryption, String acl) {
        return initiateMultipartUpload(bucket, key, contentType, metadata, storageClass, contentDisposition,
                serverSideEncryption, acl, null, null, null, null, null, null, null);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass,
                                                   String contentDisposition, String serverSideEncryption, String acl,
                                                   String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5,
                                                   String checksumAlgorithm) {
        return initiateMultipartUpload(bucket, key, contentType, metadata, storageClass, contentDisposition,
                serverSideEncryption, acl, null, sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5,
                checksumAlgorithm, null, null);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass,
                                                   String contentDisposition, String serverSideEncryption, String acl,
                                                   String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5,
                                                   String checksumAlgorithm, Map<String, String> tagging) {
        return initiateMultipartUpload(bucket, key, contentType, metadata, storageClass, contentDisposition,
                serverSideEncryption, acl, null, sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5,
                checksumAlgorithm, null, tagging);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass,
                                                   String contentDisposition, String serverSideEncryption, String acl,
                                                   String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5,
                                                   String checksumAlgorithm, String checksumType,
                                                   Map<String, String> tagging) {
        return initiateMultipartUpload(bucket, key, contentType, metadata, storageClass, contentDisposition,
                serverSideEncryption, acl, null, sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5,
                checksumAlgorithm, checksumType, tagging);
    }

    public MultipartUpload initiateMultipartUpload(String bucket, String key, String contentType,
                                                   Map<String, String> metadata, String storageClass,
                                                   String contentDisposition, String serverSideEncryption, String acl,
                                                   String sseKmsKeyId,
                                                   String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5,
                                                   String checksumAlgorithm, String checksumType,
                                                   Map<String, String> tagging) {
        return withMultipartBucketReadLock(bucket, () -> {
            ensureBucketExists(bucket);
            if (acl != null && !acl.isBlank()) {
                cannedObjectAclXml(acl);
            }
            String normalizedServerSideEncryption = normalizeServerSideEncryption(serverSideEncryption);
            SseCustomerKey customerKey = validateSseCustomerKey(sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5);
            rejectConflictingServerSideEncryption(normalizedServerSideEncryption, customerKey);
            MultipartUpload upload = new MultipartUpload(bucket, key, contentType);
            upload.setOwnerAccountId(getBucketOwnerAccountId(bucket));
            upload.setInitiatorAccountId(ownerId());
            if (metadata != null) {
                upload.getMetadata().putAll(metadata);
            }
            upload.setStorageClass(ObjectAttributeName.normalizeStorageClass(storageClass));
            upload.setContentDisposition(contentDisposition);
            upload.setServerSideEncryption(normalizedServerSideEncryption);
            upload.setSseKmsKeyId("aws:kms".equals(normalizedServerSideEncryption) ? sseKmsKeyId : null);
            if (customerKey != null) {
                upload.setSseCustomerAlgorithm(customerKey.algorithm());
                upload.setSseCustomerKeyMd5(customerKey.keyMd5());
            }
            upload.setAcl(acl);
            ChecksumAlgorithm algorithm = ChecksumAlgorithm.fromWireValue(checksumAlgorithm);
            ChecksumType requestedChecksumType = ChecksumType.fromWireValue(checksumType);
            if (requestedChecksumType != null && algorithm == null) {
                throw new AwsException("InvalidRequest",
                        "The x-amz-checksum-type header can only be used with the x-amz-checksum-algorithm header.", 400);
            }
            upload.setChecksumAlgorithm(algorithm);
            upload.setChecksumType(algorithm == null ? null : algorithm.multipartType(requestedChecksumType));
            if (tagging != null && !tagging.isEmpty()) {
                upload.setTagging(new HashMap<>(tagging));
            }

            if (inMemory) {
                memoryMultipartStore.put(upload.getUploadId(), new ConcurrentHashMap<>());
            } else {
                try {
                    Files.createDirectories(dataRoot.resolve(".multipart").resolve(upload.getUploadId()));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to create multipart temp directory", e);
                }
            }

            multipartUploads.put(upload.getUploadId(), upload);
            LOG.infov("Initiated multipart upload: {0}/{1}, uploadId={2}", bucket, key, upload.getUploadId());
            return upload;
        });
    }

    public String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data) {
        return uploadPart(bucket, key, uploadId, partNumber, data, null, null, null);
    }

    public String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data,
                             String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5) {
        return storePart(bucket, key, uploadId, partNumber, data, sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5)
                .getETag();
    }

    /** Stores one part and returns it, with the ETag and the checksum the upload's algorithm gives it. */
    public Part storePart(String bucket, String key, String uploadId, int partNumber, byte[] data,
                          String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5) {
        return withMultipartOperationLock(bucket, uploadId, () -> {
            MultipartUpload upload = getMultipartUpload(bucket, key, uploadId);
            if (partNumber < 1 || partNumber > 10000) {
                throw new AwsException("InvalidArgument",
                        "Part number must be between 1 and 10000.", 400);
            }
            validateSseCustomerAccess(upload, sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5);

            // Each upload of a part gets its own storage, so a record and the bytes it describes are
            // published together and never change: a re-upload adds new bytes and drops the old ones.
            String storageId = partNumber + "-" + UUID.randomUUID();
            if (inMemory) {
                // A re-uploaded part number replaces its earlier part, so that part does not count.
                long otherParts = upload.getParts().values().stream()
                        .filter(part -> part.getPartNumber() != partNumber)
                        .mapToLong(Part::getSize)
                        .sum();
                requireFitsInMemory("Multipart upload " + uploadId, otherParts + data.length);
                memoryMultipartStore.get(uploadId).put(storageId, data);
            } else {
                Path partPath = dataRoot.resolve(".multipart").resolve(uploadId).resolve(storageId);
                try {
                    Files.write(partPath, data, StandardOpenOption.CREATE_NEW);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write multipart part", e);
                }
            }

            String eTag = computeETag(data);
            Part part = new Part(partNumber, eTag, data.length);
            part.setChecksum(S3Checksum.of(upload.getChecksumAlgorithm(), data));
            part.setStorageId(storageId);
            Part replaced = upload.getParts().put(partNumber, part);
            if (replaced != null) {
                discardPartBytes(uploadId, replaced);
            }
            LOG.debugv("Uploaded part {0} for upload {1} ({2} bytes)", partNumber, uploadId, data.length);
            return part;
        });
    }

    /**
     * Stores one part streamed from {@code body}, checked against {@code checksums}, without holding it
     * in memory in the disk-backed modes. The upload is checked before any of the body is read. The
     * body is staged outside the upload's directory and outside any lock, so parts of one upload still
     * arrive in parallel and a slow client never holds up the bucket's other multipart operations. Only
     * once it has passed its checks is it moved into the upload and its record published, under the
     * upload's lock, so an upload aborted or completed meanwhile fails the part with NoSuchUpload.
     */
    Part storePart(String bucket, String key, String uploadId, int partNumber, InputStream body,
                   UploadChecksums checksums,
                   String sseCustomerAlgorithm, String sseCustomerKey, String sseCustomerKeyMd5) {
        checksums.requireWellFormedContentMd5();
        ChecksumAlgorithm declared = withMultipartOperationLock(bucket, uploadId, () -> {
            MultipartUpload upload = getMultipartUpload(bucket, key, uploadId);
            if (partNumber < 1 || partNumber > 10000) {
                throw new AwsException("InvalidArgument",
                        "Part number must be between 1 and 10000.", 400);
            }
            validateSseCustomerAccess(upload, sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5);
            return upload.getChecksumAlgorithm();
        });
        if (inMemory) {
            byte[] data = readVerified(body, checksums, "Multipart upload " + uploadId);
            return storePart(bucket, key, uploadId, partNumber, data,
                    sseCustomerAlgorithm, sseCustomerKey, sseCustomerKeyMd5);
        }
        ChecksumAlgorithm algorithm = declared != null ? declared : ChecksumAlgorithm.CRC64NVME;
        Set<ChecksumAlgorithm> algorithms = EnumSet.of(algorithm);
        algorithms.addAll(checksums.algorithms());
        DigestingInputStream digests = new DigestingInputStream(body, algorithms);
        Path staged = stageBody(digests);
        try {
            checksums.verify(digests.md5(), digests::checksum);
            S3Checksum partChecksum = new S3Checksum();
            partChecksum.setValueFor(algorithm, digests.checksum(algorithm));
            return withMultipartOperationLock(bucket, uploadId, () -> {
                MultipartUpload upload = getMultipartUpload(bucket, key, uploadId);
                String storageId = partNumber + "-" + UUID.randomUUID();
                try {
                    Files.move(staged, dataRoot.resolve(".multipart").resolve(uploadId).resolve(storageId),
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write multipart part", e);
                }
                Part part = new Part(partNumber, digests.eTag(), digests.size());
                part.setChecksum(partChecksum);
                part.setStorageId(storageId);
                Part replaced = upload.getParts().put(partNumber, part);
                if (replaced != null) {
                    discardPartBytes(uploadId, replaced);
                }
                LOG.debugv("Uploaded part {0} for upload {1} ({2} bytes)", partNumber, uploadId, digests.size());
                return part;
            });
        } finally {
            deleteQuietly(staged, "staged part of an upload that was not stored");
        }
    }

    /**
     * Reads a memory-mode upload body into one array, checked against {@code checksums}, failing
     * with EntityTooLarge once it outgrows the largest array the JDK allocates.
     */
    private static byte[] readVerified(InputStream body, UploadChecksums checksums, String upload) {
        DigestingInputStream digests = new DigestingInputStream(body, checksums.algorithms());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        try {
            for (int read = digests.read(buffer); read >= 0; read = digests.read(buffer)) {
                requireFitsInMemory(upload, (long) out.size() + read);
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the upload body", e);
        }
        checksums.verify(digests.md5(), digests::checksum);
        return out.toByteArray();
    }

    /**
     * Writes a streamed body to a new file under the staging directory and returns it. The file is
     * deleted if the body fails partway, so on return the caller owns it.
     */
    private Path stageBody(InputStream body) {
        Path staged = dataRoot.resolve(".multipart").resolve(INCOMING_BODIES).resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(staged.getParent());
            Files.copy(body, staged);
            return staged;
        } catch (IOException e) {
            deleteQuietly(staged, "partly staged upload body");
            throw new UncheckedIOException("Failed to write the upload body", e);
        } catch (RuntimeException e) {
            deleteQuietly(staged, "partly staged upload body");
            throw e;
        }
    }

    /**
     * Drops the bytes of a part a re-upload replaced, once the new record is published, so no record
     * ever names bytes that changed after it was written.
     */
    private void discardPartBytes(String uploadId, Part replaced) {
        if (inMemory) {
            Map<String, byte[]> memoryParts = memoryMultipartStore.get(uploadId);
            if (memoryParts != null) {
                memoryParts.remove(replaced.getStorageId());
            }
        } else {
            deleteQuietly(dataRoot.resolve(".multipart").resolve(uploadId).resolve(replaced.getStorageId()),
                    "bytes of a multipart part that was uploaded again");
        }
    }

    public String uploadPartCopy(String destBucket, String destKey, String uploadId, int partNumber,
                                  String sourceBucket, String sourceKey, String sourceVersionId,
                                  String copySourceRange) {
        return uploadPartCopy(destBucket, destKey, uploadId, partNumber, sourceBucket, sourceKey,
                sourceVersionId, copySourceRange, SseCustomerHeaders.EMPTY, SseCustomerHeaders.EMPTY);
    }

    public String uploadPartCopy(String destBucket, String destKey, String uploadId, int partNumber,
                                  String sourceBucket, String sourceKey, String sourceVersionId,
                                  String copySourceRange,
                                  SseCustomerHeaders copySourceSseCustomerHeaders,
                                  SseCustomerHeaders sseCustomerHeaders) {
        return uploadPartCopy(destBucket, destKey, uploadId, partNumber, sourceBucket, sourceKey,
                sourceVersionId, copySourceRange, copySourceSseCustomerHeaders, sseCustomerHeaders,
                CopySourceConditions.NONE);
    }

    public String uploadPartCopy(String destBucket, String destKey, String uploadId, int partNumber,
                                  String sourceBucket, String sourceKey, String sourceVersionId,
                                  String copySourceRange,
                                  SseCustomerHeaders copySourceSseCustomerHeaders,
                                  SseCustomerHeaders sseCustomerHeaders,
                                  CopySourceConditions copySourceConditions) {
        byte[] data;
        // The source is streamed the way GetObject serves it and only the copied range is read, so
        // the source can be any size.
        try (ObjectRead read = openObject(sourceBucket, sourceKey, sourceVersionId)) {
            S3Object source = read.object();
            checkCopySourcePreconditions(source, copySourceConditions);
            validateSseCustomerAccess(source,
                    copySourceSseCustomerHeaders.algorithm(),
                    copySourceSseCustomerHeaders.key(),
                    copySourceSseCustomerHeaders.keyMd5());
            CopySourceRange range = CopySourceRange.parse(copySourceRange, source.getSize());
            if (range.length() > MAX_IN_MEMORY_OBJECT_SIZE) {
                LOG.warnv("UploadPartCopy of {0} bytes into upload {1} is over the {2} bytes one part can hold in Floci; copy the source in smaller ranges",
                        range.length(), uploadId, MAX_IN_MEMORY_OBJECT_SIZE);
                throw new AwsException("EntityTooLarge", "Your proposed upload exceeds the maximum allowed object size.", 400);
            }
            data = readRange(read.body(), range);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the copy source", e);
        }

        return uploadPart(destBucket, destKey, uploadId, partNumber, data,
                sseCustomerHeaders.algorithm(), sseCustomerHeaders.key(), sseCustomerHeaders.keyMd5());
    }

    /** An inclusive byte range of a copy source. Offsets are longs, since a source can pass 2 GiB. */
    record CopySourceRange(long first, long last) {

        private static final String HEADER = "x-amz-copy-source-range";
        private static final Pattern FORM = Pattern.compile("bytes=(\\d+)-(\\d+)");

        long length() {
            return last - first + 1;
        }

        /**
         * The range an x-amz-copy-source-range header names, or the whole source when there is no
         * header. The errors are the ones S3 returns, as recorded in LocalStack's AWS-validated
         * snapshot for UploadPartCopy: anything but one {@code bytes=first-last} range with
         * {@code first <= last}, a range that ends past the source, and a range that starts past it.
         */
        static CopySourceRange parse(String header, long sourceSize) {
            if (header == null || header.isBlank()) {
                return new CopySourceRange(0, sourceSize - 1);
            }
            Matcher matcher = FORM.matcher(header);
            if (!matcher.matches()) {
                throw malformed(header);
            }
            long first;
            long last;
            try {
                first = Long.parseLong(matcher.group(1));
                last = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException e) {
                throw malformed(header);
            }
            if (first > last) {
                throw malformed(header);
            }
            // A range whose first byte does not exist starts past the source, the case S3 answers
            // with InvalidRequest; one that only runs over the end names the source size instead.
            if (first >= sourceSize) {
                throw new AwsException("InvalidRequest",
                        "The specified copy range is invalid for the source object size", 400);
            }
            if (last >= sourceSize) {
                throw new AwsException("InvalidArgument",
                        "Range specified is not valid for source object of size: " + sourceSize, 400,
                        argument(header));
            }
            return new CopySourceRange(first, last);
        }

        private static AwsException malformed(String header) {
            return new AwsException("InvalidArgument", "The x-amz-copy-source-range value must be of the form"
                    + " bytes=first-last where first and last are the zero-based offsets of the first and last"
                    + " bytes to copy", 400, argument(header));
        }

        private static Map<String, Object> argument(String header) {
            return Map.of("ArgumentName", HEADER, "ArgumentValue", header);
        }
    }

    /** Reads exactly the bytes of {@code range} from {@code body}, skipping the bytes before it. */
    static byte[] readRange(InputStream body, CopySourceRange range) throws IOException {
        body.skipNBytes(range.first());
        byte[] data = body.readNBytes((int) range.length());
        if (data.length != range.length()) {
            throw new IOException("Copy source ended " + (range.length() - data.length)
                    + " bytes before the end of the requested range");
        }
        return data;
    }

    public S3Object completeMultipartUpload(String bucket, String key, String uploadId, List<Integer> partNumbers,
                                            String checksumType, S3Checksum expectedChecksum) {
        return completeMultipartUpload(bucket, key, uploadId, partNumbers, Map.of(), Map.of(), checksumType,
                expectedChecksum);
    }

    public S3Object completeMultipartUpload(String bucket, String key, String uploadId, List<Integer> partNumbers,
                                            Map<Integer, S3Checksum> partChecksums,
                                            String checksumType, S3Checksum expectedChecksum) {
        return completeMultipartUpload(bucket, key, uploadId, partNumbers, Map.of(), partChecksums, checksumType,
                expectedChecksum);
    }

    public S3Object completeMultipartUpload(String bucket, String key, String uploadId, List<Integer> partNumbers,
                                            Map<Integer, String> partETags, Map<Integer, S3Checksum> partChecksums,
                                            String checksumType, S3Checksum expectedChecksum) {
        S3Object completed = withMultipartOperationLock(bucket, uploadId, () -> {
            MultipartUpload upload = getMultipartUpload(bucket, key, uploadId);

            ChecksumAlgorithm algorithm = upload.getChecksumAlgorithm() != null ? upload.getChecksumAlgorithm() : ChecksumAlgorithm.CRC64NVME;
            ChecksumType storedChecksumType = upload.getChecksumType() != null ? upload.getChecksumType() : ChecksumType.FULL_OBJECT;

            // Each record is read once, and everything below, the bytes included, follows these records.
            List<Part> parts = new ArrayList<>(partNumbers.size());
            int previousPartNumber = 0;
            for (int num : partNumbers) {
                if (num <= previousPartNumber) {
                    throw new AwsException("InvalidPartOrder",
                            "The list of parts was not in ascending order.", 400);
                }
                previousPartNumber = num;
                Part part = upload.getParts().get(num);
                if (part == null) {
                    throw new AwsException("InvalidPart",
                            "One or more of the specified parts could not be found. Part " + num + " is missing.", 400);
                }
                if (!partETags.isEmpty() && !etagsMatch(part.getETag(), partETags.get(num))) {
                    throw new AwsException("InvalidPart",
                            "One or more of the specified parts could not be found. Part " + num
                                    + " has an invalid ETag.", 400);
                }
                validatePartChecksum(upload.getChecksumAlgorithm(), storedChecksumType, num, part, partChecksums.get(num));
                parts.add(part);
            }

            validateCompleteChecksumType(algorithm, storedChecksumType, ChecksumType.fromWireValue(checksumType));
            if (inMemory) {
                requireFitsInMemory("Multipart upload " + uploadId, parts.stream().mapToLong(Part::getSize).sum());
            }

            // Concatenate parts in order
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                for (Part part : parts) {
                    // A part ETag is the MD5 of that part, so the composite hashes it without rehashing the data
                    String partETag = stripSurroundingQuotes(part.getETag());
                    md.update(HexFormat.of().parseHex(partETag));
                }

                // Composite ETag: MD5 of concatenated part MD5s, suffixed with part count
                String compositeETag = "\"" + bytesToHex(md.digest()) + "-" + partNumbers.size() + "\"";

                // Both checksum types come from the stored part checksums, so a mismatch is rejected
                // before any part is read.
                List<Part> completedParts = parts.stream()
                        .map(S3Service::copyPart)
                        .toList();
                S3Checksum checksum = storedChecksumType == ChecksumType.COMPOSITE
                        ? S3Checksum.composite(algorithm, completedParts.stream()
                                .map(part -> part.getChecksum().valueFor(algorithm)).toList())
                        : S3Checksum.fullObject(algorithm, completedParts);
                if (expectedChecksum != null && expectedChecksum.hasAnyValue()) {
                    validateExpectedChecksum(checksum, expectedChecksum);
                }

                ObjectBody body = inMemory
                        ? new BytesBody(concatenateParts(uploadId, parts))
                        : assembleParts(uploadId, parts);
                S3Object object;
                try {
                    object = storeObject(bucket, key, body, upload.getContentType(), upload.getMetadata(),
                            checksum, completedParts,
                            new PutObjectOptions()
                                    .withStorageClass(upload.getStorageClass())
                                    .withContentDisposition(upload.getContentDisposition())
                                    .withServerSideEncryption(upload.getServerSideEncryption())
                                    .withSseKmsKeyId(upload.getSseKmsKeyId())
                                    .withAcl(upload.getAcl())
                                    .withTagging(upload.getTagging()),
                            compositeETag);
                } finally {
                    // A stored body was moved away, so this only removes the file of a failed store and
                    // leaves the parts in place for a retry or an abort.
                    if (body instanceof AssembledBody assembled) {
                        deleteQuietly(assembled.file(), "assembled multipart object that was not stored");
                    }
                }
                if (upload.getSseCustomerAlgorithm() != null) {
                    object.setSseCustomerAlgorithm(upload.getSseCustomerAlgorithm());
                    object.setSseCustomerKeyMd5(upload.getSseCustomerKeyMd5());
                }
                String bucketOwnerAccount = resolveBucketEntry(bucket)
                        .map(AccountAwareStorageBackend.OwnedEntry::account)
                        .orElseThrow(() -> new AwsException("NoSuchBucket",
                                "The specified bucket does not exist.", 404));
                putObjectMetadataForAccount(bucketOwnerAccount, bucket, key, object);

                // Cleanup
                cleanupMultipart(uploadId);
                LOG.infov("Completed multipart upload: {0}/{1}, uploadId={2}, parts={3}",
                        bucket, key, uploadId, partNumbers.size());
                return object;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read multipart parts", e);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("MD5 algorithm not available", e);
            }
        });
        fireNotifications(bucket, key, "ObjectCreated:CompleteMultipartUpload", completed);
        return completed;
    }

    /**
     * Rejects an upload that memory mode could not hold in one byte array, with the error S3 gives an
     * object over its size limit, so it fails at the part that crosses the limit instead of running
     * the heap out of memory or overflowing the array size at completion.
     */
    private static void requireFitsInMemory(String upload, long uploadSize) {
        if (uploadSize > MAX_IN_MEMORY_OBJECT_SIZE) {
            LOG.warnv("{0} would hold {1} bytes, over the {2} an object can have in memory storage mode; set FLOCI_STORAGE_SERVICES_S3_MODE=persistent for larger objects",
                    upload, uploadSize, MAX_IN_MEMORY_OBJECT_SIZE);
            throw new AwsException("EntityTooLarge", "Your proposed upload exceeds the maximum allowed object size.", 400);
        }
    }

    /**
     * Copies the in-memory bytes of {@code parts}, in order, into one array sized to the total
     * upload. The total is measured on the arrays being copied, so it holds whatever the records say.
     */
    private byte[] concatenateParts(String uploadId, List<Part> parts) {
        Map<String, byte[]> memoryParts = memoryMultipartStore.getOrDefault(uploadId, Map.of());
        byte[][] partData = new byte[parts.size()][];
        long totalSize = 0;
        for (int i = 0; i < parts.size(); i++) {
            partData[i] = memoryParts.get(parts.get(i).getStorageId());
            if (partData[i] == null) {
                throw missingPartData(parts.get(i));
            }
            totalSize += partData[i].length;
        }
        requireFitsInMemory("Multipart upload " + uploadId, totalSize);
        byte[] allData = new byte[(int) totalSize];
        int offset = 0;
        for (byte[] part : partData) {
            System.arraycopy(part, 0, allData, offset, part.length);
            offset += part.length;
        }
        return allData;
    }

    /**
     * Copies the part files, in order, into one file in the upload's directory for storeObject to
     * move into place. The copy runs file to file, so the object never passes through the heap
     * whatever its size, and it happens before the bucket lock is taken. The file name is unique
     * per call, so two Completes racing on one upload never write the same file.
     */
    private AssembledBody assembleParts(String uploadId, List<Part> parts) throws IOException {
        Path partsDir = dataRoot.resolve(".multipart").resolve(uploadId);
        Path assembled = partsDir.resolve("assembled-" + UUID.randomUUID());
        long totalSize = 0;
        try (FileChannel out = FileChannel.open(assembled, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (Part part : parts) {
                try {
                    appendPart(partsDir.resolve(part.getStorageId()), part, out);
                } catch (NoSuchFileException e) {
                    throw missingPartData(part);
                }
                totalSize += part.getSize();
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(assembled, "partially assembled multipart object");
            throw e;
        }
        return new AssembledBody(assembled, totalSize);
    }

    /**
     * Appends part {@code part} from {@code partFile} to {@code out}, failing if the file does not
     * hold the size recorded for the part: the ETag and checksum the object is stored with were
     * built from that record.
     */
    static void appendPart(Path partFile, Part part, FileChannel out) throws IOException {
        long size = part.getSize();
        try (FileChannel in = FileChannel.open(partFile, StandardOpenOption.READ)) {
            long position = 0;
            while (position < size) {
                long transferred = in.transferTo(position, size - position, out);
                if (transferred <= 0) {
                    break;
                }
                position += transferred;
            }
            if (position != size || in.size() != size) {
                throw new IOException("Part " + part.getPartNumber() + " changed size during assembly");
            }
        }
    }

    /** A part whose record names bytes that are no longer stored. */
    private static AwsException missingPartData(Part part) {
        return new AwsException("InvalidPart", "One or more of the specified parts could not be found. Part "
                + part.getPartNumber() + " has no stored data.", 400);
    }

    private boolean etagsMatch(String storedETag, String submittedETag) {
        return stripSurroundingQuotes(storedETag).equals(stripSurroundingQuotes(submittedETag));
    }

    private String stripSurroundingQuotes(String eTag) {
        if (eTag == null) {
            return null;
        }
        if (eTag.length() >= 2 && eTag.startsWith("\"") && eTag.endsWith("\"")) {
            return eTag.substring(1, eTag.length() - 1);
        }
        return eTag;
    }

    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        withMultipartOperationLock(bucket, uploadId, () -> {
            getMultipartUpload(bucket, key, uploadId);
            cleanupMultipart(uploadId);
            LOG.infov("Aborted multipart upload: {0}/{1}, uploadId={2}", bucket, key, uploadId);
        });
    }

    public List<MultipartUpload> listMultipartUploads(String bucket) {
        ensureBucketExists(bucket);
        String ownerAccountId = getBucketOwnerAccountId(bucket);
        return multipartUploads.values().stream()
                .filter(u -> u.getBucket().equals(bucket)
                        && (u.getOwnerAccountId() == null || ownerAccountId.equals(u.getOwnerAccountId())))
                .toList();
    }

    public MultipartUpload listParts(String bucket, String key, String uploadId) {
        return getMultipartUpload(bucket, key, uploadId);
    }

    public MultipartUpload getMultipartUpload(String bucket, String key, String uploadId) {
        MultipartUpload upload = multipartUploads.get(uploadId);
        if (upload == null || !upload.getBucket().equals(bucket) || !upload.getKey().equals(key)
                || (upload.getOwnerAccountId() != null
                        && !upload.getOwnerAccountId().equals(getBucketOwnerAccountId(bucket)))) {
            throw new AwsException("NoSuchUpload",
                    "The specified multipart upload does not exist.", 404);
        }
        return upload;
    }

    // --- Notification Configuration ---

    public void putBucketNotificationConfiguration(String bucketName, NotificationConfiguration config) {
        putBucketNotificationConfiguration(bucketName, config, false);
    }

    public void putBucketNotificationConfiguration(String bucketName, NotificationConfiguration config,
                                                   boolean skipDestinationValidation) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));

        List<NotificationDestination> destinations = notificationDestinations(config);
        if (!skipDestinationValidation) {
            List<DestinationFailure> failures = new ArrayList<>();
            for (NotificationDestination destination : destinations) {
                if (!notificationDestinationExists(destination)) {
                    failures.add(missingDestination(destination));
                }
            }
            if (!failures.isEmpty()) {
                throw invalidNotificationDestinations(failures);
            }

            String testEvent = s3TestEvent(bucketName);
            for (NotificationDestination destination : destinations) {
                try {
                    if (!destination.accountId().equals(ownerId())
                            && ("sns".equals(destination.service())
                                    || enforceIam && "sqs".equals(destination.service()))) {
                        // SNS publish still resolves topics in the caller's account.
                        continue;
                    }
                    if ("sqs".equals(destination.service())) {
                        sqsService.sendMessage(sqsUrlFromArn(destination.arn()), testEvent, 0,
                                destination.region());
                    } else if ("sns".equals(destination.service())) {
                        snsService.publish(destination.arn(), null, testEvent, "Amazon S3 Notification",
                                destination.region());
                    }
                } catch (AwsException e) {
                    failures.add(new DestinationFailure(destination, e.getMessage()));
                }
            }
            if (!failures.isEmpty()) {
                throw invalidNotificationDestinations(failures);
            }
        }

        synchronized (bucket) {
            requireSameRecord(bucketName, bucket);
            bucket.setNotificationConfiguration(config);
            bucketStore.put(bucketName, bucket);
        }
        LOG.infov("Set notification configuration for bucket: {0}", bucketName);
    }

    private record NotificationDestination(String arn, String service, String region, String accountId) {}

    private record DestinationFailure(NotificationDestination destination, String reason) {}

    private static List<NotificationDestination> notificationDestinations(NotificationConfiguration config) {
        List<NotificationDestination> destinations = new ArrayList<>();
        for (QueueNotification queue : config.getQueueConfigurations()) {
            destinations.add(notificationDestination(queue.queueArn(), "Queue", "sqs"));
        }
        for (TopicNotification topic : config.getTopicConfigurations()) {
            destinations.add(notificationDestination(topic.topicArn(), "Topic", "sns"));
        }
        for (LambdaNotification lambda : config.getLambdaFunctionConfigurations()) {
            destinations.add(notificationDestination(lambda.functionArn(), "CloudFunction", "lambda"));
        }
        return destinations;
    }

    private static NotificationDestination notificationDestination(String arn, String argumentName,
                                                                   String service) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw invalidNotificationArn(arn, argumentName);
        }
        boolean validResource = switch (service) {
            case "sqs" -> parsed.resource().matches("[A-Za-z0-9_-]{1,80}")
                    || parsed.resource().matches("[A-Za-z0-9_-]{1,75}\\.fifo");
            case "sns" -> parsed.resource().matches("[A-Za-z0-9_-]{1,256}")
                    || parsed.resource().matches("[A-Za-z0-9_-]{1,251}\\.fifo");
            case "lambda" -> parsed.resource().matches(
                    "function:[A-Za-z0-9_-]{1,64}(?::[A-Za-z0-9_$-]{1,128})?");
            default -> false;
        };
        if (!parsed.partition().matches(AwsArnUtils.PARTITION_REGEX)
                || !parsed.service().equals(service)
                || !parsed.region().matches("[a-z0-9-]+")
                || !parsed.accountId().matches("[0-9]{12}")
                || !validResource
                || AwsRegions.isRegionId(parsed.region())
                        && !parsed.partition().equals(AwsRegions.partitionFor(parsed.region()))) {
            throw invalidNotificationArn(arn, argumentName);
        }
        return new NotificationDestination(arn, service, parsed.region(), parsed.accountId());
    }

    private static AwsException invalidNotificationArn(String arn, String argumentName) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ArgumentName", argumentName);
        details.put("ArgumentValue", arn);
        return new AwsException("InvalidArgument", "The ARN could not be parsed", 400, details);
    }

    private boolean notificationDestinationExists(NotificationDestination destination) {
        return switch (destination.service()) {
            case "sqs" -> sqsService != null
                    && sqsService.queueExists(sqsUrlFromArn(destination.arn()), destination.region());
            case "sns" -> snsService != null && snsService.topicExists(destination.arn(), destination.region());
            case "lambda" -> lambdaNotificationDestinationExists(destination);
            default -> false;
        };
    }

    private boolean lambdaNotificationDestinationExists(NotificationDestination destination) {
        LambdaService service = resolveLambdaService();
        if (service == null) {
            return false;
        }
        try {
            LambdaFunction function = service.getFunction(destination.region(), destination.arn(), null);
            return destination.accountId().equals(AwsArnUtils.accountOrDefault(function.getFunctionArn(), null));
        } catch (AwsException e) {
            LOG.debugv("Lambda notification destination {0} is unavailable: {1} ({2})",
                    destination.arn(), e.getErrorCode(), e.getMessage());
            return false;
        }
    }

    private static DestinationFailure missingDestination(NotificationDestination destination) {
        String reason = switch (destination.service()) {
            case "sqs" -> "The destination queue does not exist";
            case "sns" -> "The destination topic does not exist";
            case "lambda" -> "Not authorized to invoke function [" + destination.arn() + "]";
            default -> "The destination does not exist";
        };
        return new DestinationFailure(destination, reason);
    }

    private static AwsException invalidNotificationDestinations(List<DestinationFailure> failures) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int index = 0; index < failures.size(); index++) {
            DestinationFailure failure = failures.get(index);
            NotificationDestination destination = failure.destination();
            String argument = "lambda".equals(destination.service())
                    ? destination.arn() + ", null" : destination.arn();
            details.put("ArgumentName" + (index + 1), argument);
            details.put("ArgumentValue" + (index + 1), failure.reason());
        }
        return new AwsException("InvalidArgument",
                "Unable to validate the following destination configurations", 400, details);
    }

    private String s3TestEvent(String bucketName) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("Service", "Amazon S3");
        event.put("Event", "s3:TestEvent");
        event.put("Time", Instant.now().toString());
        event.put("Bucket", bucketName);
        event.put("RequestId", UUID.randomUUID().toString());
        event.put("HostId", UUID.randomUUID().toString());
        return event.toString();
    }

    public NotificationConfiguration getBucketNotificationConfiguration(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        NotificationConfiguration config = bucket.getNotificationConfiguration();
        return config != null ? config : new NotificationConfiguration();
    }

    // ──────────────────────────── Policy, CORS, Lifecycle, ACL ────────────────────────────

    public record BucketPolicyInfo(String policy, String ownerAccountId) {}

    public Optional<BucketPolicyInfo> findBucketPolicyInfo(String bucketName) {
        return resolveBucketEntry(bucketName)
                .map(entry -> new BucketPolicyInfo(entry.value().getPolicy(), entry.account()));
    }

    public Optional<String> findBucketPolicy(String bucketName) {
        return findBucketPolicyInfo(bucketName)
                .map(BucketPolicyInfo::policy)
                .filter(policy -> policy != null && !policy.isBlank());
    }

    public String getBucketPolicy(String bucketName) {
        Bucket bucket = resolveBucket(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getPolicy() == null) {
            throw new AwsException("NoSuchBucketPolicy", "The bucket policy does not exist", 404);
        }
        return bucket.getPolicy();
    }

    /**
     * {@code BlockPublicPolicy} rejects a bucket policy that grants public access. As on AWS the
     * check runs whoever the caller is, so it does not sit behind {@code enforce-auth}, and it
     * leaves an already-stored public policy alone: the setting blocks the write, not the read.
     */
    public void putBucketPolicy(String bucketName, String policy) {
        if (effectiveBlockPublicAccess(bucketName).blockPublicPolicy()
                && S3PublicAccessEvaluator.policyIsPublic(objectMapper, policy)) {
            LOG.debugv("BlockPublicPolicy rejected a public bucket policy on bucket {0}", bucketName);
            throw accessDeniedException(bucketName, null);
        }
        mutateBucket(bucketName, bucket -> bucket.setPolicy(policy));
    }

    public void deleteBucketPolicy(String bucketName) {
        mutateBucket(bucketName, bucket -> bucket.setPolicy(null));
    }

    public String getBucketCors(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getCorsConfiguration() == null) {
            throw new AwsException("NoSuchCORSConfiguration", "The CORS configuration does not exist", 404);
        }
        return bucket.getCorsConfiguration();
    }

    public record CorsEvalResult(
        String allowedOrigin,
        List<String> allowedMethods,
        List<String> allowedHeaders,
        List<String> exposeHeaders,
        int maxAgeSeconds
    ) {}

    /**
     * Evaluates a CORS request (preflight or actual) against the bucket's CORS configuration.
     *
     * @param bucketName     the bucket to check
     * @param origin         the Origin header value from the browser request
     * @param requestMethod  the Access-Control-Request-Method (for preflight) or the HTTP method (for actual requests)
     * @param requestHeaders the Access-Control-Request-Headers values (may be empty for actual requests)
     * @return the matching CORS rule details, or empty if no rule matches
     */
    public Optional<CorsEvalResult> evaluateCors(String bucketName, String origin,
                                                  String requestMethod, List<String> requestHeaders) {
        Bucket bucket = bucketStore.get(bucketName).orElse(null);
        if (bucket == null || bucket.getCorsConfiguration() == null) return Optional.empty();

        String corsXml = bucket.getCorsConfiguration();
        List<Map<String, List<String>>> rules = XmlParser.extractGroupsMulti(corsXml, "CORSRule");

        for (Map<String, List<String>> rule : rules) {
            List<String> allowedOrigins = rule.getOrDefault("AllowedOrigin", List.of());
            List<String> allowedMethods = rule.getOrDefault("AllowedMethod", List.of());
            List<String> allowedHeaders = rule.getOrDefault("AllowedHeader", List.of());
            List<String> exposeHeaders  = rule.getOrDefault("ExposeHeader",  List.of());
            List<String> maxAgeList     = rule.getOrDefault("MaxAgeSeconds", List.of());
            int maxAge = 0;
            if (!maxAgeList.isEmpty()) {
                String maxAgeRaw = maxAgeList.get(0);
                if (maxAgeRaw != null) {
                    String trimmed = maxAgeRaw.trim();
                    if (!trimmed.isEmpty()) {
                        try {
                            maxAge = Integer.parseInt(trimmed);
                        } catch (NumberFormatException ignored) {
                            // Treat invalid MaxAgeSeconds as no max-age (equivalent to 0)
                        }
                    }
                }
            }

            boolean originMatches = allowedOrigins.contains("*")
                || (origin != null && allowedOrigins.stream().anyMatch(ao -> matchesCorsOrigin(ao, origin)));
            if (!originMatches) continue;

            if (requestMethod != null
                    && allowedMethods.stream().noneMatch(m -> m.equalsIgnoreCase(requestMethod))) continue;

            if (requestHeaders != null && !requestHeaders.isEmpty()) {
                boolean headersOk = allowedHeaders.contains("*")
                    || requestHeaders.stream().allMatch(rh ->
                        allowedHeaders.stream().anyMatch(ah -> ah.equalsIgnoreCase(rh)));
                if (!headersOk) continue;
            }

            String echoOrigin = allowedOrigins.contains("*") ? "*" : origin;
            return Optional.of(new CorsEvalResult(echoOrigin, allowedMethods, allowedHeaders, exposeHeaders, maxAge));
        }
        return Optional.empty();
    }

    /**
     * Matches an AllowedOrigin pattern against a concrete Origin header value.
     *
     * <p>AWS S3 CORS allows at most one {@code *} wildcard anywhere in the pattern
     * (e.g. {@code *}, {@code http://*.example.com}, {@code http://app-*.example.com}).
     * The {@code *} matches zero or more characters at that position in the origin string.
     * The concrete Origin is always treated as an exact scheme+host+port string.
     */
    private static boolean matchesCorsOrigin(String pattern, String origin) {
        if ("*".equals(pattern)) return true;
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(origin);
        }
        // Single wildcard: split into prefix and suffix around the '*'
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        // The wildcard may match zero or more characters, so the origin must be at
        // least as long as prefix+suffix combined (no overlap allowed).
        return origin.length() >= prefix.length() + suffix.length()
                && origin.startsWith(prefix)
                && origin.endsWith(suffix);
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

    public static final String DEFAULT_TRANSITION_DEFAULT_MIN_OBJECT_SIZE = "all_storage_classes_128K";

    public record LifecycleConfigurationResult(String xml, String transitionDefaultMinimumObjectSize) {}

    public LifecycleConfigurationResult getBucketLifecycle(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getLifecycleConfiguration() == null) {
            throw new AwsException("NoSuchLifecycleConfiguration", "The lifecycle configuration does not exist", 404);
        }
        String size = bucket.getTransitionDefaultMinimumObjectSize();
        if (size == null) {
            size = DEFAULT_TRANSITION_DEFAULT_MIN_OBJECT_SIZE;
        }
        return new LifecycleConfigurationResult(bucket.getLifecycleConfiguration(), size);
    }

    public String putBucketLifecycle(String bucketName, String lifecycle, String transitionDefaultMinimumObjectSize) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setLifecycleConfiguration(lifecycle);
        String size = (transitionDefaultMinimumObjectSize == null || transitionDefaultMinimumObjectSize.isBlank())
                ? DEFAULT_TRANSITION_DEFAULT_MIN_OBJECT_SIZE
                : transitionDefaultMinimumObjectSize;
        bucket.setTransitionDefaultMinimumObjectSize(size);
        bucketStore.put(bucketName, bucket);
        return size;
    }

    public void deleteBucketLifecycle(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setLifecycleConfiguration(null);
        bucket.setTransitionDefaultMinimumObjectSize(null);
        bucketStore.put(bucketName, bucket);
    }

    public String getBucketAcl(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        return bucket.getAcl() != null ? bucket.getAcl() : defaultAclXml(ownerId(), DEFAULT_OWNER_DISPLAY_NAME);
    }

    public void putBucketAcl(String bucketName, String bodyAcl, String cannedAcl, String grantRead,
                              String grantWrite, String grantFullControl, String grantReadAcp, String grantWriteAcp) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        String resolvedAcl = resolveObjectAclXml(
                cannedAcl, grantRead, grantWrite, grantFullControl, grantReadAcp, grantWriteAcp);
        String newAcl = resolvedAcl != null ? resolvedAcl : (bodyAcl.isBlank() ? null : bodyAcl);
        rejectPublicAclWhenBlocked(bucketName, newAcl);
        bucket.setAcl(newAcl);
        bucketStore.put(bucketName, bucket);
    }

    public String getObjectAcl(String bucketName, String key, String versionId) {
        AccountAwareStorageBackend.OwnedEntry<S3Object> ownedObject =
                getStoredObjectEntry(bucketName, key, versionId);
        S3Object obj = ownedObject.value();
        return obj.getAcl() != null
                ? obj.getAcl()
                : defaultAclXml(ownedObject.account(), DEFAULT_OWNER_DISPLAY_NAME);
    }

    public void putObjectAcl(String bucketName, String key, String versionId, String bodyAcl, String cannedAcl,
                              String grantRead, String grantWrite, String grantFullControl,
                              String grantReadAcp, String grantWriteAcp) {
        AccountAwareStorageBackend.OwnedEntry<S3Object> ownedObject =
                getStoredObjectEntry(bucketName, key, versionId);
        S3Object obj = ownedObject.value();
        String resolvedAcl = resolveObjectAclXml(ownedObject.account(), cannedAcl, grantRead,
                grantWrite, grantFullControl, grantReadAcp, grantWriteAcp);
        String newAcl = resolvedAcl != null ? resolvedAcl : (bodyAcl.isBlank() ? null : bodyAcl);
        rejectPublicAclWhenBlocked(bucketName, newAcl);
        obj.setAcl(newAcl);
        putObjectMetadataForAccount(ownedObject.account(), bucketName, key, obj);
    }

    /**
     * Returns the bucket's server-side encryption configuration as XML.
     * <p>
     * Buckets that have
     * never been configured return the AWS default (SSE-S3 / {@code AES256});
     * since January 2023 AWS applies SSE-S3 as the base level of encryption on
     * every bucket and never returns 404 for {@code GetBucketEncryption}.
     */
    public String getBucketEncryption(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getEncryptionConfiguration() == null) {
            return new XmlBuilder()
                    .start("ServerSideEncryptionConfiguration", AwsNamespaces.S3)
                      .start("Rule")
                        .start("ApplyServerSideEncryptionByDefault")
                          .elem("SSEAlgorithm", "AES256")
                        .end("ApplyServerSideEncryptionByDefault")
                        .elem("BucketKeyEnabled", "false")
                      .end("Rule")
                    .end("ServerSideEncryptionConfiguration")
                    .build();
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

    public String getPublicAccessBlock(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getPublicAccessBlockConfiguration() == null) {
            throw new AwsException("NoSuchPublicAccessBlockConfiguration",
                    "The public access block configuration was not found", 404);
        }
        return bucket.getPublicAccessBlockConfiguration();
    }

    public void putPublicAccessBlock(String bucketName, String xml) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setPublicAccessBlockConfiguration(xml);
        bucketStore.put(bucketName, bucket);
    }

    public void deletePublicAccessBlock(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setPublicAccessBlockConfiguration(null);
        bucketStore.put(bucketName, bucket);
    }

    /**
     * The Block Public Access settings in force for a bucket: its own configuration combined with
     * the bucket owner account's, most restrictive wins. A bucket that does not resolve blocks
     * nothing; the caller is about to fail on NoSuchBucket anyway.
     */
    private S3BlockPublicAccessSettings effectiveBlockPublicAccess(String bucketName) {
        return resolveBucketEntry(bucketName)
                .map(owned -> blockPublicAccessFor(owned.value(), owned.account()))
                .orElse(S3BlockPublicAccessSettings.NONE);
    }

    private S3BlockPublicAccessSettings blockPublicAccessFor(Bucket bucket, String bucketOwnerAccount) {
        return S3BlockPublicAccessSettings.parse(bucket.getPublicAccessBlockConfiguration())
                .mostRestrictive(accountBlockPublicAccess(bucketOwnerAccount));
    }

    private S3BlockPublicAccessSettings accountBlockPublicAccess(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return S3BlockPublicAccessSettings.NONE;
        }
        return accountPublicAccessBlockStore
                .getForAccount(accountId, ACCOUNT_PUBLIC_ACCESS_BLOCK_KEY)
                .map(S3BlockPublicAccessSettings::parse)
                .orElse(S3BlockPublicAccessSettings.NONE);
    }

    /**
     * {@code BlockPublicAcls} rejects the write that would store a public ACL. AWS applies this
     * whoever the caller is, so it does not sit behind {@code enforce-auth}: the call fails the
     * same way for a signed and an unsigned caller.
     */
    private void rejectPublicAclWhenBlocked(String bucketName, String acl) {
        if (acl == null || !S3AclPublicAccessEvaluator.aclIsPublic(acl)) {
            return;
        }
        if (effectiveBlockPublicAccess(bucketName).blockPublicAcls()) {
            LOG.debugv("BlockPublicAcls rejected a public ACL on bucket {0}", bucketName);
            throw accessDeniedException(bucketName, null);
        }
    }

    // --- Account-level (S3 Control) Public Access Block ---
    // AWS s3control PutPublicAccessBlock / GetPublicAccessBlock / DeletePublicAccessBlock,
    // keyed by AccountId (from the x-amz-account-id header). AWS LZA's
    // Custom::PutPublicAccessBlock custom resource drives these during the LoggingStack deploy.
    // S3ControlController checks the header against the caller before any of these run.

    public void putAccountPublicAccessBlock(String accountId, String configXml) {
        accountPublicAccessBlockStore.putForAccount(
                requireAccountId(accountId), ACCOUNT_PUBLIC_ACCESS_BLOCK_KEY, configXml);
    }

    public String getAccountPublicAccessBlock(String accountId) {
        return accountPublicAccessBlockStore
                .getForAccount(requireAccountId(accountId), ACCOUNT_PUBLIC_ACCESS_BLOCK_KEY)
                .orElseThrow(() -> new AwsException("NoSuchPublicAccessBlockConfiguration",
                        "The public access block configuration was not found", 404));
    }

    public void deleteAccountPublicAccessBlock(String accountId) {
        accountPublicAccessBlockStore.deleteForAccount(
                requireAccountId(accountId), ACCOUNT_PUBLIC_ACCESS_BLOCK_KEY);
    }

    /** The s3control {@code AccountId} shape: {@code pattern ^\d{12}$}. */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("\\d{12}");

    /**
     * The account id is the storage partition key for every account-level Block Public Access
     * operation, so a value outside the modelled shape would file a security configuration under
     * a partition no account can address again. Reject it before it reaches the store.
     */
    private static String requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new AwsException("InvalidRequest",
                    "The x-amz-account-id header is required.", 400);
        }
        if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
            throw new AwsException("InvalidRequest",
                    "The x-amz-account-id header must be a 12-digit AWS account ID.", 400);
        }
        return accountId;
    }

    public String getBucketOwnershipControls(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getOwnershipControlsConfiguration() == null) {
            throw new AwsException("OwnershipControlsNotFoundError",
                    "The bucket ownership controls were not found.", 404);
        }
        return bucket.getOwnershipControlsConfiguration();
    }

    public void putBucketOwnershipControls(String bucketName, String ownershipControlsXml) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setOwnershipControlsConfiguration(ownershipControlsXml);
        bucketStore.put(bucketName, bucket);
    }

    public void deleteBucketOwnershipControls(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        bucket.setOwnershipControlsConfiguration(null);
        bucketStore.put(bucketName, bucket);
    }

    public String getBucketReplication(String bucketName) {
        Bucket bucket = resolveBucket(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (bucket.getReplicationConfiguration() == null) {
            throw new AwsException("ReplicationConfigurationNotFoundError",
                    "The replication configuration was not found", 404);
        }
        return bucket.getReplicationConfiguration();
    }

    /**
     * Stores the bucket replication configuration for round-trip fidelity. The document is
     * validated minimally (a {@code Role} and at least one {@code Rule} with a
     * {@code Destination/Bucket}) and stored verbatim; Floci performs no actual replication.
     */
    public void putBucketReplication(String bucketName, String replicationXml) {
        mutateBucket(bucketName, bucket -> {
            if (!"ReplicationConfiguration".equals(XmlParser.rootElementName(replicationXml))) {
                throw new AwsException("MalformedXML",
                        "The XML you provided was not well-formed or did not validate against our published schema.",
                        400);
            }
            String role = XmlParser.extractFirst(replicationXml, "Role", null);
            List<Map<String, List<String>>> rules = XmlParser.extractGroupsMulti(replicationXml, "Rule");
            // A document-wide count of Rule vs Destination elements can't tell a well-formed
            // document from one where a rule has two Destinations and another has none (the
            // totals still balance). Destination is a direct child of Rule, so walking the parsed
            // element tree and inspecting each Rule's own children is the only way to require
            // that every rule carries exactly its own destination.
            XmlParser.XmlElement root = XmlParser.extractElementTree(replicationXml, "ReplicationConfiguration");
            List<XmlParser.XmlElement> ruleElements = root == null
                    ? List.of()
                    : root.children().stream().filter(c -> "Rule".equals(c.name())).toList();
            if (role == null || role.isBlank() || ruleElements.isEmpty()) {
                throw new AwsException("MalformedXML",
                        "The XML you provided was not well-formed or did not validate against our published schema.",
                        400);
            }
            // ReplicationRule/Status is Required: Yes with enum Enabled|Disabled. Storing an
            // out-of-enum status would have GetBucketReplication echo back a document AWS
            // would never have accepted.
            for (Map<String, List<String>> rule : rules) {
                List<String> statuses = rule.getOrDefault("Status", List.of());
                if (statuses.size() != 1 || !REPLICATION_RULE_STATUSES.contains(statuses.get(0))) {
                    throw new AwsException("MalformedXML",
                            "The XML you provided was not well-formed or did not validate against "
                                    + "our published schema.", 400);
                }
            }
            // Destination is Required: Yes on Rule, and ReplicationRuleAndOperator/Destination.Bucket
            // is Required: Yes on Destination — checked against each rule's own children, not the
            // document as a whole.
            for (XmlParser.XmlElement ruleElement : ruleElements) {
                List<XmlParser.XmlElement> ruleDestinations = ruleElement.children().stream()
                        .filter(c -> "Destination".equals(c.name())).toList();
                // Bucket is a required *scalar* member of Destination (botocore
                // s3/2006-03-01/service-2.json: "Bucket":{"shape":"BucketName"}), not a list.
                // child("Bucket") returns only the first match, so a Destination with two Bucket
                // elements must be counted explicitly rather than silently accepting the first.
                List<XmlParser.XmlElement> bucketElements = ruleDestinations.size() == 1
                        ? ruleDestinations.get(0).children().stream()
                                .filter(c -> "Bucket".equals(c.name())).toList()
                        : List.of();
                XmlParser.XmlElement bucketElement = bucketElements.size() == 1
                        ? bucketElements.get(0) : null;
                if (bucketElement == null || bucketElement.text().isBlank()) {
                    throw new AwsException("MalformedXML",
                            "The XML you provided was not well-formed or did not validate against "
                                    + "our published schema.", 400);
                }
            }
            bucket.setReplicationConfiguration(replicationXml);
        });
    }

    /** The {@code ReplicationRuleStatus} enum from the S3 model. */
    private static final Set<String> REPLICATION_RULE_STATUSES = Set.of("Enabled", "Disabled");

    public void deleteBucketReplication(String bucketName) {
        mutateBucket(bucketName, bucket -> bucket.setReplicationConfiguration(null));
    }

    /**
     * Stores the bucket Request Payment configuration. AWS only allows the values
     * {@code BucketOwner} and {@code Requester}; we accept either and reject anything
     * else with {@code MalformedXML} to match the real S3 behavior.
     */
    public void putBucketRequestPayment(String bucketName, String requestPaymentXml) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        String payer = XmlParser.extractFirst(requestPaymentXml, "Payer", null);
        if (payer == null) {
            throw new AwsException("MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema.",
                    400);
        }
        payer = payer.trim();
        if (!"BucketOwner".equals(payer) && !"Requester".equals(payer)) {
            throw new AwsException("MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema.",
                    400);
        }
        bucket.setRequestPaymentPayer(payer);
        bucketStore.put(bucketName, bucket);
    }

    /**
     * Returns the bucket Request Payment configuration as XML. Buckets that have
     * never been configured return the AWS default ({@code BucketOwner}); this matches
     * real S3, which never returns 404 for {@code GetBucketRequestPayment}.
     */
    public String getBucketRequestPayment(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        String payer = bucket.getRequestPaymentPayer() != null ? bucket.getRequestPaymentPayer() : "BucketOwner";
        return new XmlBuilder()
                .start("RequestPaymentConfiguration", AwsNamespaces.S3)
                .elem("Payer", payer)
                .end("RequestPaymentConfiguration")
                .build();
    }

    /**
     * Stores the bucket Transfer Acceleration state. The AccelerateConfiguration root
     * is required, so a body that does not parse to one is rejected with
     * {@code MalformedXML}; the Status element inside it is optional in the AWS schema,
     * so a configuration without one is accepted and leaves the stored state unchanged.
     * AWS only allows the values {@code Enabled} and {@code Suspended}.
     */
    public void putBucketAccelerateConfiguration(String bucketName, String accelerateXml) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        if (!"AccelerateConfiguration".equals(XmlParser.rootElementName(accelerateXml))) {
            throw new AwsException("MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema.",
                    400);
        }
        String status = XmlParser.extractFirst(accelerateXml, "Status", null);
        if (status == null) {
            return;
        }
        status = status.trim();
        if (!"Enabled".equals(status) && !"Suspended".equals(status)) {
            throw new AwsException("MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema.",
                    400);
        }
        bucket.setAccelerateStatus(status);
        bucketStore.put(bucketName, bucket);
    }

    /**
     * Returns the bucket Transfer Acceleration state as XML. A bucket that has never
     * been configured returns an {@code AccelerateConfiguration} with no Status element
     * rather than an error, matching real S3.
     */
    public String getBucketAccelerateConfiguration(String bucketName) {
        Bucket bucket = bucketStore.get(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));
        return new XmlBuilder()
                .start("AccelerateConfiguration", AwsNamespaces.S3)
                .elem("Status", bucket.getAccelerateStatus())
                .end("AccelerateConfiguration")
                .build();
    }

    public void restoreObject(String bucketName, String key, String versionId, String restoreXml) {
        // Validation only - stub implementation
        getObject(bucketName, key, versionId);
        LOG.infov("Restored object: {0}/{1} (stub)", bucketName, key);
    }

    private static String defaultAclXml(String id, String displayName) {
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

    /**
     * Resolves the ACL to store for an object/bucket from a canned ACL and/or the explicit
     * ACL grant headers (x-amz-grant-read, x-amz-grant-write, x-amz-grant-full-control,
     * x-amz-grant-read-acp, x-amz-grant-write-acp). If both are somehow present, the canned ACL
     * takes precedence - real S3 instead rejects that combination with 400 "Conflicting header
     * values", but Floci doesn't model that validation yet. Returns null if neither is set, so
     * callers can fall back to a pre-existing ACL (e.g. an explicit AccessControlPolicy XML body).
     */
    String resolveObjectAclXml(String cannedAcl, String grantRead, String grantWrite,
                                String grantFullControl, String grantReadAcp, String grantWriteAcp) {
        return resolveObjectAclXml(ownerId(), cannedAcl, grantRead, grantWrite,
                grantFullControl, grantReadAcp, grantWriteAcp);
    }

    private String resolveObjectAclXml(String ownerAccount, String cannedAcl,
                                       String grantRead, String grantWrite,
                                       String grantFullControl, String grantReadAcp,
                                       String grantWriteAcp) {
        if (cannedAcl != null && !cannedAcl.isBlank()) {
            return cannedObjectAclXml(ownerAccount, cannedAcl);
        }
        if (isBlank(grantRead) && isBlank(grantWrite) && isBlank(grantFullControl)
                && isBlank(grantReadAcp) && isBlank(grantWriteAcp)) {
            return null;
        }
        List<String> grants = new ArrayList<>();
        grants.add(ownerFullControlGrant(ownerAccount));
        appendGrantHeader(grants, grantRead, "READ");
        appendGrantHeader(grants, grantWrite, "WRITE");
        appendGrantHeader(grants, grantFullControl, "FULL_CONTROL");
        appendGrantHeader(grants, grantReadAcp, "READ_ACP");
        appendGrantHeader(grants, grantWriteAcp, "WRITE_ACP");
        return objectAclXml(ownerAccount, grants.toArray(new String[0]));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Matches a single grantee token from an x-amz-grant-* header value, e.g.
    // uri="http://acs.amazonaws.com/groups/global/AllUsers" or id="<canonical-id>". AWS allows
    // a comma-separated list of these per header.
    private static final Pattern GRANTEE_TOKEN_PATTERN = Pattern.compile("(uri|id|emailAddress)=\"([^\"]*)\"");

    private void appendGrantHeader(List<String> grants, String headerValue, String permission) {
        if (isBlank(headerValue)) {
            return;
        }
        Matcher matcher = GRANTEE_TOKEN_PATTERN.matcher(headerValue);
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            grants.add(granteeGrant(matcher.group(1), matcher.group(2), permission));
        }
        if (!matched) {
            throw new AwsException("InvalidArgument", "Malformed ACL grant header: " + headerValue, 400);
        }
    }

    private String granteeGrant(String granteeType, String granteeValue, String permission) {
        return switch (granteeType) {
            case "uri" -> groupGrant(granteeValue, permission);
            // Floci has no directory of external accounts, so an explicit CanonicalUser grant is
            // stored using the caller-supplied ID verbatim as both ID and display name.
            case "id" -> canonicalUserGrant(granteeValue, granteeValue, permission);
            default -> throw new AwsException("NotImplemented",
                    "Explicit ACL grants by emailAddress are not supported.", 501);
        };
    }

    String cannedObjectAclXml(String cannedAcl) {
        return cannedObjectAclXml(ownerId(), cannedAcl);
    }

    private String cannedObjectAclXml(String ownerAccount, String cannedAcl) {
        if (cannedAcl == null || cannedAcl.isBlank()) {
            return null;
        }
        return switch (cannedAcl) {
            case "private", "bucket-owner-read", "bucket-owner-full-control" ->
                    defaultAclXml(ownerAccount, DEFAULT_OWNER_DISPLAY_NAME);
            // Floci currently runs as a single synthetic account, so there is no distinct EC2 bundle-reader
            // principal to represent in GetObjectAcl responses yet.
            case "aws-exec-read" -> defaultAclXml(ownerAccount, DEFAULT_OWNER_DISPLAY_NAME);
            case "public-read" -> objectAclXml(ownerAccount,
                    ownerFullControlGrant(ownerAccount),
                    groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "READ"));
            case "public-read-write" -> objectAclXml(ownerAccount,
                    ownerFullControlGrant(ownerAccount),
                    groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "READ"),
                    groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "WRITE"));
            case "authenticated-read" -> objectAclXml(ownerAccount,
                    ownerFullControlGrant(ownerAccount),
                    groupGrant(AUTHENTICATED_USERS_GROUP_URI, "READ"));
            // Standard canned ACL used by S3 server-access-logging (and Terraform's
            // aws_s3_bucket_acl / access-logging modules) to grant the S3 log-delivery service
            // group permission to write log objects into this bucket and read their own ACL.
            case "log-delivery-write" -> objectAclXml(ownerAccount,
                    ownerFullControlGrant(ownerAccount),
                    groupGrant(LOG_DELIVERY_GROUP_URI, "WRITE"),
                    groupGrant(LOG_DELIVERY_GROUP_URI, "READ_ACP"));
            default -> throw new AwsException("InvalidArgument",
                    "Unsupported x-amz-acl value: " + cannedAcl, 400);
        };
    }

    static String normalizeServerSideEncryption(String serverSideEncryption) {
        if (serverSideEncryption == null) {
            return null;
        }

        String normalized = serverSideEncryption.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        if (!SUPPORTED_SERVER_SIDE_ENCRYPTION_VALUES.contains(normalized)) {
            throw new AwsException("InvalidArgument",
                    "Unsupported x-amz-server-side-encryption value: " + normalized, 400);
        }

        return normalized;
    }

    static SseCustomerKey validateSseCustomerKey(String algorithm, String key, String keyMd5) {
        boolean hasAnySseCustomerHeader = hasText(algorithm) || hasText(key) || hasText(keyMd5);
        if (!hasAnySseCustomerHeader) {
            return null;
        }
        if (!hasText(algorithm) || !hasText(key) || !hasText(keyMd5)) {
            throw new AwsException("InvalidRequest",
                    "SSE-C requests require algorithm, key, and key MD5 headers.", 400);
        }
        String normalizedAlgorithm = algorithm.trim();
        if (!SSE_C_ALGORITHM.equals(normalizedAlgorithm)) {
            throw new AwsException("InvalidArgument",
                    "Unsupported x-amz-server-side-encryption-customer-algorithm value: " + normalizedAlgorithm, 400);
        }
        String normalizedKey = key.trim();
        String computedMd5 = computeSseCustomerKeyMd5(normalizedKey);
        if (!computedMd5.equals(keyMd5.trim())) {
            throw new AwsException("InvalidDigest",
                    "The x-amz-server-side-encryption-customer-key-MD5 value is invalid.", 400);
        }
        return new SseCustomerKey(normalizedAlgorithm, computedMd5);
    }

    static void validateSseCustomerAccess(S3Object object, String algorithm, String key, String keyMd5) {
        if (object.getSseCustomerAlgorithm() == null) {
            return;
        }
        SseCustomerKey requestKey = validateSseCustomerKey(algorithm, key, keyMd5);
        if (requestKey == null) {
            throw new AwsException("InvalidRequest",
                    "SSE-C encrypted objects require customer key headers.", 400);
        }
        if (!object.getSseCustomerAlgorithm().equals(requestKey.algorithm()) ||
                !object.getSseCustomerKeyMd5().equals(requestKey.keyMd5())) {
            throw new AwsException("AccessDenied",
                    "The provided SSE-C customer key does not match the object.", 403);
        }
    }

    static void validateSseCustomerAccess(MultipartUpload upload, String algorithm, String key, String keyMd5) {
        if (upload.getSseCustomerAlgorithm() == null) {
            if (hasText(algorithm) || hasText(key) || hasText(keyMd5)) {
                throw new AwsException("InvalidRequest",
                        "SSE-C headers are not valid for multipart uploads initiated without SSE-C.", 400);
            }
            return;
        }
        SseCustomerKey requestKey = validateSseCustomerKey(algorithm, key, keyMd5);
        if (requestKey == null) {
            throw new AwsException("InvalidRequest",
                    "SSE-C multipart uploads require customer key headers.", 400);
        }
        if (!upload.getSseCustomerAlgorithm().equals(requestKey.algorithm()) ||
                !upload.getSseCustomerKeyMd5().equals(requestKey.keyMd5())) {
            throw new AwsException("AccessDenied",
                    "The provided SSE-C customer key does not match the multipart upload.", 403);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void rejectConflictingServerSideEncryption(String serverSideEncryption, SseCustomerKey sseCustomerKey) {
        if (serverSideEncryption != null && sseCustomerKey != null) {
            throw new AwsException("InvalidRequest",
                    "SSE-C cannot be combined with x-amz-server-side-encryption.", 400);
        }
    }

    private static String computeSseCustomerKeyMd5(String key) {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(key.trim());
            if (decodedKey.length != SSE_C_KEY_BYTES) {
                throw new AwsException("InvalidArgument",
                        "The x-amz-server-side-encryption-customer-key must be a 256-bit key.", 400);
            }
            byte[] md5 = MessageDigest.getInstance("MD5").digest(decodedKey);
            return Base64.getEncoder().encodeToString(md5);
        }
        catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgument",
                    "The x-amz-server-side-encryption-customer-key value is not valid base64.", 400);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    record SseCustomerKey(String algorithm, String keyMd5) {}

    record SseCustomerHeaders(String algorithm, String key, String keyMd5) {
        static final SseCustomerHeaders EMPTY = new SseCustomerHeaders(null, null, null);
    }

    private static String ownerFullControlGrant(String ownerAccount) {
        return canonicalUserGrant(ownerAccount, DEFAULT_OWNER_DISPLAY_NAME, "FULL_CONTROL");
    }

    private static String canonicalUserGrant(String id, String displayName, String permission) {
        return new XmlBuilder()
                .start("Grant")
                .raw("<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">")
                .elem("ID", id)
                .elem("DisplayName", displayName)
                .raw("</Grantee>")
                .elem("Permission", permission)
                .end("Grant")
                .build();
    }

    private static String groupGrant(String uri, String permission) {
        return new XmlBuilder()
                .start("Grant")
                .raw("<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">")
                .elem("URI", uri)
                .raw("</Grantee>")
                .elem("Permission", permission)
                .end("Grant")
                .build();
    }

    private static String objectAclXml(String ownerAccount, String... grants) {
        XmlBuilder xml = new XmlBuilder()
                .start("AccessControlPolicy")
                .start("Owner")
                .elem("ID", ownerAccount)
                .elem("DisplayName", DEFAULT_OWNER_DISPLAY_NAME)
                .end("Owner")
                .start("AccessControlList");
        for (String grant : grants) {
            xml.raw(grant);
        }
        return xml.end("AccessControlList")
                .end("AccessControlPolicy")
                .build();
    }

    private void fireNotifications(String bucketName, String key, String eventName, S3Object obj) {
        if (s3UpdatedEvent != null && eventName.startsWith("ObjectCreated")) {
            s3UpdatedEvent.fire(new S3ObjectUpdatedEvent(bucketName, key));
        }
        if (sqsService == null && snsService == null && lambdaService == null
                && lambdaServiceProvider == null && lambdaInvoker == null && eventBridgeService == null) {
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

        String region = bucket.getRegion();
        String eventJson = buildS3EventJson(bucketName, key, eventName, obj, region, bucket.isVersioningEnabled());

        for (QueueNotification qn : config.getQueueConfigurations()) {
            if (qn.events().stream().anyMatch(p -> matchesEvent(p, eventName)) && qn.matchesKey(key)) {
                try {
                    sqsService.sendMessage(sqsUrlFromArn(qn.queueArn()), eventJson, 0,
                            extractRegionFromArn(qn.queueArn()));
                    LOG.debugv("Fired S3 event {0} to SQS {1}", eventName, qn.queueArn());
                } catch (Exception e) {
                    LOG.warnv("Failed to deliver S3 event to SQS {0}: {1}", qn.queueArn(), e.getMessage());
                }
            }
        }

        for (TopicNotification tn : config.getTopicConfigurations()) {
            if (tn.events().stream().anyMatch(p -> matchesEvent(p, eventName)) && tn.matchesKey(key)) {
                try {
                    snsService.publish(tn.topicArn(), null, eventJson, "Amazon S3 Notification",
                            extractRegionFromArn(tn.topicArn()));
                    LOG.debugv("Fired S3 event {0} to SNS {1}", eventName, tn.topicArn());
                } catch (Exception e) {
                    LOG.warnv("Failed to deliver S3 event to SNS {0}: {1}", tn.topicArn(), e.getMessage());
                }
            }
        }

        if (lambdaInvoker != null || resolveLambdaService() != null) {
            for (LambdaNotification ln : config.getLambdaFunctionConfigurations()) {
                if (ln.events().stream().anyMatch(p -> matchesEvent(p, eventName)) && ln.matchesKey(key)) {
                    try {
                        String lambdaRegion = extractRegionFromArn(ln.functionArn());
                        String functionName = extractLambdaFunctionName(ln.functionArn());
                        if (lambdaRegion == null || functionName == null) {
                            throw new AwsException("InvalidParameterValueException",
                                    "Invalid Lambda function ARN: " + ln.functionArn(), 400);
                        }
                        invokeLambda(lambdaRegion, functionName, eventJson.getBytes(StandardCharsets.UTF_8));
                        LOG.debugv("Fired S3 event {0} to Lambda {1}", eventName, ln.functionArn());
                    } catch (Exception e) {
                        LOG.warnv("Failed to deliver S3 event to Lambda {0}: {1}", ln.functionArn(), e.getMessage());
                    }
                }
            }
        }

        if (config.isEventBridgeEnabled() && eventBridgeService != null) {
            try {
                String detailType = eventName.startsWith("ObjectCreated") ? "Object Created"
                        : eventName.startsWith("ObjectAnnotation") ? "Object Annotation"
                        : "Object Deleted";
                Map<String, Object> entry = new java.util.HashMap<>();
                entry.put("Source", "aws.s3");
                entry.put("DetailType", detailType);
                entry.put("Detail", buildS3EventBridgeDetail(bucketName, key, eventName, obj, region));
                eventBridgeService.putEvents(List.of(entry), region);
                LOG.debugv("Fired S3 event {0} to EventBridge default bus", eventName);
            } catch (Exception e) {
                LOG.warnv("Failed to deliver S3 event to EventBridge: {0}", e.getMessage());
            }
        }
    }

    private String buildS3EventBridgeDetail(String bucketName, String key, String eventName,
                                            S3Object obj, String region) {
        try {
            long size = obj != null ? obj.getSize() : 0;
            String eTag = obj != null && obj.getETag() != null ? obj.getETag().replace("\"", "") : "";
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("version", "0");
            ObjectNode bucketNode = detail.putObject("bucket");
            bucketNode.put("name", bucketName);
            ObjectNode objectNode = detail.putObject("object");
            objectNode.put("key", key);
            objectNode.put("size", size);
            objectNode.put("etag", eTag);
            if (obj != null && obj.getVersionId() != null) {
                objectNode.put("version-id", obj.getVersionId());
            }
            detail.put("request-id", UUID.randomUUID().toString());
            detail.put("requester", "aws:emulator");
            detail.put("source-ip-address", "127.0.0.1");
            detail.put("reason", eventName);
            String deletionType = eventBridgeDeletionType(eventName);
            if (deletionType != null) {
                detail.put("deletion-type", deletionType);
            }
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String eventBridgeDeletionType(String eventName) {
        return switch (eventName) {
            case "ObjectRemoved:Delete" -> "Permanently Deleted";
            case "ObjectRemoved:DeleteMarkerCreated" -> "Delete Marker Created";
            default -> null;
        };
    }

    private boolean matchesEvent(String pattern, String eventName) {
        String full = "s3:" + eventName;
        if (pattern.endsWith("*")) {
            return full.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return full.equals(pattern);
    }

    private String sqsUrlFromArn(String arn) {
        try {
            return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
        } catch (IllegalArgumentException e) {
            return arn;
        }
    }

    private static String extractRegionFromArn(String arn) {
        return AwsArnUtils.regionOrDefault(arn, null);
    }

    private static String extractLambdaFunctionName(String functionArn) {
        if (functionArn == null) {
            return null;
        }
        int functionMarker = functionArn.indexOf(":function:");
        if (functionMarker < 0) {
            return null;
        }
        return functionArn.substring(functionMarker + ":function:".length());
    }

    private LambdaService resolveLambdaService() {
        if (lambdaService != null) {
            return lambdaService;
        }
        if (lambdaServiceProvider != null && lambdaServiceProvider.isResolvable()) {
            return lambdaServiceProvider.get();
        }
        return null;
    }

    private void invokeLambda(String region, String functionName, byte[] payload) {
        if (lambdaInvoker != null) {
            lambdaInvoker.invoke(region, functionName, payload, InvocationType.Event);
            return;
        }
        LambdaService service = resolveLambdaService();
        if (service != null) {
            service.invoke(region, functionName, payload, InvocationType.Event);
        }
    }

    private String buildS3EventJson(String bucketName, String key, String eventName,
                                    S3Object obj, String region, boolean isVersionEnabled) {
        try {
            String eventTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            long size = obj != null ? obj.getSize() : 0;
            String eTag = obj != null && obj.getETag() != null ? obj.getETag().replace("\"", "") : "";
            String requestId = UUID.randomUUID().toString();

            ObjectNode bucketNode = objectMapper.createObjectNode();
            bucketNode.put("name", bucketName);
            bucketNode.put("arn", AwsArnUtils.Arn.global(bucketPartition(bucketName), "s3", "", bucketName).toString());

            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("key", key);
            objectNode.put("size", size);
            objectNode.put("eTag", eTag);
            if(isVersionEnabled) {
                String versionId = obj !=null && obj.getVersionId()!=null ? obj.getVersionId() : "";
                objectNode.put("versionId", versionId);
            }
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
        if (inMemory) {
            memoryMultipartStore.remove(uploadId);
        } else {
            deleteDirectory(dataRoot.resolve(".multipart").resolve(uploadId));
        }
    }

    private static void validateCompleteChecksumType(ChecksumAlgorithm algorithm, ChecksumType storedType,
                                                     ChecksumType requestedType) {
        if (requestedType == null) {
            return;
        }
        if (requestedType == ChecksumType.FULL_OBJECT && !algorithm.supports(ChecksumType.FULL_OBJECT)) {
            throw new AwsException("InvalidRequest",
                    "The algorithm type you specified in x-amz-checksum- header is invalid.", 400);
        }
        if (requestedType != storedType) {
            throw new AwsException("InvalidRequest",
                    "The upload was created using the " + storedType + " checksum mode. "
                            + "The complete request must use the same checksum mode.", 400);
        }
    }

    // As on S3: a COMPOSITE upload needs the checksum of every part in the request body, a checksum for
    // another algorithm is a BadDigest, a wrong value an InvalidPart. FULL_OBJECT uploads need none.
    private static void validatePartChecksum(ChecksumAlgorithm declared, ChecksumType storedType, int partNumber,
                                             Part uploaded, S3Checksum expected) {
        if (expected == null || !expected.hasAnyValue()) {
            if (declared != null && storedType == ChecksumType.COMPOSITE) {
                throw new AwsException("InvalidRequest", "The upload was created using a " + declared.wireValue()
                        + " checksum. The complete request must include the checksum for each part. It was missing for part "
                        + partNumber + " in the request.", 400);
            }
            return;
        }
        ChecksumAlgorithm algorithm = declared != null && expected.valueFor(declared) != null ? declared : expected.algorithm();
        if (declared != null && algorithm != declared) {
            throw new AwsException("BadDigest", "The " + algorithm.wireValue() + " you specified for part " + partNumber
                    + " did not match what we received.", 400);
        }
        if (!expected.valueFor(algorithm).equals(uploaded.getChecksum().valueFor(algorithm))) {
            throw new AwsException("InvalidPart",
                    "One or more of the specified parts could not be found.  The part may not have been uploaded, "
                            + "or the specified entity tag may not match the part's entity tag.", 400);
        }
    }

    // AWS accepts a composite value with or without its "-N" suffix, but a wrong suffix is a BadDigest.
    private static void validateExpectedChecksum(S3Checksum computed, S3Checksum expected) {
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            String expectedValue = expected.valueFor(algorithm);
            if (expectedValue == null) {
                continue;
            }
            String computedValue = computed.valueFor(algorithm);
            boolean matches = expectedValue.equals(computedValue)
                    || (computed.getChecksumType() == ChecksumType.COMPOSITE
                            && expectedValue.equals(S3Checksum.withoutPartCount(computedValue)));
            if (!matches) {
                throw new AwsException("BadDigest", "The " + algorithm.wireValue()
                        + " you specified did not match the calculated checksum.", 400);
            }
        }
    }

    private static S3Object copyObject(S3Object source) {
        S3Object copy = new S3Object();
        copy.setBucketName(source.getBucketName());
        copy.setKey(source.getKey());
        copy.setData(source.getData() != null ? Arrays.copyOf(source.getData(), source.getData().length) : null);
        copy.setMetadata(new HashMap<>(source.getMetadata()));
        copy.setContentType(source.getContentType());
        copy.setContentEncoding(source.getContentEncoding());
        copy.setContentDisposition(source.getContentDisposition());
        copy.setCacheControl(source.getCacheControl());
        copy.setServerSideEncryption(source.getServerSideEncryption());
        copy.setSseKmsKeyId(source.getSseKmsKeyId());
        copy.setSseCustomerAlgorithm(source.getSseCustomerAlgorithm());
        copy.setSseCustomerKeyMd5(source.getSseCustomerKeyMd5());
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
        copy.setDataGeneration(source.getDataGeneration());
        return copy;
    }

    private static S3Checksum copyChecksum(S3Checksum source) {
        return source == null ? null : source.copy();
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
        if (resolveBucket(bucketName).isEmpty()) {
            throw new AwsException("NoSuchBucket",
                    "The specified bucket does not exist.", 404);
        }
    }

    public boolean bucketExists(String bucketName) {
        return resolveBucket(bucketName).isPresent();
    }

    /**
     * Resolves a bucket for existence/access. With {@code globalBucketNamespace} enabled, the
     * lookup spans every account's partition (AWS bucket names are globally unique and reachable
     * cross-account); otherwise it stays scoped to the calling account. Write-side ownership
     * checks (CreateBucket, delete) intentionally do not use this — they remain account-scoped.
     */
    private Optional<Bucket> resolveBucket(String bucketName) {
        return resolveBucketEntry(bucketName).map(AccountAwareStorageBackend.OwnedEntry::value);
    }

    /**
     * The partition a bucket's ARN is written in: the partition of the bucket's own region, not
     * the request's. Bucket names are one namespace here, so a request signed for another
     * partition can still reach the bucket, and a policy naming it must match as written. A
     * bucket that does not exist yet takes the request's partition, where it would be created.
     */
    String bucketPartition(String bucketName) {
        return resolveBucket(bucketName)
                .map(Bucket::getRegion)
                .map(AwsRegions::partitionFor)
                .orElseGet(this::requestPartition);
    }

    private String requestPartition() {
        return regionResolver != null ? regionResolver.getPartition() : AwsRegions.partitionFor(null);
    }

    private Optional<AccountAwareStorageBackend.OwnedEntry<Bucket>> resolveBucketEntry(String bucketName) {
        if (globalBucketNamespace && bucketStore instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Bucket> typed = (AccountAwareStorageBackend<Bucket>) aware;
            return typed.findAnyAccountEntry(bucketName);
        }
        return bucketStore.get(bucketName)
                .map(bucket -> new AccountAwareStorageBackend.OwnedEntry<>(ownerId(), bucket));
    }

    /**
     * Resolves an existing bucket and applies a configuration mutation, persisting it back to the
     * bucket's <em>owning</em> account partition. With {@code globalBucketNamespace} enabled the
     * bucket is resolved cross-account (mirroring {@link #resolveBucket}) and written back to its
     * owner via {@link AccountAwareStorageBackend#putForAccount} — so a cross-account custom-resource
     * caller (e.g. LZA's {@code Custom::S3PutBucketReplication} Lambda, which calls back under the
     * management-account context) cannot fork a phantom bucket into its own partition or silently
     * drop the config on the real bucket. With the flag off this is exactly the original
     * account-scoped get-mutate-put.
     *
     * <p>The {@code mutation} runs after existence is established and before the write-back, so it
     * may perform bucket-dependent validation and throw (e.g. {@code MalformedXML}); a throw skips
     * the write, matching the original methods' fail-before-persist behavior.
     */
    private void mutateBucket(String bucketName, java.util.function.Consumer<Bucket> mutation) {
        if (globalBucketNamespace && bucketStore instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Bucket> typed = (AccountAwareStorageBackend<Bucket>) aware;
            AccountAwareStorageBackend.OwnedEntry<Bucket> owned = typed.findAnyAccountEntry(bucketName)
                    .orElseThrow(() -> new AwsException("NoSuchBucket",
                            "The specified bucket does not exist.", 404));
            mutation.accept(owned.value());
            typed.putForAccount(owned.account(), bucketName, owned.value());
        } else {
            Bucket bucket = bucketStore.get(bucketName)
                    .orElseThrow(() -> new AwsException("NoSuchBucket",
                            "The specified bucket does not exist.", 404));
            mutation.accept(bucket);
            bucketStore.put(bucketName, bucket);
        }
    }

    /**
     * Resolves an object for read access. Mirrors {@link #resolveBucket}: cross-account when
     * {@code globalBucketNamespace} is enabled, otherwise account-scoped.
     */
    private Optional<S3Object> resolveObject(String storeKey) {
        if (globalBucketNamespace && objectStore instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<S3Object> typed = (AccountAwareStorageBackend<S3Object>) aware;
            return typed.findAnyAccount(storeKey);
        }
        return objectStore.get(storeKey);
    }

    private Optional<S3Object> resolveObjectForAccount(String accountId, String storeKey) {
        if (objectStore instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<S3Object> typed = (AccountAwareStorageBackend<S3Object>) aware;
            return typed.getForAccount(accountId, storeKey);
        }
        return objectStore.get(storeKey);
    }

    private void putObjectForAccount(String accountId, String storeKey, S3Object object) {
        if (objectStore instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<S3Object> typed = (AccountAwareStorageBackend<S3Object>) aware;
            typed.putForAccount(accountId, storeKey, object);
            return;
        }
        objectStore.put(storeKey, object);
    }

    /**
     * Persists a metadata change to one stored object. The current version of a versioned object
     * is indexed under both its version key and the latest key, so both entries are written: the
     * journaled object store replays each entry on its own, and writing one would bring the
     * other back with the old metadata after a restart. The latest key is written only while it
     * still refers to this object, checked under the bucket lock that puts and deletes hold, so a
     * concurrent overwrite or delete marker is never replaced by the object it superseded.
     */
    private void putObjectMetadataForAccount(String accountId, String bucketName, String key, S3Object object) {
        Bucket bucket = resolveBucket(bucketName)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        synchronized (bucket) {
            String latestKey = objectKey(bucketName, key);
            String versionId = object.getVersionId();
            S3Object latest = resolveObjectForAccount(accountId, latestKey).orElse(null);
            boolean isCurrent = latest == object
                    || (versionId != null && latest != null && !latest.isDeleteMarker()
                            && versionId.equals(latest.getVersionId()));
            if (versionId != null) {
                putObjectForAccount(accountId, versionedKey(bucketName, key, versionId), object);
            }
            if (isCurrent) {
                putObjectForAccount(accountId, latestKey, object);
            }
        }
    }

    private String objectKey(String bucketName, String key) {
        return bucketName + "/" + key;
    }

    private String versionedKey(String bucketName, String key, String versionId) {
        return bucketName + "/" + key + "#v#" + versionId;
    }

    private static final String DATA_SUFFIX = ".s3data";

    // A 12-digit bucket name is valid on S3 and would otherwise collide with an account ID,
    // making dataRoot/<accountId>/... indistinguishable from the legacy dataRoot/<bucket>/...
    // layout. A leading "." keeps this namespace unreachable by any real bucket name.
    private static final String ACCOUNT_STORAGE_ROOT = ".accounts";
    private static final String VERSION_STORAGE_ROOT = ".versions";
    private static final Set<String> RESERVED_BUCKET_NAMES = Set.of(
            ACCOUNT_STORAGE_ROOT, VERSION_STORAGE_ROOT, ANNOTATION_STORAGE_ROOT);

    /**
     * Floci does not hold bucket names to AWS's DNS rules, but a name must still be one directory:
     * on the disk-backed stores anything else climbs out of the owning account's directory or
     * collides with Floci's reserved storage directories.
     */
    private static void requirePathSafeBucketName(String bucketName) {
        boolean pathSafe = bucketName != null
                && !bucketName.isBlank()
                && !bucketName.contains("/")
                && !bucketName.contains("\\")
                && bucketName.indexOf('\0') < 0
                && !".".equals(bucketName)
                && !"..".equals(bucketName)
                && !RESERVED_BUCKET_NAMES.contains(bucketName);
        if (!pathSafe) {
            throw new AwsException("InvalidBucketName", "The specified bucket is not valid.", 400);
        }
    }

    /**
     * One bucket's directory, asserted to sit directly under {@code parent}. Checked on the
     * resolved path, not the name, so a bucket persisted before the name was refused is caught too.
     */
    private static Path bucketDirectory(Path parent, String bucketName) {
        requirePathSafeBucketName(bucketName);
        Path resolved = parent.resolve(bucketName).normalize();
        if (!parent.normalize().equals(resolved.getParent())) {
            throw new AwsException("InvalidBucketName", "The specified bucket is not valid.", 400);
        }
        return resolved;
    }

    // Unlike bucketStore/objectStore, object bytes get no automatic account prefixing — two
    // accounts can own a bucket named "orders" and would collide here without this scoping.
    private String physicalKey(String bucketName, String key) {
        return physicalKey(ownerId(), bucketName, key);
    }

    private String physicalKey(String accountId, String bucketName, String key) {
        return accountId + "/" + objectKey(bucketName, key);
    }

    private String physicalVersionedKey(String bucketName, String key, String versionId) {
        return physicalVersionedKey(ownerId(), bucketName, key, versionId);
    }

    private String physicalVersionedKey(String accountId, String bucketName, String key, String versionId) {
        return accountId + "/" + versionedKey(bucketName, key, versionId);
    }

    private Path resolveObjectPath(String bucketName, String key) {
        return resolveObjectPath(ownerId(), bucketName, key);
    }

    private Path resolveObjectPath(String accountId, String bucketName, String key) {
        Path bucketDir = bucketDirectory(dataRoot.resolve(ACCOUNT_STORAGE_ROOT).resolve(accountId), bucketName);

        String safeKey = key;
        while (safeKey.startsWith("/")) {
            safeKey = safeKey.substring(1);
        }

        Path resolved = bucketDir.resolve(safeKey + DATA_SUFFIX).normalize();
        if (!resolved.startsWith(bucketDir)) {
            throw new AwsException("InvalidKey", "The specified key is invalid.", 400);
        }
        return resolved;
    }

    private Path legacyObjectPath(String bucketName, String key) {
        String safeKey = key;
        while (safeKey.startsWith("/")) {
            safeKey = safeKey.substring(1);
        }
        return dataRoot.resolve(bucketName).normalize().resolve(safeKey + DATA_SUFFIX);
    }

    /**
     * Resolves the path for a read, copying in a legacy-layout file if present. Reads only —
     * a write/delete ({@link #resolveObjectPath}) must never touch legacy data, since it isn't
     * known to belong to any one account and two accounts can share a bucket name. Copying
     * (not moving) leaves the ambiguous source in place so every account that reads it gets
     * its own copy.
     */
    private Path resolveObjectPathForRead(String bucketName, String key) {
        return resolveObjectPathForRead(ownerId(), bucketName, key);
    }

    private Path resolveObjectPathForRead(String accountId, String bucketName, String key) {
        Path resolved = resolveObjectPath(accountId, bucketName, key);
        copyLegacyFileIfPresent(legacyObjectPath(bucketName, key), resolved);
        return resolved;
    }

    private Path resolveVersionedPath(String bucketName, String key, String versionId) {
        return resolveVersionedPath(ownerId(), bucketName, key, versionId);
    }

    private Path resolveVersionedPath(String accountId, String bucketName, String key, String versionId) {
        Path baseDir = bucketDirectory(
                dataRoot.resolve(ACCOUNT_STORAGE_ROOT).resolve(accountId).resolve(VERSION_STORAGE_ROOT), bucketName);

        String safeKey = key;
        while (safeKey.startsWith("/")) {
            safeKey = safeKey.substring(1);
        }

        Path resolved = baseDir.resolve(safeKey).resolve(versionId + DATA_SUFFIX).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new AwsException("InvalidKey", "The specified key is invalid.", 400);
        }
        return resolved;
    }

    private Path legacyVersionedPath(String bucketName, String key, String versionId) {
        String safeKey = key;
        while (safeKey.startsWith("/")) {
            safeKey = safeKey.substring(1);
        }
        return dataRoot.resolve(VERSION_STORAGE_ROOT).resolve(bucketName).normalize()
                .resolve(safeKey).resolve(versionId + DATA_SUFFIX);
    }

    /** Read-only counterpart of {@link #resolveObjectPathForRead} for versioned objects. */
    private Path resolveVersionedPathForRead(String bucketName, String key, String versionId) {
        return resolveVersionedPathForRead(ownerId(), bucketName, key, versionId);
    }

    private Path resolveVersionedPathForRead(String accountId, String bucketName, String key, String versionId) {
        Path resolved = resolveVersionedPath(accountId, bucketName, key, versionId);
        copyLegacyFileIfPresent(legacyVersionedPath(bucketName, key, versionId), resolved);
        return resolved;
    }

    private ReentrantLock diskFileLock(Path path) {
        // A stripe collision just serializes unrelated paths — safe, unlike a map entry.
        return diskFileLocks[Math.floorMod(path.hashCode(), diskFileLocks.length)];
    }

    /**
     * Copies in a legacy file for {@code newPath}, under the same lock {@link #writeFile}/
     * {@link #deleteFile} hold for that path — otherwise a concurrent write could land its
     * real content and then be silently clobbered by a racing legacy copy.
     */
    private void copyLegacyFileIfPresent(Path legacyPath, Path newPath) {
        if (!Files.exists(legacyPath)) {
            return;
        }
        ReentrantLock lock = diskFileLock(newPath);
        lock.lock();
        try {
            if (Files.exists(newPath)) {
                return;
            }
            Files.createDirectories(newPath.getParent());
            Files.copy(legacyPath, newPath);
        } catch (IOException e) {
            // A failed copy can leave newPath truncated, which would wrongly look
            // "already migrated" on a later retry — clean it up before rethrowing.
            deleteQuietly(newPath, "partially-copied legacy S3 object file, so a later read can retry migration");
            throw new UncheckedIOException("Failed to copy legacy S3 object file to account-scoped layout", e);
        } finally {
            lock.unlock();
        }
    }

    private void deleteQuietly(Path path, String reason) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warnv(e, "Failed to delete {0} ({1})", path, reason);
        }
    }

    private void writeVersionedFile(String bucketName, String key, String versionId, byte[] data) {
        writeVersionedFile(ownerId(), bucketName, key, versionId, data);
    }

    private void writeVersionedFile(
            String accountId, String bucketName, String key, String versionId, byte[] data) {
        if (inMemory) {
            memoryDataStore.put(physicalVersionedKey(accountId, bucketName, key, versionId), data);
            return;
        }
        Path filePath = resolveVersionedPath(accountId, bucketName, key, versionId);
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            atomicWrite(filePath, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write versioned S3 object file", e);
        } finally {
            lock.unlock();
        }
    }

    private byte[] readVersionedFile(String accountId, String bucketName, String key, String versionId) {
        if (inMemory) {
            return memoryDataStore.get(physicalVersionedKey(accountId, bucketName, key, versionId));
        }
        try {
            return Files.readAllBytes(resolveVersionedPathForRead(accountId, bucketName, key, versionId));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read versioned S3 object file", e);
        }
    }

    private void writeFile(String bucketName, String key, byte[] data) {
        writeFile(ownerId(), bucketName, key, data);
    }

    private void writeFile(String accountId, String bucketName, String key, byte[] data) {
        if (inMemory) {
            memoryDataStore.put(physicalKey(accountId, bucketName, key), data);
            return;
        }
        Path filePath = resolveObjectPath(accountId, bucketName, key);
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            atomicWrite(filePath, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write S3 object file", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes {@code data} to {@code filePath} such that a concurrent reader ({@link #readFile})
     * always observes either the complete previous file or the complete new one — never a
     * truncated view. {@code Files.write} truncates-then-writes in place, so a reader that opens
     * the path mid-write reads a short or empty file; under LZA's Bootstrap fan-out that torn
     * read surfaced as an empty {@code src-Config} secondary source. Writing to a unique sibling
     * temp file and atomically renaming it over the target closes that window. The temp name is
     * unique per call so concurrent writers to the same key never clobber each other's temp file;
     * whichever rename lands last wins, and every rename is all-or-nothing.
     */
    private void atomicWrite(Path filePath, byte[] data) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, data);
            replaceAtomically(tmp, filePath);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Rare filesystems (some network mounts) reject ATOMIC_MOVE; fall back to a plain
            // replace. This narrows but does not fully close the window, which is acceptable only
            // because the default overlay/ext filesystems used here support atomic rename.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Moves an assembled multipart object to {@code target} with the same all-or-nothing rename
     * {@link #atomicWrite} ends with: a concurrent reader sees the previous file or the whole new
     * one, and the move costs the same whatever the object's size.
     */
    private void moveIntoPlace(Path source, Path target) {
        ReentrantLock lock = diskFileLock(target);
        lock.lock();
        try {
            Files.createDirectories(target.getParent());
            replaceAtomically(source, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to move assembled S3 object file into place", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gives {@code target} the file already stored at {@code source} through a hard link, so a body
     * a versioned object already has on disk is shared instead of being copied under the bucket
     * lock. Sharing the file is safe because no object file is ever modified in place: every write
     * replaces its path with a rename, which leaves the file under the other name untouched.
     * A filesystem without hard links gets a copy instead.
     */
    private void linkIntoPlace(Path source, Path target) {
        ReentrantLock lock = diskFileLock(target);
        lock.lock();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.createLink(tmp, source);
            } catch (UnsupportedOperationException | FileSystemException e) {
                LOG.debugv(e, "No hard link for {0}, copying {1} instead", target, source);
                Files.copy(source, tmp);
            }
            replaceAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to link S3 object file into place", e);
        } finally {
            deleteQuietly(tmp, "temporary link of an S3 object file that was not put in place");
            lock.unlock();
        }
    }

    /**
     * Makes the stored body of {@code versionId} the key's current body without reading it, so a
     * version of any size can be promoted: on disk the current file becomes a hard link to the
     * version's file, and in memory both entries share the version's array.
     */
    private void promoteVersionedFile(String bucketName, String key, String versionId) {
        if (inMemory) {
            byte[] data = memoryDataStore.get(physicalVersionedKey(bucketName, key, versionId));
            if (data != null) {
                memoryDataStore.put(physicalKey(bucketName, key), data);
            } else {
                deleteFile(bucketName, key);
            }
            return;
        }
        linkIntoPlace(resolveVersionedPathForRead(bucketName, key, versionId), resolveObjectPath(bucketName, key));
    }

    private byte[] readFile(String bucketName, String key) {
        return readFile(ownerId(), bucketName, key);
    }

    private byte[] readFile(String accountId, String bucketName, String key) {
        if (inMemory) {
            return memoryDataStore.get(physicalKey(accountId, bucketName, key));
        }
        try {
            return Files.readAllBytes(resolveObjectPathForRead(accountId, bucketName, key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read S3 object file", e);
        }
    }

    private void deleteFile(String bucketName, String key) {
        if (inMemory) {
            memoryDataStore.remove(physicalKey(bucketName, key));
            return;
        }
        Path filePath = resolveObjectPath(bucketName, key);
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to delete S3 object file: {0}/{1}", bucketName, key);
        } finally {
            lock.unlock();
        }
    }

    private void deleteVersionedFile(String bucketName, String key, String versionId) {
        if (inMemory) {
            memoryDataStore.remove(physicalVersionedKey(bucketName, key, versionId));
            return;
        }
        Path filePath = resolveVersionedPath(bucketName, key, versionId);
        ReentrantLock lock = diskFileLock(filePath);
        lock.lock();
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to delete versioned S3 object file: {0}/{1} v={2}", bucketName, key, versionId);
        } finally {
            lock.unlock();
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

    private S3Object copyS3Object(String sourceBucket, String sourceKey,
                          String destBucket, String destKey, CopySource copySource, CopyObjectOptions options) {
        S3Object source = copySource.object();
        ensureBucketExists(destBucket);
        CopyObjectOptions effectiveOptions = options != null ? options : new CopyObjectOptions();
        String normalizedServerSideEncryption = normalizeServerSideEncryption(effectiveOptions.getServerSideEncryption());
        SseCustomerKey destinationCustomerKey = validateSseCustomerKey(
                effectiveOptions.getSseCustomerAlgorithm(),
                effectiveOptions.getSseCustomerKey(),
                effectiveOptions.getSseCustomerKeyMd5());
        rejectConflictingServerSideEncryption(normalizedServerSideEncryption, destinationCustomerKey);

        boolean replaceMetadata = "REPLACE".equalsIgnoreCase(effectiveOptions.getMetadataDirective());
        Map<String, String> metadata = replaceMetadata ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getMetadata());
        if (replaceMetadata && effectiveOptions.getReplacementMetadata() != null) {
            metadata.putAll(effectiveOptions.getReplacementMetadata());
        }

        String effectiveContentType = replaceMetadata && effectiveOptions.getContentType() != null
                ? effectiveOptions.getContentType()
                : source.getContentType();
        String effectiveStorageClass = effectiveOptions.getStorageClass() != null
                ? effectiveOptions.getStorageClass()
                : source.getStorageClass();
        String effectiveContentEncoding = replaceMetadata && effectiveOptions.getContentEncoding() != null
                ? effectiveOptions.getContentEncoding()
                : source.getContentEncoding();
        String effectiveContentDisposition = replaceMetadata && effectiveOptions.getContentDisposition() != null
                ? effectiveOptions.getContentDisposition()
                : source.getContentDisposition();
        String effectiveCacheControl = replaceMetadata && effectiveOptions.getCacheControl() != null
                ? effectiveOptions.getCacheControl()
                : source.getCacheControl();
        String effectiveServerSideEncryption = destinationCustomerKey != null
                ? null
                : (normalizedServerSideEncryption != null ? normalizedServerSideEncryption : source.getServerSideEncryption());
        String effectiveSseKmsKeyId = "aws:kms".equals(effectiveServerSideEncryption)
                ? (normalizedServerSideEncryption != null
                    ? effectiveOptions.getSseKmsKeyId()
                    : source.getSseKmsKeyId())
                : null;
        boolean replaceTags = "REPLACE".equalsIgnoreCase(effectiveOptions.getTaggingDirective());
        Map<String, String> effectiveTags = replaceTags
                ? effectiveOptions.getReplacementTagging()
                : source.getTags();

        // A copy is written as one object without a part manifest; a composite source gets a
        // full-object checksum recomputed with its algorithm, as AWS does.
        S3Checksum effectiveChecksum = source.getChecksum();
        ChecksumAlgorithm copyChecksumAlgorithm = ChecksumAlgorithm.fromWireValue(effectiveOptions.getChecksumAlgorithm());
        if (copyChecksumAlgorithm == null && effectiveChecksum != null
                && effectiveChecksum.getChecksumType() == ChecksumType.COMPOSITE) {
            copyChecksumAlgorithm = effectiveChecksum.algorithm();
        }
        if (copyChecksumAlgorithm != null) {
            effectiveChecksum = null;
        }
        // Prepared before any bucket monitor is taken, so reading a large source to hash it never
        // holds up other writes to either bucket.
        CopyBody body = copyBody(copySource, effectiveChecksum, copyChecksumAlgorithm);

        // Annotations travel with the copy by default (x-amz-annotation-directive COPY). They are
        // snapshotted before storeObject: a self-copy (same bucket and key) or a pre-versioning
        // overwrite deletes the shared annotation entries as part of the overwrite, so metadata
        // and payload must be read beforehand. The snapshot holds payload bytes in memory, the
        // same profile as the source body copy itself.
        boolean copyAnnotations = !"EXCLUDE".equalsIgnoreCase(effectiveOptions.getAnnotationDirective());
        boolean selfCopy = sourceBucket.equals(destBucket);
        // resolveBucket (not requireBucket): with globalBucketNamespace the bucket can belong to
        // another account, and this must be the same instance the write path (storeObject) locks.
        Bucket sourceMonitor = resolveBucket(sourceBucket)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404));
        List<AnnotationSnapshot> sourceAnnotations = List.of();
        if (copyAnnotations && !selfCopy) {
            // Cross-bucket copy: the destination overwrite cannot touch the source's annotation
            // entries, so a monitor-guarded snapshot is enough. Holding the source monitor across
            // storeObject here would risk a deadlock with a concurrent reverse copy.
            synchronized (sourceMonitor) {
                sourceAnnotations = snapshotAnnotations(source);
            }
        }

        // A copy is written as one object, so it keeps the ETag storeObject computed (the MD5 of the
        // whole content) instead of the source's, which for a multipart source ends in "-N". As on S3.
        if (selfCopy) {
            // The overwrite deletes the shared annotation entries, so snapshot, overwrite, and
            // restore must be atomic against annotation writes: all three under the bucket
            // monitor, which storeObject re-enters for the same bucket.
            S3Object[] result = {null};
            synchronized (sourceMonitor) {
                if (copyAnnotations) {
                    sourceAnnotations = snapshotAnnotations(source);
                }
                result[0] = storeObjectCopy(destBucket, destKey, body, metadata,
                        effectiveContentType, effectiveStorageClass, effectiveContentEncoding,
                        effectiveContentDisposition, effectiveCacheControl, effectiveServerSideEncryption,
                        effectiveSseKmsKeyId, effectiveOptions, copyChecksumAlgorithm, effectiveTags);
                if (copyAnnotations) {
                    restoreAnnotations(sourceAnnotations, destBucket, destKey, result[0]);
                }
            }
            // Fired outside the bucket monitor, matching the cross-bucket path.
            LOG.debugv("Copied object: {0}/{1} -> {2}/{3}", sourceBucket, sourceKey, destBucket, destKey);
            fireNotifications(destBucket, destKey, "ObjectCreated:Copy", result[0]);
            return result[0];
        }
        // Publish and restore under the DESTINATION bucket monitor: an overwrite of the
        // destination key is serialized against the restore, so the copied annotations can
        // never attach to a newer, unrelated object that lands in between (the annotations'
        // plain-key identity is shared by every non-versioned object at this key). The source
        // monitor above was already released, so the two locks are never held together and a
        // concurrent reverse copy cannot deadlock. resolveBucket (not requireBucket) keeps
        // cross-account destinations working with globalBucketNamespace, and returns the same
        // instance storeObject locks, so this monitor re-enters the write path's own.
        S3Object[] result = {null};
        synchronized (resolveBucket(destBucket)
                .orElseThrow(() -> new AwsException("NoSuchBucket",
                        "The specified bucket does not exist.", 404))) {
            result[0] = storeObjectCopy(destBucket, destKey, body, metadata,
                    effectiveContentType, effectiveStorageClass, effectiveContentEncoding,
                    effectiveContentDisposition, effectiveCacheControl, effectiveServerSideEncryption,
                    effectiveSseKmsKeyId, effectiveOptions, copyChecksumAlgorithm, effectiveTags);
            if (copyAnnotations) {
                restoreAnnotations(sourceAnnotations, destBucket, destKey, result[0]);
            }
        }
        // Fired outside the bucket monitor, matching the self-copy path.
        LOG.debugv("Copied object: {0}/{1} -> {2}/{3}", sourceBucket, sourceKey, destBucket, destKey);
        fireNotifications(destBucket, destKey, "ObjectCreated:Copy", result[0]);
        return result[0];
    }

    private S3Object storeObjectCopy(String destBucket, String destKey, CopyBody body,
                                     Map<String, String> metadata,
                                     String effectiveContentType, String effectiveStorageClass,
                                     String effectiveContentEncoding, String effectiveContentDisposition,
                                     String effectiveCacheControl, String effectiveServerSideEncryption,
                                     String effectiveSseKmsKeyId,
                                     CopyObjectOptions effectiveOptions, ChecksumAlgorithm copyChecksumAlgorithm,
                                     Map<String, String> effectiveTags) {
        return storeObject(destBucket, destKey, body.body(), effectiveContentType,
                metadata, body.checksum(), null,
                new PutObjectOptions()
                        .withStorageClass(effectiveStorageClass)
                        .withContentEncoding(effectiveContentEncoding)
                        .withContentDisposition(effectiveContentDisposition)
                        .withCacheControl(effectiveCacheControl)
                        .withServerSideEncryption(effectiveServerSideEncryption)
                        .withSseKmsKeyId(effectiveSseKmsKeyId)
                        .withSseCustomerAlgorithm(effectiveOptions.getSseCustomerAlgorithm())
                        .withSseCustomerKey(effectiveOptions.getSseCustomerKey())
                        .withSseCustomerKeyMd5(effectiveOptions.getSseCustomerKeyMd5())
                        .withAcl(effectiveOptions.getAcl())
                        .withGrantRead(effectiveOptions.getGrantRead())
                        .withGrantWrite(effectiveOptions.getGrantWrite())
                        .withGrantFullControl(effectiveOptions.getGrantFullControl())
                        .withGrantReadAcp(effectiveOptions.getGrantReadAcp())
                        .withGrantWriteAcp(effectiveOptions.getGrantWriteAcp())
                        .withChecksumAlgorithm(copyChecksumAlgorithm != null ? copyChecksumAlgorithm.name() : null)
                        .withTagging(effectiveTags)
                        .withIfMatch(effectiveOptions.getIfMatch())
                        .withIfNoneMatch(effectiveOptions.getIfNoneMatch()),
                body.eTag());
    }

    private record AnnotationSnapshot(ObjectAnnotation metadata, byte[] payload) {}

    private List<AnnotationSnapshot> snapshotAnnotations(S3Object source) {
        String sourceParentKey = annotationParentKey(source.getBucketName(), source.getKey(), source.getVersionId());
        List<AnnotationSnapshot> snapshots = new ArrayList<>();
        for (ObjectAnnotation annotation : annotationStore.scan(k -> k.startsWith(sourceParentKey + ANNOTATION_SEPARATOR))) {
            byte[] payload = readAnnotationPayload(annotation);
            if (payload != null) {
                snapshots.add(new AnnotationSnapshot(annotation, payload));
            }
        }
        return snapshots;
    }

    private void restoreAnnotations(List<AnnotationSnapshot> snapshots, String destBucket, String destKey,
                                    S3Object copy) {
        if (copy.getSseCustomerAlgorithm() != null) {
            // The destination copy is SSE-C encrypted: annotations cannot live on it, the same
            // rule a direct PutObjectAnnotation enforces.
            return;
        }
        String destParentKey = annotationParentKey(destBucket, destKey, copy.getVersionId());
        // The destination object is already published, so a mid-restore failure must not leave a
        // partial annotation set behind (a failed copy whose retry would find partial state).
        // Every restored annotation is tracked before its writes; on failure the written
        // annotations are rolled back best-effort, leaving the destination with none of the
        // copied annotations rather than a partial set.
        List<ObjectAnnotation> restored = new ArrayList<>();
        try {
            for (AnnotationSnapshot snapshot : snapshots) {
                // The payload bytes are identical, so the source annotation's ETag and checksum
                // are preserved; only the identity fields and lastModified are recomputed.
                ObjectAnnotation copied = new ObjectAnnotation(destBucket, destKey, copy.getVersionId(),
                        snapshot.metadata().getAnnotationName(), snapshot.metadata().getSize(),
                        snapshot.metadata().getETag(), Instant.now(),
                        snapshot.metadata().getChecksumAlgorithm(), snapshot.metadata().getChecksumValue());
                copied.setServerSideEncryption(copy.getServerSideEncryption());
                restored.add(copied);
                writeAnnotationPayload(copied, snapshot.payload());
                annotationStore.put(annotationStoreKey(destParentKey, copied.getAnnotationName()), copied);
            }
        } catch (RuntimeException e) {
            for (ObjectAnnotation restoredAnnotation : restored) {
                try {
                    annotationStore.delete(annotationStoreKey(destParentKey, restoredAnnotation.getAnnotationName()));
                    deleteAnnotationPayload(restoredAnnotation);
                } catch (RuntimeException rollbackError) {
                    LOG.warnv(rollbackError, "Failed to roll back annotation {0} on copy destination {1}/{2}",
                            restoredAnnotation.getAnnotationName(), destBucket, destKey);
                }
            }
            throw e;
        }
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Bucket bucket : listBuckets()) {
            resources.add(new ExplorerResource(
                    AwsArnUtils.Arn.global(AwsRegions.partitionFor(bucket.getRegion() != null
                            ? bucket.getRegion() : regionResolver.getDefaultRegion()), "s3", "", bucket.getName()).toString(),
                    "s3:bucket",
                    "s3",
                    bucket.getRegion() != null ? bucket.getRegion() : regionResolver.getDefaultRegion(),
                    regionResolver.getAccountId(),
                    bucket.getCreationDate() != null ? bucket.getCreationDate() : Instant.now(),
                    bucket.getTags() != null ? bucket.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("s3:bucket", "s3", true));
    }
}
