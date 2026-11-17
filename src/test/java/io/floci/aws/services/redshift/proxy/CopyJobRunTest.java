package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopyJobRunTest {
    @Test
    void recognizesOnlyAStandaloneRunWithAValidName() {
        assertEquals("Mixed", CopyJobSql.runName("/* comment */ COPY JOB RUN \"Mixed\";"));
        assertEquals("my_job", CopyJobSql.runName("copy job run MY_JOB"));
        assertNull(CopyJobSql.runName("COPY JOB RUN j; SELECT 1"));
        assertNull(CopyJobSql.runName("COPY JOB RUN"));
        assertNull(CopyJobSql.runName("COPY JOB RUN j extra"));
        assertNotNull(CopyStatementParser.parse("COPY JOB RUN j"));
    }

    @Test
    void preservesOptionsAndUsesTheExactObjectKey() {
        String copy = "COPY t FROM 's3://bucket/prefix/' IAM_ROLE 'role' CSV";
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(copy);
        CopyStatementParser.S3CopyFrom object = CopyJobRunner.forObject(spec, "prefix/a'b.csv");
        assertEquals("prefix/a'b.csv", object.keyOrPrefix());
        assertEquals("role", object.iamRoleArn());
        assertTrue(object.csv());
        assertEquals("bucket", object.bucket());
    }

    @Test
    void deletedObjectNeverFallsBackToALongerKeyWithTheSamePrefix() {
        S3Service s3 = mock(S3Service.class);
        S3Object longer = new S3Object("bucket", "prefix/abc", new byte[0], "text/plain");
        when(s3.objectExists("bucket", "prefix/a")).thenReturn(false);
        when(s3.listObjectsWithPrefixes("bucket", "prefix/a", null, 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(longer), List.of(), false, null));
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://bucket/prefix/a' CSV");
        S3CopySimulator.CopyInput input = S3CopySimulator.prepareObjectCopy(spec, s3, null, null, List.of());
        assertEquals(List.of("prefix/a"), input.keys());
        verify(s3, never()).listObjectsWithPrefixes("bucket", "prefix/a", null, 1000, null, null);
    }
}
