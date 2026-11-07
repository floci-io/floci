package io.floci.aws.services.cur;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParquetEmitterStagingTest {

    @Test
    void emissionKeepsItsStagingNameAndUsesInternalCreation() {
        S3Service s3 = mock(S3Service.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        FocusRowProjector projector = mock(FocusRowProjector.class);
        when(projector.project(List.of())).thenReturn(List.of());
        ParquetEmitter emitter = new ParquetEmitter(projector, duck, s3, new ObjectMapper(),
                "floci-cur-staging", "us-east-2");

        ParquetEmitter.Result result = emitter.emit("report", "results", "billing", List.of(), "000000000002");

        verify(s3).createInternalBucket("floci-cur-staging", "us-east-2");
        verify(s3, never()).createBucket("floci-cur-staging", "us-east-2");
        verify(s3).putObject(eq("floci-cur-staging"), anyString(), any(byte[].class),
                eq("application/x-ndjson"), any());
        assertEquals("s3://results/" + result.key(), result.s3Uri());
        verify(duck).execute(eq("SELECT 1 AS ok"), anyString(), anyString(), eq("000000000002"));
    }

    @Test
    void aForeignNamespaceConflictIsNotIgnoredBeforeWriting() {
        S3Service s3 = mock(S3Service.class);
        when(s3.createInternalBucket("floci-cur-staging", "us-east-2"))
                .thenThrow(new AwsException("BucketAlreadyExists", "foreign", 409));
        ParquetEmitter emitter = new ParquetEmitter(mock(FocusRowProjector.class), mock(FlociDuckClient.class),
                s3, new ObjectMapper(), "floci-cur-staging", "us-east-2");
        assertEquals("BucketAlreadyExists", assertThrows(AwsException.class,
                () -> emitter.emit("report", "results", null, List.of())).getErrorCode());
        verify(s3, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }
}
