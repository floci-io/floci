package io.github.hectorvent.floci.services.redshift.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ManifestReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SQLSTATE_INTERNAL = "XX000";

    private ManifestReader() {
    }

    static List<String> resolveManifestKeys(String bucket, String manifestKey, S3Service s3) {
        S3Object object = s3.getObject(bucket, manifestKey);
        if (object == null || object.getData() == null) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                    "S3 manifest file not found: s3://" + bucket + "/" + manifestKey, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(object.getData(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                    "Invalid manifest file format in s3://" + bucket + "/" + manifestKey, e);
        }
        JsonNode entries = root.get("entries");
        if (entries == null || !entries.isArray()) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                    "Manifest file missing 'entries' array", null);
        }

        List<String> keys = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode urlNode = entry.get("url");
            if (urlNode == null || !urlNode.isTextual()) {
                continue;
            }
            String url = urlNode.asText();
            boolean mandatory = !entry.has("mandatory") || entry.get("mandatory").asBoolean(true);
            if (!url.startsWith("s3://")) {
                throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                        "Invalid S3 URL in manifest: " + url, null);
            }
            String path = url.substring(5);
            int slash = path.indexOf('/');
            if (slash < 0) {
                throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                        "Malformed S3 URL in manifest: " + url, null);
            }
            String entryBucket = path.substring(0, slash);
            String entryKey = path.substring(slash + 1);

            if (!bucket.equals(entryBucket)) {
                throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                        "Cross-bucket manifest entry not supported: " + url, null);
            }

            if (s3.objectExists(entryBucket, entryKey)) {
                keys.add(entryKey);
            } else if (mandatory) {
                throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                        "The specified key does not exist: s3://" + entryBucket + "/" + entryKey, null);
            }
        }
        return keys;
    }
}
