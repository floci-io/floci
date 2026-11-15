package io.floci.aws.services.glue.iceberg;

import io.floci.aws.testutil.AwsRequestSigner;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** The commit bodies the Iceberg catalog tests send, as an Iceberg writer builds them, and how they send them. */
final class IcebergCommits {

    private IcebergCommits() {
    }

    /**
     * An append committing snapshot {@code snapshotId} to {@code main}, required to find {@code main} at
     * {@code parentSnapshotId} (absent when null), which is also the new snapshot's parent.
     */
    static String snapshotCommit(long snapshotId, Long parentSnapshotId, long sequenceNumber) {
        String parent = parentSnapshotId == null ? "" : "\"parent-snapshot-id\":" + parentSnapshotId + ",";
        return """
                {"requirements":[{"type":"assert-ref-snapshot-id","ref":"main","snapshot-id":%s}],"updates":[
                 {"action":"add-snapshot","snapshot":{"snapshot-id":%d,%s"sequence-number":%d,"timestamp-ms":5000,
                  "manifest-list":"s3://lake-bucket/lake/events/metadata/snap-%d.avro",
                  "summary":{"operation":"append"},"schema-id":0}},
                 {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":%d}]}
                """.formatted(parentSnapshotId == null ? "null" : parentSnapshotId, snapshotId, parent, sequenceNumber,
                snapshotId, snapshotId);
    }

    /**
     * The commit that creates a table a staged create described, as DuckDB and Spark send it: required to find no
     * table, with a two-column {@code schema} and its {@code location}.
     */
    static String createCommit(String schema, String location) {
        return """
                {"requirements":[{"type":"assert-create"}],"updates":[
                 {"action":"assign-uuid","uuid":"11111111-2222-3333-4444-555555555555"},
                 {"action":"upgrade-format-version","format-version":2},
                 {"action":"add-schema","last-column-id":2,"schema":%s},
                 {"action":"add-spec","spec":{"spec-id":0,"fields":[]}},{"action":"set-default-spec","spec-id":0},
                 {"action":"add-sort-order","sort-order":{"order-id":0,"fields":[]}},
                 {"action":"set-default-sort-order","sort-order-id":0},
                 {"action":"set-location","location":"%s"},
                 {"action":"set-properties","updates":{"format-version":"2"}},
                 {"action":"set-current-schema","schema-id":0}]}
                """.formatted(schema, location);
    }

    /** Sends a request to the Iceberg REST endpoint at {@code path}, signed by {@code signer}. */
    static Response send(URI endpoint, AwsRequestSigner signer, String method, String path, String body) {
        URI uri = endpoint.resolve(path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try {
            RequestSpecification request = given().urlEncodingEnabled(false)
                    .headers(signer.headersFor(method, uri, Map.of(), bytes));
            if (body != null) {
                request = request.contentType("application/json").body(bytes);
            }
            return request.request(method, uri);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
