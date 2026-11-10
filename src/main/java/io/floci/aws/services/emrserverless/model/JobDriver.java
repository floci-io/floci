package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** What a job run executes: a Spark submission or a Hive query. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobDriver {
    private SparkSubmit sparkSubmit;
    private Hive hive;

    public SparkSubmit getSparkSubmit() { return sparkSubmit; }
    public void setSparkSubmit(SparkSubmit sparkSubmit) { this.sparkSubmit = sparkSubmit; }
    public Hive getHive() { return hive; }
    public void setHive(Hive hive) { this.hive = hive; }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SparkSubmit {
        private String entryPoint;
        private List<String> entryPointArguments;
        private String sparkSubmitParameters;

        public String getEntryPoint() { return entryPoint; }
        public void setEntryPoint(String entryPoint) { this.entryPoint = entryPoint; }
        public List<String> getEntryPointArguments() { return entryPointArguments; }
        public void setEntryPointArguments(List<String> entryPointArguments) { this.entryPointArguments = entryPointArguments; }
        public String getSparkSubmitParameters() { return sparkSubmitParameters; }
        public void setSparkSubmitParameters(String sparkSubmitParameters) { this.sparkSubmitParameters = sparkSubmitParameters; }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Hive {
        private String query;
        private String initQueryFile;
        private String parameters;

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getInitQueryFile() { return initQueryFile; }
        public void setInitQueryFile(String initQueryFile) { this.initQueryFile = initQueryFile; }
        public String getParameters() { return parameters; }
        public void setParameters(String parameters) { this.parameters = parameters; }
    }
}
