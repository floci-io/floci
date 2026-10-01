package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2UserDataDecoderTest {

    @Test
    void decodesGzippedUserData() throws IOException {
        String userData = "#!/bin/bash\necho hi\n";

        assertEquals(userData, Ec2UserDataDecoder.decode(gzipBase64(userData)));
    }

    @Test
    void acceptsGzipExpansionAtExecutionLimit() throws IOException {
        String userData = "A".repeat(UserDataPipeline.MAX_DECOMPRESSED_USER_DATA_BYTES);

        assertEquals(userData, Ec2UserDataDecoder.decode(gzipBase64(userData)));
    }

    @Test
    void acceptsAwsRawUserDataLimit() {
        String raw = "A".repeat(16 * 1024);

        assertEquals(raw, Ec2UserDataDecoder.decode(
                Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsRawUserDataBeyondAwsLimit() {
        String raw = "A".repeat(16 * 1024 + 1);

        AwsException error = assertThrows(AwsException.class, () -> Ec2UserDataDecoder.decode(
                Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    void rejectsGzipExpansionBeyondExecutionLimit() throws IOException {
        String oversized = "A".repeat(UserDataPipeline.MAX_DECOMPRESSED_USER_DATA_BYTES + 1);

        AwsException error = assertThrows(AwsException.class,
                () -> Ec2UserDataDecoder.decode(gzipBase64(oversized)));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void preservesInvalidBase64AndGzipErrors() {
        AwsException invalidBase64 = assertThrows(AwsException.class,
                () -> Ec2UserDataDecoder.decode("not-base64"));
        assertEquals("InvalidParameterValue", invalidBase64.getErrorCode());

        String malformedGzip = Base64.getEncoder().encodeToString(new byte[]{0x1f, (byte) 0x8b, 0x00});
        AwsException invalidGzip = assertThrows(AwsException.class,
                () -> Ec2UserDataDecoder.decode(malformedGzip));
        assertEquals("InvalidParameterValue", invalidGzip.getErrorCode());
    }

    private static String gzipBase64(String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
