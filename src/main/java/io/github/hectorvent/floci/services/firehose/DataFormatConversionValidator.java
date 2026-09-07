package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.DataFormatConversionConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Deserializer;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.SchemaConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Serializer;

/**
 * Validates a destination whose data format conversion is enabled, with the
 * messages and precedence captured from real AWS (probe 2026-09-07, both
 * CreateDeliveryStream and UpdateDestination): compression format first, then
 * the buffering size floor, then the three member null checks in
 * Input/Output/Schema order, then the members inside them. On
 * UpdateDestination real AWS validates the merged effective state, which is
 * why this runs against a merged view rather than the update shape.
 *
 * All checks are skipped when conversion is absent or explicitly disabled; an
 * omitted Enabled counts as enabled. The buffering floor applies only to an
 * explicitly specified SizeInMBs: AWS accepts a destination with no
 * BufferingHints at all (probe: it proceeds to role validation).
 *
 * The OrcSerDe rejection is Floci's own: real AWS accepts it, but Floci's
 * conversion engine (DuckDB) cannot write ORC, and refusing at create time
 * beats accepting a configuration that could never deliver.
 */
final class DataFormatConversionValidator {

    private static final int MIN_BUFFERING_SIZE_MBS = 64;

    private DataFormatConversionValidator() {}

    static void validateEffective(S3Destination config) {
        if (config == null || !config.isDataFormatConversionEnabled()) {
            return;
        }
        if (config.getCompressionFormat() != null && !"UNCOMPRESSED".equals(config.getCompressionFormat())) {
            throw invalidArgument("The S3 destination's compression format must be set to UNCOMPRESSED"
                    + " when data format conversion is enabled. To enable compression within the converted"
                    + " output format, set the appropriate serialization option in the output format configuration.");
        }
        if (config.getBufferingHints() != null && config.getBufferingHints().getSizeInMBs() != null
                && config.getBufferingHints().getSizeInMBs() < MIN_BUFFERING_SIZE_MBS) {
            throw invalidArgument("BufferingHints.SizeInMBs must be at least " + MIN_BUFFERING_SIZE_MBS
                    + " when data format conversion is enabled.");
        }
        DataFormatConversionConfiguration conversion = config.getDataFormatConversionConfiguration();
        if (conversion.getInputFormatConfiguration() == null) {
            throw invalidArgument("InputFormatConfiguration must not be null");
        }
        if (conversion.getOutputFormatConfiguration() == null) {
            throw invalidArgument("OutputFormatConfiguration must not be null");
        }
        if (conversion.getSchemaConfiguration() == null) {
            throw invalidArgument("SchemaConfiguration must not be null");
        }
        validateDeserializer(conversion.getInputFormatConfiguration().getDeserializer());
        validateSerializer(conversion.getOutputFormatConfiguration().getSerializer());
        validateSchema(conversion.getSchemaConfiguration());
    }

    /** An empty Deserializer object reports the same error as an absent one (probe a9). */
    private static void validateDeserializer(Deserializer deserializer) {
        int specified = deserializer == null ? 0
                : (deserializer.getOpenXJsonSerDe() != null ? 1 : 0)
                + (deserializer.getHiveJsonSerDe() != null ? 1 : 0);
        if (specified == 0) {
            throw invalidArgument("Deserializer must not be null");
        }
        if (specified > 1) {
            throw invalidArgument("More than one deserializer specified. Only one may be chosen.");
        }
    }

    private static void validateSerializer(Serializer serializer) {
        int specified = serializer == null ? 0
                : (serializer.getParquetSerDe() != null ? 1 : 0)
                + (serializer.getOrcSerDe() != null ? 1 : 0);
        if (specified == 0) {
            throw invalidArgument("Serializer must not be null");
        }
        if (specified > 1) {
            throw invalidArgument("More than one serializer specified. Only one may be chosen.");
        }
        if (serializer.getOrcSerDe() != null) {
            throw invalidArgument("OrcSerDe is not supported. Floci supports only ParquetSerDe"
                    + " for data format conversion.");
        }
    }

    /** AWS's message spells the member RoleArn even though the wire member is RoleARN. */
    private static void validateSchema(SchemaConfiguration schema) {
        requireSchemaMember(schema.getRoleArn(), "RoleArn");
        requireSchemaMember(schema.getDatabaseName(), "DatabaseName");
        requireSchemaMember(schema.getTableName(), "TableName");
    }

    private static void requireSchemaMember(String value, String member) {
        if (value == null || value.isEmpty()) {
            throw invalidArgument("SchemaConfiguration." + member + " must not be null or empty");
        }
    }

    private static AwsException invalidArgument(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }
}
