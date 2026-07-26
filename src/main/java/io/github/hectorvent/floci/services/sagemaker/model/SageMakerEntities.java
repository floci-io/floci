package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public final class SageMakerEntities {
    private SageMakerEntities() {
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelResource {
        public String modelName;
        public String modelArn;
        public Map<String, Object> primaryContainer = new LinkedHashMap<>();
        public List<Map<String, Object>> containers = new ArrayList<>();
        public String executionRoleArn;
        public long creationTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointConfigResource {
        public String endpointConfigName;
        public String endpointConfigArn;
        public List<Map<String, Object>> productionVariants = new ArrayList<>();
        public long creationTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointResource {
        public String endpointName;
        public String endpointArn;
        public String endpointConfigName;
        public String endpointStatus;
        public String failureReason;
        public long creationTime;
        public long lastModifiedTime;
        public String region;
        public String accountId;
        public String containerId;
        public String invokeHost;
        public int invokePort;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TrainingJobResource {
        public String trainingJobName;
        public String trainingJobArn;
        public String trainingJobStatus;
        public String secondaryStatus;
        public String failureReason;
        public long creationTime;
        public long trainingStartTime;
        public long trainingEndTime;
        public String region;
        public String accountId;
        public Map<String, Object> algorithmSpecification = new LinkedHashMap<>();
        public List<Map<String, Object>> inputDataConfig = new ArrayList<>();
        public Map<String, Object> outputDataConfig = new LinkedHashMap<>();
        public Map<String, Object> resourceConfig = new LinkedHashMap<>();
        public Map<String, Object> stoppingCondition = new LinkedHashMap<>();
        public Map<String, String> hyperParameters = new LinkedHashMap<>();
        public String modelArtifactsS3ModelArtifacts;
        public String containerId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }
}
