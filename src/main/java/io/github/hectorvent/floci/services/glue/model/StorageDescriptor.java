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
    @JsonProperty("AdditionalLocations")
    private List<String> additionalLocations;
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
    @JsonProperty("BucketColumns")
    private List<String> bucketColumns;
    @JsonProperty("SortColumns")
    private List<Order> sortColumns;
    @JsonProperty("Parameters")
    private Map<String, String> parameters;
    @JsonProperty("SkewedInfo")
    private SkewedInfo skewedInfo;
    @JsonProperty("StoredAsSubDirectories")
    private Boolean storedAsSubDirectories;
    @JsonProperty("SchemaReference")
    private SchemaReference schemaReference;

    public StorageDescriptor() {}

    public List<Column> getColumns() { return columns; }
    public void setColumns(List<Column> columns) { this.columns = columns; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public List<String> getAdditionalLocations() { return additionalLocations; }
    public void setAdditionalLocations(List<String> additionalLocations) {
        this.additionalLocations = additionalLocations;
    }
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
    public List<String> getBucketColumns() { return bucketColumns; }
    public void setBucketColumns(List<String> bucketColumns) { this.bucketColumns = bucketColumns; }
    public List<Order> getSortColumns() { return sortColumns; }
    public void setSortColumns(List<Order> sortColumns) { this.sortColumns = sortColumns; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    public SkewedInfo getSkewedInfo() { return skewedInfo; }
    public void setSkewedInfo(SkewedInfo skewedInfo) { this.skewedInfo = skewedInfo; }
    public Boolean getStoredAsSubDirectories() { return storedAsSubDirectories; }
    public void setStoredAsSubDirectories(Boolean storedAsSubDirectories) {
        this.storedAsSubDirectories = storedAsSubDirectories;
    }
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

    @RegisterForReflection
    public static class Order {
        @JsonProperty("Column")
        private String column;
        @JsonProperty("SortOrder")
        private Integer sortOrder;

        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }

    @RegisterForReflection
    public static class SkewedInfo {
        @JsonProperty("SkewedColumnNames")
        private List<String> skewedColumnNames;
        @JsonProperty("SkewedColumnValues")
        private List<String> skewedColumnValues;
        @JsonProperty("SkewedColumnValueLocationMaps")
        private Map<String, String> skewedColumnValueLocationMaps;

        public List<String> getSkewedColumnNames() { return skewedColumnNames; }
        public void setSkewedColumnNames(List<String> skewedColumnNames) { this.skewedColumnNames = skewedColumnNames; }
        public List<String> getSkewedColumnValues() { return skewedColumnValues; }
        public void setSkewedColumnValues(List<String> skewedColumnValues) {
            this.skewedColumnValues = skewedColumnValues;
        }
        public Map<String, String> getSkewedColumnValueLocationMaps() { return skewedColumnValueLocationMaps; }
        public void setSkewedColumnValueLocationMaps(Map<String, String> skewedColumnValueLocationMaps) {
            this.skewedColumnValueLocationMaps = skewedColumnValueLocationMaps;
        }
    }
}
