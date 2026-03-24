package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RegisterForReflection
public class Part {

    private int partNumber;
    private String eTag;
    private long size;
    private Instant lastModified;

    public Part() {}

    public Part(int partNumber, String eTag, long size) {
        this.partNumber = partNumber;
        this.eTag = eTag;
        this.size = size;
        this.lastModified = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public int getPartNumber() { return partNumber; }
    public void setPartNumber(int partNumber) { this.partNumber = partNumber; }

    public String getETag() { return eTag; }
    public void setETag(String eTag) { this.eTag = eTag; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }
}
