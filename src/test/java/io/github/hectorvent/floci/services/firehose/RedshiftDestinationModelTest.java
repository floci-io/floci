package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RedshiftDestinationModelTest {

    private static final String CONFIG = """
            {
              "RoleARN": "arn:aws:iam::000000000000:role/firehose",
              "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
              "Username": "admin",
              "Password": "Secret123",
              "CopyCommand": {"DataTableName": "events", "CopyOptions": "json 'auto'", "DataTableColumns": "id,name"},
              "RetryOptions": {"DurationInSeconds": 120},
              "S3BackupMode": "Disabled",
              "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"},
              "S3BackupConfiguration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::backup"}
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void configurationShapeMapsToDescriptionKeys() throws Exception {
        RedshiftDestination dest = mapper.readValue(CONFIG, RedshiftDestination.class);

        assertEquals("events", dest.getCopyCommand().getDataTableName());
        assertEquals(120, dest.getRetryOptions().getDurationInSeconds());
        assertEquals("stage", dest.getS3().bucketName());
        assertEquals("backup", dest.getS3Backup().bucketName());

        JsonNode out = mapper.readTree(mapper.writeValueAsString(dest));
        assertEquals("arn:aws:s3:::stage", out.get("S3DestinationDescription").get("BucketARN").asText());
        assertNotNull(out.get("S3BackupDescription"));
        assertFalse(out.has("S3Configuration"));
    }

    @Test
    void updateShapeAliasesAreAccepted() throws Exception {
        RedshiftDestination dest = mapper.readValue(
                "{\"S3Update\": {\"BucketARN\": \"arn:aws:s3:::next\"}, \"S3BackupUpdate\": {\"BucketARN\": \"arn:aws:s3:::nextbk\"}}",
                RedshiftDestination.class);

        assertEquals("next", dest.getS3().bucketName());
        assertEquals("nextbk", dest.getS3Backup().bucketName());
    }

    @Test
    void copyWithoutPasswordDropsOnlyThePassword() throws Exception {
        RedshiftDestination dest = mapper.readValue(CONFIG, RedshiftDestination.class);

        RedshiftDestination redacted = dest.copyWithoutPassword();

        assertNull(redacted.getPassword());
        assertEquals("admin", redacted.getUsername());
        assertEquals("Secret123", dest.getPassword());
    }

    @Test
    void streamWithRedshiftDestinationExposesIntermediateS3AndSurvivesReload() throws Exception {
        RedshiftDestination dest = mapper.readValue(CONFIG, RedshiftDestination.class);

        DeliveryStreamDescription stream = new DeliveryStreamDescription("s",
                "arn:aws:firehose:us-east-1:000000000000:deliverystream/s", null, dest, null);

        assertEquals("stage", stream.s3Destination().bucketName());
        assertNotNull(stream.s3Destination().getBufferingHints());

        DeliveryStreamDescription reloaded =
                mapper.readValue(mapper.writeValueAsString(stream), DeliveryStreamDescription.class);
        assertEquals("Secret123", reloaded.getDestinations().get(0).getRedshiftDestinationDescription().getPassword());
        assertEquals("stage", reloaded.s3Destination().bucketName());
    }

    @Test
    void aRedshiftStreamsProcessingConfigurationIsTheDestinationsOwn() throws Exception {
        RedshiftDestination dest = mapper.readValue("""
                {"S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/r", "BucketARN": "arn:aws:s3:::stage"},
                 "ProcessingConfiguration": {"Enabled": true, "Processors": [{"Type": "Lambda"}]}}""",
                RedshiftDestination.class);

        DeliveryStreamDescription stream = new DeliveryStreamDescription("s", "arn", null, dest, null);

        assertSame(dest.getProcessingConfiguration(), stream.processingConfiguration());
        assertNull(stream.s3Destination().getProcessingConfiguration());
    }

    @Test
    void aRedshiftStreamWithoutProcessingHasNoProcessingConfiguration() throws Exception {
        RedshiftDestination dest = mapper.readValue(CONFIG, RedshiftDestination.class);

        assertNull(new DeliveryStreamDescription("s", "arn", null, dest, null).processingConfiguration());
    }

    @Test
    void anS3StreamsProcessingConfigurationIsTheS3Destinations() {
        DeliveryStreamDescription.S3Destination s3 = new DeliveryStreamDescription.S3Destination();
        DeliveryStreamDescription.ProcessingConfiguration processing =
                new DeliveryStreamDescription.ProcessingConfiguration();
        s3.setProcessingConfiguration(processing);

        assertSame(processing, new DeliveryStreamDescription("s", "arn", s3).processingConfiguration());
    }

    @Test
    void redshiftStreamWithoutBucketHasNoBucketName() throws Exception {
        RedshiftDestination dest = mapper.readValue(
                "{\"S3Configuration\": {\"RoleARN\": \"arn:aws:iam::000000000000:role/r\"}}", RedshiftDestination.class);

        DeliveryStreamDescription stream = new DeliveryStreamDescription("s", "arn", null, dest, null);

        assertNull(stream.s3Destination().bucketName());
    }
}
