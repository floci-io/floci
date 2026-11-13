package io.floci.aws.testing;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.time.Duration;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsDockerCleanupTest {

    @Test
    void deletesCompletedCreationImmediately() {
        RdsClient client = mock(RdsClient.class);
        RdsRequests.cleanupDockerInstance(client, "owned", null, Duration.ofSeconds(1));
        verify(client).deleteDBInstance(anyDelete());
    }

    @Test
    void waitsForLatePublicationAfterClientTimeout() {
        RdsClient client = mock(RdsClient.class);
        when(client.deleteDBInstance(anyDelete()))
                .thenThrow(absent()).thenReturn(DeleteDbInstanceResponse.builder().build());
        SdkClientException timeout = SdkClientException.create("create request timed out");

        RdsRequests.cleanupDockerInstance(client, "owned", timeout, Duration.ofSeconds(1));

        verify(client, times(2)).deleteDBInstance(
                anyDelete());
        assertEquals(0, timeout.getSuppressed().length);
    }

    @Test
    void toleratesAbsentInstanceOnlyAfterServerRejection() {
        RdsClient client = mock(RdsClient.class);
        when(client.deleteDBInstance(anyDelete()))
                .thenThrow(absent());
        RdsException rejection = (RdsException) RdsException.builder().message("rejected").statusCode(400).build();

        RdsRequests.cleanupDockerInstance(client, "owned", rejection, Duration.ofMillis(20));

        verify(client).deleteDBInstance(anyDelete());
        assertEquals(0, rejection.getSuppressed().length);
    }

    @Test
    void unresolvedCreateRetainsCleanupFailureOnOriginalError() {
        RdsClient client = mock(RdsClient.class);
        when(client.deleteDBInstance(anyDelete()))
                .thenThrow(absent());
        SdkClientException timeout = SdkClientException.create("create request timed out");

        RdsRequests.cleanupDockerInstance(client, "owned", timeout, Duration.ofMillis(20));

        assertEquals(1, timeout.getSuppressed().length);
        assertInstanceOf(ConditionTimeoutException.class, timeout.getSuppressed()[0]);
    }

    @Test
    void deletionFailureAfterSuccessfulCreateIsNotHidden() {
        RdsClient client = mock(RdsClient.class);
        IllegalStateException failure = new IllegalStateException("delete failed");
        when(client.deleteDBInstance(anyDelete()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                RdsRequests.cleanupDockerInstance(client, "owned", null, Duration.ofSeconds(1))));
    }

    private static DbInstanceNotFoundException absent() {
        return DbInstanceNotFoundException.builder().message("not published").statusCode(404).build();
    }

    private static Consumer<DeleteDbInstanceRequest.Builder> anyDelete() {
        return any();
    }
}
