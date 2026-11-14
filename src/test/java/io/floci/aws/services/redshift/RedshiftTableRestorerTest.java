package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RedshiftTableRestorerTest {

    @Test
    void aFailureReportsThePsqlErrorWithoutTheRowsInItsContext() {
        RedshiftContainerManager containerManager = mock(RedshiftContainerManager.class);
        doThrow(new AwsException("InternalFailure", "Failed to restore snapshot for cluster c: psql:/tmp/x.sql:9: "
                + "ERROR:  invalid input syntax for type integer: \"secret-row\"\nCONTEXT:  COPY t, line 1: \"secret-row\"",
                500)).when(containerManager).runTableRestoreScript(anyString(), anyString(), anyString(), anyString(),
                any(Path.class));

        AwsException failure = assertThrows(AwsException.class,
                () -> new RedshiftTableRestorer(containerManager).run("1", "c", "admin", "dev", "BEGIN;\nCOMMIT;\n"));

        assertEquals("The table could not be restored: invalid input syntax for type integer: \"secret-row\"",
                failure.getMessage());
        assertFalse(failure.getMessage().contains("CONTEXT"));
    }
}
