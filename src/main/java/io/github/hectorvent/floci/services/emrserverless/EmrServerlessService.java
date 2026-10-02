package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.ApplicationSummary;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsRequest;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class EmrServerlessService {

    // The EMR Serverless user guide's tagging limitations: at most 50 user-created tags per resource
    // (the model's TagMap max of 200 only bounds a single request), and no "aws:" prefix, in any case,
    // on a key or a value. TagKey and TagValue share one character set in the API model.
    private static final int MAX_TAGS = 50;
    private static final String RESERVED_PREFIX = "aws:";
    private static final Pattern TAG_TEXT = Pattern.compile("[A-Za-z0-9 /_.:=+@-]*");
    private static final Pattern RESOURCE = Pattern.compile("/applications/([0-9a-zA-Z]+)(?:/jobruns/([0-9a-zA-Z]+))?");

    private final EmulatorConfig config;
    private final AccountAwareStorageBackend<Application> storage;
    
    @Inject
    RequestContext requestContext;

    @Inject
    public EmrServerlessService(EmulatorConfig config, StorageFactory storageFactory) {
        this.config = config;
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

        if (request.getClientToken() != null) {
            for (Application existing : storage.scan(k -> true)) {
                if (request.getClientToken().equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }

        String id = generateId();
        String arn = buildArn(id);
        long now = System.currentTimeMillis();

        Application app = new Application();
        app.setApplicationId(id);
        app.setClientToken(request.getClientToken());
        app.setArn(arn);
        app.setName(request.getName());
        app.setReleaseLabel(request.getReleaseLabel());
        app.setType(request.getType());
        app.setState("CREATED");
        app.setStateDetails("");
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
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

        storage.put(id, app);
        return app;
    }

    public Application getApplication(String applicationId) {
        return storage.get(applicationId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application " + applicationId + " not found", 404));
    }

    public PaginatedResult<ApplicationSummary> listApplications(ListApplicationsRequest request) {
        List<Application> all = storage.scan(k -> true);
        if (request.getStates() != null && !request.getStates().isEmpty()) {
            all = all.stream().filter(app -> request.getStates().contains(app.getState())).collect(Collectors.toList());
        }
        
        PaginatedResult<Application> page = Pagination.paginate(all, Application::getApplicationId, request.getMaxResults(), request.getNextToken(), 50, "ValidationException");
        
        return new PaginatedResult<>(
                page.items().stream().map(this::toSummary).collect(Collectors.toList()),
                page.nextToken()
        );
    }

    public synchronized Application updateApplication(String applicationId, UpdateApplicationRequest request) {
        Application app = getApplication(applicationId);
        
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be updated.", 400);
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
            app.setImageConfiguration(request.getImageConfiguration());
        }
        if (request.getWorkerTypeSpecifications() != null) {
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

        app.setUpdatedAt(System.currentTimeMillis());
        storage.put(applicationId, app);
        return app;
    }

    public synchronized void deleteApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be deleted.", 400);
        }
        storage.delete(applicationId);
    }

    public synchronized void startApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STARTED".equals(state) || "STARTING".equals(state)) {
            return;
        }
        app.setState("STARTED");
        app.setUpdatedAt(System.currentTimeMillis());
        storage.put(applicationId, app);
    }

    public synchronized void stopApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STOPPED".equals(state) || "STOPPING".equals(state)) {
            return;
        }
        app.setState("STOPPED");
        app.setUpdatedAt(System.currentTimeMillis());
        storage.put(applicationId, app);
    }

    // ---- Tags ---------------------------------------------------------------------------------

    public synchronized Map<String, String> listTags(String arn) {
        Application app = applicationFor(arn);
        return app.getTags() != null ? new LinkedHashMap<>(app.getTags()) : new LinkedHashMap<>();
    }

    /** Adds the tags to the application's, replacing any with the same key. */
    public synchronized void tagResource(String arn, Map<String, String> tags) {
        Application app = applicationFor(arn);
        // An empty map is a valid TagMap (min 0) and changes nothing; the dispatcher already refuses a
        // missing one.
        if (tags == null) {
            throw new AwsException("ValidationException", "tags is required", 400);
        }
        validateTags(tags);
        Map<String, String> merged = app.getTags() != null ? new LinkedHashMap<>(app.getTags()) : new LinkedHashMap<>();
        merged.putAll(tags);
        if (merged.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                    "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        app.setTags(merged);
        storage.put(app.getApplicationId(), app);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        Application app = applicationFor(arn);
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("ValidationException", "tagKeys is required", 400);
        }
        tagKeys.forEach(EmrServerlessService::validateTagKey);
        if (app.getTags() == null || app.getTags().isEmpty()) {
            return;
        }
        Map<String, String> remaining = new LinkedHashMap<>(app.getTags());
        tagKeys.forEach(remaining::remove);
        app.setTags(remaining);
        storage.put(app.getApplicationId(), app);
    }

    /**
     * The application an EMR Serverless ARN names:
     * {@code arn:<partition>:emr-serverless:<region>:<account>:/applications/<id>}, the form
     * CreateApplication returns. A job run ARN ({@code .../jobruns/<id>}) is refused, as job runs are
     * not emulated.
     */
    private Application applicationFor(String arn) {
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
        if (matcher.group(2) != null) {
            throw new AwsException("ResourceNotFoundException", "Job run " + matcher.group(2) + " not found", 404);
        }
        return storage.get(matcher.group(1))
                .filter(app -> arn.equals(app.getArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Resource " + arn + " not found", 404));
    }

    /** At most 50 tags, each key and value within TagKey and TagValue and free of the reserved prefix. */
    private static void validateTags(Map<String, String> tags) {
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

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String buildArn(String id) {
        String region = requestContext != null && requestContext.getRegion() != null ? requestContext.getRegion() : config.defaultRegion();
        String accountId = requestContext != null && requestContext.getAccountId() != null ? requestContext.getAccountId() : config.defaultAccountId();
        return AwsArnUtils.Arn.of("emr-serverless", region, accountId, "/applications/" + id).toString();
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
