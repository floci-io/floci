package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class SageMakerService {
    static final int DEFAULT_LIMIT = 100;
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
        if (model(name).isPresent()) {
            throw new AwsException("ValidationException", "Cannot create already existing model \"" + name + "\".", 400);
        }
        JsonNode primary = request.path("PrimaryContainer");
        if (!primary.isObject() && !request.path("Containers").isArray()) {
            throw validation("PrimaryContainer or Containers is required");
        }
        ModelResource model = new ModelResource();
        model.modelName = name;
        model.modelArn = arn(region, "model/" + name);
        model.executionRoleArn = text(request, "ExecutionRoleArn");
        model.creationTime = nowMillis();
        model.region = region;
        model.accountId = regionResolver.getAccountId();
        model.primaryContainer = primary.isObject() ? map(primary) : map(request.path("Containers").get(0));
        model.containers = listMap(request.path("Containers"));
        model.tags = tagsFromList(request.path("Tags"));
        modelStore.put(name, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("ModelArn", model.modelArn);
        return out;
    }

    public ObjectNode describeModel(JsonNode request) {
        String name = required(request, "ModelName");
        ModelResource m = model(name).orElseThrow(() -> validation("Could not find model \"" + name + "\"."));
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

    public synchronized ObjectNode deleteModel(JsonNode request) {
        String name = required(request, "ModelName");
        model(name).orElseThrow(() -> validation("Could not find model \"" + name + "\"."));
        modelStore.delete(name);
        return mapper.createObjectNode();
    }

    public ObjectNode listModels(JsonNode request) {
        List<ModelResource> models = modelStore.scan(k -> true).stream()
                .sorted(Comparator.comparing(m -> m.modelName)).toList();
        ArrayNode arr = mapper.createArrayNode();
        page(models, request).forEach(m -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("ModelName", m.modelName);
            n.put("ModelArn", m.modelArn);
            n.put("CreationTime", epoch(m.creationTime));
            arr.add(n);
        });
        ObjectNode out = mapper.createObjectNode();
        out.set("Models", arr);
        return out;
    }

    public synchronized ObjectNode createEndpointConfig(JsonNode request, String region) {
        String name = required(request, "EndpointConfigName");
        if (endpointConfig(name).isPresent()) {
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
            if (model(modelName).isEmpty()) {
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
        endpointConfigStore.put(name, cfg);
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointConfigArn", cfg.endpointConfigArn);
        return out;
    }

    public ObjectNode describeEndpointConfig(JsonNode request) {
        String name = required(request, "EndpointConfigName");
        EndpointConfigResource cfg = endpointConfig(name)
                .orElseThrow(() -> validation("Could not find endpoint configuration \"" + name + "\"."));
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointConfigName", cfg.endpointConfigName);
        out.put("EndpointConfigArn", cfg.endpointConfigArn);
        out.set("ProductionVariants", mapper.valueToTree(cfg.productionVariants));
        out.put("CreationTime", epoch(cfg.creationTime));
        return out;
    }

    public synchronized ObjectNode deleteEndpointConfig(JsonNode request) {
        String name = required(request, "EndpointConfigName");
        endpointConfig(name).orElseThrow(() -> validation("Could not find endpoint configuration \"" + name + "\"."));
        endpointConfigStore.delete(name);
        return mapper.createObjectNode();
    }

    public ObjectNode listEndpointConfigs(JsonNode request) {
        ArrayNode arr = mapper.createArrayNode();
        endpointConfigStore.scan(k -> true).stream().sorted(Comparator.comparing(c -> c.endpointConfigName)).forEach(c -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("EndpointConfigName", c.endpointConfigName);
            n.put("EndpointConfigArn", c.endpointConfigArn);
            n.put("CreationTime", epoch(c.creationTime));
            arr.add(n);
        });
        ObjectNode out = mapper.createObjectNode();
        out.set("EndpointConfigs", arr);
        return out;
    }

    public synchronized ObjectNode createEndpoint(JsonNode request, String region) {
        String name = required(request, "EndpointName");
        if (endpoint(name).isPresent()) {
            throw new AwsException("ValidationException", "Cannot create already existing endpoint \"" + name + "\".", 400);
        }
        String cfgName = required(request, "EndpointConfigName");
        endpointConfig(cfgName).orElseThrow(() -> validation("Could not find endpoint configuration \"" + cfgName + "\"."));
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
        endpointStore.put(name, ep);
        endpointManager.startEndpointAsync(ep, this);
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointArn", ep.endpointArn);
        return out;
    }

    public synchronized ObjectNode updateEndpoint(JsonNode request) {
        String name = required(request, "EndpointName");
        EndpointResource ep = endpoint(name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\"."));
        String cfgName = required(request, "EndpointConfigName");
        endpointConfig(cfgName).orElseThrow(() -> validation("Could not find endpoint configuration \"" + cfgName + "\"."));
        if (ep.containerId != null) {
            endpointManager.stopEndpoint(ep);
        }
        ep.endpointConfigName = cfgName;
        ep.endpointStatus = "Creating";
        ep.failureReason = null;
        ep.lastModifiedTime = nowMillis();
        endpointStore.put(name, ep);
        endpointManager.startEndpointAsync(ep, this);
        ObjectNode out = mapper.createObjectNode();
        out.put("EndpointArn", ep.endpointArn);
        return out;
    }

    public ObjectNode describeEndpoint(JsonNode request) {
        String name = required(request, "EndpointName");
        EndpointResource ep = endpoint(name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\"."));
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

    public synchronized ObjectNode deleteEndpoint(JsonNode request) {
        String name = required(request, "EndpointName");
        EndpointResource ep = endpoint(name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\"."));
        ep.endpointStatus = "Deleting";
        endpointStore.put(name, ep);
        endpointManager.stopEndpoint(ep);
        endpointStore.delete(name);
        return mapper.createObjectNode();
    }

    public ObjectNode listEndpoints(JsonNode request) {
        ArrayNode arr = mapper.createArrayNode();
        endpointStore.scan(k -> true).stream().sorted(Comparator.comparing(e -> e.endpointName)).forEach(e -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("EndpointName", e.endpointName);
            n.put("EndpointArn", e.endpointArn);
            n.put("EndpointStatus", e.endpointStatus);
            n.put("CreationTime", epoch(e.creationTime));
            n.put("LastModifiedTime", epoch(e.lastModifiedTime));
            arr.add(n);
        });
        ObjectNode out = mapper.createObjectNode();
        out.set("Endpoints", arr);
        return out;
    }

    public synchronized ObjectNode createTrainingJob(JsonNode request, String region) {
        String name = required(request, "TrainingJobName");
        if (trainingJob(name).isPresent()) {
            throw new AwsException("ResourceInUse", "Training job already exists: " + name, 400);
        }
        if (!request.path("AlgorithmSpecification").isObject() || text(request.path("AlgorithmSpecification"), "TrainingImage") == null) {
            throw validation("AlgorithmSpecification.TrainingImage is required");
        }
        if (!request.path("OutputDataConfig").isObject() || text(request.path("OutputDataConfig"), "S3OutputPath") == null) {
            throw validation("OutputDataConfig.S3OutputPath is required");
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
        trainingJobStore.put(name, job);
        trainingRunner.runAsync(job, this);
        ObjectNode out = mapper.createObjectNode();
        out.put("TrainingJobArn", job.trainingJobArn);
        return out;
    }

    public ObjectNode describeTrainingJob(JsonNode request) {
        String name = required(request, "TrainingJobName");
        TrainingJobResource job = trainingJob(name).orElseThrow(() -> validation("Could not find training job \"" + name + "\"."));
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

    public ObjectNode listTrainingJobs(JsonNode request) {
        ArrayNode arr = mapper.createArrayNode();
        trainingJobStore.scan(k -> true).stream().sorted(Comparator.comparing(j -> j.trainingJobName)).forEach(j -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("TrainingJobName", j.trainingJobName);
            n.put("TrainingJobArn", j.trainingJobArn);
            n.put("TrainingJobStatus", j.trainingJobStatus);
            n.put("CreationTime", epoch(j.creationTime));
            arr.add(n);
        });
        ObjectNode out = mapper.createObjectNode();
        out.set("TrainingJobSummaries", arr);
        return out;
    }

    public synchronized ObjectNode stopTrainingJob(JsonNode request) {
        String name = required(request, "TrainingJobName");
        TrainingJobResource job = trainingJob(name).orElseThrow(() -> validation("Could not find training job \"" + name + "\"."));
        trainingRunner.stop(job);
        job.trainingJobStatus = "Stopped";
        job.secondaryStatus = "Stopped";
        job.trainingEndTime = nowMillis();
        trainingJobStore.put(name, job);
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

    Optional<ModelResource> model(String name) {
        return modelStore.get(name);
    }

    Optional<EndpointConfigResource> endpointConfig(String name) {
        return endpointConfigStore.get(name);
    }

    Optional<EndpointResource> endpoint(String name) {
        return endpointStore.get(name);
    }

    Optional<TrainingJobResource> trainingJob(String name) {
        return trainingJobStore.get(name);
    }

    public ModelResource modelForEndpoint(EndpointResource ep) {
        EndpointConfigResource cfg = endpointConfig(ep.endpointConfigName)
                .orElseThrow(() -> validation("Could not find endpoint configuration \"" + ep.endpointConfigName + "\"."));
        String modelName = String.valueOf(cfg.productionVariants.get(0).get("ModelName"));
        return model(modelName).orElseThrow(() -> validation("Could not find model \"" + modelName + "\"."));
    }

    public synchronized void updateEndpoint(EndpointResource ep) {
        ep.lastModifiedTime = nowMillis();
        endpointStore.put(ep.endpointName, ep);
    }

    public synchronized void updateTrainingJob(TrainingJobResource job) {
        trainingJobStore.put(job.trainingJobName, job);
    }

    private Map<String, String> resourceTags(String arn) {
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        if (arn.contains(":model/")) return model(name).orElseThrow(() -> validation("Could not find model \"" + name + "\".")).tags;
        if (arn.contains(":endpoint-config/")) return endpointConfig(name).orElseThrow(() -> validation("Could not find endpoint configuration \"" + name + "\".")).tags;
        if (arn.contains(":endpoint/")) return endpoint(name).orElseThrow(() -> validation("Could not find endpoint \"" + name + "\".")).tags;
        if (arn.contains(":training-job/")) return trainingJob(name).orElseThrow(() -> validation("Could not find training job \"" + name + "\".")).tags;
        throw validation("ResourceArn is invalid");
    }

    private void persistByArn(String arn) {
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        if (arn.contains(":model/")) model(name).ifPresent(v -> modelStore.put(name, v));
        else if (arn.contains(":endpoint-config/")) endpointConfig(name).ifPresent(v -> endpointConfigStore.put(name, v));
        else if (arn.contains(":endpoint/")) endpoint(name).ifPresent(v -> endpointStore.put(name, v));
        else if (arn.contains(":training-job/")) trainingJob(name).ifPresent(v -> trainingJobStore.put(name, v));
    }

    private String arn(String region, String resource) {
        return "arn:aws:sagemaker:" + region + ":" + regionResolver.getAccountId() + ":" + resource;
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
            node.forEach(t -> out.put(text(t, "Key"), text(t, "Value")));
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

    private <T> List<T> page(List<T> values, JsonNode request) {
        int max = request.path("MaxResults").asInt(DEFAULT_LIMIT);
        return values.stream().limit(max).toList();
    }
}
