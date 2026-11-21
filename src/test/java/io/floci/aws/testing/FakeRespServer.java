package io.floci.aws.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A RESP server on a free local port that records every command array it is sent, in order across its
 * connections, and answers each with the raw reply {@code replies} gives for it.
 */
public final class FakeRespServer implements AutoCloseable {

    private final ServerSocket server;
    private final Function<List<String>, String> replies;
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    public FakeRespServer(Function<List<String>, String> replies) throws IOException {
        this.server = new ServerSocket(0);
        this.replies = replies;
        Thread acceptor = new Thread(this::acceptConnections, "fake-resp-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Answers {@code +OK} to everything. */
    public static FakeRespServer answeringOk() throws IOException {
        return new FakeRespServer(command -> "+OK\r\n");
    }

    public static String bulk(String value) {
        return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n";
    }

    public int port() {
        return server.getLocalPort();
    }

    /** The commands received so far, a live view. */
    public List<List<String>> commands() {
        return commands;
    }

    public int connections() {
        return accepted.size();
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : accepted) {
            socket.close();
        }
    }

    private void acceptConnections() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                accepted.add(socket);
                Thread handler = new Thread(() -> serve(socket), "fake-resp-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (true) {
                List<String> command = readCommand(in);
                if (command == null) {
                    return;
                }
                commands.add(command);
                out.write(replies.apply(command).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException expected) {
            // The client or close() ended the connection, which is how every fake connection ends.
        }
    }

    private static List<String> readCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        int count = Integer.parseInt(header.substring(1));
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = Integer.parseInt(readLine(in).substring(1));
            byte[] bytes = in.readNBytes(length);
            readLine(in);
            arguments.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return arguments;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                return line.toString();
            }
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return null;
    }
}
