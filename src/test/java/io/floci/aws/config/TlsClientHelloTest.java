package io.floci.aws.config;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsClientHelloTest {

    @Test
    void readsTheServerNameOfARealClientHello() throws Exception {
        Buffer hello = clientHello("App-LB-123.elb.localhost.floci.io");
        assertFalse(TlsClientHello.needsMore(hello));
        assertEquals("app-lb-123.elb.localhost.floci.io", TlsClientHello.serverName(hello));
    }

    @Test
    void absentServerNameIsNull() throws Exception {
        Buffer hello = clientHello(null);
        assertFalse(TlsClientHello.needsMore(hello));
        assertNull(TlsClientHello.serverName(hello));
    }

    @Test
    void aPartialRecordNeedsMoreAndHasNoNameYet() throws Exception {
        Buffer hello = clientHello("api.example.test");
        Buffer head = hello.getBuffer(0, 3);
        Buffer partial = hello.getBuffer(0, hello.length() - 1);
        assertTrue(TlsClientHello.needsMore(head));
        assertTrue(TlsClientHello.needsMore(partial));
        assertNull(TlsClientHello.serverName(partial));
    }

    @Test
    void nonHandshakeOrOversizedRecordsNeverWait() {
        assertFalse(TlsClientHello.needsMore(Buffer.buffer("GET / HTTP/1.1\r\n")));
        assertFalse(TlsClientHello.needsMore(Buffer.buffer(new byte[]{0x16, 3, 1, (byte) 0xff, (byte) 0xff})));
    }

    @Test
    void malformedHellosAreNull() throws Exception {
        assertNull(TlsClientHello.serverName(Buffer.buffer()));
        assertNull(TlsClientHello.serverName(Buffer.buffer("GET / HTTP/1.1\r\n")));
        Buffer notAClientHello = clientHello("api.example.test");
        notAClientHello.setByte(5, (byte) 2);
        assertNull(TlsClientHello.serverName(notAClientHello));
        Buffer overrunExtensions = handBuiltHello("api.example.test".getBytes());
        // Session id length beyond the record shifts every later length out of bounds.
        overrunExtensions.setByte(5 + 4 + 2 + 32, (byte) 0xff);
        assertNull(TlsClientHello.serverName(overrunExtensions));
        Buffer garbage = Buffer.buffer(new byte[]{0x16, 3, 1, 0, 4, 1, 0, 0, 9});
        assertNull(TlsClientHello.serverName(garbage));
    }

    @Test
    void aServerNameWithIllegalCharactersIsNull() {
        byte[] name = "bad host".getBytes();
        assertNull(TlsClientHello.serverName(handBuiltHello(name)));
        assertEquals("ok.example.test", TlsClientHello.serverName(handBuiltHello("ok.example.test.".getBytes())));
    }

    static Buffer clientHello(String serverName) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(true);
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setServerNames(serverName == null ? List.of() : List.of(new SNIHostName(serverName)));
        engine.setSSLParameters(parameters);
        ByteBuffer out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        engine.beginHandshake();
        engine.wrap(ByteBuffer.allocate(0), out);
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        return Buffer.buffer(bytes);
    }

    private static Buffer handBuiltHello(byte[] name) {
        Buffer sni = Buffer.buffer().appendShort((short) (name.length + 3)).appendByte((byte) 0)
                .appendShort((short) name.length).appendBytes(name);
        Buffer extensions = Buffer.buffer().appendShort((short) 0).appendShort((short) sni.length()).appendBuffer(sni);
        Buffer body = Buffer.buffer().appendShort((short) 0x0303).appendBytes(new byte[32]).appendByte((byte) 0)
                .appendShort((short) 2).appendShort((short) 0x1301).appendByte((byte) 1).appendByte((byte) 0)
                .appendShort((short) extensions.length()).appendBuffer(extensions);
        Buffer handshake = Buffer.buffer().appendByte((byte) 1).appendByte((byte) 0)
                .appendShort((short) body.length()).appendBuffer(body);
        return Buffer.buffer().appendByte((byte) 0x16).appendShort((short) 0x0301)
                .appendShort((short) handshake.length()).appendBuffer(handshake);
    }
}
