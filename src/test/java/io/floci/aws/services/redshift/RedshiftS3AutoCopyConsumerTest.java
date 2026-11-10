package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftCopyJobExecutor;
import io.floci.aws.services.s3.model.S3ObjectUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftS3AutoCopyConsumerTest {
    @Test
    @Timeout(10)
    void observingAnUploadDoesNotWaitForAnActiveCopy() throws Exception {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftCopyJobExecutor writer = mock(RedshiftCopyJobExecutor.class);
        Integration integration = integration("active", "active");
        when(service.listS3AutoCopyIntegrations()).thenReturn(List.of(integration));
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            copying.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(writer).runAutomatic(integration);
        RedshiftS3AutoCopyConsumer consumer = new RedshiftS3AutoCopyConsumer(service, writer, null, 1000);
        CompletableFuture<Void> poll = CompletableFuture.runAsync(consumer::pollOnce);
        try {
            assertTrue(copying.await(5, TimeUnit.SECONDS));
            consumer.updated(new S3ObjectUpdatedEvent("bucket", "key"));
        } finally {
            release.countDown();
            poll.get(5, TimeUnit.SECONDS);
            consumer.close();
        }
    }

    @Test
    void processesActiveIntegrationsAndContinuesAfterAFailedTarget() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftCopyJobExecutor writer = mock(RedshiftCopyJobExecutor.class);
        Integration failed = integration("failed", "active");
        Integration healthy = integration("healthy", "active");
        Integration inactive = integration("inactive", "creating");
        when(service.listS3AutoCopyIntegrations()).thenReturn(List.of(failed, inactive, healthy));
        doThrow(new IllegalStateException("Target unavailable")).when(writer).runAutomatic(failed);
        RedshiftS3AutoCopyConsumer consumer = new RedshiftS3AutoCopyConsumer(service, writer, null, 1000);
        try {
            consumer.pollOnce();
            verify(writer).runAutomatic(failed);
            verify(writer).runAutomatic(healthy);
            verify(writer, never()).runAutomatic(inactive);
            consumer.beforeReset();
            consumer.pollOnce();
            verify(writer, times(1)).runAutomatic(healthy);
            consumer.clear();
            consumer.afterReset();
            consumer.pollOnce();
            verify(writer, times(2)).runAutomatic(healthy);
        } finally {
            consumer.close();
        }
    }

    @Test
    void deletionDisablesJobsWithoutDroppingTheirMetadata() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftCopyJobExecutor writer = mock(RedshiftCopyJobExecutor.class);
        RedshiftS3AutoCopyConsumer consumer = new RedshiftS3AutoCopyConsumer(service, writer, null, 1000);
        Integration integration = integration("deleted", "active");
        try {
            consumer.disable(integration);
            verify(writer).disable(integration);
            verifyNoMoreInteractions(writer);
        } finally {
            consumer.close();
        }
    }

    private static Integration integration(String name, String status) {
        Integration integration = new Integration();
        integration.setAccountId("123456789012");
        integration.setIntegrationArn(AwsArnUtils.Arn.of("redshift", "us-east-1", "123456789012",
                "integration:" + name).toString());
        integration.setStatus(status);
        integration.setSourceArn("arn:aws:s3:::bucket");
        return integration;
    }
}
