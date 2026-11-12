package io.floci.aws.services.rds.proxy;

import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.testutil.IamServiceTestHelper;
import io.floci.aws.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Adler32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlProtocolHandlerTest {

    private static final int CLIENT_PROTOCOL_41 = 0x0200;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_COMPRESS = 0x0020;
    private static final int CLIENT_PLUGIN_AUTH = 0x0008_0000;
    private static final String MYSQL_NATIVE_PASSWORD = "mysql_native_password";
    private static final String MYSQL_CLEAR_PASSWORD = "mysql_clear_password";
    private static final int CLIENT_CONNECT_WITH_DB = 0x0008;
    private static final int CLIENT_CONNECT_ATTRS = 0x0010_0000;
    private static final int CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x0020_0000;
    private static final int CLIENT_ZSTD_COMPRESSION_ALGORITHM = 0x0400_0000;
    /** What libmysqlclient 8 sends: every optional field after the auth-response is present. */
    private static final int FULL_RESPONSE_CAPABILITIES = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION
            | CLIENT_CONNECT_WITH_DB | CLIENT_PLUGIN_AUTH | CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
            | CLIENT_CONNECT_ATTRS | CLIENT_ZSTD_COMPRESSION_ALGORITHM;
    private static final int ER_ACCESS_DENIED = 1045;
    private static final byte[] OK_PAYLOAD = {0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00};
    private static final byte[] MASTER_BACKEND_NONCE =
            "second-backend-nonce".getBytes(StandardCharsets.US_ASCII);

    /** Fails a login that opens a second backend connection; only an IAM auth switch may. */
    private static final PostgresProtocolHandler.BackendConnector NO_RECONNECT = () -> {
        throw new IOException("unexpected second backend connection");
    };

    @TempDir
    Path tempDir;

    @Test
    void forwardsPlaintextHandshakeResponseUnmodifiedWhenClientDoesNotRequestTls() throws Exception {
        withLoggedInClient(0, 0, false, streams -> {
            // The proxy forces CLIENT_SSL even though the mock backend did not advertise it.
            assertTrue((capabilityFlagsLower(streams.serverHandshake()) & CLIENT_SSL) != 0);
            assertEquals((byte) 1, streams.backendResponse()[3],
                    "sequence must be unchanged when no TLS upgrade occurs");
            assertEquals("admin", extractUsername(Arrays.copyOfRange(
                    streams.backendResponse(), 4, streams.backendResponse().length)));
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "true,true,false", "true,false,true"})
    void relaysCompleteCommandFrameAfterCompressionNegotiation(boolean compressed, boolean zlibPayload,
                                                               boolean tls) throws Exception {
        ByteArrayOutputStream queryPayload = new ByteArrayOutputStream();
        queryPayload.write(0x03); // COM_QUERY
        queryPayload.writeBytes("SET @floci_probe=1".getBytes(StandardCharsets.UTF_8));
        byte[] command = wrapPacket(0, queryPayload.toByteArray());
        byte[] expectedFrame = compressed ? wrapCompressedFrame(command, zlibPayload) : command;
        assertRelayedCommandFrames(expectedFrame, compressed, tls);
    }

    @Test
    void relaysZstdNegotiatedFrameUnchanged() throws Exception {
        byte[] command = wrapPacket(0, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1'});
        assertRelayedCommandFrames(wrapCompressedPacket(command), CLIENT_ZSTD_COMPRESSION_ALGORITHM, false);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void relaysFragmentedCompressedCommandBytesUnchanged(boolean trailingPartialHeader,
                                                        boolean zlibPayload) throws Exception {
        ByteArrayOutputStream queryPayload = new ByteArrayOutputStream();
        queryPayload.write(0x03); // COM_QUERY
        queryPayload.writeBytes("SET @floci_probe=1".getBytes(StandardCharsets.UTF_8));
        byte[] command = wrapPacket(0, queryPayload.toByteArray());
        byte[] firstPayload;
        byte[] secondPayload;
        if (trailingPartialHeader) {
            byte[] nextCommand = wrapPacket(1, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1'});
            firstPayload = Arrays.copyOf(command, command.length + 2);
            System.arraycopy(nextCommand, 0, firstPayload, command.length, 2);
            secondPayload = Arrays.copyOfRange(nextCommand, 2, nextCommand.length);
        } else {
            int split = command.length / 2;
            firstPayload = Arrays.copyOf(command, split);
            secondPayload = Arrays.copyOfRange(command, split, command.length);
        }
        byte[] firstFrame = wrapCompressedFrame(firstPayload, zlibPayload, 0);
        byte[] secondFrame = wrapCompressedFrame(secondPayload, zlibPayload, 1);
        if (trailingPartialHeader) {
            withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
                streams.clientOut().write(firstFrame);
                streams.clientOut().flush();
                assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
                streams.clientOut().write(secondFrame);
                streams.clientOut().flush();
                assertArrayEquals(secondFrame, readCompressedFrame(streams.backendIn()));
            });
        } else {
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            frames.writeBytes(firstFrame);
            frames.writeBytes(secondFrame);
            assertRelayedCommandFrames(frames.toByteArray(), true, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relaysLargeNonIamCompressedCommandUnchanged(boolean zlibPayload) throws Exception {
        ByteArrayOutputStream query = new ByteArrayOutputStream();
        query.write(0x03); // COM_QUERY
        query.writeBytes("SELECT 1 /*"
                .getBytes(StandardCharsets.UTF_8));
        byte[] padding = new byte[1 << 20];
        Arrays.fill(padding, (byte) 'x');
        query.writeBytes(padding);
        query.writeBytes("*/".getBytes(StandardCharsets.UTF_8));
        byte[] command = wrapPacket(0, query.toByteArray());
        byte[] frame = wrapCompressedFrame(command, zlibPayload);
        assertRelayedCommandFrames(frame, CLIENT_COMPRESS, false, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rewritesIamCommandAfterLargeCompressedCommand(boolean zlibPayload) throws Exception {
        ByteArrayOutputStream firstQuery = new ByteArrayOutputStream();
        firstQuery.write(0x03);
        firstQuery.writeBytes("SELECT 1 /*".getBytes(StandardCharsets.US_ASCII));
        byte[] padding = new byte[1 << 20];
        Arrays.fill(padding, (byte) 'x');
        firstQuery.writeBytes(padding);
        firstQuery.writeBytes("*/".getBytes(StandardCharsets.US_ASCII));
        byte[] firstFrame = wrapCompressedFrame(wrapPacket(0, firstQuery.toByteArray()), zlibPayload);
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] secondQuery = new byte[sql.length() + 1];
        secondQuery[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, secondQuery, 1, sql.length());
        byte[] secondFrame = wrapCompressedFrame(wrapPacket(0, secondQuery), zlibPayload);
        withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
            FutureTask<Void> sendFirst = new FutureTask<>(() -> {
                streams.clientOut().write(firstFrame);
                streams.clientOut().flush();
                return null;
            });
            Thread.ofVirtual().start(sendFirst);
            assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
            sendFirst.get(3, TimeUnit.SECONDS);
            streams.backendOut().write(wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 1));
            streams.backendOut().flush();
            assertNotNull(readCompressedFrame(streams.clientIn()));
            streams.clientOut().write(secondFrame);
            streams.clientOut().flush();
            assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                    decodeCompressedFrame(readCompressedFrame(streams.backendIn()), false));
        });
    }

    @Test
    void streamsNearLimitNonIamCompressedQueryBeforeItsLastFrame() throws Exception {
        byte[] query = new byte[0xFFFFFF - 2];
        Arrays.fill(query, (byte) ' ');
        query[0] = 0x03;
        System.arraycopy("SELECT 1".getBytes(StandardCharsets.US_ASCII), 0, query, 1, 8);
        byte[] command = wrapPacket(0, query);
        byte[] firstFrame = wrapCompressedPacket(Arrays.copyOf(command, 32), 0);
        byte[] secondFrame = wrapCompressedPacket(Arrays.copyOfRange(command, 32, command.length), 1);
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] nextQuery = new byte[sql.length() + 1];
        nextQuery[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, nextQuery, 1, sql.length());
        byte[] nextFrame = wrapCompressedPacket(wrapPacket(0, nextQuery), 0);

        withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
            streams.clientOut().write(firstFrame);
            streams.clientOut().flush();
            assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
            FutureTask<Void> sendRest = new FutureTask<>(() -> {
                streams.clientOut().write(secondFrame);
                streams.clientOut().flush();
                return null;
            });
            Thread.ofVirtual().start(sendRest);
            assertArrayEquals(secondFrame, readCompressedFrame(streams.backendIn()));
            sendRest.get(5, TimeUnit.SECONDS);
            streams.backendOut().write(wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 2));
            streams.backendOut().flush();
            assertNotNull(readCompressedFrame(streams.clientIn()));
            streams.clientOut().write(nextFrame);
            streams.clientOut().flush();
            assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                    decodeCompressedFrame(readCompressedFrame(streams.backendIn()), false));
        });
    }

    @Test
    void streamsSplitCreateTableCommandBeforeItsLastFrame() throws Exception {
        String sql = "CREATE TABLE sample (id INT)";
        byte[] query = new byte[sql.length() + 1];
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] command = wrapPacket(0, query);
        byte[] firstFrame = wrapCompressedPacket(Arrays.copyOf(command, 5 + "CREATE TABLE".length()), 0);
        byte[] secondFrame = wrapCompressedPacket(
                Arrays.copyOfRange(command, 5 + "CREATE TABLE".length(), command.length), 1);
        String iamSql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] iamQuery = new byte[iamSql.length() + 1];
        iamQuery[0] = 0x03;
        System.arraycopy(iamSql.getBytes(StandardCharsets.US_ASCII), 0, iamQuery, 1, iamSql.length());

        withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
            streams.clientOut().write(firstFrame);
            streams.clientOut().flush();
            assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
            streams.clientOut().write(secondFrame);
            streams.clientOut().flush();
            assertArrayEquals(secondFrame, readCompressedFrame(streams.backendIn()));
            streams.backendOut().write(wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 2));
            streams.backendOut().flush();
            assertNotNull(readCompressedFrame(streams.clientIn()));
            streams.clientOut().write(wrapCompressedPacket(wrapPacket(0, iamQuery), 0));
            streams.clientOut().flush();
            assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                    decodeCompressedFrame(readCompressedFrame(streams.backendIn()), false));
        });
    }

    @Test
    void releasesLargeEncodedFramesBeforeAnIncompleteIamCommandFinishes() throws Exception {
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] query = new byte[sql.length() + 1];
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] command = wrapPacket(0, query);
        byte[] firstFrame = wrapZlibFrameWithEmptyBlocks(Arrays.copyOf(command, 8), 0, 120_000);
        byte[] secondFrame = wrapZlibFrameWithEmptyBlocks(Arrays.copyOfRange(command, 8, 9), 1, 120_000);
        byte[] thirdFrame = wrapZlibCompressedPacket(Arrays.copyOfRange(command, 9, command.length), 2);
        byte[] nextFrame = wrapZlibCompressedPacket(command, 0);

        withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
            streams.clientOut().write(firstFrame);
            streams.clientOut().write(secondFrame);
            streams.clientOut().flush();
            assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
            assertArrayEquals(secondFrame, readCompressedFrame(streams.backendIn()));
            streams.clientOut().write(thirdFrame);
            streams.clientOut().flush();
            assertArrayEquals(thirdFrame, readCompressedFrame(streams.backendIn()));
            streams.backendOut().write(wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 3));
            streams.backendOut().flush();
            assertNotNull(readCompressedFrame(streams.clientIn()));
            streams.clientOut().write(nextFrame);
            streams.clientOut().flush();
            assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                    decodeCompressedFrame(readCompressedFrame(streams.backendIn()), false));
        });
    }

    @Test
    void retainsPartialInnerHeaderAfterEncodedFrameFallback() throws Exception {
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] query = new byte[sql.length() + 1];
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] command = wrapPacket(0, query);
        byte[] firstFrame = wrapZlibFrameWithEmptyBlocks(Arrays.copyOf(command, 1), 0, 220_000);
        byte[] secondFrame = wrapCompressedPacket(Arrays.copyOfRange(command, 1, 4), 1);
        byte[] thirdFrame = wrapCompressedPacket(Arrays.copyOfRange(command, 4, command.length), 2);

        withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
            FutureTask<Void> sendFirst = new FutureTask<>(() -> {
                streams.clientOut().write(firstFrame);
                streams.clientOut().flush();
                return null;
            });
            Thread.ofVirtual().start(sendFirst);
            assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
            sendFirst.get(5, TimeUnit.SECONDS);
            streams.clientOut().write(secondFrame);
            streams.clientOut().flush();
            assertArrayEquals(secondFrame, readCompressedFrame(streams.backendIn()));
            streams.clientOut().write(thirdFrame);
            streams.clientOut().flush();
            assertArrayEquals(thirdFrame, readCompressedFrame(streams.backendIn()));
            streams.backendOut().write(wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 3));
            streams.backendOut().flush();
            assertNotNull(readCompressedFrame(streams.clientIn()));
            streams.clientOut().write(wrapCompressedPacket(command, 0));
            streams.clientOut().flush();
            assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                    decodeCompressedFrame(readCompressedFrame(streams.backendIn()), false));
        });
    }

    @Test
    void leavesContinuationOfLargeCompressedQueryUnchangedThenRewritesNextQuery() throws Exception {
        byte[] largeQuery = new byte[0xFFFFFF];
        Arrays.fill(largeQuery, (byte) 'x');
        largeQuery[0] = 0x03;
        byte[] firstPacket = wrapPacket(0, largeQuery);
        String continuationSql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] continuation = new byte[continuationSql.length() + 1];
        continuation[0] = 0x03;
        System.arraycopy(continuationSql.getBytes(StandardCharsets.US_ASCII), 0,
                continuation, 1, continuationSql.length());
        byte[] firstFrame = wrapCompressedPacket(Arrays.copyOf(firstPacket, 0xFFFFFF), 0);
        ByteArrayOutputStream remainder = new ByteArrayOutputStream();
        remainder.write(firstPacket, 0xFFFFFF, firstPacket.length - 0xFFFFFF);
        remainder.writeBytes(wrapPacket(1, continuation));
        byte[] secondFrame = wrapCompressedPacket(remainder.toByteArray(), 1);
        byte[] nextQuery = wrapPacket(0, continuation);
        byte[] thirdFrame = wrapCompressedPacket(nextQuery, 0);

        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(firstFrame);
        input.writeBytes(secondFrame);
        input.writeBytes(thirdFrame);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        assertArrayEquals(firstFrame, readCompressedFrame(forwarded));
        assertArrayEquals(secondFrame, readCompressedFrame(forwarded));
        assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                decodeCompressedFrame(readCompressedFrame(forwarded), false));
        assertEquals(-1, forwarded.read());
    }

    @Test
    void relaysUnrewritableFullCompressedFrameAndRewritesNextQuery() throws Exception {
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] query = new byte[0xFFFFFF - 39];
        Arrays.fill(query, (byte) ' ');
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] fullFrame = wrapCompressedPacket(wrapPacket(0, query), 0);
        byte[] smallQuery = new byte[sql.length() + 1];
        smallQuery[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, smallQuery, 1, sql.length());
        byte[] nextFrame = wrapCompressedPacket(wrapPacket(0, smallQuery), 0);

        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(fullFrame);
        input.writeBytes(nextFrame);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        assertArrayEquals(fullFrame, readCompressedFrame(forwarded));
        assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                decodeCompressedFrame(readCompressedFrame(forwarded), false));
        assertEquals(-1, forwarded.read());
    }

    @Test
    void leavesIamRewriteAtExactInnerPacketLimitUnchanged() throws Exception {
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] baseQuery = new byte[sql.length() + 1];
        baseQuery[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, baseQuery, 1, sql.length());
        int growth = expectedRewrittenIamCommand("CREATE USER").length - wrapPacket(0, baseQuery).length;
        byte[] query = new byte[0xFFFFFF - growth];
        Arrays.fill(query, (byte) ' ');
        System.arraycopy(baseQuery, 0, query, 0, baseQuery.length);
        byte[] command = wrapPacket(0, query);
        int split = command.length / 2;
        byte[] firstFrame = wrapCompressedPacket(Arrays.copyOf(command, split), 0);
        byte[] secondFrame = wrapCompressedPacket(Arrays.copyOfRange(command, split, command.length), 1);
        byte[] nextFrame = wrapCompressedPacket(wrapPacket(0, baseQuery), 0);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(firstFrame);
        input.writeBytes(secondFrame);
        input.writeBytes(nextFrame);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        assertArrayEquals(firstFrame, readCompressedFrame(forwarded));
        assertArrayEquals(secondFrame, readCompressedFrame(forwarded));
        assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                decodeCompressedFrame(readCompressedFrame(forwarded), false));
        assertEquals(-1, forwarded.read());
    }

    @Test
    void relaysLargeCommandAfterPreviousFrameEndedWithItsHeader() throws Exception {
        byte[] smallQuery = wrapPacket(0, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1'});
        byte[] largePayload = new byte[0xFFFFFF - 2];
        Arrays.fill(largePayload, (byte) 'x');
        largePayload[0] = 0x03;
        byte[] largeQuery = wrapPacket(1, largePayload);
        ByteArrayOutputStream firstPayload = new ByteArrayOutputStream();
        firstPayload.writeBytes(smallQuery);
        firstPayload.write(largeQuery, 0, 4);
        byte[] firstFrame = wrapCompressedPacket(firstPayload.toByteArray(), 0);
        byte[] secondFrame = wrapCompressedPacket(Arrays.copyOfRange(largeQuery, 4, largeQuery.length), 1);
        String sql = "ALTER USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] nextQuery = new byte[sql.length() + 1];
        nextQuery[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, nextQuery, 1, sql.length());
        byte[] thirdFrame = wrapCompressedPacket(wrapPacket(0, nextQuery), 0);

        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(firstFrame);
        input.writeBytes(secondFrame);
        input.writeBytes(thirdFrame);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        assertArrayEquals(firstFrame, readCompressedFrame(forwarded));
        assertArrayEquals(secondFrame, readCompressedFrame(forwarded));
        assertArrayEquals(expectedRewrittenIamCommand("ALTER USER"),
                decodeCompressedFrame(readCompressedFrame(forwarded), false));
        assertEquals(-1, forwarded.read());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rewritesCompleteIamCommandAfterForwardedPartialPacket(boolean zlibPayload) throws Exception {
        byte[] firstQuery = wrapPacket(0, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1'});
        byte[] secondQuery = wrapPacket(1, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '2'});
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] iamPayload = new byte[sql.length() + 1];
        iamPayload[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, iamPayload, 1, sql.length());
        int split = 5;
        ByteArrayOutputStream firstPayload = new ByteArrayOutputStream();
        firstPayload.writeBytes(firstQuery);
        firstPayload.write(secondQuery, 0, split);
        ByteArrayOutputStream secondPayload = new ByteArrayOutputStream();
        secondPayload.write(secondQuery, split, secondQuery.length - split);
        secondPayload.writeBytes(wrapPacket(0, iamPayload));
        byte[] firstFrame = wrapCompressedFrame(firstPayload.toByteArray(), zlibPayload, 0);
        byte[] secondFrame = wrapCompressedFrame(secondPayload.toByteArray(), zlibPayload, 1);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(firstFrame);
        input.writeBytes(secondFrame);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        assertArrayEquals(firstFrame, readCompressedFrame(forwarded));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(secondQuery, split, secondQuery.length - split);
        expected.writeBytes(expectedRewrittenIamCommand("CREATE USER"));
        assertArrayEquals(expected.toByteArray(), decodeCompressedFrame(readCompressedFrame(forwarded), false));
        assertEquals(-1, forwarded.read());
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "zlib", "zstd"})
    void leavesPluginNameInCompressedUserPasswordAndCommentUnchanged(String compression) throws Exception {
        String sql = "CREATE USER 'app'@'%' IDENTIFIED BY 'IDENTIFIED WITH AWSAuthenticationPlugin'"
                + " /* IDENTIFIED WITH AWSAuthenticationPlugin */";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[sqlBytes.length + 1];
        payload[0] = 0x03;
        System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
        byte[] frame = wrapCompressedCommand(wrapPacket(0, payload), compression, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(frame), output,
                "zstd".equals(compression));
        assertArrayEquals(frame, output.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 'IDENTIFIED WITH AWSAuthenticationPlugin'",
            "SELECT 1 /* IDENTIFIED WITH AWSAuthenticationPlugin */"})
    void relaysPluginNameInCompressedQueryTextUnchanged(String sql) throws Exception {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.US_ASCII);
        byte[] query = new byte[sqlBytes.length + 1];
        query[0] = 0x03;
        System.arraycopy(sqlBytes, 0, query, 1, sqlBytes.length);
        byte[] frame = wrapCompressedPacket(wrapPacket(0, query));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(frame), output, false);
        assertArrayEquals(frame, output.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relaysEachCompressedCommandBeforeTheNextArrives(boolean zlibPayload) throws Exception {
        byte[] firstCommand = wrapPacket(0, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1'});
        byte[] secondCommand = wrapPacket(0, new byte[]{0x03, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '2'});
        byte[] firstFrame = wrapCompressedFrame(firstCommand, zlibPayload);
        byte[] secondFrame = wrapCompressedFrame(secondCommand, zlibPayload);
        byte[] responseFrame = wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 1);
        withLoggedInClient(CLIENT_COMPRESS, CLIENT_COMPRESS, false, streams -> {
            streams.clientOut().write(firstFrame);
            streams.clientOut().flush();
            assertArrayEquals(firstFrame, readCompressedFrame(streams.backendIn()));
            streams.backendOut().write(responseFrame);
            streams.backendOut().flush();
            assertArrayEquals(responseFrame, readCompressedFrame(streams.clientIn()));
            streams.clientOut().write(secondFrame);
            streams.clientOut().flush();
            assertArrayEquals(secondFrame, readCompressedFrame(streams.backendIn()));
        });
    }

    private void assertRelayedCommandFrames(byte[] expectedFrame, boolean compressed, boolean tls)
            throws Exception {
        assertRelayedCommandFrames(expectedFrame, compressed ? CLIENT_COMPRESS : 0, tls);
    }

    private void assertRelayedCommandFrames(byte[] expectedFrame, int compressionCapability, boolean tls)
            throws Exception {
        assertRelayedCommandFrames(expectedFrame, compressionCapability, tls, false);
    }

    private void assertRelayedCommandFrames(byte[] expectedFrame, int compressionCapability, boolean tls,
                                          boolean readConcurrently) throws Exception {
        withLoggedInClient(CLIENT_COMPRESS | CLIENT_ZSTD_COMPRESSION_ALGORITHM,
                compressionCapability, tls, streams -> {
                    FutureTask<byte[]> receivedFrame = null;
                    if (readConcurrently) {
                        receivedFrame = new FutureTask<>(
                                () -> streams.backendIn().readNBytes(expectedFrame.length));
                        Thread.ofVirtual().start(receivedFrame);
                        FutureTask<Void> sentFrame = new FutureTask<>(() -> {
                            streams.clientOut().write(expectedFrame);
                            streams.clientOut().flush();
                            return null;
                        });
                        Thread.ofVirtual().start(sentFrame);
                        sentFrame.get(3, TimeUnit.SECONDS);
                    } else {
                        streams.clientOut().write(expectedFrame);
                        streams.clientOut().flush();
                    }
                    assertEquals(compressionCapability, streams.backendClientCapabilities()
                            & (CLIENT_COMPRESS | CLIENT_ZSTD_COMPRESSION_ALGORITHM));
                    byte[] received = readConcurrently
                            ? receivedFrame.get(3, TimeUnit.SECONDS)
                            : streams.backendIn().readNBytes(expectedFrame.length);
                    assertArrayEquals(expectedFrame, received);
                });
    }

    private record ClientBackendStreams(InputStream clientIn, OutputStream clientOut,
                                        InputStream backendIn, OutputStream backendOut,
                                        int backendClientCapabilities, byte[] serverHandshake,
                                        byte[] backendResponse, Socket client, Socket proxyClient) {}

    @FunctionalInterface
    private interface LoggedInClientAssertion {
        void accept(ClientBackendStreams streams) throws Exception;
    }

    private void withLoggedInClient(int backendCompressionCapabilities, int clientCompressionCapability,
                                    boolean tls, LoggedInClientAssertion assertion) throws Exception {
        byte[] nonce = fixedNonce();
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0);
             Socket ourClient = new Socket("localhost", clientServer.getLocalPort());
             Socket proxyClient = clientServer.accept();
             Socket backend = new Socket("localhost", backendServer.getLocalPort());
             Socket backendPeer = backendServer.accept()) {
            ourClient.setSoTimeout(2_500);
            backendPeer.setSoTimeout(2_500);
            Thread authThread = startProxy(proxyClient, backend, NO_RECONNECT, false, testTlsCertificates());
            try {
                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();
                InputStream backendIn = backendPeer.getInputStream();
                OutputStream backendOut = backendPeer.getOutputStream();
                backendOut.write(buildHandshakeV10(nonce, null, backendCompressionCapabilities));
                backendOut.flush();
                byte[] handshake = readMysqlPacketRaw(clientIn);
                assertNotNull(handshake);
                if ((backendCompressionCapabilities & CLIENT_COMPRESS) != 0) {
                    assertTrue((capabilityFlagsLower(handshake) & CLIENT_COMPRESS) != 0);
                }
                int capabilities = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION
                        | clientCompressionCapability;
                int sequence = 1;
                if (tls) {
                    clientOut.write(buildSslRequest());
                    clientOut.flush();
                    SSLSocket sslClient = trustedClientSocket(ourClient);
                    sslClient.startHandshake();
                    clientIn = sslClient.getInputStream();
                    clientOut = sslClient.getOutputStream();
                    capabilities |= CLIENT_SSL;
                    sequence = 2;
                }
                clientOut.write(buildHandshakeResponse41("admin", scrambleNativePassword("secret", nonce),
                        (byte) sequence, capabilities));
                clientOut.flush();
                byte[] backendResponse = readMysqlPacketRaw(backendIn);
                assertNotNull(backendResponse);
                int backendClientCapabilities = (backendResponse[4] & 0xFF)
                        | ((backendResponse[5] & 0xFF) << 8)
                        | ((backendResponse[6] & 0xFF) << 16)
                        | ((backendResponse[7] & 0xFF) << 24);
                writeMysqlPacket(backendOut, 2, OK_PAYLOAD);
                backendOut.flush();
                assertArrayEquals(wrapPacket(sequence + 1, OK_PAYLOAD), readMysqlPacketRaw(clientIn));
                assertion.accept(new ClientBackendStreams(clientIn, clientOut, backendIn, backendOut,
                        backendClientCapabilities, handshake, backendResponse, ourClient, proxyClient));
            } finally {
                ourClient.close();
                proxyClient.close();
                backend.close();
                backendPeer.close();
                authThread.join(3_000);
                assertFalse(authThread.isAlive(), "proxy did not terminate");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"plain,CREATE USER", "zlib,CREATE USER", "zstd,CREATE USER",
            "plain,ALTER USER", "zlib,ALTER USER", "zstd,ALTER USER"})
    void rewritesCompressedIamCommand(String compression, String operation) throws Exception {
        ByteArrayOutputStream query = new ByteArrayOutputStream();
        query.write(0x03); // COM_QUERY
        query.writeBytes((operation + " 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'")
                .getBytes(StandardCharsets.UTF_8));
        byte[] command = wrapPacket(0, query.toByteArray());
        byte[] frame = wrapCompressedCommand(command, compression, 0);
        int capability = "zstd".equals(compression) ? CLIENT_ZSTD_COMPRESSION_ALGORITHM : CLIENT_COMPRESS;
        withLoggedInClient(CLIENT_COMPRESS | capability, capability, false, streams -> {
            streams.clientOut().write(frame);
            streams.clientOut().flush();
            byte[] forwarded = readCompressedFrame(streams.backendIn());
            byte[] payload = decodeCompressedFrame(forwarded, "zstd".equals(compression));
            assertArrayEquals(expectedRewrittenIamCommand(operation), payload);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/* client */ CREATE USER 'app'@'%'",
            "CREATE /* client */ USER 'app'@'%'"})
    void rewritesCompressedIamCommandWithSqlComments(String prefix) throws Exception {
        String sql = prefix + " IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.US_ASCII);
        byte[] query = new byte[sqlBytes.length + 1];
        query[0] = 0x03;
        System.arraycopy(sqlBytes, 0, query, 1, sqlBytes.length);
        byte[] frame = wrapCompressedPacket(wrapPacket(0, query));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(frame), output, false);
        byte[] expectedSql = (prefix + " IDENTIFIED WITH mysql_native_password AS "
                + "'*0000000000000000000000000000000000000000'").getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[expectedSql.length + 1];
        expected[0] = 0x03;
        System.arraycopy(expectedSql, 0, expected, 1, expectedSql.length);
        assertArrayEquals(wrapPacket(0, expected), decodeCompressedFrame(
                readCompressedFrame(new ByteArrayInputStream(output.toByteArray())), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "-", "--"})
    void rewritesCompressedIamCommandWithSplitLeadingComment(String splitPrefix) throws Exception {
        String comment = "/".equals(splitPrefix) ? "/* client */ " : "-- client\n";
        String sql = comment + "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] query = new byte[sql.length() + 1];
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] command = wrapPacket(0, query);
        int split = 5 + splitPrefix.length();
        byte[] firstFrame = wrapCompressedPacket(Arrays.copyOf(command, split), 0);
        byte[] secondFrame = wrapCompressedPacket(Arrays.copyOfRange(command, split, command.length), 1);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(firstFrame);
        input.writeBytes(secondFrame);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.writeBytes(decodeCompressedFrame(readCompressedFrame(forwarded), false));
        combined.writeBytes(decodeCompressedFrame(readCompressedFrame(forwarded), false));
        byte[] expectedSql = (comment + "CREATE USER 'app'@'%' IDENTIFIED WITH mysql_native_password AS "
                + "'*0000000000000000000000000000000000000000'").getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[expectedSql.length + 1];
        expected[0] = 0x03;
        System.arraycopy(expectedSql, 0, expected, 1, expectedSql.length);
        assertArrayEquals(wrapPacket(0, expected), combined.toByteArray());
        assertEquals(-1, forwarded.read());
    }

    @Test
    void rewritesCompressedIamCommandWithEmptyQueryAttributes() throws Exception {
        String sql = "ALTER USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.US_ASCII);
        byte[] query = new byte[sqlBytes.length + 3];
        query[0] = 0x03;
        query[1] = 0x00; // zero query attributes
        query[2] = 0x01; // one parameter set
        System.arraycopy(sqlBytes, 0, query, 3, sqlBytes.length);
        byte[] frame = wrapCompressedPacket(wrapPacket(0, query));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(frame), output, false, true);
        byte[] expectedSql = ("ALTER USER 'app'@'%' IDENTIFIED WITH mysql_native_password AS "
                + "'*0000000000000000000000000000000000000000'").getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[expectedSql.length + 3];
        expected[0] = 0x03;
        expected[2] = 0x01;
        System.arraycopy(expectedSql, 0, expected, 3, expectedSql.length);
        assertArrayEquals(wrapPacket(0, expected), decodeCompressedFrame(
                readCompressedFrame(new ByteArrayInputStream(output.toByteArray())), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "zlib", "zstd"})
    void rewritesIamCommandSplitAcrossCompressedFrames(String compression) throws Exception {
        String sql = "ALTER USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] query = new byte[sql.length() + 1];
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] command = wrapPacket(0, query);
        int split = command.length / 2;
        byte[] firstFrame = wrapCompressedCommand(Arrays.copyOf(command, split), compression, 0);
        byte[] secondFrame = wrapCompressedCommand(
                Arrays.copyOfRange(command, split, command.length), compression, 1);
        int capability = "zstd".equals(compression) ? CLIENT_ZSTD_COMPRESSION_ALGORITHM : CLIENT_COMPRESS;
        withLoggedInClient(CLIENT_COMPRESS | capability, capability, false, streams -> {
            streams.clientOut().write(firstFrame);
            streams.clientOut().write(secondFrame);
            streams.clientOut().flush();
            byte[] firstOutput = readCompressedFrame(streams.backendIn());
            byte[] secondOutput = readCompressedFrame(streams.backendIn());
            assertEquals(0, firstOutput[3]);
            assertEquals(1, secondOutput[3]);
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            combined.writeBytes(decodeCompressedFrame(firstOutput, "zstd".equals(compression)));
            combined.writeBytes(decodeCompressedFrame(secondOutput, "zstd".equals(compression)));
            assertArrayEquals(expectedRewrittenIamCommand("ALTER USER"), combined.toByteArray());
            byte[] response = wrapCompressedPacket(wrapPacket(1, OK_PAYLOAD), 2);
            streams.backendOut().write(response);
            streams.backendOut().flush();
            assertArrayEquals(response, readCompressedFrame(streams.clientIn()));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "zlib", "zstd"})
    void rewritesIamCommandSplitOneBytePerCompressedFrame(String compression) throws Exception {
        String sql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] query = new byte[sql.length() + 1];
        query[0] = 0x03;
        System.arraycopy(sql.getBytes(StandardCharsets.US_ASCII), 0, query, 1, sql.length());
        byte[] command = wrapPacket(0, query);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (int sequence = 0; sequence < command.length; sequence++) {
            input.writeBytes(wrapCompressedCommand(new byte[]{command[sequence]}, compression, sequence));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output,
                "zstd".equals(compression));
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int sequence = 0; sequence < command.length; sequence++) {
            byte[] frame = readCompressedFrame(forwarded);
            assertEquals(sequence, frame[3] & 0xFF);
            payload.writeBytes(decodeCompressedFrame(frame, "zstd".equals(compression)));
        }
        assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"), payload.toByteArray());
        assertEquals(-1, forwarded.read());
    }

    @Test
    void relaysEachFrameWhenIamRewriteWouldLeaveEmptyFrames() throws Exception {
        String sql = "ALTER USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS '"
                + "x".repeat(100) + "'";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.US_ASCII);
        byte[] query = new byte[sqlBytes.length + 1];
        query[0] = 0x03;
        System.arraycopy(sqlBytes, 0, query, 1, sqlBytes.length);
        byte[] command = wrapPacket(0, query);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (int sequence = 0; sequence < command.length; sequence++) {
            input.writeBytes(wrapCompressedPacket(new byte[]{command[sequence]}, sequence));
        }
        String nextSql = "CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'";
        byte[] nextQuery = new byte[nextSql.length() + 1];
        nextQuery[0] = 0x03;
        System.arraycopy(nextSql.getBytes(StandardCharsets.US_ASCII), 0, nextQuery, 1, nextSql.length());
        input.writeBytes(wrapCompressedPacket(wrapPacket(0, nextQuery), 0));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MySqlCompressedCommandRelay.relay(new ByteArrayInputStream(input.toByteArray()), output, false);
        ByteArrayInputStream forwarded = new ByteArrayInputStream(output.toByteArray());
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int sequence = 0; sequence < command.length; sequence++) {
            byte[] frame = readCompressedFrame(forwarded);
            assertEquals(sequence, frame[3] & 0xFF);
            payload.writeBytes(decodeCompressedFrame(frame, false));
        }
        assertArrayEquals(command, payload.toByteArray());
        assertArrayEquals(expectedRewrittenIamCommand("CREATE USER"),
                decodeCompressedFrame(readCompressedFrame(forwarded), false));
        assertEquals(-1, forwarded.read());
    }

    @Test
    void closesTheBackendWhenTheClientDropsWithoutQuitting() throws Exception {
        AtomicReference<Socket> proxyClient = new AtomicReference<>();
        withLoggedInClient(0, 0, false, streams -> {
            proxyClient.set(streams.proxyClient());
            // No COM_QUIT: the client just goes away while the backend stays idle.
            streams.client().close();
            assertEquals(-1, streams.backendIn().read(),
                    "the proxy must close the backend once the client drops");
        });
        assertTrue(proxyClient.get().isClosed(), "the proxy must close its side of the dropped client");
    }

    @Test
    void validatesCachingSha2MasterScrambleAndForwardsResponse() throws Exception {
        byte[] nonce = fixedNonce();
        AtomicReference<Byte> backendResponseSeq = new AtomicReference<>();
        AtomicReference<byte[]> backendResponsePayload = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockMySqlBackend(
                            backendServer, nonce, "caching_sha2_password",
                            backendResponseSeq, backendResponsePayload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());
                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000,
                                username -> true, testBinding());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();
                assertNotNull(readMysqlPacketRaw(clientIn));
                byte[] scramble = HexFormat.of().parseHex(
                        "746ebe205d56a0707acb3e796e834e0dd7b1d61743b26bd5202c7a623230c7c9");
                clientOut.write(buildHandshakeResponse41("admin", scramble, (byte) 1));
                clientOut.flush();
                assertNotNull(readMysqlPacketRaw(clientIn));

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }

        assertEquals((byte) 1, backendResponseSeq.get());
        assertEquals("admin", extractUsername(backendResponsePayload.get()));
    }

    @Test
    void upgradesToTlsOnSslRequestAndRenumbersSequenceBeforeForwardingToBackend() throws Exception {
        byte[] nonce = fixedNonce();
        AtomicReference<Byte> backendResponseSeq = new AtomicReference<>();
        AtomicReference<byte[]> backendResponsePayload = new AtomicReference<>();
        RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
        tlsCertificates.ensureHost("172.17.0.6");

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockMySqlBackend(backendServer, nonce, backendResponseSeq, backendResponsePayload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000,
                                username -> true, testBinding());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();

                byte[] serverHandshake = readMysqlPacketRaw(clientIn);
                assertNotNull(serverHandshake);
                assertTrue((capabilityFlagsLower(serverHandshake) & CLIENT_SSL) != 0);

                // SSLRequest: 32-byte payload, CLIENT_SSL set, sequence 1 (right after the server's
                // handshake at sequence 0).
                clientOut.write(buildSslRequest());
                clientOut.flush();

                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                InputStream tlsIn = sslClient.getInputStream();
                OutputStream tlsOut = sslClient.getOutputStream();

                byte[] scramble = scrambleNativePassword("secret", nonce);
                // Real clients send this at sequence 2 after the TLS upgrade — the proxy must
                // rewrite it to 1 before forwarding to the (plaintext-only) backend. They also
                // keep CLIENT_SSL set in it; a real backend would treat a first packet carrying
                // that bit as an SSLRequest and hang waiting for a TLS handshake.
                byte[] response = buildHandshakeResponse41("admin", scramble, (byte) 2,
                        CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_SSL);
                tlsOut.write(response);
                tlsOut.flush();

                // The mock backend replies with sequence 2 (its own view of the shared counter);
                // the client, having sent its SSLRequest at sequence 1, expects that reply at 3.
                byte[] okPacket = readMysqlPacketRaw(tlsIn);
                assertNotNull(okPacket);
                assertEquals((byte) 3, okPacket[3],
                        "client sent SSLRequest(1) then response(2), so it expects the backend's "
                                + "reply at sequence 3, not the backend's own sequence 2");

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }

        assertEquals((byte) 1, backendResponseSeq.get(),
                "backend never saw the SSLRequest, so it must receive the real response at sequence 1");
        assertEquals("admin", extractUsername(backendResponsePayload.get()));
        int backendCaps = (backendResponsePayload.get()[0] & 0xFF)
                | ((backendResponsePayload.get()[1] & 0xFF) << 8);
        assertEquals(0, backendCaps & CLIENT_SSL,
                "the plaintext backend must not see CLIENT_SSL, or it treats the response as an "
                        + "SSLRequest and waits forever for a TLS handshake");
    }

    @Test
    void fastAuthSuccessDoesNotBlockWaitingForAClientReply() throws Exception {
        byte[] nonce = fixedNonce();
        RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
        tlsCertificates.ensureHost("172.17.0.7");

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = backendServer.accept()) {
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    out.write(buildHandshakeV10(nonce));
                    out.flush();

                    readMysqlPacketRaw(in); // the real HandshakeResponse41, forwarded at sequence 1

                    // caching_sha2_password fast-auth success: AuthMoreData(0x03) immediately
                    // followed by the terminal OK — the client sends nothing back in between.
                    writeMysqlPacket(out, 2, new byte[]{0x01, 0x03});
                    writeMysqlPacket(out, 3, new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00});
                    out.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000,
                                username -> true, testBinding());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();

                assertNotNull(readMysqlPacketRaw(clientIn));

                clientOut.write(buildSslRequest());
                clientOut.flush();

                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                InputStream tlsIn = sslClient.getInputStream();
                OutputStream tlsOut = sslClient.getOutputStream();

                byte[] scramble = scrambleNativePassword("secret", nonce);
                byte[] response = buildHandshakeResponse41("admin", scramble, (byte) 2,
                        CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_SSL);
                tlsOut.write(response);
                tlsOut.flush();

                // Neither packet should require anything further from the client — if the proxy
                // wrongly waits for a client reply after AuthMoreData(0x03), this blocks until the
                // join timeout below fails the test.
                byte[] authMoreData = readMysqlPacketRaw(tlsIn);
                assertNotNull(authMoreData);
                assertEquals((byte) 0x01, authMoreData[4], "expected AuthMoreData");

                byte[] okPacket = readMysqlPacketRaw(tlsIn);
                assertNotNull(okPacket);
                assertEquals((byte) 0x00, okPacket[4], "expected the terminal OK packet");

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }
    }

    @Test
    void silentBackendHandshakeFailsWithinTheConfiguredTimeoutInsteadOfHanging() throws Exception {
        try (ServerSocket silentBackend = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", silentBackend.getLocalPort());

                AtomicReference<IOException> authFailure = new AtomicReference<>();
                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 200,
                                username -> true, testBinding());
                    } catch (IOException e) {
                        authFailure.set(e);
                    }
                });

                // The silent backend accepts the TCP connection but never sends its Handshake V10;
                // the backend-side handshake read deadline must fire well within the 5s join instead
                // of hanging forever.
                authThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertNotNull(authFailure.get(),
                        "expected handleAuth to fail once the backend stayed silent");

                ourClient.close();
                proxyClient.close();
            }
        }
    }

    // ── RDS IAM authentication ───────────────────────────────────────────────

    @Test
    void switchesIamLoginToClearPasswordAndAuthenticatesBackendAsMaster() throws Exception {
        AuthSwitchLogin login = authSwitchLogin("app", rdsToken("app", Instant.now()), true, true);

        assertNotNull(login.authSwitch(), "an access-denied backend verdict must become an auth switch");
        assertEquals(3, login.authSwitch()[3] & 0xFF);
        assertEquals(MYSQL_CLEAR_PASSWORD, authSwitchPluginName(login.authSwitch()));
        assertOk(login.clientVerdict(), 5);
        assertEquals(1, login.reconnects());
        // The master leg keeps the client's database, attributes and zstd level, in wire order.
        assertArrayEquals(
                fullHandshakeResponse41("admin", scrambleNativePassword("secret", MASTER_BACKEND_NONCE),
                        MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1),
                login.masterResponse());
    }

    @Test
    void iamAuthSwitchKeepsSequenceNumbersInStepAfterTlsUpgrade() throws Exception {
        AuthSwitchLogin login = authSwitchLogin("app", rdsToken("app", Instant.now()), true, true);

        // SSLRequest(1) and HandshakeResponse(2) leave the client one packet ahead of the backend.
        assertNotNull(login.authSwitch());
        assertEquals(3, login.authSwitch()[3] & 0xFF);
        assertOk(login.clientVerdict(), 5);
        assertArrayEquals(
                fullHandshakeResponse41("admin", scrambleNativePassword("secret", MASTER_BACKEND_NONCE),
                        MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1),
                login.masterResponse(),
                "the fresh backend connection expects the response at sequence 1 without CLIENT_SSL");
    }

    @Test
    void rejectsIamTokenWithTamperedSignature() throws Exception {
        String token = rdsToken("app", Instant.now());
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0");

        assertIamLoginRejected(authSwitchLogin("app", tampered, true, false));
    }

    @Test
    void rejectsExpiredIamToken() throws Exception {
        String expired = rdsToken("app", Instant.now().minusSeconds(3600));

        assertIamLoginRejected(authSwitchLogin("app", expired, true, false));
    }

    @Test
    void rejectsIamTokenIssuedForAnotherDbUser() throws Exception {
        String otherUser = rdsToken("reporting", Instant.now());

        assertIamLoginRejected(authSwitchLogin("app", otherUser, true, false));
    }

    @Test
    void iamDisabledRelaysBackendRejectionWithoutAuthSwitch() throws Exception {
        AuthSwitchLogin login = authSwitchLogin("app", rdsToken("app", Instant.now()), false, false);

        assertNull(login.authSwitch(), "the proxy must not ask for an IAM token when IAM auth is disabled");
        assertAccessDenied(login.clientVerdict(), 2);
        assertEquals(0, login.reconnects());
    }

    @Test
    void validatesIamTokenSentUpFrontAfterTheDatabaseAndAuthenticatesBackendAsMaster() throws Exception {
        byte[] response = fullHandshakeResponse41("app", nulTerminated(rdsToken("app", Instant.now())),
                MYSQL_CLEAR_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, true);

        assertAccessDenied(login.clientVerdict(), 2);
        assertNull(login.backendResponse(), "clear-password IAM must require TLS");
    }

    @Test
    void iamDisabledForwardsClearPasswordLoginToBackendUnmodified() throws Exception {
        byte[] response = fullHandshakeResponse41("app", nulTerminated(rdsToken("app", Instant.now())),
                MYSQL_CLEAR_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, false);

        assertOk(login.clientVerdict(), 2);
        assertArrayEquals(response, login.backendResponse(),
                "without IAM auth the backend, not the proxy, decides on a non-master login");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forwardsNonMasterNativePasswordLoginUnmodified(boolean iamEnabled) throws Exception {
        byte[] response = fullHandshakeResponse41("app", scrambleNativePassword("app-password", fixedNonce()),
                MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, iamEnabled);

        assertOk(login.clientVerdict(), 2);
        assertArrayEquals(response, login.backendResponse());
    }

    @Test
    void forwardsMasterPasswordLoginUnmodifiedWhenIamEnabled() throws Exception {
        byte[] response = fullHandshakeResponse41("admin", scrambleNativePassword("secret", fixedNonce()),
                MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, true);

        assertOk(login.clientVerdict(), 2);
        assertArrayEquals(response, login.backendResponse());
    }

    @Test
    void rejectsWrongMasterPasswordWithoutAuthSwitchWhenIamEnabled() throws Exception {
        byte[] response = fullHandshakeResponse41("admin", scrambleNativePassword("wrong", fixedNonce()),
                MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, true);

        assertAccessDenied(login.clientVerdict(), 2);
        assertNull(login.backendResponse(), "a rejected master login must never reach the backend");
    }

    private static String rdsToken(String dbUser, Instant issuedAt) throws Exception {
        return SigV4TokenTestHelper.createRdsToken("mydb.abc123.us-east-1.rds.amazonaws.com", 3306,
                dbUser, "AKIATEST", "secret", issuedAt, 900);
    }

    private static void assertIamLoginRejected(AuthSwitchLogin login) {
        assertNull(login.authSwitch(), "invalid or non-TLS IAM must not receive an auth switch");
        assertAccessDenied(login.clientVerdict(), 2);
        assertEquals(0, login.reconnects(), "an invalid token must never reach the backend as master");
        assertNull(login.masterResponse());
    }

    private static void assertOk(byte[] packet, int sequence) {
        assertNotNull(packet);
        assertEquals(0x00, packet[4] & 0xFF, "expected an OK packet");
        assertEquals(sequence, packet[3] & 0xFF);
    }

    private static void assertAccessDenied(byte[] packet, int sequence) {
        assertNotNull(packet);
        assertEquals(0xFF, packet[4] & 0xFF, "expected an ERR packet");
        assertEquals(ER_ACCESS_DENIED, (packet[5] & 0xFF) | ((packet[6] & 0xFF) << 8));
        assertEquals(sequence, packet[3] & 0xFF);
    }

    private record DirectLogin(byte[] clientVerdict, byte[] backendResponse) {}

    /** One client HandshakeResponse against a single backend connection that accepts it. */
    private DirectLogin directLogin(byte[] clientResponse, boolean iamEnabled) throws Exception {
        AtomicReference<byte[]> backendResponse = new AtomicReference<>();
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0);
             Socket ourClient = new Socket("localhost", clientServer.getLocalPort());
             Socket proxyClient = clientServer.accept();
             Socket backend = new Socket("localhost", backendServer.getLocalPort())) {
            ourClient.setSoTimeout(5_000);
            Thread backendThread = startBackend(backendServer, fixedNonce(), OK_PAYLOAD, backendResponse);
            Thread authThread = startProxy(proxyClient, backend, NO_RECONNECT, iamEnabled,
                    testTlsCertificates());

            InputStream clientIn = ourClient.getInputStream();
            OutputStream clientOut = ourClient.getOutputStream();
            assertNotNull(readMysqlPacketRaw(clientIn));
            clientOut.write(clientResponse);
            clientOut.flush();
            byte[] verdict = readMysqlPacketRaw(clientIn);

            ourClient.close();
            proxyClient.close();
            joinAll(authThread, backendThread);
            return new DirectLogin(verdict, backendResponse.get());
        }
    }

    private record AuthSwitchLogin(byte[] authSwitch, byte[] clientVerdict, byte[] masterResponse,
                                   int reconnects) {}

    /**
     * Drives a cleartext-enabled client the way libmysqlclient, PyMySQL, mysql2 and Connector/J log in
     * with an IAM token: the HandshakeResponse answers the advertised mysql_native_password with a
     * scramble of the token, and the token itself is only sent if the server switches to
     * mysql_clear_password. The first backend connection rejects that scramble — as the real backend
     * does for an IAM user, whose password hash never matches — and a second one, if the proxy opens
     * it, accepts whatever it receives.
     */
    private AuthSwitchLogin authSwitchLogin(String username, String token, boolean iamEnabled, boolean tls)
            throws Exception {
        AtomicReference<byte[]> rejectedResponse = new AtomicReference<>();
        AtomicReference<byte[]> masterResponse = new AtomicReference<>();
        AtomicReference<Thread> masterBackendThread = new AtomicReference<>();
        AtomicInteger reconnects = new AtomicInteger();
        RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
        if (tls) {
            tlsCertificates.ensureHost("172.17.0.8");
        }

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket masterBackendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0);
             Socket ourClient = new Socket("localhost", clientServer.getLocalPort());
             Socket proxyClient = clientServer.accept();
             Socket backend = new Socket("localhost", backendServer.getLocalPort())) {
            ourClient.setSoTimeout(5_000);
            PostgresProtocolHandler.BackendConnector connector = () -> {
                reconnects.incrementAndGet();
                masterBackendThread.set(startBackend(
                        masterBackendServer, MASTER_BACKEND_NONCE, OK_PAYLOAD, masterResponse));
                return new Socket("localhost", masterBackendServer.getLocalPort());
            };
            Thread backendThread = startBackend(
                    backendServer, fixedNonce(), accessDeniedPayload(), rejectedResponse);
            Thread authThread = startProxy(proxyClient, backend, connector, iamEnabled, tlsCertificates);

            InputStream clientIn = ourClient.getInputStream();
            OutputStream clientOut = ourClient.getOutputStream();
            assertNotNull(readMysqlPacketRaw(clientIn));
            int capabilities = FULL_RESPONSE_CAPABILITIES;
            int sequence = 1;
            if (tls) {
                clientOut.write(buildSslRequest());
                clientOut.flush();
                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                clientIn = sslClient.getInputStream();
                clientOut = sslClient.getOutputStream();
                capabilities |= CLIENT_SSL;
                sequence = 2;
            }
            clientOut.write(fullHandshakeResponse41(username, scrambleNativePassword(token, fixedNonce()),
                    MYSQL_NATIVE_PASSWORD, capabilities, sequence));
            clientOut.flush();

            byte[] reply = readMysqlPacketRaw(clientIn);
            byte[] authSwitch = null;
            if (reply != null && (reply[4] & 0xFF) == 0xFE) {
                authSwitch = reply;
                clientOut.write(wrapPacket((reply[3] & 0xFF) + 1, nulTerminated(token)));
                clientOut.flush();
                reply = readMysqlPacketRaw(clientIn);
            }

            ourClient.close();
            proxyClient.close();
            joinAll(authThread, backendThread);
            if (masterBackendThread.get() != null) {
                joinAll(masterBackendThread.get());
            }
            return new AuthSwitchLogin(authSwitch, reply, masterResponse.get(), reconnects.get());
        }
    }

    /**
     * Backend that sends its handshake, records the HandshakeResponse it receives (null if the proxy
     * never forwards one), answers it with {@code verdict} at sequence 2, then closes.
     */
    private static Thread startBackend(ServerSocket server, byte[] nonce, byte[] verdict,
                                       AtomicReference<byte[]> response) {
        return Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                OutputStream out = socket.getOutputStream();
                out.write(buildHandshakeV10(nonce, MYSQL_NATIVE_PASSWORD));
                out.flush();
                byte[] raw = readMysqlPacketRaw(socket.getInputStream());
                response.set(raw);
                if (raw != null) {
                    writeMysqlPacket(out, 2, verdict);
                    out.flush();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static Thread startProxy(Socket proxyClient, Socket backend,
                                     PostgresProtocolHandler.BackendConnector connector,
                                     boolean iamEnabled, RdsProxyTlsCertificates tlsCertificates) {
        return Thread.ofVirtual().start(() -> {
            try {
                MySqlProtocolHandler.handleAuth(
                        proxyClient, backend, connector, "admin", "secret",
                        iamEnabled, testSigV4Validator(), tlsCertificates,
                        (user, pass) -> PasswordValidator.AuthResult.PASSTHROUGH, 5000,
                        username -> true, testBinding());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void joinAll(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(5_000);
            assertFalse(thread.isAlive(), "thread did not terminate");
        }
    }

    private RdsProxyTlsCertificates testTlsCertificates() {
        return new RdsProxyTlsCertificates(FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls")),
                new CertificateGenerator());
    }

    private static RdsSigV4Validator testSigV4Validator() {
        return new RdsSigV4Validator(IamServiceTestHelper.iamServiceWithAccessKey("AKIATEST", "secret"));
    }

    /** What the proxy publishes for the endpoint {@link #rdsToken} generates tokens for. */
    private static RdsProxyBinding testBinding() {
        return new RdsProxyBinding("mydb.abc123.us-east-1.rds.amazonaws.com", 3306, "us-east-1",
                "123456789012", "db-ABCDEFGHIJKL01234", true);
    }

    private static SSLSocket trustedClientSocket(Socket socket) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        SSLSocket sslSocket = (SSLSocket) context.getSocketFactory()
                .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        return sslSocket;
    }

    private static void mockMySqlBackend(ServerSocket server, byte[] nonce,
                                         AtomicReference<Byte> responseSeq,
                                         AtomicReference<byte[]> responsePayload) throws IOException {
        mockMySqlBackend(server, nonce, null, responseSeq, responsePayload);
    }

    private static void mockMySqlBackend(ServerSocket server, byte[] nonce, String authPlugin,
                                         AtomicReference<Byte> responseSeq,
                                         AtomicReference<byte[]> responsePayload) throws IOException {
        try (Socket socket = server.accept()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(buildHandshakeV10(nonce, authPlugin));
            out.flush();

            byte[] responseRaw = readMysqlPacketRaw(in);
            responseSeq.set(responseRaw[3]);
            byte[] payload = new byte[responseRaw.length - 4];
            System.arraycopy(responseRaw, 4, payload, 0, payload.length);
            responsePayload.set(payload);

            writeMysqlPacket(out, 2, new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00}); // OK packet
            out.flush();
        }
    }

    // ── MySQL Handshake V10 (server -> client) ──────────────────────────────

    private static byte[] buildHandshakeV10(byte[] nonce) throws IOException {
        return buildHandshakeV10(nonce, null);
    }

    private static byte[] buildHandshakeV10(byte[] nonce, String authPlugin) throws IOException {
        return buildHandshakeV10(nonce, authPlugin, 0);
    }

    private static byte[] buildHandshakeV10(byte[] nonce, String authPlugin, int extraCapabilities)
            throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(10); // protocol version
        payload.write("8.0.34".getBytes(StandardCharsets.UTF_8));
        payload.write(0);
        writeInt32LE(payload, 1); // connection id
        payload.write(nonce, 0, 8); // auth-plugin-data part 1
        payload.write(0); // filler
        int capabilities = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | extraCapabilities;
        if (authPlugin != null) {
            capabilities |= CLIENT_PLUGIN_AUTH;
        }
        int capsLower = capabilities & 0xFFFF; // no CLIENT_SSL
        writeInt16LE(payload, capsLower);
        payload.write(0x21); // charset
        writeInt16LE(payload, 0x0002); // status flags
        writeInt16LE(payload, (capabilities >>> 16) & 0xFFFF);
        payload.write(authPlugin == null ? 0 : 21);
        payload.write(new byte[10]); // reserved
        payload.write(nonce, 8, 12); // auth-plugin-data part 2 (12 bytes)
        payload.write(0); // null terminator
        if (authPlugin != null) {
            payload.write(authPlugin.getBytes(StandardCharsets.UTF_8));
            payload.write(0);
        }

        return wrapPacket(0, payload.toByteArray());
    }

    // ── MySQL SSLRequest / HandshakeResponse41 (client -> server) ───────────

    private static byte[] buildSslRequest() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_SSL);
        writeInt32LE(payload, 0); // max packet size
        payload.write(0x21); // charset
        payload.write(new byte[23]); // reserved
        return wrapPacket(1, payload.toByteArray());
    }

    private static byte[] buildHandshakeResponse41(String username, byte[] scramble, byte sequence)
            throws IOException {
        return buildHandshakeResponse41(username, scramble, sequence,
                CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION);
    }

    private static byte[] buildHandshakeResponse41(String username, byte[] scramble, byte sequence,
                                                   int capabilities) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, capabilities);
        writeInt32LE(payload, 0); // max packet size
        payload.write(0x21); // charset
        payload.write(new byte[23]); // reserved
        payload.write(username.getBytes(StandardCharsets.UTF_8));
        payload.write(0);
        payload.write(scramble.length); // CLIENT_SECURE_CONNECTION: 1-byte length prefix
        payload.write(scramble);
        if ((capabilities & CLIENT_ZSTD_COMPRESSION_ALGORITHM) != 0) {
            payload.write(3); // zstd compression level
        }

        return wrapPacket(sequence, payload.toByteArray());
    }

    /**
     * A HandshakeResponse41 shaped like libmysqlclient 8's: a length-encoded auth-response followed by
     * the database, client plugin name, connection attributes and zstd compression level, in the
     * order the protocol defines them.
     */
    private static byte[] fullHandshakeResponse41(String username, byte[] authData, String plugin,
                                                  int capabilities, int sequence) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, capabilities);
        writeInt32LE(payload, 0x0100_0000); // max packet size
        payload.write(0xFF); // charset utf8mb4_0900_ai_ci
        payload.writeBytes(new byte[23]); // reserved
        payload.writeBytes(nulTerminated(username));
        writeLenenc(payload, authData.length);
        payload.writeBytes(authData);
        payload.writeBytes(nulTerminated("appdb"));
        payload.writeBytes(nulTerminated(plugin));
        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        for (String entry : new String[]{"_client_name", "libmysql", "program_name", "mysql"}) {
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            writeLenenc(attributes, bytes.length);
            attributes.writeBytes(bytes);
        }
        writeLenenc(payload, attributes.size());
        payload.writeBytes(attributes.toByteArray());
        payload.write(3); // zstd compression level
        return wrapPacket(sequence, payload.toByteArray());
    }

    private static void writeLenenc(ByteArrayOutputStream out, int value) {
        if (value < 0xFB) {
            out.write(value);
        } else {
            out.write(0xFC);
            writeInt16LE(out, value);
        }
    }

    private static byte[] nulTerminated(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    private static byte[] accessDeniedPayload() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0xFF);
        writeInt16LE(payload, ER_ACCESS_DENIED);
        payload.writeBytes("#28000Access denied for user 'app'@'172.17.0.1' (using password: YES)"
                .getBytes(StandardCharsets.UTF_8));
        return payload.toByteArray();
    }

    private static String authSwitchPluginName(byte[] raw) {
        int end = 5;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 5, end - 5, StandardCharsets.UTF_8);
    }

    private static String extractUsername(byte[] payload) {
        int i = 4 + 4 + 1 + 23; // caps + max-packet-size + charset + reserved
        int start = i;
        while (i < payload.length && payload[i] != 0) {
            i++;
        }
        return new String(payload, start, i - start, StandardCharsets.UTF_8);
    }

    private static int capabilityFlagsLower(byte[] handshakeRaw) {
        int i = 4 + 1; // header + protocol version
        while (i < handshakeRaw.length && handshakeRaw[i] != 0) {
            i++;
        }
        i++; // null terminator
        i += 4; // connection id
        i += 8; // auth-plugin-data part 1
        i++; // filler
        return (handshakeRaw[i] & 0xFF) | ((handshakeRaw[i + 1] & 0xFF) << 8);
    }

    private static byte[] fixedNonce() {
        byte[] nonce = new byte[20];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (i + 1);
        }
        return nonce;
    }

    private static byte[] scrambleNativePassword(String password, byte[] nonce) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash1 = sha1.digest(password.getBytes(StandardCharsets.UTF_8));
        sha1.reset();
        byte[] hash2 = sha1.digest(hash1);
        sha1.reset();
        sha1.update(nonce);
        sha1.update(hash2);
        byte[] hash3 = sha1.digest();

        byte[] result = new byte[20];
        for (int i = 0; i < 20; i++) {
            result[i] = (byte) (hash1[i] ^ hash3[i]);
        }
        return result;
    }

    // ── Raw packet helpers ───────────────────────────────────────────────────

    private static byte[] wrapPacket(int sequence, byte[] payload) {
        byte[] raw = new byte[4 + payload.length];
        int len = payload.length;
        raw[0] = (byte) (len & 0xFF);
        raw[1] = (byte) ((len >> 8) & 0xFF);
        raw[2] = (byte) ((len >> 16) & 0xFF);
        raw[3] = (byte) sequence;
        System.arraycopy(payload, 0, raw, 4, payload.length);
        return raw;
    }

    private static byte[] expectedRewrittenIamCommand(String operation) {
        String sql = operation + " 'app'@'%' IDENTIFIED WITH mysql_native_password AS "
                + "'*0000000000000000000000000000000000000000'";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[sqlBytes.length + 1];
        payload[0] = 0x03;
        System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
        return wrapPacket(0, payload);
    }

    private static byte[] wrapCompressedFrame(byte[] payload, boolean zlibPayload) throws IOException {
        return wrapCompressedFrame(payload, zlibPayload, 0);
    }

    private static byte[] wrapCompressedFrame(byte[] payload, boolean zlibPayload, int sequence) throws IOException {
        return zlibPayload ? wrapZlibCompressedPacket(payload, sequence) : wrapCompressedPacket(payload, sequence);
    }

    private static byte[] wrapCompressedCommand(byte[] command, String compression, int sequence) throws IOException {
        return switch (compression) {
            case "plain" -> wrapCompressedPacket(command, sequence);
            case "zlib" -> wrapZlibCompressedPacket(command, sequence);
            case "zstd" -> wrapZstdCompressedPacket(command, sequence);
            default -> throw new IllegalArgumentException(compression);
        };
    }

    private static byte[] wrapCompressedPacket(byte[] mysqlPacket) {
        return wrapCompressedPacket(mysqlPacket, 0);
    }

    private static byte[] wrapCompressedPacket(byte[] mysqlPacket, int sequence) {
        byte[] raw = new byte[7 + mysqlPacket.length];
        raw[0] = (byte) (mysqlPacket.length & 0xFF);
        raw[1] = (byte) ((mysqlPacket.length >> 8) & 0xFF);
        raw[2] = (byte) ((mysqlPacket.length >> 16) & 0xFF);
        raw[3] = (byte) sequence;
        // Uncompressed length 0: short commands may use an uncompressed payload.
        System.arraycopy(mysqlPacket, 0, raw, 7, mysqlPacket.length);
        return raw;
    }

    private static byte[] wrapZlibCompressedPacket(byte[] mysqlPacket, int sequence) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(mysqlPacket);
        }
        byte[] payload = compressed.toByteArray();
        byte[] raw = new byte[7 + payload.length];
        raw[0] = (byte) (payload.length & 0xFF);
        raw[1] = (byte) ((payload.length >> 8) & 0xFF);
        raw[2] = (byte) ((payload.length >> 16) & 0xFF);
        raw[3] = (byte) sequence;
        raw[4] = (byte) (mysqlPacket.length & 0xFF);
        raw[5] = (byte) ((mysqlPacket.length >> 8) & 0xFF);
        raw[6] = (byte) ((mysqlPacket.length >> 16) & 0xFF);
        System.arraycopy(payload, 0, raw, 7, payload.length);
        return raw;
    }

    private static byte[] wrapZlibFrameWithEmptyBlocks(byte[] payload, int sequence, int emptyBlocks) {
        byte[] compressed = new byte[2 + emptyBlocks * 5 + 5 + payload.length + 4];
        compressed[0] = 0x78;
        compressed[1] = 0x01;
        int offset = 2;
        for (int i = 0; i < emptyBlocks; i++) {
            compressed[offset++] = 0;
            compressed[offset++] = 0;
            compressed[offset++] = 0;
            compressed[offset++] = (byte) 0xFF;
            compressed[offset++] = (byte) 0xFF;
        }
        compressed[offset++] = 1;
        compressed[offset++] = (byte) payload.length;
        compressed[offset++] = (byte) (payload.length >> 8);
        compressed[offset++] = (byte) ~payload.length;
        compressed[offset++] = (byte) ~(payload.length >> 8);
        System.arraycopy(payload, 0, compressed, offset, payload.length);
        offset += payload.length;
        Adler32 adler = new Adler32();
        adler.update(payload);
        long checksum = adler.getValue();
        for (int shift = 24; shift >= 0; shift -= 8) {
            compressed[offset++] = (byte) (checksum >> shift);
        }
        byte[] frame = new byte[7 + compressed.length];
        frame[0] = (byte) compressed.length;
        frame[1] = (byte) (compressed.length >> 8);
        frame[2] = (byte) (compressed.length >> 16);
        frame[3] = (byte) sequence;
        frame[4] = (byte) payload.length;
        frame[5] = (byte) (payload.length >> 8);
        frame[6] = (byte) (payload.length >> 16);
        System.arraycopy(compressed, 0, frame, 7, compressed.length);
        return frame;
    }

    private static byte[] wrapZstdCompressedPacket(byte[] mysqlPacket, int sequence) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream encoder = new ZstdOutputStream(compressed)) {
            encoder.write(mysqlPacket);
        }
        byte[] payload = compressed.toByteArray();
        byte[] raw = new byte[7 + payload.length];
        raw[0] = (byte) (payload.length & 0xFF);
        raw[1] = (byte) ((payload.length >> 8) & 0xFF);
        raw[2] = (byte) ((payload.length >> 16) & 0xFF);
        raw[3] = (byte) sequence;
        raw[4] = (byte) (mysqlPacket.length & 0xFF);
        raw[5] = (byte) ((mysqlPacket.length >> 8) & 0xFF);
        raw[6] = (byte) ((mysqlPacket.length >> 16) & 0xFF);
        System.arraycopy(payload, 0, raw, 7, payload.length);
        return raw;
    }

    private static byte[] decodeCompressedFrame(byte[] frame, boolean zstd) throws IOException {
        int uncompressedLength = (frame[4] & 0xFF) | ((frame[5] & 0xFF) << 8)
                | ((frame[6] & 0xFF) << 16);
        byte[] payload = Arrays.copyOfRange(frame, 7, frame.length);
        if (uncompressedLength == 0) {
            return payload;
        }
        try (InputStream decoder = zstd
                ? new ZstdInputStream(new ByteArrayInputStream(payload))
                : new InflaterInputStream(new ByteArrayInputStream(payload))) {
            byte[] decoded = decoder.readAllBytes();
            assertEquals(uncompressedLength, decoded.length);
            return decoded;
        }
    }

    private static byte[] readCompressedFrame(InputStream in) throws IOException {
        byte[] header = in.readNBytes(7);
        if (header.length != 7) {
            throw new EOFException("Incomplete compressed MySQL header");
        }
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Incomplete compressed MySQL payload");
        }
        byte[] raw = Arrays.copyOf(header, 7 + length);
        System.arraycopy(payload, 0, raw, 7, length);
        return raw;
    }

    private static void writeMysqlPacket(OutputStream out, int sequence, byte[] payload) throws IOException {
        out.write(wrapPacket(sequence, payload));
    }

    private static byte[] readMysqlPacketRaw(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int seq = in.read();
        if ((b0 | b1 | b2 | seq) < 0) {
            return null;
        }
        int length = b0 | (b1 << 8) | (b2 << 16);
        byte[] raw = new byte[4 + length];
        raw[0] = (byte) b0;
        raw[1] = (byte) b1;
        raw[2] = (byte) b2;
        raw[3] = (byte) seq;
        int offset = 4;
        while (offset < raw.length) {
            int n = in.read(raw, offset, raw.length - offset);
            if (n < 0) {
                throw new EOFException("Connection closed while reading MySQL packet");
            }
            offset += n;
        }
        return raw;
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
