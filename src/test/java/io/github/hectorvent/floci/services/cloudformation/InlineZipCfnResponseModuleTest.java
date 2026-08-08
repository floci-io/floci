package io.github.hectorvent.floci.services.cloudformation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inline {@code ZipFile} Lambda packages must carry the cfn-response module AWS injects
 * for that code path — custom-resource handlers in AWS Solutions templates (e.g. Landing
 * Zone Accelerator's installer stack) {@code require('cfn-response')} / {@code import
 * cfnresponse} and fail at init without it.
 */
class InlineZipCfnResponseModuleTest {

    private static Map<String, String> unzip(String base64) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void nodeZipCarriesRequirableCfnResponseModule() throws Exception {
        Map<String, String> entries = unzip(InlineZipPackager.sourceToZipBase64(
                "exports.handler = async () => {};", "index.handler", "nodejs22.x"));

        assertEquals("exports.handler = async () => {};", entries.get("index.js"));
        assertTrue(entries.containsKey("node_modules/cfn-response/package.json"));
        String module = entries.get("node_modules/cfn-response/cfn-response.js");
        assertTrue(module.contains("exports.send"));
        // Floci's ResponseURL is plain http on the emulator port, so the module must
        // pick the transport and port from the URL instead of hardcoding https:443.
        assertTrue(module.contains("require(\"http\")"));
        assertTrue(module.contains("parsedUrl.port"));
    }

    @Test
    void pythonZipCarriesCfnresponseModule() throws Exception {
        Map<String, String> entries = unzip(InlineZipPackager.sourceToZipBase64(
                "def handler(event, context):\n    pass\n", "index.handler", "python3.12"));

        assertTrue(entries.containsKey("index.py"));
        String module = entries.get("cfnresponse.py");
        assertTrue(module.contains("def send("));
        assertTrue(module.contains("HTTPConnection"));
        assertFalse(entries.containsKey("node_modules/cfn-response/cfn-response.js"));
    }
}
