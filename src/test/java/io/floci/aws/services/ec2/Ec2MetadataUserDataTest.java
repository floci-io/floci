package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Ec2MetadataUserDataTest {

    private Ec2MetadataTestFixture fixture;
    private Instance instance;

    @BeforeEach
    void startServer() throws Exception {
        fixture = new Ec2MetadataTestFixture();
        fixture.start();
        instance = fixture.instance();
    }

    @AfterEach
    void stopServer() throws Exception {
        fixture.close();
    }

    @Test
    void returnsOriginalBinaryBytes() throws Exception {
        byte[] original = {0, (byte) 0xff, (byte) 0x80, 10, 13};
        instance.setEncodedUserData(Base64.getEncoder().encodeToString(original));
        instance.setUserData("lossy execution text");
        HttpResponse<byte[]> response = getUserData(null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(original, response.body());
        assertEquals("application/octet-stream", response.headers().firstValue("content-type").orElseThrow());
    }

    @Test
    void returnsCompressedBytesWhileExecutionUsesDecodedScript() throws Exception {
        String script = "#!/bin/sh\necho snow: 雪\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
        instance.setEncodedUserData(encoded);
        instance.setUserData(Ec2UserDataDecoder.decode(encoded));
        assertEquals(script, instance.getUserData());
        String token = fixture.putToken("60").body();
        HttpResponse<byte[]> response = getUserData(token);
        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes.toByteArray(), response.body());
    }

    @Test
    void returnsLegacyUtf8AndWhitespace() throws Exception {
        for (String data : new String[]{"雪\n", " \n\t"}) {
            instance.setUserData(data);
            HttpResponse<byte[]> response = getUserData(null);
            assertEquals(200, response.statusCode());
            assertArrayEquals(
                    data.getBytes(StandardCharsets.UTF_8), response.body());
            instance.setEncodedUserData(Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)));
            HttpResponse<byte[]> encodedResponse = getUserData(null);
            assertEquals(200, encodedResponse.statusCode());
            assertArrayEquals(data.getBytes(StandardCharsets.UTF_8), encodedResponse.body());
            instance.setEncodedUserData(null);
        }
    }

    @Test
    void absentAndEmptyUserDataRemainNotFound() throws Exception {
        assertEquals(404, getUserData(null).statusCode());
        instance.setUserData("");
        assertEquals(404, getUserData(null).statusCode());
        instance.setUserData("stale text");
        for (String encoded : new String[]{"", " \n\t"}) {
            instance.setEncodedUserData(encoded);
            assertEquals(404, getUserData(null).statusCode());
        }
    }

    @Test
    void alreadyStoredExpandedUserDataIsNotSubjectToTheIngestionLimit() throws Exception {
        byte[] data = "A".repeat(32 * 1024).getBytes(StandardCharsets.UTF_8);
        instance.setUserData(new String(data, StandardCharsets.UTF_8));
        instance.setEncodedUserData(Base64.getEncoder().encodeToString(data));
        HttpResponse<byte[]> response = getUserData(null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(data, response.body());
    }

    @Test
    void malformedStoredEncodingFallsBackToSavedText() throws Exception {
        instance.setEncodedUserData("not-base64!");
        instance.setUserData("saved text 雪");
        HttpResponse<byte[]> response = getUserData(null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(instance.getUserData().getBytes(StandardCharsets.UTF_8), response.body());
    }

    @Test
    void malformedStoredEncodingWithoutSavedTextRemainsNotFound() throws Exception {
        instance.setEncodedUserData("not-base64!");
        assertEquals(404, getUserData(null).statusCode());
    }

    @Test
    void invalidTokenCannotReadUserData() throws Exception {
        instance.setUserData("private user data");
        assertEquals(401, getUserData("invalid-token").statusCode());
    }

    private HttpResponse<byte[]> getUserData(String token) throws Exception {
        return fixture.get("/latest/user-data", token, HttpResponse.BodyHandlers.ofByteArray());
    }
}
