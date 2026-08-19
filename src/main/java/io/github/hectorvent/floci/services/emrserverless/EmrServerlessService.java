package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.ApplicationSummary;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EmrServerlessService {

    private final EmulatorConfig config;
    private final AccountAwareStorageBackend<Application> storage;

    @Inject
    public EmrServerlessService(EmulatorConfig config, StorageFactory storageFactory) {
        this.config = config;
        this.storage = storageFactory.create("emrserverless", "emr-serverless-applications.json",
                new TypeReference<Map<String, Application>>() {});
    }

    public Application createApplication(CreateApplicationRequest request) {
        String id = generateId();
        String arn = buildArn(id);
        long now = System.currentTimeMillis();

        Application app = new Application();
        app.setApplicationId(id);
        app.setArn(arn);
        app.setName(request.getName());
        app.setReleaseLabel(request.getReleaseLabel());
        app.setType(request.getType());
        app.setState("CREATED");
        app.setStateDetails("");
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
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
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application " + applicationId + " not found", 400));
    }

    public List<ApplicationSummary> listApplications() {
        return storage.scan(k -> true).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    public Application updateApplication(String applicationId, UpdateApplicationRequest request) {
        Application app = getApplication(applicationId);
        
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be updated.", 400);
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

        app.setUpdatedAt(System.currentTimeMillis());
        storage.put(applicationId, app);
        return app;
    }

    public void deleteApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if (!"CREATED".equals(state) && !"STOPPED".equals(state)) {
            throw new AwsException("ValidationException", "Application must be in a stopped or created state in order to be deleted.", 400);
        }
        storage.delete(applicationId);
    }

    public void startApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STARTED".equals(state) || "STARTING".equals(state)) {
            return;
        }
        app.setState("STARTED");
        app.setUpdatedAt(System.currentTimeMillis());
        storage.put(applicationId, app);
    }

    public void stopApplication(String applicationId) {
        Application app = getApplication(applicationId);
        String state = app.getState();
        if ("STOPPED".equals(state) || "STOPPING".equals(state)) {
            return;
        }
        app.setState("STOPPED");
        app.setUpdatedAt(System.currentTimeMillis());
        storage.put(applicationId, app);
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String buildArn(String id) {
        return String.format("arn:aws:emr-serverless:%s:%s:/applications/%s",
                config.defaultRegion(), config.defaultAccountId(), id);
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
