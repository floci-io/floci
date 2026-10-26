package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.core.common.PostgresWire;
import org.postgresql.ssl.NonValidatingFactory;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A client of a DSQL endpoint that writes the wire protocol itself, to send what JDBC sends one
 * message at a time: several queries, or several Syncs, before reading any answer.
 */
public final class DsqlWireClient implements AutoCloseable {

    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;

    /** Signs in to the endpoint on {@code port} as {@code user}, naming {@code host} as the server. */
    public DsqlWireClient(int port, String host, String user, String password) throws Exception {
        this(handshake(afterSslRequest(port), host, port));
        signIn(user, password);
    }

    private DsqlWireClient(SSLSocket tls) throws IOException {
        socket = tls;
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /**
     * TLS to the endpoint on {@code port} after an SSLRequest, naming {@code host} and offering
     * {@code alpn} (none when empty), and nothing sent on it yet: no startup packet.
     */
    public static DsqlWireClient tls(int port, String host, String... alpn) throws Exception {
        return new DsqlWireClient(handshake(afterSslRequest(port), host, port, alpn));
    }

    /** As {@link #tls}, but TLS started at once, with no SSLRequest, as {@code sslnegotiation=direct} does. */
    public static DsqlWireClient direct(int port, String host, String... alpn) throws Exception {
        return new DsqlWireClient(handshake(new Socket("127.0.0.1", port), host, port, alpn));
    }

    /** The ALPN protocol the endpoint picked, empty when none. */
    public String applicationProtocol() {
        return socket.getApplicationProtocol();
    }

    /** Sends the startup packet and the password, and reads up to the first ReadyForQuery. */
    public DsqlWireClient signIn(String user, String password) throws IOException {
        Map<String, String> startup = new LinkedHashMap<>();
        startup.put("user", user);
        startup.put("database", "postgres");
        out.write(PostgresWire.startupMessage(startup));
        assertEquals('R', PostgresWire.read(in, Integer.MAX_VALUE, "message").type());
        send(message('p', PostgresWire.cStringBytes(password)));
        untilReady();
        return this;
    }

    private static Socket afterSslRequest(int port) throws IOException {
        Socket plain = new Socket("127.0.0.1", port);
        plain.getOutputStream().write(PostgresWire.int32(8));
        plain.getOutputStream().write(PostgresWire.int32(PostgresWire.SSL_REQUEST));
        assertEquals('S', plain.getInputStream().read());
        return plain;
    }

    private static SSLSocket handshake(Socket plain, String host, int port, String... alpn) throws Exception {
        SSLSocket tls = (SSLSocket) new NonValidatingFactory(null).createSocket(plain, host, port, true);
        if (alpn.length > 0) {
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setApplicationProtocols(alpn);
            tls.setSSLParameters(parameters);
        }
        tls.startHandshake();
        return tls;
    }

    /** The next message the endpoint sends; an {@link IOException} once it has closed the connection. */
    public PostgresWire.Message next() throws IOException {
        return PostgresWire.read(in, Integer.MAX_VALUE, "message");
    }

    public void send(byte[]... messages) throws IOException {
        for (byte[] message : messages) {
            out.write(message);
        }
        out.flush();
    }

    /** The command tags, and the SQLSTATE of an error, up to the next ReadyForQuery. */
    public List<String> untilReady() throws IOException {
        List<String> answers = new ArrayList<>();
        while (true) {
            PostgresWire.Message message = PostgresWire.read(in, Integer.MAX_VALUE, "message");
            switch (message.type()) {
                case 'C' -> answers.add(PostgresWire.cString(message.body(), 0).value());
                case 'E' -> answers.add("ERROR " + PostgresWire.parseFields(message.body(), 0).get('C'));
                case 'Z' -> {
                    return answers;
                }
                default -> {
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static byte[] query(String sql) throws IOException {
        return message('Q', PostgresWire.cStringBytes(sql));
    }

    public static byte[] parse(String statement, String sql) throws IOException {
        return message('P', PostgresWire.cStringBytes(statement), PostgresWire.cStringBytes(sql), new byte[2]);
    }

    /** The unnamed portal for {@code statement}, with no parameters and text results. */
    public static byte[] bind(String statement) throws IOException {
        return message('B', PostgresWire.cStringBytes(""), PostgresWire.cStringBytes(statement), new byte[6]);
    }

    public static byte[] execute() throws IOException {
        return message('E', PostgresWire.cStringBytes(""), PostgresWire.int32(0));
    }

    public static byte[] close(String statement) throws IOException {
        return message('C', new byte[] {'S'}, PostgresWire.cStringBytes(statement));
    }

    public static byte[] sync() throws IOException {
        return message('S');
    }

    private static byte[] message(int type, byte[]... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PostgresWire.write(bytes, type, parts);
        return bytes.toByteArray();
    }
}
