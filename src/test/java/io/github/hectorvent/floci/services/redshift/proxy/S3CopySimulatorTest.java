package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class S3CopySimulatorTest {

    @Test
    public void testRunCopyFrom_ObjectNotFound() throws Exception {
        Socket client = mock(Socket.class);
        Socket backend = mock(Socket.class);
        S3Service s3 = mock(S3Service.class);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        when(client.getOutputStream()).thenReturn(clientOut);

        when(s3.objectExists("bucket", "missing.csv")).thenReturn(false);
        when(s3.listObjects("bucket", "missing.csv", null, 10000)).thenReturn(List.of());

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "table", List.of(), "bucket", "missing.csv", ",", false, false, null
        );

        S3CopySimulator.runCopyFrom(client, backend, spec, s3);

        byte[] output = clientOut.toByteArray();
        assertTrue(output.length > 0);
        assertEquals('E', output[0]);
    }

    @Test
    public void testRunCopyFrom_Success() throws Exception {
        Socket client = mock(Socket.class);
        Socket backend = mock(Socket.class);
        S3Service s3 = mock(S3Service.class);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        when(client.getOutputStream()).thenReturn(clientOut);

        ByteArrayOutputStream backendOut = new ByteArrayOutputStream();
        when(backend.getOutputStream()).thenReturn(backendOut);

        when(s3.objectExists("bucket", "file.csv")).thenReturn(true);
        when(s3.openObjectStream(eq("bucket"), eq("file.csv"), isNull())).thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        byte[] backendResponse = new byte[] {
                'G', 0, 0, 0, 4,
                'C', 0, 0, 0, 4,
                'Z', 0, 0, 0, 5, 'I'
        };
        when(backend.getInputStream()).thenReturn(new ByteArrayInputStream(backendResponse));

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "table", List.of(), "bucket", "file.csv", ",", false, false, null
        );

        S3CopySimulator.runCopyFrom(client, backend, spec, s3);

        byte[] bout = backendOut.toByteArray();
        assertTrue(bout.length > 0);
        assertEquals('Q', bout[0]);

        byte[] cout = clientOut.toByteArray();
        assertTrue(cout.length > 0);
        assertEquals('C', cout[0]);
    }

    @Test
    public void testRunCopyFrom_S3AccessDenied() throws Exception {
        Socket client = mock(Socket.class);
        Socket backend = mock(Socket.class);
        S3Service s3 = mock(S3Service.class);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        when(client.getOutputStream()).thenReturn(clientOut);
        Mockito.doThrow(new AwsException("AccessDenied", "Access Denied", 403))
                .when(s3).authorizeAnonymousListBucket("secret");

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "secret", "x.csv", ",", false, false, null);

        S3CopySimulator.runCopyFrom(client, backend, spec, s3);

        byte[] out = clientOut.toByteArray();
        assertEquals('E', out[0], "denied access must surface as a Postgres ErrorResponse");
        assertTrue(new String(out, StandardCharsets.UTF_8).contains("42501"),
                "error should carry the insufficient_privilege SQLSTATE");
        verify(s3, Mockito.never()).openObjectStream(anyString(), anyString(), any());
    }

    @Test
    public void testRunUnload_S3AccessDenied() throws Exception {
        Socket client = mock(Socket.class);
        Socket backend = mock(Socket.class);
        S3Service s3 = mock(S3Service.class);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        when(client.getOutputStream()).thenReturn(clientOut);
        Mockito.doThrow(new AwsException("AccessDenied", "Access Denied", 403))
                .when(s3).authorizeAnonymousPutObject(eq("secret"), anyString());

        CopyStatementParser.S3Unload spec = new CopyStatementParser.S3Unload(
                "SELECT 1", "secret", "out/", ",", false, false, true, false, null, false);

        S3CopySimulator.runUnload(client, backend, spec, s3);

        byte[] out = clientOut.toByteArray();
        assertEquals('E', out[0], "denied write must surface as a Postgres ErrorResponse");
        verify(backend, Mockito.never()).getOutputStream();
        verify(s3, Mockito.never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    public void testRunUnload_ManifestKeyAuthorizedSeparately() throws Exception {
        Socket client = mock(Socket.class);
        Socket backend = mock(Socket.class);
        S3Service s3 = mock(S3Service.class);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        when(client.getOutputStream()).thenReturn(clientOut);
        // Policy allows the data object but denies the manifest key.
        Mockito.doThrow(new AwsException("AccessDenied", "Access Denied", 403))
                .when(s3).authorizeAnonymousPutObject("bkt", "out/manifest");

        CopyStatementParser.S3Unload spec = new CopyStatementParser.S3Unload(
                "SELECT 1", "bkt", "out/", ",", false, false, true, false, null, true /* MANIFEST */);

        S3CopySimulator.runUnload(client, backend, spec, s3);

        byte[] out = clientOut.toByteArray();
        assertEquals('E', out[0], "a denied manifest key must block the whole UNLOAD");
        verify(s3).authorizeAnonymousPutObject("bkt", "out/manifest");
        verify(s3, Mockito.never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
        verify(backend, Mockito.never()).getOutputStream();
    }
}
