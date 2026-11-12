package io.floci.aws.services.redshift.proxy;

import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/** Uses an autocommit connection so COPY rollback cannot erase diagnostics. */
final class CopyLoadErrorLog implements Consumer<CopyLoadError>, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(CopyLoadErrorLog.class);
    private static final String INSERT = "INSERT INTO floci_internal.copy_load_errors "
            + "(userid, session, tbl, query, starttime, filename, line_number, colname, type, col_length, "
            + "raw_line, raw_field_value, err_code, err_reason, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
    private final ConnectionFactory connections;
    private Connection connection;
    private PreparedStatement statement;
    private boolean closed;

    CopyLoadErrorLog(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public void accept(CopyLoadError error) {
        if (closed) {
            return;
        }
        try {
            if (statement == null) {
                connection = connections.open();
                connection.setAutoCommit(true);
                try (Statement settings = connection.createStatement()) {
                    settings.execute("SET lock_timeout = '250ms'");
                    settings.execute("SET statement_timeout = '1000ms'");
                }
                statement = connection.prepareStatement(INSERT);
            }
            statement.setInt(1, error.context().userId());
            statement.setInt(2, error.context().sessionId());
            statement.setLong(3, error.context().tableId());
            statement.setInt(4, error.context().queryId());
            statement.setObject(5, LocalDateTime.ofInstant(error.context().started(), ZoneOffset.UTC));
            statement.setString(6, error.filename());
            statement.setLong(7, error.lineNumber());
            statement.setString(8, error.column());
            statement.setString(9, error.type());
            statement.setString(10, error.columnLength());
            statement.setString(11, error.rawLine());
            statement.setString(12, error.rawFieldValue());
            statement.setInt(13, error.code());
            statement.setString(14, error.reason());
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOG.warnv(exception, "Unable to record COPY load error for {0} at line {1}", error.filename(), error.lineNumber());
            // A caller may hold the catalog lock. Do not wait again for every rejected row.
            close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException exception) {
                LOG.debugv(exception, "Unable to close COPY load error statement");
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                LOG.debugv(exception, "Unable to close COPY load error connection");
            }
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }
}
