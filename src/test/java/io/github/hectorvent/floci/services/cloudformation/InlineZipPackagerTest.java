package io.github.hectorvent.floci.services.cloudformation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The injected {@code cfnresponse} shim, packaged alongside inline Python custom-resource code. */
class InlineZipPackagerTest {

    @Test
    void pythonCallbackBodyIsUtf8EncodedBeforeSendingOverHttp() throws Exception {
        String zipBase64 = InlineZipPackager.sourceToZipBase64(
                "def handler(event, context):\n    pass\n", "index.handler", "python3.12");

        String cfnResponsePy = readZipEntry(zipBase64, "cfnresponse.py");

        // http.client encodes a str body as latin-1 by default, raising UnicodeEncodeError for any
        // response payload (e.g. a non-Latin-1 Reason or Data value) outside that range. The body
        // passed to conn.request must already be UTF-8 encoded bytes, not the raw str.
        assertTrue(cfnResponsePy.contains("response_body.encode('utf-8')")
                        || cfnResponsePy.contains("response_body.encode(\"utf-8\")"),
                "cfnresponse.py must UTF-8 encode the response body before conn.request:\n" + cfnResponsePy);
    }

    private static String readZipEntry(String zipBase64, String entryName) throws Exception {
        byte[] zipBytes = Base64.getDecoder().decode(zipBase64);
        try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    var out = new ByteArrayOutputStream();
                    zis.transferTo(out);
                    return out.toString(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Zip entry not found: " + entryName);
    }
}
