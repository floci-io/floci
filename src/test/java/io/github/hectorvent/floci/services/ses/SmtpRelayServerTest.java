package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.StartTLSOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the relay through a real Vert.x {@link MailClient} against an in-process SMTP server, so
 * the assertions cover what a mocked client cannot: the SMTP envelope actually negotiated, and the
 * bytes the encoder puts on the wire.
 */
class SmtpRelayServerTest {

    private static StubSmtpServer server;
    private static Vertx vertx;

    private final List<MailClient> clients = new ArrayList<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = new StubSmtpServer();
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeClients() {
        // Vert.x pools SMTP connections, so an unclosed client keeps its socket open and the next
        // test would assert against a stale session.
        clients.forEach(MailClient::close);
        clients.clear();
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.close();
        vertx.close();
    }

    /**
     * Builds the relay the way CDI would, then swaps in the synchronous-executor constructor so the
     * assertions run after delivery rather than racing it. The authentication and STARTTLS-OPTIONAL
     * branches of {@link SmtpRelay#buildMailConfig} are exercised on the way through.
     */
    private SmtpRelay relayAgainstServer() {
        MailConfig mailConfig = SmtpRelay.buildMailConfig(
                config("relay-user", "relay-pass", "OPTIONAL"),
                server.host(), server.port());
        MailClient client = MailClient.create(vertx, mailConfig);
        clients.add(client);
        return new SmtpRelay(client, true);
    }

    private static EmulatorConfig config(String user, String pass, String starttls) {
        EmulatorConfig config = mock(EmulatorConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.services().ses().smtpUser()).thenReturn(Optional.ofNullable(user));
        when(config.services().ses().smtpPass()).thenReturn(Optional.ofNullable(pass));
        when(config.services().ses().smtpStarttls()).thenReturn(starttls);
        return config;
    }

    @Test
    void rawRelay_deliversEnvelopeHeadersAndAttachmentOverSmtp() throws Exception {
        server.reset();
        String attachmentBody = Base64.getEncoder().encodeToString(
                "PDF-PAYLOAD".getBytes(StandardCharsets.UTF_8));
        String rawMime = "From: Alice Sender <sender@example.com>\r\n"
                + "To: Bob <to@example.com>\r\n"
                + "Subject: Real SMTP\r\n"
                + "Return-Path: <bounces@example.com>\r\n"
                + "X-Custom: keep-me\r\n"
                + "X-SES-CONFIGURATION-SET: my-config-set\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "the real body\r\n"
                + "--outer\r\n"
                + "Content-Type: application/pdf\r\n"
                + "Content-Disposition: attachment; filename=\"report.pdf\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + attachmentBody + "\r\n"
                + "--outer--";

        relayAgainstServer().relayRaw(new SmtpRelay.RawRelayMessage("sender@example.com",
                "bounces@example.com", List.of("dest@example.com"), rawMime,
                "msg-42", "eu-west-1"));

        assertTrue(server.awaitDelivery(), "the stub server should have accepted a message");
        assertEquals("bounces@example.com", server.mailFrom(),
                "the Return-Path must become the SMTP envelope sender");
        assertEquals(List.of("dest@example.com"), server.recipients());

        String data = server.data();
        assertTrue(data.contains("Message-ID: <msg-42@eu-west-1.amazonses.com>"), data);
        assertTrue(data.contains("From: Alice Sender <sender@example.com>"),
                "the display name must survive the relay: " + data);
        assertTrue(data.contains("X-Custom: keep-me"), data);
        assertTrue(data.contains("the real body"), data);
        assertTrue(data.contains("report.pdf"), data);
        assertTrue(data.contains(attachmentBody), "the attachment payload must survive the relay");
        // AWS consumes the X-SES-* control headers rather than forwarding them.
        assertFalse(data.contains("X-SES-CONFIGURATION-SET"), data);
        // The caller's Content-Type described the original tree, which the encoder has rebuilt.
        assertFalse(data.contains("boundary=\"outer\""), data);
    }

    @Test
    void structuredRelay_usesReturnPathAsEnvelopeSenderAndStampsMessageId() throws Exception {
        server.reset();

        relayAgainstServer().relay(SmtpRelay.RelayMessage.builder("sender@example.com")
                .returnPath("bounces@example.com")
                .to(List.of("to@example.com"))
                .cc(List.of("cc@example.com"))
                .subject("Structured")
                .bodyText("plain body")
                .headers(List.of(new MessageHeader("X-Custom", "kept")))
                .messageId("msg-7")
                .region("us-west-2")
                .build());

        assertTrue(server.awaitDelivery(), "the stub server should have accepted a message");
        assertEquals("bounces@example.com", server.mailFrom());
        assertEquals(List.of("to@example.com", "cc@example.com"), server.recipients());

        String data = server.data();
        assertTrue(data.contains("Message-ID: <msg-7@us-west-2.amazonses.com>"), data);
        assertTrue(data.contains("X-Custom: kept"), data);
        assertTrue(data.contains("plain body"), data);
    }

    @Test
    void relay_authenticatesWithTheConfiguredCredentials() throws Exception {
        server.reset();

        relayAgainstServer().relay(SmtpRelay.RelayMessage.builder("sender@example.com")
                .to(List.of("to@example.com"))
                .subject("Auth")
                .bodyText("body")
                .build());

        assertTrue(server.awaitDelivery());
        String auth = server.authCommand();
        assertNotNull(auth, "the relay should authenticate when smtp-user is configured");
        String credentials = new String(Base64.getDecoder().decode(auth.substring("AUTH PLAIN ".length())),
                StandardCharsets.UTF_8);
        assertEquals("\0relay-user\0relay-pass", credentials);
    }

    @Test
    void buildMailConfig_mapsStarttlsModesAndFallsBackToDisabled() {
        assertEquals(StartTLSOptions.REQUIRED,
                SmtpRelay.buildMailConfig(config(null, null, "required"), "h", 25).getStarttls());
        assertEquals(StartTLSOptions.OPTIONAL,
                SmtpRelay.buildMailConfig(config(null, null, "OPTIONAL"), "h", 25).getStarttls());
        assertEquals(StartTLSOptions.DISABLED,
                SmtpRelay.buildMailConfig(config(null, null, "DISABLED"), "h", 25).getStarttls());
        assertEquals(StartTLSOptions.DISABLED,
                SmtpRelay.buildMailConfig(config(null, null, "nonsense"), "h", 25).getStarttls());
    }

    @Test
    void buildMailConfig_withoutAUsername_leavesCredentialsUnset() {
        MailConfig mailConfig = SmtpRelay.buildMailConfig(config(" ", "pass", "DISABLED"), "h", 25);
        assertNull(mailConfig.getUsername());
        assertNull(mailConfig.getPassword());
    }

    /**
     * A minimal SMTP server: enough of the protocol for a real {@link MailClient} to complete a
     * session, capturing the envelope and the DATA payload.
     */
    private static final class StubSmtpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private volatile java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);
        private volatile String mailFrom;
        private volatile String authCommand;
        private volatile String data;
        private volatile List<String> recipients = new ArrayList<>();

        StubSmtpServer() throws IOException {
            serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            acceptThread = new Thread(this::acceptLoop, "stub-smtp");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        String host() {
            return serverSocket.getInetAddress().getHostAddress();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void reset() {
            delivered = new java.util.concurrent.CountDownLatch(1);
            mailFrom = null;
            authCommand = null;
            data = null;
            recipients = new ArrayList<>();
        }

        boolean awaitDelivery() throws InterruptedException {
            return delivered.await(20, java.util.concurrent.TimeUnit.SECONDS);
        }

        String mailFrom() {
            return mailFrom;
        }

        List<String> recipients() {
            return recipients;
        }

        String data() {
            return data;
        }

        String authCommand() {
            return authCommand;
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    // Vert.x keeps the connection pooled after a send, so the session is handled off
                    // the accept loop: otherwise the first idle connection blocks every later test.
                    Thread worker = new Thread(() -> {
                        try (socket) {
                            converse(socket);
                        } catch (IOException e) {
                            // The client closed the pooled connection; nothing left to serve.
                        }
                    }, "stub-smtp-session");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        throw new IllegalStateException("stub SMTP server failed", e);
                    }
                    return;
                }
            }
        }

        private void converse(Socket socket) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            reply(out, "220 floci-stub ESMTP");

            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    reply(out, "250-floci-stub");
                    reply(out, "250 AUTH PLAIN");
                } else if (command.startsWith("HELO")) {
                    reply(out, "250 floci-stub");
                } else if (command.startsWith("AUTH")) {
                    authCommand = line;
                    reply(out, "235 2.7.0 Authentication successful");
                } else if (command.startsWith("MAIL FROM")) {
                    mailFrom = address(line);
                    reply(out, "250 OK");
                } else if (command.startsWith("RCPT TO")) {
                    recipients.add(address(line));
                    reply(out, "250 OK");
                } else if (command.startsWith("DATA")) {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    data = readData(in);
                    reply(out, "250 OK: queued");
                    delivered.countDown();
                } else if (command.startsWith("QUIT")) {
                    reply(out, "221 Bye");
                    return;
                } else if (command.startsWith("RSET") || command.startsWith("NOOP")) {
                    reply(out, "250 OK");
                } else {
                    reply(out, "500 Unrecognized command");
                }
            }
        }

        private static String readData(BufferedReader in) throws IOException {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null && !".".equals(line)) {
                // Undo the dot-stuffing the client applies to lines starting with a period.
                body.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
            }
            return body.toString();
        }

        private static String address(String line) {
            int start = line.indexOf('<');
            int end = line.indexOf('>', start + 1);
            return start >= 0 && end > start ? line.substring(start + 1, end) : "";
        }

        private static void reply(BufferedWriter out, String response) throws IOException {
            out.write(response);
            out.write("\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
