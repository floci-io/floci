package io.github.hectorvent.floci.services.ce.enumerator;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3UsageEnumeratorTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void aBucketWithoutAStoredRegionIsBilledToTheDeploymentDefaultRegion() {
        S3Service s3 = mock(S3Service.class);
        when(s3.listBuckets()).thenReturn(List.of(new Bucket("legacy-bucket")));
        S3Object object = new S3Object();
        object.setSize(1_073_741_824L);
        when(s3.listObjects(eq("legacy-bucket"), any(), any(), anyInt())).thenReturn(List.of(object));
        S3UsageEnumerator enumerator = new S3UsageEnumerator(s3, new RegionResolver("cn-north-1", "000000000000"));

        List<UsageLine> lines = enumerator.enumerate(START, END, "cn-north-1").toList();

        UsageLine bucketLine = lines.stream().filter(line -> line.resourceId() != null).findFirst().orElseThrow();
        assertEquals("cn-north-1", bucketLine.region());
        assertEquals("arn:aws-cn:s3:::legacy-bucket", bucketLine.resourceId());
    }
}
