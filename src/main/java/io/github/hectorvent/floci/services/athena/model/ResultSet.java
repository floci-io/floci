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
        @JsonProperty("Name")
        private String name;
        @JsonProperty("Type")
        private String type;

        public ColumnInfo() {}
        public ColumnInfo(String name, String type) { this.name = name; this.name = name; this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
