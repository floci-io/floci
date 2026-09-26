package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.redshift.spectrum.PostgresBackendSession;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumInterceptor;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumMaterializer;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumReadException;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSqlException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Executes the Phase 1 query for an Extended Query portal and pages its spooled result rows. */
final class ExtendedSpectrumExchange {

    private static final Logger LOG = Logger.getLogger(ExtendedSpectrumExchange.class);

    private ExtendedSpectrumExchange() {
    }

    static void execute(Socket client, Socket backend, SpectrumInterceptor.Plan plan,
                        SpectrumInterceptor interceptor, SpectrumSession session,
                        BackendResponseCoordinator coordinator, BackendResponseCoordinator.Ticket ticket,
                        String portalName, int maxRows, Map<String, PortalCursor> cursors) throws IOException {
        boolean failed = true;
        SpectrumMaterializer.Materialization unownedMaterialization = null;
        try {
            PortalCursor cursor = cursors.get(portalName);
            if (cursor == null) {
                SpectrumInterceptor.Decision decision = interceptor.execute(plan, session, new PostgresBackendSession(backend));
                if (decision instanceof SpectrumInterceptor.Decision.Handled handled) {
                    forward(client, commandComplete(handled.commandTag()));
                    failed = false;
                    return;
                }
                if (decision instanceof SpectrumInterceptor.Decision.Rewritten rewritten) {
                    unownedMaterialization = rewritten.materialization();
                    cursor = new PortalCursor(rewritten.materialization(), bufferRows(backend, rewritten.sql()));
                    cursors.put(portalName, cursor);
                    unownedMaterialization = null;
                } else {
                    throw new SpectrumSqlException("0A000", "Spectrum plan did not produce an executable query");
                }
            }
            deliverRows(client, cursor, maxRows);
            failed = false;
            if (cursor.exhausted()) {
                cursors.remove(portalName);
                cleanup(interceptor, backend, cursor);
            }
        } catch (RuntimeException exception) {
            PortalCursor abandoned = cursors.remove(portalName);
            if (abandoned != null) {
                cleanup(interceptor, backend, abandoned);
            } else if (unownedMaterialization != null) {
                cleanup(interceptor, backend, unownedMaterialization);
            }
            forward(client, error(exception));
        } catch (IOException exception) {
            PortalCursor abandoned = cursors.remove(portalName);
            if (abandoned != null) {
                cleanup(interceptor, backend, abandoned);
            } else if (unownedMaterialization != null) {
                cleanup(interceptor, backend, unownedMaterialization);
            }
            throw exception;
        } finally {
            coordinator.completeOwnedExecute(ticket, failed);
        }
    }

    static void abandon(SpectrumInterceptor interceptor, Socket backend, PortalCursor cursor) {
        cleanup(interceptor, backend, cursor);
    }

    static void dispose(PortalCursor cursor) {
        cursor.closeSpool();
    }

    private static void cleanup(SpectrumInterceptor interceptor, Socket backend, PortalCursor cursor) {
        cleanup(interceptor, backend, cursor.materialization());
        cursor.closeSpool();
    }

    private static void cleanup(SpectrumInterceptor interceptor, Socket backend,
                                SpectrumMaterializer.Materialization materialization) {
        try {
            interceptor.cleanup(new PostgresBackendSession(backend), materialization);
        } catch (RuntimeException cleanupFailure) {
            LOG.warnv(cleanupFailure, "Unable to clean up Spectrum materialization {0}", materialization.identifier());
        }
    }

    private static Path bufferRows(Socket backend, String sql) throws IOException {
        Path spoolFile = Files.createTempFile("floci-spectrum-", ".rows");
        boolean complete = false;
        try {
            try (OutputStream spool = Files.newOutputStream(spoolFile)) {
                bufferRows(backend, sql, spool);
            }
            complete = true;
            return spoolFile;
        } finally {
            if (!complete) {
                Files.deleteIfExists(spoolFile);
            }
        }
    }

    private static void bufferRows(Socket backend, String sql, OutputStream spool) throws IOException {
        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(sql));
        backendOut.flush();

        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage backendError = null;
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                throw new IOException("Backend closed during Spectrum Execute");
            }
            if (message.type() == 'Z') {
                if (backendError != null) {
                    String sqlState = errorField(backendError, 'C');
                    throw new SpectrumSqlException("unknown error".equals(sqlState) ? "22000" : sqlState,
                            "PostgreSQL rejected the rewritten Spectrum query: " + describeError(backendError));
                }
                return;
            }
            if (message.type() == 'E') {
                backendError = message;
            } else if (message.type() == 'D' && backendError == null) {
                spool.write(message.toPacketBytes());
            }
        }
    }

    private static String describeError(PostgresWireDecoder.FrontendMessage errorResponse) {
        return errorField(errorResponse, 'M');
    }

    private static String errorField(PostgresWireDecoder.FrontendMessage errorResponse, char field) {
        byte[] body = errorResponse.body();
        if (body == null) {
            return "unknown error";
        }
        int offset = 0;
        while (offset < body.length && body[offset] != 0) {
            if (body[offset] == field) {
                int start = offset + 1;
                int end = start;
                while (end < body.length && body[end] != 0) {
                    end++;
                }
                return new String(body, start, end - start, StandardCharsets.UTF_8);
            }
            while (offset < body.length && body[offset] != 0) {
                offset++;
            }
            offset++;
        }
        return "unknown error";
    }

    private static void deliverRows(Socket client, PortalCursor cursor, int maxRows) throws IOException {
        int delivered = 0;
        while (maxRows <= 0 || delivered < maxRows) {
            PostgresWireDecoder.FrontendMessage row = cursor.nextRow();
            if (row == null) {
                cursor.delivered(delivered);
                forward(client, commandComplete("SELECT " + cursor.totalDelivered()));
                return;
            }
            forward(client, row);
            delivered++;
        }
        cursor.delivered(delivered);
        forward(client, cursor.hasNextRow() ? portalSuspended()
                : commandComplete("SELECT " + cursor.totalDelivered()));
    }

    private static PostgresWireDecoder.FrontendMessage commandComplete(String tag) {
        return new PostgresWireDecoder.FrontendMessage('C', (tag + '\0').getBytes(StandardCharsets.UTF_8));
    }

    private static PostgresWireDecoder.FrontendMessage portalSuspended() {
        return new PostgresWireDecoder.FrontendMessage('s', new byte[0]);
    }

    private static PostgresWireDecoder.FrontendMessage error(RuntimeException exception) {
        return new PostgresWireDecoder.FrontendMessage('E',
                S3CopySimulator.errorBody(sqlState(exception), exception.getMessage()));
    }

    private static String sqlState(RuntimeException exception) {
        if (exception instanceof SpectrumSqlException sqlException) {
            return sqlException.sqlState();
        }
        if (exception instanceof SpectrumReadException readException) {
            return readException.sqlState();
        }
        return "22023";
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        OutputStream output = client.getOutputStream();
        output.write(message.toPacketBytes());
        output.flush();
    }

    static final class PortalCursor {
        private final SpectrumMaterializer.Materialization materialization;
        private final Path spoolFile;
        private final InputStream spoolInput;
        private final PostgresWireDecoder decoder;
        private PostgresWireDecoder.FrontendMessage pending;
        private boolean inputExhausted;
        private long totalDelivered;

        PortalCursor(SpectrumMaterializer.Materialization materialization, Path spoolFile) throws IOException {
            this.materialization = materialization;
            this.spoolFile = spoolFile;
            this.spoolInput = Files.newInputStream(spoolFile);
            this.decoder = new PostgresWireDecoder(spoolInput);
        }

        SpectrumMaterializer.Materialization materialization() {
            return materialization;
        }

        PostgresWireDecoder.FrontendMessage nextRow() throws IOException {
            if (pending != null) {
                PostgresWireDecoder.FrontendMessage result = pending;
                pending = null;
                return result;
            }
            if (inputExhausted) {
                return null;
            }
            PostgresWireDecoder.FrontendMessage result = decoder.nextMessage();
            inputExhausted = result == null;
            return result;
        }

        boolean hasNextRow() throws IOException {
            if (pending == null && !inputExhausted) {
                pending = decoder.nextMessage();
                inputExhausted = pending == null;
            }
            return pending != null;
        }

        void delivered(int count) {
            totalDelivered += count;
        }

        long totalDelivered() {
            return totalDelivered;
        }

        boolean exhausted() {
            return inputExhausted && pending == null;
        }

        void closeSpool() {
            try {
                spoolInput.close();
            } catch (IOException exception) {
                LOG.warnv(exception, "Unable to close Spectrum result spool {0}", spoolFile);
            }
            try {
                Files.deleteIfExists(spoolFile);
            } catch (IOException exception) {
                LOG.warnv(exception, "Unable to delete Spectrum result spool {0}", spoolFile);
            }
        }
    }
}
