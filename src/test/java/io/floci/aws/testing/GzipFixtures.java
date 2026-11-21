package io.floci.aws.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/** Gzip request bodies shared by protocol and decoded-size tests. */
public final class GzipFixtures {

    private GzipFixtures() {
    }

    public static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream stream = new GZIPOutputStream(output)) {
            stream.write(body);
        }
        return output.toByteArray();
    }
}
