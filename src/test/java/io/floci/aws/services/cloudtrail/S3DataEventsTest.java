package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S3 data events CloudTrail records, as the vendored table generated from botocore, the S3 user guide and the S3
 * service reference gives them.
 */
class S3DataEventsTest {

    @Test
    void anOperationIsRecordedUnderItsOwnNameOrTheOneTheGuideGivesIt() {
        assertEquals(Optional.of(new S3DataEvents.Event("HeadBucket", true)), S3DataEvents.forOperation("HeadBucket"));
        assertEquals(Optional.of(new S3DataEvents.Event("CopyObject", false)), S3DataEvents.forOperation("CopyObject"));
        assertEquals(Optional.of(new S3DataEvents.Event("UploadPart", false)), S3DataEvents.forOperation("UploadPart"));
        assertEquals(Optional.of(new S3DataEvents.Event("ListObjects", true)),
                S3DataEvents.forOperation("ListObjectsV2"));
        assertEquals(Optional.of(new S3DataEvents.Event("PutObjectLockRetention", false)),
                S3DataEvents.forOperation("PutObjectRetention"));
    }

    @Test
    void anOperationCloudTrailRecordsNoDataEventForHasNone() {
        assertEquals(Optional.empty(), S3DataEvents.forOperation("ListBuckets"));
        assertEquals(Optional.empty(), S3DataEvents.forOperation("GetBucketPolicy"));
        assertEquals(Optional.empty(), S3DataEvents.forOperation(null));
    }

    @Test
    void anEventIsReadOnlyWhenItsOperationChangesNothing() {
        assertTrue(S3DataEvents.isReadOnly("GetObject"));
        assertTrue(S3DataEvents.isReadOnly("ListParts"));
        assertTrue(S3DataEvents.isReadOnly("SelectObjectContent"));
        assertFalse(S3DataEvents.isReadOnly("PutObject"));
        assertFalse(S3DataEvents.isReadOnly("DeleteObjects"));
        assertFalse(S3DataEvents.isReadOnly("SomeFutureOperation"));
    }

    @Test
    void aFileWithoutAnEventsNameOrFlagIsRefused() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(IllegalStateException.class, () -> new S3DataEvents(mapper.createObjectNode()));
        assertThrows(IllegalStateException.class, () -> new S3DataEvents(mapper.readTree(
                "{\"dataEvents\":{\"GetObject\":{\"eventName\":\"GetObject\"}}}")));
        // One record carries one flag for its name, so the operations recorded under it must agree.
        assertThrows(IllegalStateException.class, () -> new S3DataEvents(mapper.readTree(
                "{\"dataEvents\":{\"ListObjects\":{\"eventName\":\"ListObjects\",\"readOnly\":true},"
                        + "\"ListObjectsV2\":{\"eventName\":\"ListObjects\",\"readOnly\":false}}}")));
    }
}
