package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopyLoadErrorLogTest {
    private final CopyLoadErrorLog.ConnectionFactory connections = mock(CopyLoadErrorLog.ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement insert = mock(PreparedStatement.class);
    private final Statement settings = mock(Statement.class);
    private CopyLoadErrorLog log;

    @BeforeEach
    void setup() throws Exception {
        when(connections.open()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(settings);
        when(connection.prepareStatement(anyString())).thenReturn(insert);
        log = new CopyLoadErrorLog(connections);
    }

    @Test
    void opensLazilyAndReusesAutocommitConnectionAndPreparedStatement() throws Exception {
        verifyNoInteractions(connections);
        log.accept(error());
        log.accept(error());
        verify(connections).open();
        verify(connection).setAutoCommit(true);
        verify(connection).prepareStatement(contains("floci_internal.copy_load_errors"));
        verify(insert, times(2)).executeUpdate();
        verify(connection, never()).close();
        verify(insert, times(2)).setString(11, "private raw record");
    }

    @Test
    void boundsLockAndStatementWaitsBeforeInserting() throws Exception {
        log.accept(error());
        verify(settings).execute("SET lock_timeout = '250ms'");
        verify(settings).execute("SET statement_timeout = '1000ms'");
        verify(settings).close();
    }

    @Test
    void failedInsertDisablesDiagnosticsForTheBridgeAndClosesResources() throws Exception {
        when(insert.executeUpdate()).thenThrow(new SQLException("locked", "55P03"));
        assertDoesNotThrow(() -> log.accept(error()));
        assertDoesNotThrow(() -> log.accept(error()));
        verify(connections).open();
        verify(insert).executeUpdate();
        verify(insert).close();
        verify(connection).close();
    }

    @Test
    void connectionFailureIsAttemptedOnlyOnce() throws Exception {
        when(connections.open()).thenThrow(new SQLException("unavailable"));
        assertDoesNotThrow(() -> log.accept(error()));
        assertDoesNotThrow(() -> log.accept(error()));
        verify(connections).open();
    }

    @Test
    void settingFailureClosesConnectionAndDoesNotInsert() throws Exception {
        when(settings.execute(anyString())).thenThrow(new SQLException("settings failed"));
        assertDoesNotThrow(() -> log.accept(error()));
        verify(connection).close();
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void closeIsIdempotentAndPreventsReopening() throws Exception {
        log.accept(error());
        AutoCloseable closeable = assertInstanceOf(AutoCloseable.class, log);
        closeable.close();
        closeable.close();
        log.accept(error());
        verify(insert).close();
        verify(connection).close();
        verify(connections).open();
        verify(insert).executeUpdate();
    }

    @Test
    void closeWithoutErrorsDoesNotConnect() throws Exception {
        assertInstanceOf(AutoCloseable.class, log).close();
        verifyNoInteractions(connections);
    }

    @Test
    void closeFailureStillClosesConnectionAndRetainsOriginalFailure() throws Exception {
        when(insert.executeUpdate()).thenThrow(new SQLException("original"));
        doThrow(new SQLException("close failed")).when(insert).close();
        assertDoesNotThrow(() -> log.accept(error()));
        verify(connection).close();
    }

    private CopyLoadError error() {
        return new CopyLoadError(new CopyLoadError.Context(10, 20, 30, 40, Instant.EPOCH),
                "s3://bucket/key", 7, "id", "int4", "", "private raw record", "bad", 1207, "Invalid digit");
    }
}
