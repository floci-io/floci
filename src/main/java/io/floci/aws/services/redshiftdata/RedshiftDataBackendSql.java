package io.floci.aws.services.redshiftdata;

import io.floci.aws.services.redshift.spectrum.BackendSql;
import io.floci.aws.services.redshift.spectrum.SpectrumReadException;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    public Optional<String> queryString(String sql) {
        try (Statement statement = connection.createStatement()) {
            if (execution != null) {
                execution.track(statement);
            }
            try (ResultSet rows = statement.executeQuery(sql)) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
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
    public List<List<String>> queryRows(String sql) {
        try (Statement statement = connection.createStatement()) {
            if (execution != null) {
                execution.track(statement);
            }
            try (ResultSet rows = statement.executeQuery(sql)) {
                int columns = rows.getMetaData().getColumnCount();
                List<List<String>> result = new ArrayList<>();
                while (rows.next()) {
                    List<String> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) {
                        row.add(rows.getString(i));
                    }
                    result.add(row);
                }
                return result;
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
    public void execute(String sql, List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            execute(sql);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (execution != null) {
                execution.track(statement);
            }
            try {
                for (int i = 0; i < parameters.size(); i++) {
                    String value = parameters.get(i);
                    if (value == null) {
                        statement.setNull(i + 1, Types.NULL);
                    } else {
                        statement.setString(i + 1, value);
                    }
                }
                statement.execute();
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
