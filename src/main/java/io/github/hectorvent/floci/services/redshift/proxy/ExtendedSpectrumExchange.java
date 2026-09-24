package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumInterceptor;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumMaterializer;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumReadException;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSqlException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Runs a Spectrum {@code Plan} for one Extended Query Execute.
 *
 * <p>Results are sent to the client immediately and this method then returns without waiting for
 * Flush or Sync: PostgreSQL lets a frontend pipeline any number of Parse/Bind/Execute steps before
 * a single trailing Sync, and Execute itself never produces {@code ReadyForQuery}. The client's
 * eventual Sync is handled generically by {@link RedshiftInterceptingBridge#run()}, which forwards
 * it to the real backend; the backend's own {@code ReadyForQuery} for that Sync is then relayed to
 * the client by the ordinary backend-to-client pump. Waiting here for Sync (or even just Flush)
 * before producing output would deadlock a client that reads Execute's result before deciding
 * whether to send more commands or Sync.
 *
 * <p>A Spectrum SELECT cannot be paused and resumed on the real backend (it runs as a single Simple
 * Query against the materialized temp table), so {@code Execute}'s {@code maxRows} is honored by
 * buffering the full row set once per portal in a {@link PortalCursor} and doling it out across
 * however many Execute calls the client makes, exactly like a real suspended portal: once the
 * buffer is empty this sends {@code CommandComplete}, otherwise {@code PortalSuspended}.
 */
final class ExtendedSpectrumExchange {

    private static final Logger LOG = Logger.getLogger(ExtendedSpectrumExchange.class);

    private ExtendedSpectrumExchange() {
    }

    static void execute(Socket client, Socket backend, SpectrumInterceptor.Plan plan,
            SpectrumInterceptor interceptor, String accountId, String database,
            BackendResponseCoordinator coordinator, BackendResponseCoordinator.Ticket ticket,
            String portalName, int maxRows, Map<String, PortalCursor> cursors) throws IOException {
        boolean failed = true;
        try {
            PortalCursor cursor = cursors.get(portalName);
            if (cursor == null) {
                SpectrumInterceptor.Decision decision = interceptor.execute(plan, accountId, database, backend);
                if (decision instanceof SpectrumInterceptor.Decision.Handled) {
                    forward(client, commandComplete("CREATE EXTERNAL"));
                    failed = false;
                    return;
                }
                if (decision instanceof SpectrumInterceptor.Decision.Rewritten rewritten) {
                    cursor = new PortalCursor(rewritten.materialization(),
                            bufferRows(backend, rewritten.sql()));
                    cursors.put(portalName, cursor);
                } else {
                    throw new SpectrumSqlException("0A000", "Spectrum plan did not produce an executable statement");
                }
            }
            deliverRows(client, cursor, maxRows);
            failed = false;
            if (cursor.exhausted()) {
                cursors.remove(portalName);
                cleanup(interceptor, backend, cursor.materialization());
            }
        } catch (RuntimeException e) {
            PortalCursor abandoned = cursors.remove(portalName);
            if (abandoned != null) {
                cleanup(interceptor, backend, abandoned.materialization());
            }
            forward(client, error(e));
        } finally {
            coordinator.completeOwnedExecute(ticket, failed);
        }
    }

    /** Drops a cursor's temp table, e.g. when its portal is closed before the client exhausts it. */
    static void abandon(SpectrumInterceptor interceptor, Socket backend, PortalCursor cursor) {
        cleanup(interceptor, backend, cursor.materialization());
    }

    private static void cleanup(SpectrumInterceptor interceptor, Socket backend,
            SpectrumMaterializer.Materialization materialization) {
        try {
            interceptor.cleanup(backend, materialization);
        } catch (RuntimeException cleanupFailure) {
            LOG.warnv(cleanupFailure, "Unable to clean up Spectrum materialization {0}",
                    materialization.identifier());
        }
    }

    private static Deque<PostgresWireDecoder.FrontendMessage> bufferRows(Socket backend, String sql)
            throws IOException {
        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(sql));
        backendOut.flush();

        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        Deque<PostgresWireDecoder.FrontendMessage> rows = new ArrayDeque<>();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                throw new IOException("Backend closed during Spectrum Execute");
            }
            switch (message.type()) {
                case 'Z' -> {
                    return rows;
                }
                case 'T', 'C' -> {
                    // RowDescription was already sent from catalog metadata at Describe time; the
                    // backend's own CommandComplete tag is discarded in favor of one reflecting the
                    // buffered row count, since rows are delivered across possibly several Executes.
                }
                case 'E' -> throw new SpectrumSqlException("22000",
                        "PostgreSQL rejected the rewritten Spectrum query: " + describeError(message));
                default -> rows.add(message);
            }
        }
    }

    private static String describeError(PostgresWireDecoder.FrontendMessage errorResponse) {
        byte[] body = errorResponse.body();
        if (body == null) {
            return "unknown error";
        }
        int offset = 0;
        while (offset < body.length && body[offset] != 0) {
            if (body[offset] == 'M') {
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
        while ((maxRows <= 0 || delivered < maxRows) && !cursor.rows().isEmpty()) {
            forward(client, cursor.rows().pollFirst());
            delivered++;
        }
        cursor.delivered(delivered);
        if (cursor.rows().isEmpty()) {
            forward(client, commandComplete("SELECT " + cursor.totalDelivered()));
        } else {
            forward(client, portalSuspended());
        }
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
        if (exception instanceof SpectrumSqlException spectrumSqlException) {
            return spectrumSqlException.sqlState();
        }
        if (exception instanceof SpectrumReadException spectrumReadException) {
            return spectrumReadException.sqlState();
        }
        return "22023";
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        OutputStream output = client.getOutputStream();
        output.write(message.toPacketBytes());
        output.flush();
    }

    /** The buffered, not-yet-delivered rows of one Spectrum SELECT bound to a portal, and the temp
     * table they came from. Rows are removed as they are delivered; {@link #exhausted()} is true
     * once every row has been sent to the client across one or more {@code Execute} calls. */
    static final class PortalCursor {
        private final SpectrumMaterializer.Materialization materialization;
        private final Deque<PostgresWireDecoder.FrontendMessage> rows;
        private long totalDelivered;

        PortalCursor(SpectrumMaterializer.Materialization materialization,
                Deque<PostgresWireDecoder.FrontendMessage> rows) {
            this.materialization = materialization;
            this.rows = rows;
        }

        SpectrumMaterializer.Materialization materialization() {
            return materialization;
        }

        Deque<PostgresWireDecoder.FrontendMessage> rows() {
            return rows;
        }

        void delivered(int count) {
            totalDelivered += count;
        }

        long totalDelivered() {
            return totalDelivered;
        }

        boolean exhausted() {
            return rows.isEmpty();
        }
    }
}
