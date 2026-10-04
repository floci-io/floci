package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RegisterForReflection
public class Part {

    private int partNumber;
    private String eTag;
    private long size;
    private S3Checksum checksum;
    private Instant lastModified;
    // Names this part's bytes while its upload is open: a file in the upload's directory, or a key
    // in memory. A re-upload stores under a new name, so the bytes under a name never change.
    @JsonIgnore
    private String storageId;

    public Part() {
        this.checksum = new S3Checksum();
    }

    public Part(int partNumber, String eTag, long size) {
        this.partNumber = partNumber;
        this.eTag = eTag;
        this.size = size;
        this.checksum = new S3Checksum();
        this.lastModified = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public int getPartNumber() { return partNumber; }
    public void setPartNumber(int partNumber) { this.partNumber = partNumber; }

    public String getETag() { return eTag; }
    public void setETag(String eTag) { this.eTag = eTag; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public S3Checksum getChecksum() { return checksum; }
    public void setChecksum(S3Checksum checksum) { this.checksum = checksum; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    public String getStorageId() { return storageId; }
    public void setStorageId(String storageId) { this.storageId = storageId; }
}
