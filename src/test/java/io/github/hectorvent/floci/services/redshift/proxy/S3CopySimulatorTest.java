package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
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

        when(s3.headObject("bucket", "missing.csv")).thenThrow(new AwsException("NoSuchKey", "not found", 404));
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

        when(s3.headObject("bucket", "file.csv")).thenReturn(new S3Object("bucket", "file.csv", new byte[0], "text/csv"));
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
}
