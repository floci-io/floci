package com.floci.test;

import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import com.amazonaws.services.schemaregistry.utils.AvroRecordType;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Compatibility;
import software.amazon.awssdk.services.glue.model.CreateRegistryRequest;
import software.amazon.awssdk.services.glue.model.CreateSchemaRequest;
import software.amazon.awssdk.services.glue.model.DataFormat;
import software.amazon.awssdk.services.glue.model.DeleteRegistryRequest;
import software.amazon.awssdk.services.glue.model.DeleteSchemaVersionsRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaRequest;
import software.amazon.awssdk.services.glue.model.ListSchemaVersionsRequest;
import software.amazon.awssdk.services.glue.model.RegisterSchemaVersionRequest;
import software.amazon.awssdk.services.glue.model.RegistryId;
import software.amazon.awssdk.services.glue.model.SchemaId;
import software.amazon.awssdk.services.glue.model.SchemaVersionNumber;
import software.amazon.awssdk.services.glue.model.UpdateSchemaRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Glue Schema Registry")
class GlueSchemaRegistryTest {

    private static final String AVRO_V1 =
            "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"com.floci\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_V2 =
            "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"com.floci\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"amount\",\"type\":[\"null\",\"double\"],\"default\":null}]}";

    @BeforeAll
    static void configureAwsDefaultsForSchemaRegistrySerde() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        System.setProperty("aws.secretKey", "test");
        System.setProperty("aws.region", "us-east-1");
    }

    @Test
    void sdkClientCanManageSchemaRegistryWithPaginationAndCheckpointDeletion() {
        String registryName = TestFixtures.uniqueName("java-gsr");
        String schemaName = TestFixtures.uniqueName("orders");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            var created = glue.createSchema(CreateSchemaRequest.builder()
                    .registryId(RegistryId.builder().registryName(registryName).build())
                    .schemaName(schemaName)
                    .dataFormat(DataFormat.AVRO)
                    .compatibility(Compatibility.BACKWARD)
                    .schemaDefinition(AVRO_V1)
                    .build());
            assertThat(created.schemaVersionId()).isNotBlank();

            var registered = glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaDefinition(AVRO_V2)
                    .build());
            assertThat(registered.versionNumber()).isEqualTo(2L);

            var firstPage = glue.listSchemaVersions(ListSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .maxResults(1)
                    .build());
            assertThat(firstPage.schemas()).hasSize(1);
            assertThat(firstPage.nextToken()).isNotBlank();

            var secondPage = glue.listSchemaVersions(ListSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .maxResults(1)
                    .nextToken(firstPage.nextToken())
                    .build());
            assertThat(secondPage.schemas()).hasSize(1);
            assertThat(secondPage.nextToken()).isNull();

            glue.updateSchema(UpdateSchemaRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaVersionNumber(SchemaVersionNumber.builder().versionNumber(2L).build())
                    .build());

            var deleted = glue.deleteSchemaVersions(DeleteSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .versions("1")
                    .build());
            assertThat(deleted.schemaVersionErrors()).isEmpty();

            glue.deleteRegistry(DeleteRegistryRequest.builder()
                    .registryId(RegistryId.builder().registryName(registryName).build())
                    .build());
        }
    }

    @Test
    void kafkaAvroSerializerAndDeserializerRoundTripThroughFlociEndpoint() {
        String registryName = TestFixtures.uniqueName("java-gsr-serde");
        String schemaName = TestFixtures.uniqueName("orders-serde");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());

            Schema schema = new Schema.Parser().parse(AVRO_V1);
            GenericRecord record = new GenericData.Record(schema);
            record.put("id", 42L);

            Map<String, Object> configs = new HashMap<>();
            configs.put(AWSSchemaRegistryConstants.AWS_ENDPOINT, TestFixtures.endpoint().toString());
            configs.put(AWSSchemaRegistryConstants.AWS_REGION, "us-east-1");
            configs.put(AWSSchemaRegistryConstants.DATA_FORMAT, DataFormat.AVRO.name());
            configs.put(AWSSchemaRegistryConstants.REGISTRY_NAME, registryName);
            configs.put(AWSSchemaRegistryConstants.SCHEMA_NAME, schemaName);
            configs.put(AWSSchemaRegistryConstants.SCHEMA_AUTO_REGISTRATION_SETTING, true);
            configs.put(AWSSchemaRegistryConstants.AVRO_RECORD_TYPE, AvroRecordType.GENERIC_RECORD.getName());

            GlueSchemaRegistryKafkaSerializer serializer = new GlueSchemaRegistryKafkaSerializer();
            GlueSchemaRegistryKafkaDeserializer deserializer = new GlueSchemaRegistryKafkaDeserializer();
            try {
                serializer.configure(configs, false);
                deserializer.configure(configs, false);

                byte[] bytes = serializer.serialize("orders-topic", record);
                Object decoded = deserializer.deserialize("orders-topic", bytes);

                assertThat(decoded).isInstanceOf(GenericRecord.class);
                assertThat(((GenericRecord) decoded).get("id")).isEqualTo(42L);
                assertThat(glue.getSchema(GetSchemaRequest.builder()
                        .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                        .build()).schemaName()).isEqualTo(schemaName);
            } finally {
                serializer.close();
                deserializer.close();
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }
}
