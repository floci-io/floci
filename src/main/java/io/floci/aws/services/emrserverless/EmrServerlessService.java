package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.ImageDetail;
import io.floci.aws.services.ecr.model.ImageIdentifier;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.ApplicationSummary;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.ImageConfiguration;
import io.floci.aws.services.emrserverless.model.ListApplicationsRequest;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import io.floci.aws.services.emrserverless.model.WorkerTypeSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class EmrServerlessService {

    // The EMR Serverless user guide's tagging limitations: at most 50 user-created tags per resource
    // (the model's TagMap max of 200 only bounds a single request), and no "aws:" prefix, in any case,
    // on a key or a value. TagKey and TagValue share one character set in the API model.
    private static final String NOT_STOPPED_FOR_UPDATE =
            "Application must be in a stopped or created state in order to be updated.";
    // The settings an update may change only on a CREATED or STOPPED application: all but its maximum capacity and
    // its custom images, which a STARTED one takes too (checkStartedUpdate).
    private static final List<Function<UpdateApplicationRequest, Object>> STOPPED_ONLY_SETTINGS = List.of(
            UpdateApplicationRequest::getReleaseLabel, UpdateApplicationRequest::getInitialCapacity,
            UpdateApplicationRequest::getAutoStartConfiguration, UpdateApplicationRequest::getAutoStopConfiguration,
            UpdateApplicationRequest::getNetworkConfiguration, UpdateApplicationRequest::getArchitecture,
            UpdateApplicationRequest::getMonitoringConfiguration, UpdateApplicationRequest::getRuntimeConfiguration,
            UpdateApplicationRequest::getSchedulerConfiguration,
            UpdateApplicationRequest::getDiskEncryptionConfiguration,
            UpdateApplicationRequest::getInteractiveConfiguration,
            UpdateApplicationRequest::getIdentityCenterConfiguration,
            UpdateApplicationRequest::getJobLevelCostAllocationConfiguration);
    private static final int MAX_TAGS = 50;
    private static final String RESERVED_PREFIX = "aws:";
    private static final Pattern TAG_TEXT = Pattern.compile("[A-Za-z0-9 /_.:=+@-]*");
    // ResourceArn's resource: an application, or one of its job runs or sessions.
    private static final Logger LOG = Logger.getLogger(EmrServerlessService.class);
    // Floci's ECR's answers for an image it does not hold, and, as ServerException, for image queries with no Docker
    // daemon to serve its registry.
    private static final Set<String> UNREADABLE_IMAGE_CODES =
            Set.of("RepositoryNotFoundException", "ImageNotFoundException", "ServerException");
    // A release label's major and minor versions.
    private static final Pattern RELEASE_VERSION = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})(?:\\..*)?");
    private static final Pattern RESOURCE =
            Pattern.compile("/applications/([0-9a-zA-Z]+)(?:/(jobruns|sessions)/([0-9a-zA-Z]+))?");
    private static final List<String> APPLICATION_STATES =
            List.of("CREATING", "CREATED", "STARTING", "STARTED", "STOPPING", "STOPPED", "TERMINATED");
    // ApplicationStateSet holds at most 7 states; a ListApplications page holds 1 to 50 applications.
    private static final int MAX_APPLICATION_STATES = 7;
    private static final int MAX_LIST_RESULTS = 50;

    /** What an EMR Serverless resource ARN names: an application, or one of its job runs. */
    public record ResourceArn(String arn, String applicationId, String jobRunId) {}

    private final EmulatorConfig config;
    private final AccountAwareStorageBackend<Application> storage;
    private final RegionResolver regionResolver;
    private final Clock clock;
    
    @Inject
    RequestContext requestContext;

    // Floci's ECR, which a custom image's digest is read from as an application starts; none in a test without it.
    @Inject
    EcrService ecrService;

    @Inject
    public EmrServerlessService(EmulatorConfig config, StorageFactory storageFactory,
                                RegionResolver regionResolver, Clock clock) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.storage = storageFactory.create("emrserverless", "emr-serverless-applications.json",
                new TypeReference<Map<String, Application>>() {});
    }

    public synchronized Application createApplication(CreateApplicationRequest request) {
        if (request.getReleaseLabel() == null || request.getReleaseLabel().isBlank()) {
            throw new AwsException("ValidationException", "releaseLabel is required", 400);
        }
        if (request.getType() == null || request.getType().isBlank()) {
            throw new AwsException("ValidationException", "type is required", 400);
        }
        if (request.getClientToken() == null || request.getClientToken().isBlank()) {
            throw new AwsException("ValidationException", "clientToken is required", 400);
        }
        validateImages(request.getImageConfiguration(), request.getWorkerTypeSpecifications(), true);

        String region = regionResolver.getRegion();
        if (request.getClientToken() != null) {
            for (Application existing : storage.scan(k -> true)) {
                if (inRegion(existing, region) && request.getClientToken().equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }

        String id = newId();
        String arn = buildArn(id);
        Instant createdAt = now();

        Application app = new Application();
        app.setApplicationId(id);
        app.setClientToken(request.getClientToken());
        app.setArn(arn);
        app.setName(request.getName());
        app.setReleaseLabel(request.getReleaseLabel());
        app.setType(request.getType());
        app.setState("CREATED");
        app.setStateDetails("");
        app.setCreatedAt(createdAt);
        app.setUpdatedAt(createdAt);
        if (request.getTags() != null) {
            validateTags(request.getTags());
        }
        app.setTags(request.getTags());
        app.setArchitecture(request.getArchitecture());
        app.setInitialCapacity(request.getInitialCapacity());
        app.setMaximumCapacity(request.getMaximumCapacity());
        app.setAutoStartConfiguration(request.getAutoStartConfiguration());
        app.setAutoStopConfiguration(request.getAutoStopConfiguration());
        app.setNetworkConfiguration(request.getNetworkConfiguration());
        app.setImageConfiguration(request.getImageConfiguration());
        app.setWorkerTypeSpecifications(request.getWorkerTypeSpecifications());
        app.setMonitoringConfiguration(request.getMonitoringConfiguration());
        app.setRuntimeConfiguration(request.getRuntimeConfiguration());
        app.setSchedulerConfiguration(request.getSchedulerConfiguration());
        app.setDiskEncryptionConfiguration(request.getDiskEncryptionConfiguration());
        app.setInteractiveConfiguration(request.getInteractiveConfiguration());
        app.setIdentityCenterConfiguration(request.getIdentityCenterConfiguration());
        app.setJobLevelCostAllocationConfiguration(request.getJobLevelCostAllocationConfiguration());

        storage.put(id, app);
        return app;
    }

    public Application getApplication(String applicationId) {
        return storage.get(applicationId)
                .filter(app -> inRegion(app, regionResolver.getRegion()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application " + applicationId + " not found", 404));
    }

    public PaginatedResult<ApplicationSummary> listApplications(ListApplicationsRequest request) {
        List<String> violations = new ArrayList<>();
        if (request.getMaxResults() != null) {
            String violation = SmithyValidationErrors.outOfRange("maxResults", request.getMaxResults(), 1,
                    MAX_LIST_RESULTS);
            if (violation != null) {
                violations.add(violation);
            }
        }
        violations.addAll(statesViolations(request.getStates(), APPLICATION_STATES, MAX_APPLICATION_STATES));
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        String region = regionResolver.getRegion();
        List<Application> all = storage.scan(k -> true).stream()
                .filter(app -> inRegion(app, region)).collect(Collectors.toList());
        if (request.getStates() != null && !request.getStates().isEmpty()) {
            all = all.stream().filter(app -> request.getStates().contains(app.getState())).collect(Collectors.toList());
        } else {
            all = all.stream().filter(app -> !"TERMINATED".equals(app.getState())).collect(Collectors.toList());
        }
        
        PaginatedResult<Application> page = Pagination.paginate(all, Application::getApplicationId, request.getMaxResults(), request.getNextToken(), MAX_LIST_RESULTS, "ValidationException");
        
        return new PaginatedResult<>(
                page.items().stream().map(this::toSummary).collect(Collectors.toList()),
                page.nextToken()
        );
    }

    public synchronized Application updateApplication(String applicationId, UpdateApplicationRequest request) {
        validateImages(request.getImageConfiguration(), request.getWorkerTypeSpecifications(), false);
        Application app = getApplication(applicationId);

        String state = app.getState();
        if ("STARTED".equals(state)) {
            checkStartedUpdate(app, request);
        } else if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", NOT_STOPPED_FOR_UPDATE, 400);
        }

        if (request.getReleaseLabel() != null) {
            app.setReleaseLabel(request.getReleaseLabel());
        }
        if (request.getInitialCapacity() != null) {
            app.setInitialCapacity(request.getInitialCapacity());
        }
        if (request.getMaximumCapacity() != null) {
            app.setMaximumCapacity(request.getMaximumCapacity());
        }
        if (request.getAutoStartConfiguration() != null) {
            app.setAutoStartConfiguration(request.getAutoStartConfiguration());
        }
        if (request.getAutoStopConfiguration() != null) {
            app.setAutoStopConfiguration(request.getAutoStopConfiguration());
        }
        if (request.getNetworkConfiguration() != null) {
            app.setNetworkConfiguration(request.getNetworkConfiguration());
        }
        if (request.getArchitecture() != null) {
            app.setArchitecture(request.getArchitecture());
        }
        if (request.getImageConfiguration() != null) {
            app.setImageConfiguration(imageOrNone(request.getImageConfiguration()));
        }
        if (request.getWorkerTypeSpecifications() != null) {
            for (WorkerTypeSpecification specification : request.getWorkerTypeSpecifications().values()) {
                if (specification != null) {
                    specification.setImageConfiguration(imageOrNone(specification.getImageConfiguration()));
                }
            }
            app.setWorkerTypeSpecifications(request.getWorkerTypeSpecifications());
        }
        if (request.getMonitoringConfiguration() != null) {
            app.setMonitoringConfiguration(request.getMonitoringConfiguration());
        }
        if (request.getRuntimeConfiguration() != null) {
            app.setRuntimeConfiguration(request.getRuntimeConfiguration());
        }
        if (request.getSchedulerConfiguration() != null) {
            app.setSchedulerConfiguration(request.getSchedulerConfiguration());
        }
        if (request.getDiskEncryptionConfiguration() != null) {
            app.setDiskEncryptionConfiguration(request.getDiskEncryptionConfiguration());
        }
        if (request.getInteractiveConfiguration() != null) {
            app.setInteractiveConfiguration(request.getInteractiveConfiguration());
        }
        if (request.getIdentityCenterConfiguration() != null) {
            app.setIdentityCenterConfiguration(request.getIdentityCenterConfiguration());
        }
        if (request.getJobLevelCostAllocationConfiguration() != null) {
            app.setJobLevelCostAllocationConfiguration(request.getJobLevelCostAllocationConfiguration());
        }
        unpinImagesResolvedPerWorkload(app);

        app.setUpdatedAt(now());
        storage.put(applicationId, app);
        return app;
    }

    /**
     * What an update may change on a STARTED application, from AWS's "Updating Running Applications": its maximum
     * capacity, "for a STARTED application to modify the scaling boundary", and its custom images, "without stopping
     * it", though "Custom image updates on running applications are not supported on applications with
     * pre-initialized capacity configured". Any other setting still needs the application "in a stopped or created
     * state". A run already going keeps the image and maximum capacity it was launched with, as "existing workloads
     * continue with the original image" and "The new settings only apply to workers on new workloads"; a new run, or a
     * new attempt of one, takes the application's current ones.
     */
    private static void checkStartedUpdate(Application app, UpdateApplicationRequest request) {
        if (STOPPED_ONLY_SETTINGS.stream().anyMatch(setting -> setting.apply(request) != null)) {
            throw new AwsException("ValidationException", NOT_STOPPED_FOR_UPDATE, 400);
        }
        boolean images = request.getImageConfiguration() != null || request.getWorkerTypeSpecifications() != null;
        if (images && app.getInitialCapacity() != null && !app.getInitialCapacity().isEmpty()) {
            throw new AwsException("ValidationException", "Custom image updates on running applications are not "
                    + "supported on applications with pre-initialized capacity configured.", 400);
        }
    }

    public synchronized void deleteApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be deleted.", 400);
        }
        app.setState("TERMINATED");
        app.setUpdatedAt(now());
        storage.put(applicationId, app);
    }

    public synchronized void startApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STARTED".equals(state) || "STARTING".equals(state)) {
            return;
        }
        if ("TERMINATED".equals(state)) {
            throw new AwsException("ValidationException", "Application is terminated.", 400);
        }
        pinImages(app);
        app.setState("STARTED");
        app.setUpdatedAt(now());
        storage.put(applicationId, app);
    }

    /**
     * Resolves, as the application starts, each custom image it resolves then: "Custom images are resolved to a digest
     * at application start; that digest is used for all jobs until the application restarts" (EMR Serverless user
     * guide, "Image digest resolution behavior"). Its image configuration gets the digest as its
     * resolvedImageDigest, which "doesn't exist until an application has started", and its runs take that image
     * until it starts again, an update meanwhile notwithstanding unless it turns the image to resolution per
     * workload. An image outside ECR, in another account's repository, or one Floci's ECR does not hold, has no digest
     * read, and is resolved for each run.
     */
    private void pinImages(Application app) {
        Map<String, ImageConfiguration> images = new LinkedHashMap<>();
        images.put("", app.getImageConfiguration());
        if (app.getWorkerTypeSpecifications() != null) {
            app.getWorkerTypeSpecifications().forEach((workerType, specification) ->
                    images.put(workerType, specification != null ? specification.getImageConfiguration() : null));
        }
        // Every digest is read before any is written, so an answer that fails the start leaves the application as it
        // was.
        Map<String, String> digests = new LinkedHashMap<>();
        images.forEach((key, image) -> {
            String digest = startDigest(app, image);
            if (digest != null) {
                digests.put(key, digest);
            }
        });
        Map<String, ImageConfiguration> pinned = new LinkedHashMap<>();
        images.forEach((key, image) -> {
            if (image == null) {
                return;
            }
            // A digest from an earlier start is not this start's: one read now replaces it, and none read leaves
            // none, as the runs then resolve the image themselves.
            String digest = digests.get(key);
            image.setResolvedImageDigest(digest);
            if (digest != null) {
                ImageConfiguration resolved = new ImageConfiguration();
                resolved.setImageUri(image.getImageUri());
                resolved.setResolvedImageDigest(digest);
                pinned.put(key, resolved);
            }
        });
        app.setPinnedImages(pinned.isEmpty() ? null : pinned);
    }

    // The digest an image configuration resolves as its application starts, or null where it has none to read then.
    private String startDigest(Application app, ImageConfiguration image) {
        if (image == null || image.getImageUri() == null || !applicationLevelDigests(app.getReleaseLabel(), image)) {
            return null;
        }
        return ecrDigest(image.getImageUri(), accountOf(app));
    }

    /**
     * Whether a custom image is resolved as its application starts: its image configuration's
     * applicationLevelDigestResolution, else, as the user guide's table gives the default, true for "EMR 7.13 and
     * earlier" and false for "EMR 7.14 and later". A release label not of the emr-x.y form is taken as current.
     */
    static boolean applicationLevelDigests(String releaseLabel, ImageConfiguration image) {
        if (image.getApplicationLevelDigestResolution() != null) {
            return image.getApplicationLevelDigestResolution();
        }
        Matcher version = RELEASE_VERSION.matcher(releaseLabel == null ? "" : releaseLabel);
        if (!version.matches()) {
            return false;
        }
        int major = Integer.parseInt(version.group(1));
        int minor = Integer.parseInt(version.group(2));
        return major < 7 || major == 7 && minor <= 13;
    }

    /**
     * Each run resolves its image again where an update turns a STARTED application's image to resolution per
     * workload, or removes it: the pin of an image the application still resolves at its start stays until it starts
     * again, "that digest is used for all jobs until the application restarts", and any other ends now.
     */
    private static void unpinImagesResolvedPerWorkload(Application app) {
        Map<String, ImageConfiguration> pinned = app.getPinnedImages();
        if (pinned == null) {
            return;
        }
        Map<String, ImageConfiguration> kept = new LinkedHashMap<>(pinned);
        kept.keySet().removeIf(key -> {
            ImageConfiguration current = imageConfigurationOf(app, key);
            return current == null || current.getImageUri() == null
                    || !applicationLevelDigests(app.getReleaseLabel(), current);
        });
        app.setPinnedImages(kept.isEmpty() ? null : kept);
    }

    // The application's image configuration for a pin's key: its own for "", else its worker type's.
    private static ImageConfiguration imageConfigurationOf(Application app, String key) {
        if (key.isEmpty()) {
            return app.getImageConfiguration();
        }
        WorkerTypeSpecification specification = app.getWorkerTypeSpecifications() == null ? null
                : app.getWorkerTypeSpecifications().get(key);
        return specification == null ? null : specification.getImageConfiguration();
    }

    private static String accountOf(Application app) {
        return AwsArnUtils.parse(app.getArn()).accountId();
    }

    /**
     * The manifest digest of an image in one of the application's account's ECR repositories, the one its URI names
     * where it names one; null for an image outside ECR, one in another account's repository, which Floci does not
     * check the application may pull, or one Floci's ECR does not hold or cannot read without Docker. Each run then
     * resolves the image itself. Any other answer from ECR fails the start.
     */
    private String ecrDigest(String imageUri, String accountId) {
        Optional<EcrRegistryManager.EcrImage> image = EcrRegistryManager.ecrImage(imageUri);
        if (image.isEmpty() || !accountId.equals(image.get().registryId())) {
            return null;
        }
        if (image.get().digest() != null) {
            return image.get().digest();
        }
        if (ecrService == null) {
            return null;
        }
        try {
            List<ImageDetail> details = ecrService.describeImages(image.get().repository(),
                    List.of(new ImageIdentifier(image.get().tag(), null)), image.get().registryId(),
                    image.get().region()).imageDetails();
            return details.isEmpty() ? null : details.getFirst().getImageDigest();
        } catch (AwsException e) {
            if (!UNREADABLE_IMAGE_CODES.contains(e.getErrorCode())) {
                throw new AwsException("InternalServerException", "The custom image " + imageUri
                        + " could not be resolved: " + e.getMessage(), 500);
            }
            LOG.infov("The custom image {0} has no digest in Floci's ECR ({1}), so each run resolves it: {2}",
                    imageUri, e.getErrorCode(), e.getMessage());
            return null;
        }
    }

    private static void clearResolvedDigests(Application app) {
        if (app.getImageConfiguration() != null) {
            app.getImageConfiguration().setResolvedImageDigest(null);
        }
        if (app.getWorkerTypeSpecifications() != null) {
            for (WorkerTypeSpecification specification : app.getWorkerTypeSpecifications().values()) {
                if (specification != null && specification.getImageConfiguration() != null) {
                    specification.getImageConfiguration().setResolvedImageDigest(null);
                }
            }
        }
    }

    public synchronized void stopApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STOPPED".equals(state) || "STOPPING".equals(state)) {
            return;
        }
        if ("TERMINATED".equals(state)) {
            throw new AwsException("ValidationException", "Application is terminated.", 400);
        }
        app.setState("STOPPED");
        // Its images are resolved again as it starts again.
        app.setPinnedImages(null);
        clearResolvedDigests(app);
        app.setUpdatedAt(now());
        storage.put(applicationId, app);
    }

    /**
     * Admits a job run to the application and returns what {@code admit} builds from it. An
     * application "only accepts jobs when it's in" STARTED, and auto-start, on unless the
     * application turns it off, starts a CREATED or STOPPED one "on job submission" (EMR Serverless
     * user guide, "Application states" and "Default application behavior"). {@code check} refuses a
     * run against the application before auto-start can change it. This service's lock is held
     * throughout, so the application cannot be updated, stopped or deleted while the run is admitted.
     */
    public synchronized <T> T admitJobRun(String applicationId, Consumer<Application> check,
                                          Function<Application, T> admit) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("TERMINATED".equals(state)) {
            throw new AwsException("ValidationException", "Application is terminated.", 400);
        }
        check.accept(app);
        if (!"STARTED".equals(state)) {
            if (!autoStartEnabled(app)) {
                throw new AwsException("ValidationException", "Application " + applicationId
                        + " must be started before a job run is submitted, as its auto-start is disabled.", 400);
            }
            startApplication(applicationId);
            app = getApplication(applicationId);
        }
        return admit.apply(app);
    }

    // AutoStartConfig.enabled "Defaults to true".
    private static boolean autoStartEnabled(Application app) {
        return app.getAutoStartConfiguration() == null
                || !Boolean.FALSE.equals(app.getAutoStartConfiguration().getEnabled());
    }

    // ---- Tags ---------------------------------------------------------------------------------

    public synchronized Map<String, String> listTags(ResourceArn resource) {
        Application app = applicationFor(resource);
        return app.getTags() != null ? new LinkedHashMap<>(app.getTags()) : new LinkedHashMap<>();
    }

    public synchronized void tagResource(ResourceArn resource, Map<String, String> tags) {
        Application app = applicationFor(resource);
        app.setTags(withTags(app.getTags(), tags));
        storage.put(app.getApplicationId(), app);
    }

    public synchronized void untagResource(ResourceArn resource, List<String> tagKeys) {
        Application app = applicationFor(resource);
        Map<String, String> remaining = withoutTags(app.getTags(), tagKeys);
        if (app.getTags() == null || app.getTags().isEmpty()) {
            return;
        }
        app.setTags(remaining);
        storage.put(app.getApplicationId(), app);
    }

    /**
     * Parses a tagging ARN: {@code arn:<partition>:emr-serverless:<region>:<account>:/applications/<id>},
     * the form CreateApplication returns, with {@code /jobruns/<id>} for a job run. An ARN of another
     * Region names nothing here, and neither does a session's, as sessions are not emulated.
     */
    public ResourceArn resourceArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resourceArn: " + arn, 400);
        }
        Matcher matcher = RESOURCE.matcher(parsed.resource());
        if (!"emr-serverless".equals(parsed.service()) || !matcher.matches()) {
            throw new AwsException("ValidationException", "Invalid resourceArn: " + arn, 400);
        }
        if (!parsed.region().equals(regionResolver.getRegion()) || "sessions".equals(matcher.group(2))) {
            throw resourceNotFound(arn);
        }
        return new ResourceArn(arn, matcher.group(1), matcher.group(3));
    }

    /** Whether the application exists and has not been deleted, which leaves it TERMINATED. */
    public synchronized boolean isLive(String applicationId) {
        return live(applicationId).isPresent();
    }

    /**
     * Runs {@code use} on the application when it exists and has not been deleted, under this service's
     * lock, so that it cannot be deleted meanwhile, as with {@link #admitJobRun}; returns whether it ran.
     */
    public synchronized boolean whileLive(String applicationId, Consumer<Application> use) {
        Optional<Application> app = live(applicationId);
        app.ifPresent(use);
        return app.isPresent();
    }

    private Optional<Application> live(String applicationId) {
        return storage.get(applicationId).filter(app -> !"TERMINATED".equals(app.getState()));
    }

    static AwsException resourceNotFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Resource " + arn + " not found", 404);
    }

    private Application applicationFor(ResourceArn resource) {
        return storage.get(resource.applicationId())
                .filter(app -> resource.arn().equals(app.getArn()))
                .filter(app -> !"TERMINATED".equals(app.getState()))
                .orElseThrow(() -> resourceNotFound(resource.arn()));
    }

    /**
     * A resource's tags once TagResource adds {@code added}, which replace any with the same key. An
     * empty map is a valid TagMap (min 0) and changes nothing; the dispatcher already refuses a missing
     * one.
     */
    static Map<String, String> withTags(Map<String, String> current, Map<String, String> added) {
        if (added == null) {
            throw new AwsException("ValidationException", "tags is required", 400);
        }
        validateTags(added);
        Map<String, String> merged = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        merged.putAll(added);
        if (merged.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                    "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        return merged;
    }

    /** A resource's tags once UntagResource removes {@code tagKeys}. */
    static Map<String, String> withoutTags(Map<String, String> current, List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("ValidationException", "tagKeys is required", 400);
        }
        tagKeys.forEach(EmrServerlessService::validateTagKey);
        Map<String, String> remaining = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        tagKeys.forEach(remaining::remove);
        return remaining;
    }

    /**
     * The violations of a {@code states} filter: at most {@code max} states, each one of
     * {@code allowed}. A typo would otherwise match nothing and list nothing.
     */
    static List<String> statesViolations(List<String> states, List<String> allowed, int max) {
        List<String> violations = new ArrayList<>();
        if (states == null) {
            return violations;
        }
        if (states.size() > max) {
            violations.add(SmithyValidationErrors.Violations.QUOTED.constraint("states", states.toString(),
                    "Member must have length less than or equal to " + max));
        }
        if (!allowed.containsAll(states)) {
            violations.add(SmithyValidationErrors.notAllInEnum(states, "states", allowed));
        }
        return violations;
    }

    /**
     * Refuses an image configuration, the application's or a worker type's, that a new application leaves
     * without an {@code imageUri} ("This field is required when you create a new application"), or whose image
     * is in an ECR repository of another Region ("The Amazon ECR repository must be in the same AWS Region that
     * you use to launch EMR Serverless applications"). No source words either refusal, so both are Floci's. An
     * image outside ECR has no Region to compare.
     */
    private void validateImages(ImageConfiguration image, Map<String, WorkerTypeSpecification> workerTypes,
                                boolean creating) {
        validateImage("imageConfiguration", image, creating);
        if (workerTypes != null) {
            workerTypes.forEach((workerType, specification) -> validateImage(
                    "workerTypeSpecifications." + workerType + ".imageConfiguration",
                    specification != null ? specification.getImageConfiguration() : null, creating));
        }
    }

    private void validateImage(String member, ImageConfiguration image, boolean creating) {
        if (image == null) {
            return;
        }
        String uri = image.getImageUri();
        if (uri == null) {
            if (creating) {
                throw new AwsException("ValidationException", member + ".imageUri is required", 400);
            }
            return;
        }
        String region = regionResolver.getRegion();
        Optional<String> imageRegion = EcrRegistryManager.imageRegion(uri);
        if (imageRegion.isPresent() && !imageRegion.get().equals(region)) {
            throw new AwsException("ValidationException", "Image " + uri + " is in an Amazon ECR repository in "
                    + imageRegion.get() + "; it must be in the application's Region, " + region + ".", 400);
        }
    }

    // An update's image configuration, or none when it leaves imageUri out: "If you leave this field blank in an
    // update, Amazon EMR will remove the image configuration."
    private static ImageConfiguration imageOrNone(ImageConfiguration image) {
        return image != null && image.getImageUri() != null ? image : null;
    }

    /** At most 50 tags, each key and value within TagKey and TagValue and free of the reserved prefix. */
    static void validateTags(Map<String, String> tags) {
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                    "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        tags.forEach((key, value) -> {
            validateTagKey(key);
            if (hasReservedPrefix(key)) {
                throw new AwsException("ValidationException",
                        "Tag keys may not begin with the reserved prefix aws: (" + key + ")", 400);
            }
            if (value == null || value.length() > 256 || !TAG_TEXT.matcher(value).matches()) {
                throw new AwsException("ValidationException", "Invalid value for tag " + key, 400);
            }
            if (hasReservedPrefix(value)) {
                throw new AwsException("ValidationException",
                        "Tag values may not begin with the reserved prefix aws: (tag " + key + ")", 400);
            }
        });
    }

    private static void validateTagKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128 || !TAG_TEXT.matcher(key).matches()) {
            throw new AwsException("ValidationException", "Invalid tag key: " + key, 400);
        }
    }

    private static boolean hasReservedPrefix(String text) {
        return text.regionMatches(true, 0, RESERVED_PREFIX, 0, RESERVED_PREFIX.length());
    }

    /** An application or job run id: 16 lowercase hexadecimal characters, within the model's [0-9a-z]+. */
    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String buildArn(String id) {
        String region = regionResolver.getRegion();
        String accountId = requestContext != null && requestContext.getAccountId() != null ? requestContext.getAccountId() : config.defaultAccountId();
        return AwsArnUtils.Arn.of("emr-serverless", region, accountId, "/applications/" + id).toString();
    }

    private static boolean inRegion(Application app, String region) {
        if (app.getArn() == null) {
            return false;
        }
        try {
            return region.equals(AwsArnUtils.parse(app.getArn()).region());
        } catch (IllegalArgumentException ignored) {
            // A malformed stored ARN cannot establish Region ownership.
            return false;
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private ApplicationSummary toSummary(Application app) {
        ApplicationSummary summary = new ApplicationSummary();
        summary.setId(app.getApplicationId());
        summary.setArn(app.getArn());
        summary.setName(app.getName());
        summary.setReleaseLabel(app.getReleaseLabel());
        summary.setType(app.getType());
        summary.setState(app.getState());
        summary.setStateDetails(app.getStateDetails());
        summary.setCreatedAt(app.getCreatedAt());
        summary.setUpdatedAt(app.getUpdatedAt());
        summary.setArchitecture(app.getArchitecture());
        return summary;
    }
}
