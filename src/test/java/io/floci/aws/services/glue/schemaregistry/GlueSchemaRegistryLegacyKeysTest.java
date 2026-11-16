package io.floci.aws.services.glue.schemaregistry;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.glue.schemaregistry.model.Registry;
import io.floci.aws.services.glue.schemaregistry.model.RegistryId;
import io.floci.aws.services.glue.schemaregistry.model.Schema;
import io.floci.aws.services.glue.schemaregistry.model.SchemaId;
import io.floci.aws.services.glue.schemaregistry.model.SchemaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Registries and schemas stored before they were keyed by Region, under their bare name and {@code registry:schema},
 * move at startup onto the key of the Region their ARN names, or the default Region's when they have no ARN. Each is
 * then listed and reached there, its versions with it, and a schema of another Region than its registry keeps a copy
 * of that registry in its own Region. The stores are account stores, as the storage factory gives the service.
 */
class GlueSchemaRegistryLegacyKeysTest {

    private static final String ACCOUNT = "000000000000";
    private static final String DEFAULT = "us-east-1";
    private static final String FRANKFURT = "eu-central-1";
    private static final String DEFINITION =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    @Test
    void storedRegistriesAndSchemasMoveToTheRegionTheirArnNames() {
        AccountAwareStorageBackend<Registry> registries = AccountAwareStorageBackend.inMemory(ACCOUNT);
        AccountAwareStorageBackend<Schema> schemas = AccountAwareStorageBackend.inMemory(ACCOUNT);
        AccountAwareStorageBackend<SchemaVersion> versions = AccountAwareStorageBackend.inMemory(ACCOUNT);
        Registry frankfurt = new Registry("reg");
        frankfurt.setRegistryArn("arn:aws:glue:" + FRANKFURT + ":" + ACCOUNT + ":registry/reg");
        registries.put("reg", frankfurt);
        registries.put("bare", new Registry("bare"));
        Schema schema = new Schema();
        schema.setRegistryName("reg");
        schema.setRegistryArn(frankfurt.getRegistryArn());
        schema.setSchemaName("users");
        schema.setSchemaArn("arn:aws:glue:" + FRANKFURT + ":" + ACCOUNT + ":schema/reg/users");
        schema.setDataFormat("AVRO");
        schema.setCompatibility("BACKWARD");
        schema.setLatestSchemaVersion(1L);
        schema.setNextSchemaVersion(2L);
        schemas.put("reg:users", schema);
        SchemaVersion version = new SchemaVersion();
        version.setSchemaVersionId("version-1");
        version.setSchemaArn(schema.getSchemaArn());
        version.setVersionNumber(1L);
        version.setStatus("AVAILABLE");
        version.setSchemaDefinition(DEFINITION);
        version.setDataFormat("AVRO");
        version.setCreatedTime(Instant.parse("2026-11-01T00:00:00Z"));
        versions.put("version-1", version);

        GlueSchemaRegistryService service = new GlueSchemaRegistryService(registries, schemas, versions,
                AccountAwareStorageBackend.inMemory(ACCOUNT), new RegionResolver(DEFAULT, ACCOUNT));
        service.afterCdiInit();

        assertEquals(Set.of(FRANKFURT + "::reg", DEFAULT + "::bare"), registries.keysForAccount(ACCOUNT));
        assertEquals(Set.of(FRANKFURT + "::reg:users"), schemas.keysForAccount(ACCOUNT));
        assertEquals(List.of("reg"), service.listRegistries(FRANKFURT).stream().map(Registry::getRegistryName).toList());
        assertEquals(List.of("bare"), service.listRegistries(DEFAULT).stream().map(Registry::getRegistryName).toList());
        assertEquals(List.of("users"), service.listSchemas(new RegistryId("reg", null), FRANKFURT).stream()
                .map(Schema::getSchemaName).toList());
        assertEquals("version-1", service.getSchemaByDefinition(new SchemaId("reg", "users", null), DEFINITION,
                FRANKFURT).getSchemaVersionId());
        assertEquals("version-1", service.getSchemaVersion(null, "version-1", null, false, FRANKFURT)
                .getSchemaVersionId());
    }

    /** The copy's ARN names its Region whether the registry was stored with an ARN or, the default Region's, without. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSchemaOfAnotherRegionThanItsRegistryKeepsACopyOfItsRegistryThere(boolean storedWithAnArn) {
        AccountAwareStorageBackend<Registry> registries = AccountAwareStorageBackend.inMemory(ACCOUNT);
        AccountAwareStorageBackend<Schema> schemas = AccountAwareStorageBackend.inMemory(ACCOUNT);
        Registry home = new Registry("orders");
        home.setRegistryArn(storedWithAnArn ? "arn:aws:glue:" + DEFAULT + ":" + ACCOUNT + ":registry/orders" : null);
        home.setDescription("orders");
        registries.put("orders", home);
        // Every Region saw every registry, so a schema of it could be created in another Region.
        Schema schema = new Schema();
        schema.setRegistryName("orders");
        schema.setRegistryArn(home.getRegistryArn());
        schema.setSchemaName("o1");
        schema.setSchemaArn("arn:aws:glue:" + FRANKFURT + ":" + ACCOUNT + ":schema/orders/o1");
        schema.setDataFormat("AVRO");
        schemas.put("orders:o1", schema);

        GlueSchemaRegistryService service = new GlueSchemaRegistryService(registries, schemas, AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), new RegionResolver(DEFAULT, ACCOUNT));
        service.afterCdiInit();

        assertEquals(Set.of(DEFAULT + "::orders", FRANKFURT + "::orders"), registries.keysForAccount(ACCOUNT));
        Registry copy = service.getRegistry(new RegistryId("orders", null), FRANKFURT);
        assertEquals("arn:aws:glue:" + FRANKFURT + ":" + ACCOUNT + ":registry/orders", copy.getRegistryArn());
        assertEquals("orders", copy.getDescription());
        Schema moved = service.getSchema(new SchemaId("orders", "o1", null), FRANKFURT);
        assertEquals(copy.getRegistryArn(), moved.getRegistryArn());
        assertEquals(List.of(), service.listSchemas(new RegistryId("orders", null), DEFAULT));
    }
}
