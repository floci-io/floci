package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

@ApplicationScoped
public class SageMakerService {
    private static final Logger LOG = Logger.getLogger(SageMakerService.class);
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_RESULTS_CEILING = 100;
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    private final StorageBackend<String, ModelResource> modelStore;
    private final StorageBackend<String, EndpointConfigResource> endpointConfigStore;
    private final StorageBackend<String, EndpointResource> endpointStore;
    private final StorageBackend<String, TrainingJobResource> trainingJobStore;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SageMakerEndpointManager endpointManager;
    private final SageMakerTrainingRunner trainingRunner;
    private final Clock clock;

    @Inject
    public SageMakerService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper,
                            SageMakerEndpointManager endpointManager, SageMakerTrainingRunner trainingRunner) {
        this(storageFactory.create("sagemaker", "sagemaker-models.json", new TypeReference<Map<String, ModelResource>>() {}),
                storageFactory.create("sagemaker", "sagemaker-endpoint-configs.json", new TypeReference<Map<String, EndpointConfigResource>>() {}),
                storageFactory.create("sagemaker", "sagemaker-endpoints.json", new TypeReference<Map<String, EndpointResource>>() {}),
                storageFactory.create("sagemaker", "sagemaker-training-jobs.json", new TypeReference<Map<String, TrainingJobResource>>() {}),
                regionResolver, mapper, endpointManager, trainingRunner, Clock.systemUTC());
    }

    SageMakerService(StorageBackend<String, ModelResource> modelStore,
                     StorageBackend<String, EndpointConfigResource> endpointConfigStore,
                     StorageBackend<String, EndpointResource> endpointStore,
                     StorageBackend<String, TrainingJobResource> trainingJobStore,
                     RegionResolver regionResolver,
                     ObjectMapper mapper,
                     SageMakerEndpointManager endpointManager,
                     SageMakerTrainingRunner trainingRunner) {
        this(modelStore, endpointConfigStore, endpointStore, trainingJobStore, regionResolver, mapper, endpointManager,
                trainingRunner, Clock.systemUTC());
    }

    SageMakerService(StorageBackend<String, ModelResource> modelStore,
                     StorageBackend<String, EndpointConfigResource> endpointConfigStore,
                     StorageBackend<String, EndpointResource> endpointStore,
                     StorageBackend<String, TrainingJobResource> trainingJobStore,
                     RegionResolver regionResolver,
                     ObjectMapper mapper,
                     SageMakerEndpointManager endpointManager,
                     SageMakerTrainingRunner trainingRunner,
                     Clock clock) {
        this.modelStore = modelStore;
        this.endpointConfigStore = endpointConfigStore;
        this.endpointStore = endpointStore;
        this.trainingJobStore = trainingJobStore;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.endpointManager = endpointManager;
        this.trainingRunner = trainingRunner;
        this.clock = clock;
    }

    public synchronized ObjectNode createModel(JsonNode request, String region) {
        String name = required(request, "ModelName");
        if (model(region, name).isPresent()) {
            throw new AwsException("ValidationException", "Cannot create already existing model \"" + name + "\".", 400);
        }
        JsonNode primary = request.path("PrimaryContainer");
        JsonNode containers = request.path("Containers");
        boolean hasContainers = containers.isArray() && !containers.isEmpty() && containers.get(0).isObject();
        if (!primary.isObject() && !hasContainers) {
            throw validation("PrimaryContainer or Containers is required");
        }
        ModelResource model = new ModelResource();
        model.modelName = name;
        model.modelArn = arn(region, "model/" + name);
        model.executionRoleArn = text(request, "ExecutionRoleArn");
        model.creationTime = nowMillis();
        model.region = region;
        model.accountId = regionResolver.getAccountId();
        model.primaryContainer = primary.isObject() ? map(primary) : map(containers.get(0));
        model.containers = listMap(containers);
        model.tags = tagsFromList(request.path("Tags"));
        modelStore.put(regionKey(region, name), model);
        ObjectNode out = mapper.createObjectNode();
        out.put("ModelArn", model.modelArn);
        return out;
    }

    public ObjectNode describeModel(JsonNode request, String region) {
        String name = required(request, "ModelName");
        ModelResource m = model(region, name).orElseThrow(() -> validation("Could not find model \"" + name + "\"."));
        ObjectNode out = mapper.createObjectNode();
        out.put("ModelName", m.modelName);
        out.put("ModelArn", m.modelArn);
        out.set("PrimaryContainer", mapper.valueToTree(m.primaryContainer));
        if (!m.containers.isEmpty()) {
            out.set("Containers", mapper.valueToTree(m.containers));
        }
        if (m.executionRoleArn != null) {
            out.put("ExecutionRoleArn", m.executionRoleArn);
        }
        out.put("CreationTime", epoch(m.creationTime));
        return out;
    }

    public synchronized ObjectNode deleteModel(JsonNode request, String region) {
        String name = required(request, "ModelName");
        model(region, name).orElseThrow(() -> validation("Could not find model \"" + name + "\"."));
        modelStore.delete(regionKey(region, name));
        return mapper.createObjectNode();
    }

    public ObjectNode listModels(JsonNode request, String region) {
        SortOptions sort = sortOptions(request, "CreationTime", "Descending", "Name", "CreationTime");
        String sortBy = sort.sortBy();
        String sortOrder = sort.sortOrder();
        Comparator<ModelResource> comparator = modelComparator(sortBy, sortOrder);
        List<ModelResource> models = modelStore.scan(k -> k.startsWith(region + "::")).stream().sorted(comparator).toList();
        String nameContains = text(request, "NameContains");
        if (nameContains != null && !nameContains.isBlank()) {
            models = models.stream().filter(m -> m.modelName.contains(nameContains)).toList();
        }
        return paginate(models, request, sortBy, sortOrder,
                new CursorScope("ListModels", region,
                        nameContains == null || nameContains.isBlank() ? "" : nameContains, ""),
                m -> modelCursor(m, sortBy, sortOrder),
                m -> modelAnchor(m, sortBy), comparator, (m, n) -> {
            n.put("ModelName", m.modelName);
            n.put("ModelArn", m.modelArn);
            n.put("CreationTime", epoch(m.creationTime));
        }, "Models");
    }

    public synchronized ObjectNode createEndpointConfig(JsonNode request, String region) {
        String name = required(request, "EndpointConfigName");
        if (endpointConfig(region, name).isPresent()) {
            throw new AwsException("ValidationException", "Cannot create already existing endpoint configuration \"" + name + "\".", 400);
        }
        List<Map<String, Object>> variants = listMap(request.path("ProductionVariants"));
        if (variants.isEmpty()) {
            throw validation("ProductionVariants is required");
        }
        for (Map<String, Object> v : variants) {
            String modelName = SageMakerEndpointManager.string(v.get("ModelName"));
            if (modelName == null || modelName.isBlank()) {
                throw validation("ProductionVariants.ModelName is required");
            }
            if (model(region, modelName).isEmpty()) {
                throw validation("Could not find model \"" + modelName + "\".");
            }
        }
        EndpointConfigResource cfg = new EndpointConfigResource();
        cfg.endpointConfigName = name;
        cfg.endpointConfigArn = arn(region, "endpoint-config/" + name);
        cfg.productionVariants = variants;
        cfg.creationTime = nowMillis();
        cfg.region = region;
        cfg.accountId = regionResolver.getAccountId();
        cfg.tags = tagsFromList(request.path("Tags"));
        endpointConfigStore.put(regionKey(region, name), cfg);
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointConfigArn", cfg.endpointConfigArn);
        return out;
    }

    public ObjectNode describeEndpointConfig(JsonNode request, String region) {
        String name = required(request, "EndpointConfigName");
        EndpointConfigResource cfg = endpointConfig(region, name)
                .orElseThrow(() -> validation("Could not find endpoint configuration \"" + name + "\"."));
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointConfigName", cfg.endpointConfigName);
        out.put("EndpointConfigArn", cfg.endpointConfigArn);
        out.set("ProductionVariants", mapper.valueToTree(cfg.productionVariants));
        out.put("CreationTime", epoch(cfg.creationTime));
        return out;
    }

    public synchronized ObjectNode deleteEndpointConfig(JsonNode request, String region) {
        String name = required(request, "EndpointConfigName");
        endpointConfig(region, name).orElseThrow(() -> validation("Could not find endpoint configuration \"" + name + "\"."));
        endpointConfigStore.delete(regionKey(region, name));
        return mapper.createObjectNode();
    }

    public ObjectNode listEndpointConfigs(JsonNode request, String region) {
        SortOptions sort = sortOptions(request, "CreationTime", "Descending", "Name", "CreationTime");
        String sortBy = sort.sortBy();
        String sortOrder = sort.sortOrder();
        Comparator<EndpointConfigResource> comparator = endpointConfigComparator(sortBy, sortOrder);
        List<EndpointConfigResource> configs = endpointConfigStore.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(comparator).toList();
        return paginate(configs, request, sortBy, sortOrder,
                new CursorScope("ListEndpointConfigs", region, "", ""),
                c -> endpointConfigCursor(c, sortBy, sortOrder),
                c -> endpointConfigAnchor(c, sortBy), comparator, (c, n) -> {
            n.put("EndpointConfigName", c.endpointConfigName);
            n.put("EndpointConfigArn", c.endpointConfigArn);
            n.put("CreationTime", epoch(c.creationTime));
        }, "EndpointConfigs");
    }

    public synchronized ObjectNode createEndpoint(JsonNode request, String region) {
        String name = required(request, "EndpointName");
        if (endpoint(region, name).isPresent()) {
            throw new AwsException("ValidationException", "Cannot create already existing endpoint \"" + name + "\".", 400);
        }
        String cfgName = required(request, "EndpointConfigName");
        endpointConfig(region, cfgName).orElseThrow(() -> validation("Could not find endpoint configuration \"" + cfgName + "\"."));
        EndpointResource ep = new EndpointResource();
        ep.endpointName = name;
        ep.endpointArn = arn(region, "endpoint/" + name);
        ep.endpointConfigName = cfgName;
        ep.endpointStatus = "Creating";
        ep.creationTime = nowMillis();
        ep.lastModifiedTime = ep.creationTime;
        ep.region = region;
        ep.accountId = regionResolver.getAccountId();
        ep.tags = tagsFromList(request.path("Tags"));
        ep.generation = 1;
        endpointStore.put(regionKey(region, name), ep);
        endpointManager.startEndpointAsync(ep, this);
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointArn", ep.endpointArn);
        return out;
    }

    public synchronized ObjectNode updateEndpoint(JsonNode request, String region) {
        String name = required(request, "EndpointName");
        EndpointResource ep = endpoint(region, name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\"."));
        String cfgName = required(request, "EndpointConfigName");
        endpointConfig(region, cfgName).orElseThrow(() -> validation("Could not find endpoint configuration \"" + cfgName + "\"."));
        if (ep.containerId != null) {
            endpointManager.stopEndpoint(ep);
        }
        ep.endpointConfigName = cfgName;
        ep.endpointStatus = "Creating";
        ep.failureReason = null;
        ep.containerId = null;
        ep.lastModifiedTime = nowMillis();
        ep.generation++;
        endpointStore.put(regionKey(region, name), ep);
        endpointManager.startEndpointAsync(ep, this);
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointArn", ep.endpointArn);
        return out;
    }

    public ObjectNode describeEndpoint(JsonNode request, String region) {
        String name = required(request, "EndpointName");
        EndpointResource ep = endpoint(region, name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\"."));
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointName", ep.endpointName);
        out.put("EndpointArn", ep.endpointArn);
        out.put("EndpointConfigName", ep.endpointConfigName);
        out.put("EndpointStatus", ep.endpointStatus);
        if (ep.failureReason != null) {
            out.put("FailureReason", ep.failureReason);
        }
        out.put("CreationTime", epoch(ep.creationTime));
        out.put("LastModifiedTime", epoch(ep.lastModifiedTime));
        return out;
    }

    public synchronized ObjectNode deleteEndpoint(JsonNode request, String region) {
        String name = required(request, "EndpointName");
        EndpointResource ep = endpoint(region, name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\"."));
        ep.endpointStatus = "Deleting";
        // Bump the generation before removing the record: a start worker still in flight for
        // this endpoint checks the generation against what is (no longer) in the store and will
        // no-op instead of resurrecting the record it raced with this delete.
        ep.generation++;
        endpointStore.put(regionKey(region, name), ep);
        endpointManager.stopEndpoint(ep);
        endpointStore.delete(regionKey(region, name));
        return mapper.createObjectNode();
    }

    public ObjectNode listEndpoints(JsonNode request, String region) {
        SortOptions sort = sortOptions(request, "CreationTime", "Descending", "Name", "CreationTime", "Status");
        String sortBy = sort.sortBy();
        String sortOrder = sort.sortOrder();
        Comparator<EndpointResource> comparator = endpointComparator(sortBy, sortOrder);
        List<EndpointResource> endpoints = endpointStore.scan(k -> k.startsWith(region + "::")).stream()
                .map(SageMakerService::endpointListSnapshot)
                .sorted(comparator).toList();
        return paginate(endpoints, request, sortBy, sortOrder,
                new CursorScope("ListEndpoints", region, "", ""),
                e -> endpointCursor(e, sortBy, sortOrder),
                e -> endpointAnchor(e, sortBy), comparator, (e, n) -> {
            n.put("EndpointName", e.endpointName);
            n.put("EndpointArn", e.endpointArn);
            n.put("EndpointStatus", e.endpointStatus);
            n.put("CreationTime", epoch(e.creationTime));
            n.put("LastModifiedTime", epoch(e.lastModifiedTime));
        }, "Endpoints");
    }

    public synchronized ObjectNode createTrainingJob(JsonNode request, String region) {
        String name = required(request, "TrainingJobName");
        if (trainingJob(region, name).isPresent()) {
            throw new AwsException("ResourceInUse", "Training job already exists: " + name, 400);
        }
        if (!request.path("AlgorithmSpecification").isObject() || text(request.path("AlgorithmSpecification"), "TrainingImage") == null) {
            throw validation("AlgorithmSpecification.TrainingImage is required");
        }
        if (!request.path("OutputDataConfig").isObject() || text(request.path("OutputDataConfig"), "S3OutputPath") == null) {
            throw validation("OutputDataConfig.S3OutputPath is required");
        }
        validateS3Uri(text(request.path("OutputDataConfig"), "S3OutputPath"), "OutputDataConfig.S3OutputPath");
        for (JsonNode channel : request.path("InputDataConfig")) {
            JsonNode s3DataSource = channel.path("DataSource").path("S3DataSource");
            if (s3DataSource.isObject()) {
                validateS3Uri(text(s3DataSource, "S3Uri"), "InputDataConfig[].DataSource.S3DataSource.S3Uri");
            }
        }
        TrainingJobResource job = new TrainingJobResource();
        job.trainingJobName = name;
        job.trainingJobArn = arn(region, "training-job/" + name);
        job.trainingJobStatus = "InProgress";
        job.secondaryStatus = "Starting";
        job.creationTime = nowMillis();
        job.region = region;
        job.accountId = regionResolver.getAccountId();
        job.algorithmSpecification = map(request.path("AlgorithmSpecification"));
        job.inputDataConfig = listMap(request.path("InputDataConfig"));
        job.outputDataConfig = map(request.path("OutputDataConfig"));
        job.resourceConfig = map(request.path("ResourceConfig"));
        job.stoppingCondition = map(request.path("StoppingCondition"));
        job.hyperParameters = stringMap(request.path("HyperParameters"));
        job.tags = tagsFromList(request.path("Tags"));
        trainingJobStore.put(regionKey(region, name), job);
        trainingRunner.runAsync(job, this);
        ObjectNode out = mapper.createObjectNode();
        out.put("TrainingJobArn", job.trainingJobArn);
        return out;
    }

    public ObjectNode describeTrainingJob(JsonNode request, String region) {
        String name = required(request, "TrainingJobName");
        TrainingJobResource job = trainingJob(region, name).orElseThrow(() -> validation("Could not find training job \"" + name + "\"."));
        ObjectNode out = mapper.valueToTree(Map.of(
                "TrainingJobName", job.trainingJobName,
                "TrainingJobArn", job.trainingJobArn,
                "TrainingJobStatus", job.trainingJobStatus,
                "SecondaryStatus", job.secondaryStatus == null ? "" : job.secondaryStatus,
                "AlgorithmSpecification", job.algorithmSpecification,
                "InputDataConfig", job.inputDataConfig,
                "OutputDataConfig", job.outputDataConfig,
                "ResourceConfig", job.resourceConfig,
                "StoppingCondition", job.stoppingCondition,
                "CreationTime", epoch(job.creationTime)
        ));
        if (job.trainingStartTime > 0) {
            out.put("TrainingStartTime", epoch(job.trainingStartTime));
        }
        if (job.trainingEndTime > 0) {
            out.put("TrainingEndTime", epoch(job.trainingEndTime));
        }
        if (job.failureReason != null) {
            out.put("FailureReason", job.failureReason);
        }
        if (job.modelArtifactsS3ModelArtifacts != null) {
            ObjectNode ma = mapper.createObjectNode();
            ma.put("S3ModelArtifacts", job.modelArtifactsS3ModelArtifacts);
            out.set("ModelArtifacts", ma);
        }
        return out;
    }

    public ObjectNode listTrainingJobs(JsonNode request, String region) {
        SortOptions sort = sortOptions(request, "CreationTime", "Ascending", "Name", "CreationTime", "Status");
        String sortBy = sort.sortBy();
        String sortOrder = sort.sortOrder();
        Comparator<TrainingJobResource> comparator = trainingJobComparator(sortBy, sortOrder);
        List<TrainingJobResource> jobs = trainingJobStore.scan(k -> k.startsWith(region + "::")).stream()
                .map(SageMakerService::trainingJobListSnapshot)
                .sorted(comparator).toList();
        String nameContains = text(request, "NameContains");
        if (nameContains != null && !nameContains.isBlank()) {
            jobs = jobs.stream().filter(j -> j.trainingJobName.contains(nameContains)).toList();
        }
        String statusEquals = text(request, "StatusEquals");
        if (statusEquals != null && !statusEquals.isBlank()) {
            jobs = jobs.stream().filter(j -> statusEquals.equals(j.trainingJobStatus)).toList();
        }
        return paginate(jobs, request, sortBy, sortOrder,
                new CursorScope("ListTrainingJobs", region,
                        nameContains == null || nameContains.isBlank() ? "" : nameContains,
                        statusEquals == null || statusEquals.isBlank() ? "" : statusEquals),
                j -> trainingJobCursor(j, sortBy, sortOrder),
                j -> trainingJobAnchor(j, sortBy), comparator, (j, n) -> {
            n.put("TrainingJobName", j.trainingJobName);
            n.put("TrainingJobArn", j.trainingJobArn);
            n.put("TrainingJobStatus", j.trainingJobStatus);
            n.put("CreationTime", epoch(j.creationTime));
        }, "TrainingJobSummaries");
    }

    public synchronized ObjectNode stopTrainingJob(JsonNode request, String region) {
        String name = required(request, "TrainingJobName");
        TrainingJobResource job = trainingJob(region, name).orElseThrow(() -> validation("Could not find training job \"" + name + "\"."));
        // AWS only transitions InProgress/Stopping jobs; a job already in a terminal state is
        // left as-is rather than relabeled "Stopped" out from under its real outcome.
        if ("InProgress".equals(job.trainingJobStatus) || "Stopping".equals(job.trainingJobStatus)) {
            trainingRunner.stop(job);
            job.trainingJobStatus = "Stopped";
            job.secondaryStatus = "Stopped";
            job.trainingEndTime = nowMillis();
            trainingJobStore.put(regionKey(region, name), job);
        }
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode addTags(JsonNode request) {
        String arn = required(request, "ResourceArn");
        Map<String, String> tags = tagsFromList(request.path("Tags"));
        resourceTags(arn).putAll(tags);
        persistByArn(arn);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagsArray(resourceTags(arn)));
        return out;
    }

    public ObjectNode listTags(JsonNode request) {
        String arn = required(request, "ResourceArn");
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagsArray(resourceTags(arn)));
        return out;
    }

    public synchronized ObjectNode deleteTags(JsonNode request) {
        String arn = required(request, "ResourceArn");
        Map<String, String> tags = resourceTags(arn);
        request.path("TagKeys").forEach(k -> tags.remove(k.asText()));
        persistByArn(arn);
        return mapper.createObjectNode();
    }

    Optional<ModelResource> model(String region, String name) {
        return modelStore.get(regionKey(region, name));
    }

    Optional<EndpointConfigResource> endpointConfig(String region, String name) {
        return endpointConfigStore.get(regionKey(region, name));
    }

    public Optional<EndpointResource> endpoint(String region, String name) {
        return endpointStore.get(regionKey(region, name));
    }

    Optional<TrainingJobResource> trainingJob(String region, String name) {
        return trainingJobStore.get(regionKey(region, name));
    }

    public ModelResource modelForEndpoint(EndpointResource ep) {
        EndpointConfigResource cfg = endpointConfig(ep.region, ep.endpointConfigName)
                .orElseThrow(() -> validation("Could not find endpoint configuration \"" + ep.endpointConfigName + "\"."));
        String modelName = String.valueOf(cfg.productionVariants.get(0).get("ModelName"));
        return model(ep.region, modelName).orElseThrow(() -> validation("Could not find model \"" + modelName + "\"."));
    }

    /**
     * Persists the result of an endpoint start (success or failure) unless a delete or a
     * newer start has superseded it since {@code ep} was handed to {@link SageMakerEndpointManager}.
     * Returns {@code false} when the caller should tear down whatever it just started instead
     * of publishing it, because the endpoint it belongs to is gone or has moved on.
     */
    synchronized boolean finalizeEndpointStart(EndpointResource ep) {
        EndpointResource current = endpointStore.get(regionKey(ep.region, ep.endpointName)).orElse(null);
        if (current == null || current.generation != ep.generation) {
            return false;
        }
        ep.lastModifiedTime = nowMillis();
        endpointStore.put(regionKey(ep.region, ep.endpointName), ep);
        return true;
    }

    /**
     * Publishes a training run's progress or outcome only while the job the run started from is
     * still stored. A state reset interrupts in-flight runs and wipes the store, and an
     * interrupted run's failure write must not bring the wiped job back, whether it lands before
     * or after the wipe, nor overwrite a same-named job created after the reset.
     */
    public synchronized boolean updateTrainingJob(TrainingJobResource job) {
        String key = regionKey(job.region, job.trainingJobName);
        TrainingJobResource current = trainingJobStore.get(key).orElse(null);
        if (current == null || !Objects.equals(current.trainingJobArn, job.trainingJobArn)
                || current.creationTime != job.creationTime) {
            LOG.debugv("SageMaker training job {0} is no longer the stored job; discarding its run update",
                    job.trainingJobName);
            return false;
        }
        trainingJobStore.put(key, job);
        return true;
    }

    private Map<String, String> resourceTags(String arn) {
        String region = arnRegion(arn);
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        if (arn.contains(":model/")) return model(region, name).orElseThrow(() -> validation("Could not find model \"" + name + "\".")).tags;
        if (arn.contains(":endpoint-config/")) return endpointConfig(region, name).orElseThrow(() -> validation("Could not find endpoint configuration \"" + name + "\".")).tags;
        if (arn.contains(":endpoint/")) return endpoint(region, name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\".")).tags;
        if (arn.contains(":training-job/")) return trainingJob(region, name).orElseThrow(() -> validation("Could not find training job \"" + name + "\".")).tags;
        throw validation("ResourceArn is invalid");
    }

    private void persistByArn(String arn) {
        String region = arnRegion(arn);
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        if (arn.contains(":model/")) model(region, name).ifPresent(v -> modelStore.put(regionKey(region, name), v));
        else if (arn.contains(":endpoint-config/")) endpointConfig(region, name).ifPresent(v -> endpointConfigStore.put(regionKey(region, name), v));
        else if (arn.contains(":endpoint/")) endpoint(region, name).ifPresent(v -> endpointStore.put(regionKey(region, name), v));
        else if (arn.contains(":training-job/")) trainingJob(region, name).ifPresent(v -> trainingJobStore.put(regionKey(region, name), v));
    }

    /** ARN shape: {@code arn:aws:sagemaker:<region>:<account>:<type>/<name>}. */
    private static String arnRegion(String arn) {
        String region = AwsArnUtils.regionOrDefault(arn, "");
        if (region.isBlank()) {
            throw validation("ResourceArn is invalid");
        }
        return region;
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("sagemaker", region, regionResolver.getAccountId(), resource).toString();
    }

    private long nowMillis() {
        return clock.millis();
    }

    static double epoch(long millis) {
        return millis / 1000.0;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static void validateS3Uri(String uri, String field) {
        try {
            S3Uri.parse(uri);
        } catch (IllegalArgumentException e) {
            throw validation(field + ": " + e.getMessage());
        }
    }

    static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required");
        }
        return value;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    static Map<String, Object> map(JsonNode node) {
        if (!node.isObject()) {
            return new LinkedHashMap<>();
        }
        return STATIC_MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    static List<Map<String, Object>> listMap(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(map(n)));
        }
        return out;
    }

    static Map<String, String> tagsFromList(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isArray()) {
            node.forEach(t -> {
                String key = text(t, "Key");
                if (key != null && !key.isBlank()) {
                    out.put(key, text(t, "Value"));
                }
            });
        }
        return out;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode arr = mapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("Key", k);
            n.put("Value", v);
            arr.add(n);
        });
        return arr;
    }

    private <T> ObjectNode paginate(List<T> sorted, JsonNode request, String sortBy, String sortOrder,
                                    CursorScope scope,
                                    Function<T, SortCursor> cursorOf,
                                    Function<SortCursor, T> anchorOf, Comparator<T> comparator,
                                    BiConsumer<T, ObjectNode> render, String itemsField) {
        int max = maxResults(request);
        String nextToken = text(request, "NextToken");
        List<T> remaining = sorted;
        if (nextToken != null && !nextToken.isBlank()) {
            SortCursor cursor = decodeCursor(nextToken, sortBy, sortOrder, scope);
            T anchor = anchorOf.apply(cursor);
            remaining = remaining.stream().dropWhile(v -> comparator.compare(v, anchor) <= 0).toList();
        }
        return page(remaining, max, render, itemsField, value -> encodeCursor(cursorOf.apply(value), scope));
    }

    private static SortOptions sortOptions(JsonNode request, String defaultSortBy, String defaultSortOrder,
                                           String... allowed) {
        String sortBy = text(request, "SortBy");
        String sortOrder = text(request, "SortOrder");
        sortBy = sortBy == null ? defaultSortBy : sortBy;
        sortOrder = sortOrder == null ? defaultSortOrder : sortOrder;
        List<String> violations = new ArrayList<>();
        if (!List.of(allowed).contains(sortBy)) {
            violations.add(SmithyValidationErrors.notInEnum(sortBy, "sortBy", List.of(allowed)));
        }
        if (!sortOrder.equals("Ascending") && !sortOrder.equals("Descending")) {
            violations.add(SmithyValidationErrors.notInEnum(sortOrder, "sortOrder",
                    List.of("Ascending", "Descending")));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        return new SortOptions(sortBy, sortOrder);
    }

    private record SortOptions(String sortBy, String sortOrder) {
    }

    private static Comparator<ModelResource> modelComparator(String sortBy, String sortOrder) {
        Comparator<ModelResource> comparator = "Name".equals(sortBy)
                ? Comparator.comparing(m -> m.modelName)
                : Comparator.comparingLong((ModelResource m) -> m.creationTime).thenComparing(m -> m.modelName);
        return "Descending".equals(sortOrder) ? comparator.reversed() : comparator;
    }

    private static Comparator<EndpointConfigResource> endpointConfigComparator(String sortBy, String sortOrder) {
        Comparator<EndpointConfigResource> comparator = switch (sortBy) {
            case "Name" -> Comparator.comparing(c -> c.endpointConfigName);
            case "CreationTime" -> Comparator.comparingLong((EndpointConfigResource c) -> c.creationTime)
                    .thenComparing(c -> c.endpointConfigName);
            default -> throw new IllegalStateException("Unexpected endpoint config sort key: " + sortBy);
        };
        return "Descending".equals(sortOrder) ? comparator.reversed() : comparator;
    }

    private static EndpointResource endpointListSnapshot(EndpointResource endpoint) {
        EndpointResource snapshot = new EndpointResource();
        snapshot.endpointName = endpoint.endpointName;
        snapshot.endpointArn = endpoint.endpointArn;
        snapshot.endpointStatus = endpoint.endpointStatus;
        snapshot.creationTime = endpoint.creationTime;
        snapshot.lastModifiedTime = endpoint.lastModifiedTime;
        return snapshot;
    }

    private static TrainingJobResource trainingJobListSnapshot(TrainingJobResource job) {
        TrainingJobResource snapshot = new TrainingJobResource();
        snapshot.trainingJobName = job.trainingJobName;
        snapshot.trainingJobArn = job.trainingJobArn;
        snapshot.trainingJobStatus = job.trainingJobStatus;
        snapshot.creationTime = job.creationTime;
        return snapshot;
    }

    private static Comparator<EndpointResource> endpointComparator(String sortBy, String sortOrder) {
        Comparator<EndpointResource> comparator = switch (sortBy) {
            case "Name" -> Comparator.comparing(e -> e.endpointName);
            case "CreationTime" -> Comparator.comparingLong((EndpointResource e) -> e.creationTime)
                    .thenComparing(e -> e.endpointName);
            case "Status" -> Comparator.comparing((EndpointResource e) -> e.endpointStatus)
                    .thenComparing(e -> e.endpointName);
            default -> throw new IllegalStateException("Unexpected endpoint sort key: " + sortBy);
        };
        return "Descending".equals(sortOrder) ? comparator.reversed() : comparator;
    }

    private static Comparator<TrainingJobResource> trainingJobComparator(String sortBy, String sortOrder) {
        Comparator<TrainingJobResource> comparator = switch (sortBy) {
            case "Name" -> Comparator.comparing(j -> j.trainingJobName);
            case "CreationTime" -> Comparator.comparingLong((TrainingJobResource j) -> j.creationTime)
                    .thenComparing(j -> j.trainingJobName);
            case "Status" -> Comparator.comparing((TrainingJobResource j) -> j.trainingJobStatus)
                    .thenComparing(j -> j.trainingJobName);
            default -> throw new IllegalStateException("Unexpected training job sort key: " + sortBy);
        };
        return "Descending".equals(sortOrder) ? comparator.reversed() : comparator;
    }

    private static SortCursor endpointConfigCursor(EndpointConfigResource config, String sortBy, String sortOrder) {
        String value = "Name".equals(sortBy) ? config.endpointConfigName : Long.toString(config.creationTime);
        return new SortCursor(sortBy, sortOrder, value, config.endpointConfigName);
    }

    private static EndpointConfigResource endpointConfigAnchor(SortCursor cursor, String sortBy) {
        EndpointConfigResource anchor = new EndpointConfigResource();
        anchor.endpointConfigName = cursor.name();
        if ("CreationTime".equals(sortBy)) {
            anchor.creationTime = parseCursorTime(cursor.value());
        }
        return anchor;
    }

    private static SortCursor endpointCursor(EndpointResource endpoint, String sortBy, String sortOrder) {
        String value = switch (sortBy) {
            case "Name" -> endpoint.endpointName;
            case "CreationTime" -> Long.toString(endpoint.creationTime);
            case "Status" -> endpoint.endpointStatus;
            default -> throw new IllegalStateException("Unexpected endpoint sort key: " + sortBy);
        };
        return new SortCursor(sortBy, sortOrder, value, endpoint.endpointName);
    }

    private static EndpointResource endpointAnchor(SortCursor cursor, String sortBy) {
        EndpointResource anchor = new EndpointResource();
        anchor.endpointName = cursor.name();
        if ("CreationTime".equals(sortBy)) {
            anchor.creationTime = parseCursorTime(cursor.value());
        } else if ("Status".equals(sortBy)) {
            anchor.endpointStatus = cursor.value();
        }
        return anchor;
    }

    private static SortCursor trainingJobCursor(TrainingJobResource job, String sortBy, String sortOrder) {
        String value = switch (sortBy) {
            case "Name" -> job.trainingJobName;
            case "CreationTime" -> Long.toString(job.creationTime);
            case "Status" -> job.trainingJobStatus;
            default -> throw new IllegalStateException("Unexpected training job sort key: " + sortBy);
        };
        return new SortCursor(sortBy, sortOrder, value, job.trainingJobName);
    }

    private static TrainingJobResource trainingJobAnchor(SortCursor cursor, String sortBy) {
        TrainingJobResource anchor = new TrainingJobResource();
        anchor.trainingJobName = cursor.name();
        if ("CreationTime".equals(sortBy)) {
            anchor.creationTime = parseCursorTime(cursor.value());
        } else if ("Status".equals(sortBy)) {
            anchor.trainingJobStatus = cursor.value();
        }
        return anchor;
    }

    private static SortCursor modelCursor(ModelResource model, String sortBy, String sortOrder) {
        String value = "Name".equals(sortBy) ? model.modelName : Long.toString(model.creationTime);
        return new SortCursor(sortBy, sortOrder, value, model.modelName);
    }

    private static ModelResource modelAnchor(SortCursor cursor, String sortBy) {
        ModelResource anchor = new ModelResource();
        anchor.modelName = cursor.name();
        if ("CreationTime".equals(sortBy)) {
            anchor.creationTime = parseCursorTime(cursor.value());
        }
        return anchor;
    }

    private static long parseCursorTime(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw validation("Invalid NextToken.");
        }
    }

    private static String encodeCursor(SortCursor cursor, CursorScope scope) {
        if ("Name".equals(cursor.sortBy())) {
            return cursor.value();
        }
        ObjectNode token = STATIC_MAPPER.createObjectNode();
        token.put("version", 1);
        token.put("operation", scope.operation());
        token.put("region", scope.region());
        token.put("sortBy", cursor.sortBy());
        token.put("sortOrder", cursor.sortOrder());
        token.put("nameContains", scope.nameContains());
        token.put("statusEquals", scope.statusEquals());
        token.put("value", cursor.value());
        token.put("name", cursor.name());
        return Base64Url.encode(token.toString());
    }

    private static SortCursor decodeCursor(String token, String sortBy, String sortOrder, CursorScope scope) {
        if ("Name".equals(sortBy)) {
            return new SortCursor(sortBy, sortOrder, token, token);
        }
        try {
            JsonNode cursor = STATIC_MAPPER.readTree(Base64Url.decode(token));
            if (cursor == null || cursor.path("version").asInt() != 1
                    || !scope.operation().equals(cursor.path("operation").asText(null))
                    || !scope.region().equals(cursor.path("region").asText(null))
                    || !sortBy.equals(cursor.path("sortBy").asText(null))
                    || !sortOrder.equals(cursor.path("sortOrder").asText(null))
                    || !scope.nameContains().equals(cursor.path("nameContains").asText(null))
                    || !scope.statusEquals().equals(cursor.path("statusEquals").asText(null))
                    || !cursor.path("value").isTextual()
                    || !cursor.path("name").isTextual()
                    || cursor.path("name").asText().isBlank()) {
                throw validation("Invalid NextToken.");
            }
            return new SortCursor(sortBy, sortOrder, cursor.path("value").asText(), cursor.path("name").asText());
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw validation("Invalid NextToken.");
        }
    }

    private record SortCursor(String sortBy, String sortOrder, String value, String name) {
    }

    private record CursorScope(String operation, String region, String nameContains, String statusEquals) {
    }

    private <T> ObjectNode page(List<T> remaining, int max, BiConsumer<T, ObjectNode> render,
                                String itemsField, Function<T, String> tokenOf) {
        List<T> page = remaining.stream().limit(max).toList();
        ArrayNode arr = mapper.createArrayNode();
        page.forEach(v -> {
            ObjectNode n = mapper.createObjectNode();
            render.accept(v, n);
            arr.add(n);
        });
        ObjectNode out = mapper.createObjectNode();
        out.set(itemsField, arr);
        if (page.size() == max && remaining.size() > max) {
            out.put("NextToken", tokenOf.apply(page.get(page.size() - 1)));
        }
        return out;
    }

    private static int maxResults(JsonNode request) {
        JsonNode node = request.path("MaxResults");
        if (node.isMissingNode() || node.isNull()) {
            return DEFAULT_LIMIT;
        }
        int max = node.asInt();
        if (max < 1 || max > MAX_RESULTS_CEILING) {
            throw validation("MaxResults must be between 1 and " + MAX_RESULTS_CEILING + ".");
        }
        return max;
    }
}
