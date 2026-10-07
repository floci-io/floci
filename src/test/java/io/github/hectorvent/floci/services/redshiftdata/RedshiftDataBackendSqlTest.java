package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumReadException;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedshiftDataBackendSqlTest {
    @Test
    void usesCallerConnectionWithoutCommittingOrClosingIt() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PGConnection pg = mock(PGConnection.class);
        CopyManager copy = mock(CopyManager.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.unwrap(PGConnection.class)).thenReturn(pg);
        when(pg.getCopyAPI()).thenReturn(copy);
        InputStream data = new ByteArrayInputStream(new byte[]{1});
        when(copy.copyIn("COPY t FROM STDIN", data)).thenReturn(2L);
        RedshiftDataBackendSql backend = new RedshiftDataBackendSql(connection);
        backend.execute("CREATE TABLE t(id INTEGER)");
        assertEquals(2L, backend.copyIn("COPY t FROM STDIN", data));
        verify(statement).execute("CREATE TABLE t(id INTEGER)");
        verify(statement).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).close();
    }

    @Test
    void preservesSqlStateOnFailure() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("denied", "42501"));
        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> new RedshiftDataBackendSql(connection).execute("SELECT 1"));
        assertEquals("42501", error.sqlState());
    }
}
