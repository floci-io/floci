package io.floci.aws.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProvisionedThroughput {

    private long readCapacityUnits;
    private long writeCapacityUnits;
    private Instant lastIncreaseDateTime;
    private Instant lastDecreaseDateTime;
    private long numberOfDecreasesToday;

    public ProvisionedThroughput() {}

    public ProvisionedThroughput(long readCapacityUnits, long writeCapacityUnits) {
        this.readCapacityUnits = readCapacityUnits;
        this.writeCapacityUnits = writeCapacityUnits;
        this.numberOfDecreasesToday = 0;
    }

    public long getReadCapacityUnits() { return readCapacityUnits; }
    public void setReadCapacityUnits(long readCapacityUnits) { this.readCapacityUnits = readCapacityUnits; }

    public long getWriteCapacityUnits() { return writeCapacityUnits; }
    public void setWriteCapacityUnits(long writeCapacityUnits) { this.writeCapacityUnits = writeCapacityUnits; }

    public Instant getLastIncreaseDateTime() { return lastIncreaseDateTime; }
    public void setLastIncreaseDateTime(Instant lastIncreaseDateTime) { this.lastIncreaseDateTime = lastIncreaseDateTime; }

    public Instant getLastDecreaseDateTime() { return lastDecreaseDateTime; }
    public void setLastDecreaseDateTime(Instant lastDecreaseDateTime) { this.lastDecreaseDateTime = lastDecreaseDateTime; }

    public long getNumberOfDecreasesToday() { return numberOfDecreasesToday; }
    public void setNumberOfDecreasesToday(long numberOfDecreasesToday) { this.numberOfDecreasesToday = numberOfDecreasesToday; }
}
