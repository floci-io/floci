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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.CheckSchemaVersionValidityRequest;
import software.amazon.awssdk.services.glue.model.Compatibility;
import software.amazon.awssdk.services.glue.model.CreateRegistryRequest;
import software.amazon.awssdk.services.glue.model.CreateSchemaRequest;
import software.amazon.awssdk.services.glue.model.DataFormat;
import software.amazon.awssdk.services.glue.model.DeleteRegistryRequest;
import software.amazon.awssdk.services.glue.model.DeleteSchemaRequest;
import software.amazon.awssdk.services.glue.model.DeleteSchemaVersionsRequest;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetRegistryRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaByDefinitionRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaVersionRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaVersionsDiffRequest;
import software.amazon.awssdk.services.glue.model.GetTagsRequest;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.ListRegistriesRequest;
import software.amazon.awssdk.services.glue.model.ListSchemasRequest;
import software.amazon.awssdk.services.glue.model.ListSchemaVersionsRequest;
import software.amazon.awssdk.services.glue.model.MetadataKeyValuePair;
import software.amazon.awssdk.services.glue.model.PutSchemaVersionMetadataRequest;
import software.amazon.awssdk.services.glue.model.QuerySchemaVersionMetadataRequest;
import software.amazon.awssdk.services.glue.model.RegisterSchemaVersionRequest;
import software.amazon.awssdk.services.glue.model.RegistryId;
import software.amazon.awssdk.services.glue.model.RemoveSchemaVersionMetadataRequest;
import software.amazon.awssdk.services.glue.model.SchemaDiffType;
import software.amazon.awssdk.services.glue.model.SchemaId;
import software.amazon.awssdk.services.glue.model.SchemaVersionNumber;
import software.amazon.awssdk.services.glue.model.TagResourceRequest;
import software.amazon.awssdk.services.glue.model.UntagResourceRequest;
import software.amazon.awssdk.services.glue.model.UpdateRegistryRequest;
import software.amazon.awssdk.services.glue.model.UpdateSchemaRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Glue Schema Registry")
class GlueSchemaRegistryTest {

    private static final String AVRO_V1 =
            "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"com.floci\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_V2 =
            "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"com.floci\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"amount\",\"type\":[\"null\",\"double\"],\"default\":null}]}";

    private static final String AVRO_COMPAT_BASE =
            "{\"type\":\"record\",\"name\":\"Customer\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"},"
                    + "{\"name\":\"phone\",\"type\":[\"null\",\"string\"],\"default\":null}"
                    + "]}";

    private static final String AVRO_COMPAT_ADD_REQUIRED =
            "{\"type\":\"record\",\"name\":\"Customer\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"},"
                    + "{\"name\":\"phone\",\"type\":[\"null\",\"string\"],\"default\":null},"
                    + "{\"name\":\"zip\",\"type\":\"string\"}"
                    + "]}";

    private static final String AVRO_COMPAT_ADD_OPTIONAL =
            "{\"type\":\"record\",\"name\":\"Customer\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"},"
                    + "{\"name\":\"phone\",\"type\":[\"null\",\"string\"],\"default\":null},"
                    + "{\"name\":\"zip\",\"type\":[\"null\",\"string\"],\"default\":null}"
                    + "]}";

    private static final String AVRO_COMPAT_DELETE_REQUIRED =
            "{\"type\":\"record\",\"name\":\"Customer\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"phone\",\"type\":[\"null\",\"string\"],\"default\":null}"
                    + "]}";

    private static final String AVRO_COMPAT_DELETE_OPTIONAL =
            "{\"type\":\"record\",\"name\":\"Customer\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"}"
                    + "]}";

    private static final String AVRO_TRANSITIVE_ID_ONLY =
            "{\"type\":\"record\",\"name\":\"Account\",\"namespace\":\"com.floci\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_TRANSITIVE_ID_EMAIL_REQUIRED =
            "{\"type\":\"record\",\"name\":\"Account\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"}"
                    + "]}";

    private static final String AVRO_TRANSITIVE_ID_EMAIL_PHONE_OPTIONAL =
            "{\"type\":\"record\",\"name\":\"Account\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"},"
                    + "{\"name\":\"phone\",\"type\":[\"null\",\"string\"],\"default\":null}"
                    + "]}";

    private static final String AVRO_TRANSITIVE_ID_PHONE_OPTIONAL =
            "{\"type\":\"record\",\"name\":\"Account\",\"namespace\":\"com.floci\","
                    + "\"fields\":["
                    + "{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"phone\",\"type\":[\"null\",\"string\"],\"default\":null}"
                    + "]}";

    private static final String JSON_SCHEMA =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"id\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"id\"],"
                    + "\"additionalProperties\":false}";

    private static final String PROTOBUF_SCHEMA =
            "syntax = \"proto3\";\n"
                    + "package com.floci;\n"
                    + "message Customer {\n"
                    + "  int64 id = 1;\n"
                    + "  string email = 2;\n"
                    + "}\n";

    private static final String PROTOBUF_SCHEMA_RESPACED =
            "syntax   =   \"proto3\";\n"
                    + "\n"
                    + "package   com.floci;\n"
                    + "\n"
                    + "message Customer {\n"
                    + "\n"
                    + "  int64 id=1;\n"
                    + "    string   email   =   2;\n"
                    + "}\n";

    private static final String PROTOBUF_SCHEMA_INVALID =
            "syntax = \"proto3\";\n"
                    + "package com.floci;\n"
                    + "message Customer {\n"
                    + "  int64 id = ;\n"
                    + "}\n";

    private static final String JSON_COMPAT_BASE_CLOSED =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"firstName\":{\"type\":\"string\"}},"
                    + "\"additionalProperties\":false}";

    private static final String JSON_COMPAT_BASE_OPEN =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"firstName\":{\"type\":\"string\"}},"
                    + "\"additionalProperties\":true}";

    private static final String JSON_COMPAT_ADD_OPTIONAL_PHONE =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"firstName\":{\"type\":\"string\"},\"phone\":{\"type\":\"number\"}},"
                    + "\"additionalProperties\":false}";

    private static final String JSON_COMPAT_WITH_OPTIONAL_PHONE =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"firstName\":{\"type\":\"string\"},\"phone\":{\"type\":\"number\"}},"
                    + "\"additionalProperties\":false}";

    private static final String JSON_COMPAT_DELETE_OPTIONAL_PHONE_CLOSED =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"firstName\":{\"type\":\"string\"}},"
                    + "\"additionalProperties\":false}";

    private static final String JSON_COMPAT_DELETE_OPTIONAL_PHONE_OPEN =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                    + "\"type\":\"object\","
                    + "\"properties\":{\"firstName\":{\"type\":\"string\"}},"
                    + "\"additionalProperties\":true}";

    private static final String PROTOBUF_COMPAT_BACKWARD_BASE =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  required string email = 3;\n"
                    + "  optional string phone = 4;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_REMOVE_REQUIRED_EMAIL =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  optional string phone = 4;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_ADD_REQUIRED_ZIP =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  required string email = 3;\n"
                    + "  optional string phone = 4;\n"
                    + "  required string zip = 5;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_ADD_OPTIONAL_ZIP =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  required string email = 3;\n"
                    + "  optional string phone = 4;\n"
                    + "  optional string zip = 5;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_FORWARD_BASE =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  optional string email = 3;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_ADD_REQUIRED_PHONE =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  optional string email = 3;\n"
                    + "  required string phone = 4;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_DELETE_REQUIRED_FIRST_NAME =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  required string last_name = 2;\n"
                    + "  optional string email = 3;\n"
                    + "}\n";

    private static final String PROTOBUF_COMPAT_OPTIONAL_FIRST_NAME_BASE =
            "syntax = \"proto2\";\n"
                    + "package com.floci;\n"
                    + "message Person {\n"
                    + "  optional string first_name = 1;\n"
                    + "  required string last_name = 2;\n"
                    + "  optional string email = 3;\n"
                    + "}\n";

    @BeforeAll
    static void configureAwsDefaultsForSchemaRegistrySerde() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        System.setProperty("aws.secretKey", "test");
        System.setProperty("aws.region", "us-east-1");
    }

    @Test
    void sdkClientCanUseDefaultRegistryAndListRegistryResources() {
        String schemaName = TestFixtures.uniqueName("default-orders");

        try (GlueClient glue = TestFixtures.glueClient()) {
            var created = glue.createSchema(CreateSchemaRequest.builder()
                    .schemaName(schemaName)
                    .dataFormat(DataFormat.AVRO)
                    .schemaDefinition(AVRO_V1)
                    .build());
            try {
                assertThat(created.registryName()).isEqualTo(AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME);
                assertThat(created.compatibility()).isEqualTo(Compatibility.BACKWARD);
                assertThat(created.schemaCheckpoint()).isEqualTo(1L);
                assertThat(created.latestSchemaVersion()).isEqualTo(1L);
                assertThat(created.nextSchemaVersion()).isEqualTo(2L);

                assertThat(glue.getRegistry(GetRegistryRequest.builder().build()).registryName())
                        .isEqualTo(AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME);
                var updatedRegistry = glue.updateRegistry(UpdateRegistryRequest.builder()
                        .registryId(RegistryId.builder()
                                .registryName(AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME)
                                .build())
                        .description("default registry updated through SDK")
                        .build());
                assertThat(updatedRegistry.registryName()).isEqualTo(AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME);
                assertThat(glue.getRegistry(GetRegistryRequest.builder().build()).description())
                        .isEqualTo("default registry updated through SDK");

                assertThat(glue.listRegistries(ListRegistriesRequest.builder().build()).registries())
                        .anyMatch(registry -> AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME.equals(registry.registryName()));
                assertThat(glue.listSchemas(ListSchemasRequest.builder()
                        .registryId(RegistryId.builder()
                                .registryName(AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME)
                                .build())
                        .build()).schemas())
                        .anyMatch(schema -> schemaName.equals(schema.schemaName()));
            } finally {
                glue.deleteSchema(DeleteSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(AWSSchemaRegistryConstants.DEFAULT_REGISTRY_NAME)
                                .schemaName(schemaName)
                                .build())
                        .build());
            }
        }
    }

    @Test
    void sdkClientCanUseJsonAndProtobufSchemaFormats() {
        String registryName = TestFixtures.uniqueName("java-gsr-format");
        String jsonSchemaName = TestFixtures.uniqueName("json");
        String protobufSchemaName = TestFixtures.uniqueName("protobuf");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                var json = glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(jsonSchemaName)
                        .dataFormat(DataFormat.JSON)
                        .compatibility(Compatibility.NONE)
                        .schemaDefinition(JSON_SCHEMA)
                        .build());
                assertThat(json.dataFormat()).isEqualTo(DataFormat.JSON);
                assertThat(glue.getSchemaVersion(GetSchemaVersionRequest.builder()
                        .schemaVersionId(json.schemaVersionId())
                        .build()).schemaDefinition()).isEqualTo(JSON_SCHEMA);

                var protobuf = glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(protobufSchemaName)
                        .dataFormat(DataFormat.PROTOBUF)
                        .compatibility(Compatibility.NONE)
                        .schemaDefinition(PROTOBUF_SCHEMA)
                        .build());
                assertThat(protobuf.dataFormat()).isEqualTo(DataFormat.PROTOBUF);
                assertThat(glue.getSchemaVersion(GetSchemaVersionRequest.builder()
                        .schemaVersionId(protobuf.schemaVersionId())
                        .build()).schemaDefinition()).isEqualTo(PROTOBUF_SCHEMA);

                assertThat(glue.checkSchemaVersionValidity(CheckSchemaVersionValidityRequest.builder()
                        .dataFormat(DataFormat.JSON)
                        .schemaDefinition(JSON_SCHEMA)
                        .build()).valid()).isTrue();
                var invalidJson = glue.checkSchemaVersionValidity(CheckSchemaVersionValidityRequest.builder()
                        .dataFormat(DataFormat.JSON)
                        .schemaDefinition("{not-json")
                        .build());
                assertThat(invalidJson.valid()).isFalse();
                assertThat(invalidJson.error()).isNotBlank();

                assertThat(glue.checkSchemaVersionValidity(CheckSchemaVersionValidityRequest.builder()
                        .dataFormat(DataFormat.PROTOBUF)
                        .schemaDefinition(PROTOBUF_SCHEMA)
                        .build()).valid()).isTrue();
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    /**
     * Ensure the parser still links and runs after excluding KotlinPoet and ICU4j. Canonicalization is
     * asserted by re-registering a whitespace-only variant. It resolves to the version already stored
     * only when Wire reformatted both definitions, which the raw-definition fallback in the service
     * would not do.
     */
    @Test
    void sdkClientValidatesAndCanonicalizesProtobufSchemas() {
        var registryName = TestFixtures.uniqueName("java-gsr-protobuf-validation");
        var schemaName = TestFixtures.uniqueName("protobuf-validated");

        try (var glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                var invalid = glue.checkSchemaVersionValidity(CheckSchemaVersionValidityRequest.builder()
                        .dataFormat(DataFormat.PROTOBUF)
                        .schemaDefinition(PROTOBUF_SCHEMA_INVALID)
                        .build());
                assertThat(invalid.valid()).isFalse();
                assertThat(invalid.error()).isNotBlank();

                assertThatThrownBy(() -> glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(TestFixtures.uniqueName("protobuf-invalid"))
                        .dataFormat(DataFormat.PROTOBUF)
                        .compatibility(Compatibility.NONE)
                        .schemaDefinition(PROTOBUF_SCHEMA_INVALID)
                        .build()))
                        .isInstanceOf(InvalidInputException.class);

                var created = glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(schemaName)
                        .dataFormat(DataFormat.PROTOBUF)
                        .compatibility(Compatibility.BACKWARD)
                        .schemaDefinition(PROTOBUF_SCHEMA)
                        .build());

                var reregistered = glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(schemaName)
                                .build())
                        .schemaDefinition(PROTOBUF_SCHEMA_RESPACED)
                        .build());
                assertThat(reregistered.schemaVersionId()).isEqualTo(created.schemaVersionId());
                assertThat(reregistered.versionNumber()).isEqualTo(1L);

                assertThat(glue.getSchema(GetSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(schemaName)
                                .build())
                        .build()).latestSchemaVersion()).isEqualTo(1L);
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @Test
    void sdkClientEnforcesDocumentedJsonAndProtobufCompatibilityExamples() {
        String registryName = TestFixtures.uniqueName("java-gsr-format-compat");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                assertSchemaEvolution(glue, registryName, "json-backward-closed",
                        DataFormat.JSON, Compatibility.BACKWARD,
                        JSON_COMPAT_BASE_CLOSED, JSON_COMPAT_ADD_OPTIONAL_PHONE, true);
                assertSchemaEvolution(glue, registryName, "json-backward-open",
                        DataFormat.JSON, Compatibility.BACKWARD,
                        JSON_COMPAT_BASE_OPEN, JSON_COMPAT_ADD_OPTIONAL_PHONE, false);
                assertSchemaEvolution(glue, registryName, "json-forward-closed",
                        DataFormat.JSON, Compatibility.FORWARD,
                        JSON_COMPAT_WITH_OPTIONAL_PHONE, JSON_COMPAT_DELETE_OPTIONAL_PHONE_CLOSED, true);
                assertSchemaEvolution(glue, registryName, "json-forward-open",
                        DataFormat.JSON, Compatibility.FORWARD,
                        JSON_COMPAT_WITH_OPTIONAL_PHONE, JSON_COMPAT_DELETE_OPTIONAL_PHONE_OPEN, false);

                assertSchemaEvolution(glue, registryName, "protobuf-backward-remove-required",
                        DataFormat.PROTOBUF, Compatibility.BACKWARD,
                        PROTOBUF_COMPAT_BACKWARD_BASE, PROTOBUF_COMPAT_REMOVE_REQUIRED_EMAIL, false);
                assertSchemaEvolution(glue, registryName, "protobuf-backward-add-required",
                        DataFormat.PROTOBUF, Compatibility.BACKWARD,
                        PROTOBUF_COMPAT_BACKWARD_BASE, PROTOBUF_COMPAT_ADD_REQUIRED_ZIP, false);
                assertSchemaEvolution(glue, registryName, "protobuf-backward-add-optional",
                        DataFormat.PROTOBUF, Compatibility.BACKWARD,
                        PROTOBUF_COMPAT_BACKWARD_BASE, PROTOBUF_COMPAT_ADD_OPTIONAL_ZIP, true);
                assertSchemaEvolution(glue, registryName, "protobuf-forward-add-required",
                        DataFormat.PROTOBUF, Compatibility.FORWARD,
                        PROTOBUF_COMPAT_FORWARD_BASE, PROTOBUF_COMPAT_ADD_REQUIRED_PHONE, false);
                assertSchemaEvolution(glue, registryName, "protobuf-forward-delete-required",
                        DataFormat.PROTOBUF, Compatibility.FORWARD,
                        PROTOBUF_COMPAT_FORWARD_BASE, PROTOBUF_COMPAT_DELETE_REQUIRED_FIRST_NAME, false);
                assertSchemaEvolution(glue, registryName, "protobuf-forward-delete-optional",
                        DataFormat.PROTOBUF, Compatibility.FORWARD,
                        PROTOBUF_COMPAT_OPTIONAL_FIRST_NAME_BASE, PROTOBUF_COMPAT_DELETE_REQUIRED_FIRST_NAME, true);
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @Test
    void sdkClientRejectsSchemaDefinitionsOverDocumentedPayloadLimit() {
        String registryName = TestFixtures.uniqueName("java-gsr-quota");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                assertThatThrownBy(() -> glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(TestFixtures.uniqueName("too-large"))
                        .dataFormat(DataFormat.AVRO)
                        .schemaDefinition("x".repeat(170_001))
                        .build()))
                        .isInstanceOf(InvalidInputException.class);
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @ParameterizedTest(name = "SDK client can use {0} compatibility")
    @EnumSource(value = Compatibility.class, names = {
            "NONE",
            "DISABLED",
            "BACKWARD",
            "BACKWARD_ALL",
            "FORWARD",
            "FORWARD_ALL",
            "FULL",
            "FULL_ALL"
    })
    void sdkClientCanCreateAndUpdateSchemasWithEachCompatibilityMode(Compatibility compatibility) {
        String registryName = TestFixtures.uniqueName("java-gsr-compat");
        String createdSchemaName = TestFixtures.uniqueName("created");
        String updatedSchemaName = TestFixtures.uniqueName("updated");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(createdSchemaName)
                        .dataFormat(DataFormat.AVRO)
                        .compatibility(compatibility)
                        .schemaDefinition(AVRO_V1)
                        .build());
                assertThat(glue.getSchema(GetSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(createdSchemaName)
                                .build())
                        .build()).compatibility()).isEqualTo(compatibility);

                glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(updatedSchemaName)
                        .dataFormat(DataFormat.AVRO)
                        .compatibility(Compatibility.BACKWARD)
                        .schemaDefinition(AVRO_V1)
                        .build());
                glue.updateSchema(UpdateSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(updatedSchemaName)
                                .build())
                        .compatibility(compatibility)
                        .build());
                assertThat(glue.getSchema(GetSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(updatedSchemaName)
                                .build())
                        .build()).compatibility()).isEqualTo(compatibility);
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} is {3}")
    @MethodSource("avroCompatibilityCases")
    void sdkClientEnforcesAvroCompatibilityModePermutations(
            Compatibility compatibility,
            String change,
            String nextSchemaDefinition,
            boolean allowed) {
        String registryName = TestFixtures.uniqueName("java-gsr-compat-rule");
        String schemaName = TestFixtures.uniqueName(change.toLowerCase().replace('_', '-'));

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(schemaName)
                        .dataFormat(DataFormat.AVRO)
                        .compatibility(compatibility)
                        .schemaDefinition(AVRO_COMPAT_BASE)
                        .build());

                var register = RegisterSchemaVersionRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(schemaName)
                                .build())
                        .schemaDefinition(nextSchemaDefinition)
                        .build();
                if (allowed) {
                    assertThat(glue.registerSchemaVersion(register).versionNumber()).isEqualTo(2L);
                } else {
                    assertThatThrownBy(() -> glue.registerSchemaVersion(register))
                            .isInstanceOf(InvalidInputException.class);
                }
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @ParameterizedTest(name = "{1} checks all previous versions")
    @MethodSource("avroTransitiveCompatibilityCases")
    void sdkClientEnforcesAvroTransitiveCompatibilityModes(
            Compatibility nonTransitiveCompatibility,
            Compatibility transitiveCompatibility,
            String firstSchemaDefinition,
            String secondSchemaDefinition,
            String candidateSchemaDefinition) {
        String registryName = TestFixtures.uniqueName("java-gsr-transitive");

        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            try {
                String nonTransitiveSchemaName = TestFixtures.uniqueName("non-transitive");
                seedSchemaHistory(glue, registryName, nonTransitiveSchemaName,
                        firstSchemaDefinition, secondSchemaDefinition);
                glue.updateSchema(UpdateSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(nonTransitiveSchemaName)
                                .build())
                        .compatibility(nonTransitiveCompatibility)
                        .build());
                assertThat(glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(nonTransitiveSchemaName)
                                .build())
                        .schemaDefinition(candidateSchemaDefinition)
                        .build()).versionNumber()).isEqualTo(3L);

                String transitiveSchemaName = TestFixtures.uniqueName("transitive");
                seedSchemaHistory(glue, registryName, transitiveSchemaName,
                        firstSchemaDefinition, secondSchemaDefinition);
                glue.updateSchema(UpdateSchemaRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(transitiveSchemaName)
                                .build())
                        .compatibility(transitiveCompatibility)
                        .build());
                assertThatThrownBy(() -> glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                        .schemaId(SchemaId.builder()
                                .registryName(registryName)
                                .schemaName(transitiveSchemaName)
                                .build())
                        .schemaDefinition(candidateSchemaDefinition)
                        .build()))
                        .isInstanceOf(InvalidInputException.class);
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @Test
    void sdkClientCanManageTagsAndSchemaVersionMetadata() {
        String registryName = TestFixtures.uniqueName("java-gsr-meta");
        String schemaName = TestFixtures.uniqueName("meta-schema");

        try (GlueClient glue = TestFixtures.glueClient()) {
            var registry = glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .tags(Map.of("env", "test"))
                    .build());
            try {
                assertThat(glue.getTags(GetTagsRequest.builder()
                        .resourceArn(registry.registryArn())
                        .build()).tags()).containsEntry("env", "test");

                glue.tagResource(TagResourceRequest.builder()
                        .resourceArn(registry.registryArn())
                        .tagsToAdd(Map.of("team", "platform"))
                        .build());
                assertThat(glue.getTags(GetTagsRequest.builder()
                        .resourceArn(registry.registryArn())
                        .build()).tags())
                        .containsEntry("env", "test")
                        .containsEntry("team", "platform");

                glue.untagResource(UntagResourceRequest.builder()
                        .resourceArn(registry.registryArn())
                        .tagsToRemove("env")
                        .build());
                assertThat(glue.getTags(GetTagsRequest.builder()
                        .resourceArn(registry.registryArn())
                        .build()).tags())
                        .doesNotContainKey("env")
                        .containsEntry("team", "platform");

                var schema = glue.createSchema(CreateSchemaRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .schemaName(schemaName)
                        .dataFormat(DataFormat.AVRO)
                        .compatibility(Compatibility.BACKWARD)
                        .schemaDefinition(AVRO_V1)
                        .tags(Map.of("purpose", "metadata"))
                        .build());
                assertThat(glue.getTags(GetTagsRequest.builder()
                        .resourceArn(schema.schemaArn())
                        .build()).tags()).containsEntry("purpose", "metadata");

                var putMetadata = glue.putSchemaVersionMetadata(PutSchemaVersionMetadataRequest.builder()
                        .schemaVersionId(schema.schemaVersionId())
                        .metadataKeyValue(MetadataKeyValuePair.builder()
                                .metadataKey("stage")
                                .metadataValue("prod")
                                .build())
                        .build());
                assertThat(putMetadata.registryName()).isEqualTo(registryName);
                assertThat(putMetadata.schemaName()).isEqualTo(schemaName);
                assertThat(putMetadata.latestVersion()).isTrue();
                var metadata = glue.querySchemaVersionMetadata(QuerySchemaVersionMetadataRequest.builder()
                        .schemaVersionId(schema.schemaVersionId())
                        .build());
                assertThat(metadata.metadataInfoMap()).containsKey("stage");
                assertThat(metadata.metadataInfoMap().get("stage").metadataValue()).isEqualTo("prod");

                glue.putSchemaVersionMetadata(PutSchemaVersionMetadataRequest.builder()
                        .schemaVersionId(schema.schemaVersionId())
                        .metadataKeyValue(MetadataKeyValuePair.builder()
                                .metadataKey("stage")
                                .metadataValue("qa")
                                .build())
                        .build());
                var updatedMetadata = glue.querySchemaVersionMetadata(QuerySchemaVersionMetadataRequest.builder()
                        .schemaVersionId(schema.schemaVersionId())
                        .metadataList(MetadataKeyValuePair.builder()
                                .metadataKey("stage")
                                .metadataValue("prod")
                                .build())
                        .build());
                assertThat(updatedMetadata.metadataInfoMap().get("stage").metadataValue()).isEqualTo("qa");
                assertThat(updatedMetadata.metadataInfoMap().get("stage").otherMetadataValueList())
                        .anyMatch(item -> "prod".equals(item.metadataValue()));

                var removeMetadata = glue.removeSchemaVersionMetadata(RemoveSchemaVersionMetadataRequest.builder()
                        .schemaVersionId(schema.schemaVersionId())
                        .metadataKeyValue(MetadataKeyValuePair.builder()
                                .metadataKey("stage")
                                .metadataValue("qa")
                                .build())
                        .build());
                assertThat(removeMetadata.registryName()).isEqualTo(registryName);
                assertThat(removeMetadata.schemaName()).isEqualTo(schemaName);
                assertThat(removeMetadata.latestVersion()).isTrue();
                var afterRemoval = glue.querySchemaVersionMetadata(QuerySchemaVersionMetadataRequest.builder()
                        .schemaVersionId(schema.schemaVersionId())
                        .build());
                assertThat(afterRemoval.metadataInfoMap().get("stage").metadataValue()).isEqualTo("prod");
            } finally {
                glue.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(RegistryId.builder().registryName(registryName).build())
                        .build());
            }
        }
    }

    @Test
    void sdkClientCanManageSchemaRegistryWithPaginationAndCheckpointDeletion() {
        String registryName = TestFixtures.uniqueName("java-gsr");
        String schemaName = TestFixtures.uniqueName("orders");

        try (GlueClient glue = TestFixtures.glueClient()) {
            var registry = glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            assertThat(registry.registryName()).isEqualTo(registryName);
            assertThat(registry.registryArn()).contains(":registry/" + registryName);

            var created = glue.createSchema(CreateSchemaRequest.builder()
                    .registryId(RegistryId.builder().registryName(registryName).build())
                    .schemaName(schemaName)
                    .dataFormat(DataFormat.AVRO)
                    .compatibility(Compatibility.BACKWARD)
                    .schemaDefinition(AVRO_V1)
                    .build());
            assertThat(created.schemaName()).isEqualTo(schemaName);
            assertThat(created.schemaVersionId()).isNotBlank();

            var registered = glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaDefinition(AVRO_V2)
                    .build());
            assertThat(registered.versionNumber()).isEqualTo(2L);

            var byDefinition = glue.getSchemaByDefinition(GetSchemaByDefinitionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaDefinition(AVRO_V2)
                    .build());
            assertThat(byDefinition.schemaVersionId()).isEqualTo(registered.schemaVersionId());
            assertThat(byDefinition.dataFormat()).isEqualTo(DataFormat.AVRO);

            var diff = glue.getSchemaVersionsDiff(GetSchemaVersionsDiffRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .firstSchemaVersionNumber(SchemaVersionNumber.builder().versionNumber(1L).build())
                    .secondSchemaVersionNumber(SchemaVersionNumber.builder().versionNumber(2L).build())
                    .schemaDiffType(SchemaDiffType.SYNTAX_DIFF)
                    .build());
            assertThat(diff.diff()).contains("--- v1", "+++ v2", "amount");

            var compatibilityUpdate = glue.updateSchema(UpdateSchemaRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .compatibility(Compatibility.FORWARD)
                    .build());
            assertThat(compatibilityUpdate.schemaName()).isEqualTo(schemaName);
            assertThat(compatibilityUpdate.registryName()).isEqualTo(registryName);
            assertThat(glue.getSchema(GetSchemaRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .build()).compatibility()).isEqualTo(Compatibility.FORWARD);

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

            var checkpointUpdate = glue.updateSchema(UpdateSchemaRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaVersionNumber(SchemaVersionNumber.builder().versionNumber(2L).build())
                    .build());
            assertThat(checkpointUpdate.schemaName()).isEqualTo(schemaName);
            assertThat(checkpointUpdate.registryName()).isEqualTo(registryName);

            var deleted = glue.deleteSchemaVersions(DeleteSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .versions("1")
                    .build());
            assertThat(deleted.schemaVersionErrors()).isEmpty();

            var latest = glue.getSchemaVersion(GetSchemaVersionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaVersionNumber(SchemaVersionNumber.builder().latestVersion(true).build())
                    .build());
            assertThat(latest.versionNumber()).isEqualTo(2L);

            glue.deleteRegistry(DeleteRegistryRequest.builder()
                    .registryId(RegistryId.builder().registryName(registryName).build())
                    .build());

            assertThatThrownBy(() -> glue.getSchema(GetSchemaRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .build()))
                    .isInstanceOf(EntityNotFoundException.class);
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
            configs.put(AWSSchemaRegistryConstants.COMPRESSION_TYPE, AWSSchemaRegistryConstants.COMPRESSION.ZLIB.name());
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

    private static Stream<Arguments> avroCompatibilityCases() {
        return Stream.of(
                arguments(Compatibility.NONE, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, true),
                arguments(Compatibility.NONE, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.NONE, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, true),
                arguments(Compatibility.NONE, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true),
                arguments(Compatibility.DISABLED, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, false),
                arguments(Compatibility.DISABLED, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, false),
                arguments(Compatibility.DISABLED, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, false),
                arguments(Compatibility.DISABLED, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, false),
                arguments(Compatibility.BACKWARD, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, false),
                arguments(Compatibility.BACKWARD, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.BACKWARD, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, true),
                arguments(Compatibility.BACKWARD, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true),
                arguments(Compatibility.BACKWARD_ALL, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, false),
                arguments(Compatibility.BACKWARD_ALL, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.BACKWARD_ALL, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, true),
                arguments(Compatibility.BACKWARD_ALL, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true),
                arguments(Compatibility.FORWARD, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, true),
                arguments(Compatibility.FORWARD, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.FORWARD, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, false),
                arguments(Compatibility.FORWARD, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true),
                arguments(Compatibility.FORWARD_ALL, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, true),
                arguments(Compatibility.FORWARD_ALL, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.FORWARD_ALL, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, false),
                arguments(Compatibility.FORWARD_ALL, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true),
                arguments(Compatibility.FULL, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, false),
                arguments(Compatibility.FULL, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.FULL, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, false),
                arguments(Compatibility.FULL, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true),
                arguments(Compatibility.FULL_ALL, "ADD_REQUIRED", AVRO_COMPAT_ADD_REQUIRED, false),
                arguments(Compatibility.FULL_ALL, "ADD_OPTIONAL", AVRO_COMPAT_ADD_OPTIONAL, true),
                arguments(Compatibility.FULL_ALL, "DELETE_REQUIRED", AVRO_COMPAT_DELETE_REQUIRED, false),
                arguments(Compatibility.FULL_ALL, "DELETE_OPTIONAL", AVRO_COMPAT_DELETE_OPTIONAL, true)
        );
    }

    private static Stream<Arguments> avroTransitiveCompatibilityCases() {
        return Stream.of(
                arguments(Compatibility.BACKWARD, Compatibility.BACKWARD_ALL,
                        AVRO_TRANSITIVE_ID_ONLY,
                        AVRO_TRANSITIVE_ID_EMAIL_REQUIRED,
                        AVRO_TRANSITIVE_ID_EMAIL_PHONE_OPTIONAL),
                arguments(Compatibility.FORWARD, Compatibility.FORWARD_ALL,
                        AVRO_TRANSITIVE_ID_EMAIL_REQUIRED,
                        AVRO_TRANSITIVE_ID_ONLY,
                        AVRO_TRANSITIVE_ID_PHONE_OPTIONAL),
                arguments(Compatibility.FULL, Compatibility.FULL_ALL,
                        AVRO_TRANSITIVE_ID_EMAIL_REQUIRED,
                        AVRO_TRANSITIVE_ID_ONLY,
                        AVRO_TRANSITIVE_ID_PHONE_OPTIONAL)
        );
    }

    private static void seedSchemaHistory(
            GlueClient glue,
            String registryName,
            String schemaName,
            String firstSchemaDefinition,
            String secondSchemaDefinition) {
        glue.createSchema(CreateSchemaRequest.builder()
                .registryId(RegistryId.builder().registryName(registryName).build())
                .schemaName(schemaName)
                .dataFormat(DataFormat.AVRO)
                .compatibility(Compatibility.NONE)
                .schemaDefinition(firstSchemaDefinition)
                .build());
        glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                .schemaId(SchemaId.builder()
                        .registryName(registryName)
                        .schemaName(schemaName)
                        .build())
                .schemaDefinition(secondSchemaDefinition)
                .build());
    }

    private static void assertSchemaEvolution(
            GlueClient glue,
            String registryName,
            String scenario,
            DataFormat dataFormat,
            Compatibility compatibility,
            String firstSchemaDefinition,
            String nextSchemaDefinition,
            boolean allowed) {
        String schemaName = TestFixtures.uniqueName(scenario);
        glue.createSchema(CreateSchemaRequest.builder()
                .registryId(RegistryId.builder().registryName(registryName).build())
                .schemaName(schemaName)
                .dataFormat(dataFormat)
                .compatibility(compatibility)
                .schemaDefinition(firstSchemaDefinition)
                .build());

        var register = RegisterSchemaVersionRequest.builder()
                .schemaId(SchemaId.builder()
                        .registryName(registryName)
                        .schemaName(schemaName)
                        .build())
                .schemaDefinition(nextSchemaDefinition)
                .build();
        if (allowed) {
            assertThat(glue.registerSchemaVersion(register).versionNumber()).isEqualTo(2L);
        } else {
            assertThatThrownBy(() -> glue.registerSchemaVersion(register))
                    .isInstanceOf(InvalidInputException.class);
        }
    }

    private static Arguments arguments(
            Compatibility compatibility,
            String change,
            String nextSchemaDefinition,
            boolean allowed) {
        return Arguments.of(compatibility, change, nextSchemaDefinition, allowed);
    }

    private static Arguments arguments(
            Compatibility nonTransitiveCompatibility,
            Compatibility transitiveCompatibility,
            String firstSchemaDefinition,
            String secondSchemaDefinition,
            String candidateSchemaDefinition) {
        return Arguments.of(
                nonTransitiveCompatibility,
                transitiveCompatibility,
                firstSchemaDefinition,
                secondSchemaDefinition,
                candidateSchemaDefinition);
    }
}
