package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Record {
    @JsonProperty("Data")
    private byte[] data;

    public Record() {}
    public Record(byte[] data) { this.data = data; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
}
