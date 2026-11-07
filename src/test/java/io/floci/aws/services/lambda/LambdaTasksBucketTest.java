package io.floci.aws.services.lambda;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.lambda.zip.DeploymentPackage;
import io.floci.aws.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaTasksBucketTest {

    @Test
    void deploymentCopiesKeepTheExistingTasksBucketAndObjectKey() {
        S3Service s3 = mock(S3Service.class);
        byte[] archive = {1, 2, 3};
        assertTrue(LambdaService.putTasksObjectQuietly(s3, "us-east-2", "function/code.zip",
                DeploymentPackage.of(archive), "test package"));
        verify(s3).createInternalBucket("awslambda-us-east-2-tasks", "us-east-2");
        verify(s3, never()).createBucket("awslambda-us-east-2-tasks", "us-east-2");
        verify(s3).putObject("awslambda-us-east-2-tasks", "function/code.zip", archive,
                "application/zip", Map.of());
    }

    @Test
    void anExistingOwnedTasksBucketIsReused() {
        S3Service s3 = mock(S3Service.class);
        when(s3.createInternalBucket("awslambda-us-east-1-tasks", "us-east-1"))
                .thenThrow(new AwsException("BucketAlreadyOwnedByYou", "owned", 409));
        assertTrue(LambdaService.putTasksObjectQuietly(s3, "us-east-1", "layer.zip",
                DeploymentPackage.of(new byte[]{1}), "test layer"));
    }

    @Test
    void unexpectedStagingFailuresStillRejectTheStoredCopy() {
        S3Service s3 = mock(S3Service.class);
        when(s3.createInternalBucket("awslambda-us-east-2-tasks", "us-east-2"))
                .thenThrow(new AwsException("AccessDenied", "denied", 403));
        assertFalse(LambdaService.putTasksObjectQuietly(s3, "us-east-2", "function.zip",
                DeploymentPackage.of(new byte[]{1}), "test package"));
    }
}
