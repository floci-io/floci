package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresProtocolHandlerTest {

    private static final int SSL_REQUEST_CODE = 80877103;

    @Test
    void acceptsPostgresSslRequestAndUpgradesSocket() throws Exception {
        ArrayBlockingQueue<Integer> serverRead = new ArrayBlockingQueue<>(1);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = server.accept()) {
                    DataInputStream in = new DataInputStream(accepted.getInputStream());
                    DataOutputStream out = new DataOutputStream(accepted.getOutputStream());
                    assertEquals(8, in.readInt());
                    assertEquals(SSL_REQUEST_CODE, in.readInt());

                    out.writeByte('S');
                    out.flush();
                    Socket sslSocket = PostgresProtocolHandler.acceptSsl(accepted);
                    serverRead.add(sslSocket.getInputStream().read());
                    sslSocket.getOutputStream().write(99);
                    sslSocket.getOutputStream().flush();
                    sslSocket.close();
                } catch (Exception e) {
                    serverRead.add(-1);
                }
            });

            try (Socket rawClient = new Socket("localhost", server.getLocalPort())) {
                DataOutputStream out = new DataOutputStream(rawClient.getOutputStream());
                DataInputStream in = new DataInputStream(rawClient.getInputStream());
                out.writeInt(8);
                out.writeInt(SSL_REQUEST_CODE);
                out.flush();
                assertEquals('S', in.readUnsignedByte());

                SSLSocket sslClient = (SSLSocket) trustAllContext().getSocketFactory()
                        .createSocket(rawClient, "localhost", server.getLocalPort(), true);
                sslClient.setUseClientMode(true);
                sslClient.startHandshake();
                sslClient.getOutputStream().write(42);
                sslClient.getOutputStream().flush();
                assertEquals(99, sslClient.getInputStream().read());
                sslClient.close();
            }
        }

        assertEquals(42, serverRead.poll(5, TimeUnit.SECONDS));
    }

    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }
}
