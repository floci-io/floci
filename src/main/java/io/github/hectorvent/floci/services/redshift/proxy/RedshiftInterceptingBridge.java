package io.github.hectorvent.floci.services.redshift.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces the transparent {@code PostgresProtocolHandler.bridge} for Redshift connections so
 * Simple Query ({@code 'Q'}) DDL can be rewritten for a plain PostgreSQL backend before it is
 * forwarded.
 *
 * <p><b>Model.</b> Backend&rarr;client is a verbatim byte pump on a virtual thread, exactly as a
 * plain proxy: extended-protocol pipelines, {@code COPY … FROM STDIN}, asynchronous
 * {@code NotificationResponse} and backend EOF all flow through untouched. Client&rarr;backend is
 * a framed loop: every frontend message is forwarded opaque <em>except</em> a {@code 'Q'}, whose
 * SQL is run through {@link RedshiftSqlInterceptor#rewrite} and re-encoded only if it changed.
 *
 * <p><b>Fail-open.</b> A {@code rewrite} failure, or any statement the interceptor leaves
 * untouched, forwards the original bytes; PostgreSQL then returns its own error. The bridge never
 * closes the connection because of a rewrite failure.
 */
public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    private final Socket client;
    private final Socket backend;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedshiftInterceptingBridge(Socket client, Socket backend) {
        this.client = client;
        this.backend = backend;
    }

    public void run() {
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream backendOut = backend.getOutputStream();

            Thread.ofVirtual().name("redshift-pump-backend-to-client").start(this::pumpBackendToClient);

            PostgresWireDecoder decoder = new PostgresWireDecoder(clientIn);
            while (true) {
                PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage(backendOut);
                if (msg == null) {
                    break; // client EOF
                }

                if (!msg.isQuery()) {
                    if (msg.type() == 'X') { // Terminate
                        break;
                    }
                    continue;
                }

                String sql = msg.getSql();
                byte[] toBackend = msg.toPacketBytes();
                try {
                    String rewritten = RedshiftSqlInterceptor.rewrite(sql);
                    if (rewritten != sql) { // identity: rewrite returns the same instance when nothing matched
                        toBackend = PostgresWireDecoder.encodeQuery(rewritten);
                    }
                } catch (RuntimeException e) {
                    LOG.warnv("RedshiftSqlInterceptor failed, forwarding original query: {0}", e.getMessage());
                    toBackend = msg.toPacketBytes();
                }
                backendOut.write(toBackend);
                backendOut.flush();
                // The response returns over the untouched pump.
            }
        } catch (IOException e) {
            LOG.debugv(e, "RedshiftInterceptingBridge client loop ended");
        } catch (Exception e) {
            LOG.warnv(e, "Unexpected error in RedshiftInterceptingBridge");
        } finally {
            closeBoth();
        }
    }

    private void pumpBackendToClient() {
        try {
            InputStream backendIn = backend.getInputStream();
            OutputStream clientOut = client.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = backendIn.read(buf)) != -1) {
                clientOut.write(buf, 0, n);
                clientOut.flush();
            }
        } catch (IOException e) {
            LOG.debugv(e, "backend->client pump ended");
        } finally {
            closeBoth();
        }
    }

    private void closeBoth() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(client, "client");
            closeQuietly(backend, "backend");
        }
    }

    private void closeQuietly(Socket s, String which) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "error closing {0} socket", which);
        }
    }
}
