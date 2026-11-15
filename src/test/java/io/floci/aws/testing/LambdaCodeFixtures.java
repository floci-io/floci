package io.floci.aws.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LambdaCodeFixtures {

    private LambdaCodeFixtures() {
    }

    public static byte[] nodeJsZip(String handlerSource) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(handlerSource.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the in-memory Lambda ZIP", e);
        }
        return bytes.toByteArray();
    }

    public static String nodeJsZipBase64(String handlerSource) {
        return Base64.getEncoder().encodeToString(nodeJsZip(handlerSource));
    }
}
