package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class ResultSet {
    @JsonProperty("Rows")
    private List<Row> rows;
    @JsonProperty("ResultSetMetadata")
    private ResultSetMetadata metadata;

    public ResultSet() {}
    public ResultSet(List<Row> rows, ResultSetMetadata metadata) {
        this.rows = rows;
        this.metadata = metadata;
    }

    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }
    public ResultSetMetadata getMetadata() { return metadata; }
    public void setMetadata(ResultSetMetadata metadata) { this.metadata = metadata; }

    @RegisterForReflection
    public static class Row {
        @JsonProperty("Data")
        private List<Datum> data;

        public Row() {}
        public Row(List<Datum> data) { this.data = data; }
        public List<Datum> getData() { return data; }
        public void setData(List<Datum> data) { this.data = data; }
    }

    @RegisterForReflection
    public static class Datum {
        @JsonProperty("VarCharValue")
        private String varCharValue;

        public Datum() {}
        public Datum(String value) { this.varCharValue = value; }
        public String getVarCharValue() { return varCharValue; }
        public void setVarCharValue(String varCharValue) { this.varCharValue = varCharValue; }
    }

    @RegisterForReflection
    public static class ResultSetMetadata {
        @JsonProperty("ColumnInfo")
        private List<ColumnInfo> columnInfo;

        public ResultSetMetadata() {}
        public ResultSetMetadata(List<ColumnInfo> columnInfo) { this.columnInfo = columnInfo; }
        public List<ColumnInfo> getColumnInfo() { return columnInfo; }
        public void setColumnInfo(List<ColumnInfo> columnInfo) { this.columnInfo = columnInfo; }
    }

    @RegisterForReflection
    public static class ColumnInfo {
        @JsonProperty("CatalogName")
        private String catalogName;
        @JsonProperty("SchemaName")
        private String schemaName;
        @JsonProperty("TableName")
        private String tableName;
        @JsonProperty("Name")
        private String name;
        @JsonProperty("Label")
        private String label;
        @JsonProperty("Type")
        private String type;
        @JsonProperty("Precision")
        private Integer precision;
        @JsonProperty("Scale")
        private Integer scale;
        @JsonProperty("Nullable")
        private String nullable;
        @JsonProperty("CaseSensitive")
        private Boolean caseSensitive;

        public ColumnInfo() {}
        public ColumnInfo(String name, String type) {
            this("AwsDataCatalog", "", "", name, type);
        }
        public ColumnInfo(String catalogName, String schemaName, String tableName, String name, String type) {
            this.catalogName = catalogName;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.name = name;
            this.label = name;
            this.type = type;
            this.precision = 0;
            this.scale = 0;
            this.nullable = "UNKNOWN";
            this.caseSensitive = false;
        }
        public String getCatalogName() { return catalogName; }
        public void setCatalogName(String catalogName) { this.catalogName = catalogName; }
        public String getSchemaName() { return schemaName; }
        public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Integer getPrecision() { return precision; }
        public void setPrecision(Integer precision) { this.precision = precision; }
        public Integer getScale() { return scale; }
        public void setScale(Integer scale) { this.scale = scale; }
        public String getNullable() { return nullable; }
        public void setNullable(String nullable) { this.nullable = nullable; }
        public Boolean getCaseSensitive() { return caseSensitive; }
        public void setCaseSensitive(Boolean caseSensitive) { this.caseSensitive = caseSensitive; }
    }
}
