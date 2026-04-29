package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class StorageDescriptor {
    @JsonProperty("Columns")
    private List<Column> columns;
    @JsonProperty("Location")
    private String location;
    @JsonProperty("InputFormat")
    private String inputFormat;
    @JsonProperty("OutputFormat")
    private String outputFormat;
    @JsonProperty("Compressed")
    private Boolean compressed;
    @JsonProperty("NumberOfBuckets")
    private Integer numberOfBuckets;
    @JsonProperty("SerdeInfo")
    private SerDeInfo serdeInfo;
    @JsonProperty("Parameters")
    private Map<String, String> parameters;
    @JsonProperty("SchemaReference")
    private SchemaReference schemaReference;

    public StorageDescriptor() {}

    public List<Column> getColumns() { return columns; }
    public void setColumns(List<Column> columns) { this.columns = columns; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getInputFormat() { return inputFormat; }
    public void setInputFormat(String inputFormat) { this.inputFormat = inputFormat; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public Boolean getCompressed() { return compressed; }
    public void setCompressed(Boolean compressed) { this.compressed = compressed; }
    public Integer getNumberOfBuckets() { return numberOfBuckets; }
    public void setNumberOfBuckets(Integer numberOfBuckets) { this.numberOfBuckets = numberOfBuckets; }
    public SerDeInfo getSerdeInfo() { return serdeInfo; }
    public void setSerdeInfo(SerDeInfo serdeInfo) { this.serdeInfo = serdeInfo; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    public SchemaReference getSchemaReference() { return schemaReference; }
    public void setSchemaReference(SchemaReference schemaReference) { this.schemaReference = schemaReference; }

    @RegisterForReflection
    public static class SerDeInfo {
        @JsonProperty("Name")
        private String name;
        @JsonProperty("SerializationLibrary")
        private String serializationLibrary;
        @JsonProperty("Parameters")
        private Map<String, String> parameters;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSerializationLibrary() { return serializationLibrary; }
        public void setSerializationLibrary(String serializationLibrary) { this.serializationLibrary = serializationLibrary; }
        public Map<String, String> getParameters() { return parameters; }
        public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    }
}
