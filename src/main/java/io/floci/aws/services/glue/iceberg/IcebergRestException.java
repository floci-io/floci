package io.floci.aws.services.glue.iceberg;

/**
 * An error the Iceberg REST catalog answers with its {@code ErrorModel} ({@code {"error": {"message", "type",
 * "code"}}}), whose {@code type} an Iceberg client maps to its own exception: {@code NoSuchTableException},
 * {@code CommitFailedException} and so on.
 */
public final class IcebergRestException extends RuntimeException {

    private final int code;
    private final String type;

    IcebergRestException(int code, String type, String message) {
        super(message);
        this.code = code;
        this.type = type;
    }

    public int code() {
        return code;
    }

    public String type() {
        return type;
    }

    static IcebergRestException badRequest(String message) {
        return new IcebergRestException(400, "BadRequestException", message);
    }

    static IcebergRestException noSuchNamespace(String namespace) {
        return new IcebergRestException(404, "NoSuchNamespaceException", "Namespace does not exist: " + namespace);
    }

    static IcebergRestException noSuchTable(String namespace, String table) {
        return new IcebergRestException(404, "NoSuchTableException",
                "Table does not exist: " + namespace + "." + table);
    }

    static IcebergRestException alreadyExists(String message) {
        return new IcebergRestException(409, "AlreadyExistsException", message);
    }
}
