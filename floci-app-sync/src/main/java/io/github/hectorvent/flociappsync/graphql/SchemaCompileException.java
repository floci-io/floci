package io.github.hectorvent.flociappsync.graphql;

import java.util.Map;

/**
 * Raised by {@link AppSyncSchemaParser} when an SDL fails to parse or compile.
 *
 * <p>Stands in for Floci's own {@code AwsException} (not portable here — it belongs to
 * Floci's management-API error envelope). {@code AppSyncEngineResource} serializes this
 * as the HTTP 400 body of {@code POST /schemas/{apiId}}; Floci's own {@code AppSyncService}
 * is responsible for turning that into the {@code SchemaCreationStatus} FAILED details it
 * already produces today.
 */
public class SchemaCompileException extends RuntimeException {

    private final Map<String, Object> extendedData;

    public SchemaCompileException(String message, Map<String, Object> extendedData) {
        super(message);
        this.extendedData = extendedData;
    }

    public Map<String, Object> getExtendedData() {
        return extendedData;
    }
}
