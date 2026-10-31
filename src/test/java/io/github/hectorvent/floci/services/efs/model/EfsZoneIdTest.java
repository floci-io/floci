package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #5580: file systems and mount targets persisted before zone ids took AWS's shape stored
 * {@code <region>-az<n>}; the id is derived from the zone name, so the old form reads back as the
 * current one.
 */
class EfsZoneIdTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aLegacyFileSystemReadsBackTheCurrentId() throws Exception {
        FileSystem fs = mapper.readValue("""
                {
                  "FileSystemId": "fs-legacy",
                  "FileSystemArn": "arn:aws:elasticfilesystem:ap-southeast-1:000000000000:file-system/fs-legacy",
                  "AvailabilityZoneName": "ap-southeast-1b",
                  "AvailabilityZoneId": "ap-southeast-1-az2"
                }
                """, FileSystem.class);

        assertEquals("apse1-az2", fs.getAvailabilityZoneId());

        String written = mapper.writeValueAsString(fs);
        assertEquals("apse1-az2", mapper.readTree(written).get("AvailabilityZoneId").asText());
        assertEquals("apse1-az2", mapper.readValue(written, FileSystem.class).getAvailabilityZoneId());
    }

    @Test
    void aRegionalFileSystemHasNoZoneId() throws Exception {
        FileSystem fs = mapper.readValue("""
                {"FileSystemArn": "arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/fs-1"}
                """, FileSystem.class);

        assertNull(fs.getAvailabilityZoneId());
    }

    @Test
    void aLegacyMountTargetReadsBackTheCurrentId() throws Exception {
        MountTarget mt = mapper.readValue("""
                {
                  "MountTargetId": "fsmt-legacy",
                  "AvailabilityZoneName": "ap-southeast-1a",
                  "AvailabilityZoneId": "ap-southeast-1-az1"
                }
                """, MountTarget.class);

        assertEquals("apse1-az1", mt.getAvailabilityZoneId());

        String written = mapper.writeValueAsString(mt);
        assertEquals("apse1-az1", mapper.readTree(written).get("AvailabilityZoneId").asText());
        assertEquals("apse1-az1", mapper.readValue(written, MountTarget.class).getAvailabilityZoneId());
    }
}
