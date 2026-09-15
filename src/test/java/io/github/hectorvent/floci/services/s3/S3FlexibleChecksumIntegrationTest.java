package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.DecoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class S3FlexibleChecksumIntegrationTest {

    @Test
    void putObjectDecodesUnsignedAwsChunkedPayloadWithChecksumTrailer() throws Exception {
        String bucket = "flexible-checksum-bucket";
        String key = "when-supported.gz";
        byte[] original = gzip("{\"hello\":\"world\"}");
        byte[] framed = awsChunked(original, S3Checksum.crc32Base64(original));

        given()
                .when().put("/" + bucket)
                .then().statusCode(200);

        given()
                .contentType("application/octet-stream")
                .header("Content-Encoding", "gzip")
                .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
                .header("x-amz-decoded-content-length", original.length)
                .header("x-amz-trailer", "x-amz-checksum-crc32")
                .body(framed)
                .when().put("/" + bucket + "/" + key)
                .then().statusCode(200);

        RestAssuredConfig noDecompression = RestAssuredConfig.config()
                .decoderConfig(DecoderConfig.decoderConfig().noContentDecoders());
        Response response = given()
                .config(noDecompression)
                .when().get("/" + bucket + "/" + key)
                .then().statusCode(200)
                .extract().response();

        assertEquals("gzip", response.header("Content-Encoding"));
        assertArrayEquals(original, response.asByteArray());
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static byte[] awsChunked(byte[] payload, String checksum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(Integer.toHexString(payload.length).getBytes(StandardCharsets.US_ASCII));
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.write("\r\n0\r\nx-amz-checksum-crc32:".getBytes(StandardCharsets.US_ASCII));
        output.write(checksum.getBytes(StandardCharsets.US_ASCII));
        output.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }
}
