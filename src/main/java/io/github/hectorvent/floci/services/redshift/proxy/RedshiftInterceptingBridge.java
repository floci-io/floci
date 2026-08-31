package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    private final Socket client;
    private final Socket backend;
    private final S3Service s3Service;

    public RedshiftInterceptingBridge(Socket client, Socket backend, S3Service s3Service) {
        this.client = client;
        this.backend = backend;
        this.s3Service = s3Service;
    }

    public void run() {
        Thread pumpThread = Thread.ofVirtual().name("redshift-pump-backend-to-client").start(() -> {
            try {
                InputStream backendIn = backend.getInputStream();
                OutputStream clientOut = client.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = backendIn.read(buffer)) != -1) {
                    clientOut.write(buffer, 0, read);
                    clientOut.flush();
                }
            } catch (IOException e) {
                // Expected when sockets close
            } finally {
                closeQuietly(client);
                closeQuietly(backend);
            }
        });

        try {
            PostgresWireDecoder decoder = new PostgresWireDecoder(client.getInputStream());
            OutputStream backendOut = backend.getOutputStream();
            PostgresWireDecoder.FrontendMessage msg;
            while ((msg = decoder.nextMessage()) != null) {
                if (!msg.isQuery()) {
                    backendOut.write(msg.toPacketBytes());
                    backendOut.flush();
                    if (msg.type() == 'X') {
                        break;
                    }
                    continue;
                }

                String sql = msg.getSql();
                CopyStatementParser.S3Statement stmt = CopyStatementParser.parse(sql);
                if (stmt instanceof CopyStatementParser.S3CopyFrom copyFrom) {
                    S3CopySimulator.runCopyFrom(client, backend, copyFrom, s3Service);
                } else if (stmt instanceof CopyStatementParser.S3Unload unload) {
                    S3CopySimulator.runUnload(client, backend, unload, s3Service);
                } else {
                    String rewritten = RedshiftSqlInterceptor.rewrite(sql);
                    if (rewritten == sql) { // fast path identity check
                        backendOut.write(msg.toPacketBytes());
                    } else {
                        backendOut.write(PostgresWireDecoder.encodeQuery(rewritten));
                    }
                    backendOut.flush();
                }
            }
        } catch (Exception e) {
            LOG.warnv("Error in RedshiftInterceptingBridge: {0}", e.getMessage());
            // Fail-open behavior not fully possible if we partially read a packet, 
            // but we'll try to just close and clean up
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
