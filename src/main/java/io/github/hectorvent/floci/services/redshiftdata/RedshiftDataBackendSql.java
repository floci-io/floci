package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.services.redshift.spectrum.BackendSql;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumReadException;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class RedshiftDataBackendSql implements BackendSql {
    private final Connection connection;
    private final RedshiftDataExecution execution;

    RedshiftDataBackendSql(Connection connection) {
        this(connection, null);
    }

    RedshiftDataBackendSql(Connection connection, RedshiftDataExecution execution) {
        this.connection = connection;
        this.execution = execution;
    }

    @Override
    public void execute(String sql) {
        try (Statement statement = connection.createStatement()) {
            if (execution != null) {
                execution.track(statement);
            }
            try {
                statement.execute(sql);
            } finally {
                if (execution != null) {
                    execution.untrack(statement);
                }
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public long copyIn(String copySql, InputStream data) {
        try {
            if (execution != null) {
                execution.checkCancellation();
            }
            return connection.unwrap(PGConnection.class).getCopyAPI().copyIn(copySql, data);
        } catch (SQLException exception) {
            throw failure(exception);
        } catch (IOException exception) {
            throw new SpectrumReadException("58030", "Unable to stream external table data", exception);
        }
    }

    private static SpectrumReadException failure(SQLException exception) {
        String state = exception.getSQLState() == null ? "58030" : exception.getSQLState();
        return new SpectrumReadException(state, exception.getMessage(), exception);
    }
}
