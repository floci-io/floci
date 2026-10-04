package io.github.hectorvent.floci.services.glue.schemaregistry;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.MetadataInfo;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.Registry;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.RegistryId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.Schema;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GlueSchemaRegistryServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    private GlueSchemaRegistryService service;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        service = new GlueSchemaRegistryService(new InMemoryStorage<>(), regionResolver);
    }

    @Test
    void createRegistryReturnsRegistryWithArnAndStatusAvailable() {
        Registry registry = service.createRegistry("my-registry", "desc", Map.of("env", "test"), REGION);

        assertEquals("my-registry", registry.getRegistryName());
        assertEquals("desc", registry.getDescription());
        assertEquals("AVAILABLE", registry.getStatus());
        assertEquals(Map.of("env", "test"), registry.getTags());
        assertEquals("arn:aws:glue:us-east-1:" + ACCOUNT_ID + ":registry/my-registry", registry.getRegistryArn());
        assertNotNull(registry.getCreatedTime());
        assertNotNull(registry.getUpdatedTime());
    }

    @Test
    void createRegistryRejectsDuplicate() {
        service.createRegistry("dup", null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRegistry("dup", null, null, REGION));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createRegistryRejectsBlankName() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRegistry("", null, null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void createRegistryRejectsNullName() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRegistry(null, null, null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void createRegistryRejectsInvalidCharacters() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRegistry("bad name with spaces", null, null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void createRegistryAllowsDotAndHashCharacters() {
        for (String name : List.of("valid.name", "valid#name")) {
            Registry registry = service.createRegistry(name, null, null, REGION);
            assertEquals(name, registry.getRegistryName());
        }
    }

    @Test
    void createRegistryRejectsExcessiveLength() {
        String tooLong = "a".repeat(256);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRegistry(tooLong, null, null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void getRegistryByNameReturnsExisting() {
        service.createRegistry("r1", "d1", null, REGION);

        Registry registry = service.getRegistry(new RegistryId("r1", null), REGION);

        assertEquals("r1", registry.getRegistryName());
        assertEquals("d1", registry.getDescription());
    }

    @Test
    void getRegistryByArnReturnsExisting() {
        service.createRegistry("r1", null, null, REGION);
        String arn = "arn:aws:glue:us-east-1:" + ACCOUNT_ID + ":registry/r1";

        Registry registry = service.getRegistry(new RegistryId(null, arn), REGION);

        assertEquals("r1", registry.getRegistryName());
    }

    @Test
    void getRegistryNotFoundThrows() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getRegistry(new RegistryId("missing", null), REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void getRegistryWithNullIdAutoCreatesDefaultRegistry() {
        Registry registry = service.getRegistry(null, REGION);

        assertEquals("default-registry", registry.getRegistryName());
        assertEquals("AVAILABLE", registry.getStatus());
        assertEquals(1, service.listRegistries().size());
    }

    @Test
    void getRegistryWithEmptyIdAutoCreatesDefaultRegistry() {
        Registry registry = service.getRegistry(new RegistryId(null, null), REGION);

        assertEquals("default-registry", registry.getRegistryName());
    }

    @Test
    void getRegistryRejectsMalformedArn() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getRegistry(new RegistryId(null, "not-an-arn"), REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void listRegistriesReturnsAll() {
        service.createRegistry("a", null, null, REGION);
        service.createRegistry("b", null, null, REGION);

        List<Registry> registries = service.listRegistries();

        assertEquals(2, registries.size());
    }

    @Test
    void listRegistriesPaginatesWithNextToken() {
        service.createRegistry("a", null, null, REGION);
        service.createRegistry("b", null, null, REGION);
        service.createRegistry("c", null, null, REGION);

        GlueSchemaRegistryService.Page<Registry> first = service.listRegistries(2, null);
        GlueSchemaRegistryService.Page<Registry> second = service.listRegistries(2, first.nextToken());

        assertEquals(2, first.items().size());
        assertEquals("2", first.nextToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
        assertEquals("c", second.items().get(0).getRegistryName());
    }

    @Test
    void listRegistriesEmptyByDefault() {
        assertTrue(service.listRegistries().isEmpty());
    }

    @Test
    void updateRegistryChangesDescriptionAndUpdatedTime() throws InterruptedException {
        service.createRegistry("r1", "old", null, REGION);
        java.time.Instant beforeTime = service.getRegistry(new RegistryId("r1", null), REGION).getUpdatedTime();
        Thread.sleep(10);

        Registry updated = service.updateRegistry(new RegistryId("r1", null), "new", REGION);

        assertEquals("new", updated.getDescription());
        assertTrue(updated.getUpdatedTime().isAfter(beforeTime),
                "updatedTime should advance: before=" + beforeTime + " after=" + updated.getUpdatedTime());
    }

    @Test
    void deleteRegistryRemovesFromStore() {
        service.createRegistry("r1", null, null, REGION);
        Registry deleted = service.deleteRegistry(new RegistryId("r1", null), REGION);

        assertEquals("DELETING", deleted.getStatus());
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getRegistry(new RegistryId("r1", null), REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void deleteRegistryCascadesToSchemasVersionsAndMetadata() {
        service.createRegistry("r1", null, null, REGION);
        SchemaVersion first = service.createSchema(new RegistryId("r1", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();
        service.putSchemaVersionMetadata(first.getSchemaVersionId(), "team", "platform");

        service.deleteRegistry(new RegistryId("r1", null), REGION);
        service.createRegistry("r1", null, null, REGION);

        assertTrue(service.listSchemas(new RegistryId("r1", null), REGION).isEmpty());
        AwsException versionEx = assertThrows(AwsException.class, () ->
                service.getSchemaVersion(null, first.getSchemaVersionId(), null, false, REGION));
        assertEquals("EntityNotFoundException", versionEx.getErrorCode());
        AwsException metadataEx = assertThrows(AwsException.class, () ->
                service.querySchemaVersionMetadata(first.getSchemaVersionId(), null));
        assertEquals("EntityNotFoundException", metadataEx.getErrorCode());
    }

    @Test
    void deleteRegistryNotFoundThrows() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteRegistry(new RegistryId("nope", null), REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void createRegistryWithNullDescriptionAndTagsSucceeds() {
        Registry registry = service.createRegistry("r1", null, null, REGION);

        assertEquals("r1", registry.getRegistryName());
        assertNull(registry.getDescription());
        assertNull(registry.getTags());
    }

    // ---- Schema / SchemaVersion ----

    private static final String AVRO_V1 =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"com.example\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_V2_BACKWARD_OK =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"com.example\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}";

    private static final String AVRO_V2_BACKWARD_BAD =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"com.example\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"}]}";

    private Registry preCreateRegistry() {
        return service.createRegistry("reg", null, null, REGION);
    }

    @Test
    void createSchemaCreatesV1AndReturnsAvailable() {
        preCreateRegistry();

        GlueSchemaRegistryService.SchemaWithFirstVersion result = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", "desc", AVRO_V1, null, REGION);

        Schema schema = result.schema();
        SchemaVersion v1 = result.firstVersion();
        assertEquals("users", schema.getSchemaName());
        assertEquals("reg", schema.getRegistryName());
        assertEquals("AVRO", schema.getDataFormat());
        assertEquals("BACKWARD", schema.getCompatibility());
        assertEquals("AVAILABLE", schema.getSchemaStatus());
        assertEquals(1L, schema.getLatestSchemaVersion());
        assertEquals(2L, schema.getNextSchemaVersion());
        assertEquals("arn:aws:glue:us-east-1:" + ACCOUNT_ID + ":schema/reg/users", schema.getSchemaArn());
        assertEquals(1L, v1.getVersionNumber());
        assertEquals("AVAILABLE", v1.getStatus());
        assertNotNull(v1.getSchemaVersionId());
    }

    @Test
    void createSchemaWithoutRegistryAutoCreatesDefaultRegistry() {
        GlueSchemaRegistryService.SchemaWithFirstVersion result = service.createSchema(null, "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        assertEquals("default-registry", result.schema().getRegistryName());
    }

    @Test
    void createSchemaDefaultsToBackwardWhenCompatibilityOmitted() {
        preCreateRegistry();
        GlueSchemaRegistryService.SchemaWithFirstVersion result = service.createSchema(new RegistryId("reg", null),
                "s1", "AVRO", null, null, AVRO_V1, null, REGION);

        assertEquals("BACKWARD", result.schema().getCompatibility());
    }

    @Test
    void createSchemaRejectsDuplicate() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSchema(new RegistryId("reg", null),
                        "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
    }

    @Test
    void createSchemaRejectsInvalidAvroDefinition() {
        preCreateRegistry();
        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSchema(new RegistryId("reg", null),
                        "users", "AVRO", "BACKWARD", null, "{not-valid-avro", null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void createSchemaRejectsUnknownDataFormat() {
        preCreateRegistry();
        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSchema(new RegistryId("reg", null),
                        "users", "BOGUS", "BACKWARD", null, AVRO_V1, null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void createSchemaRejectsUnknownCompatibility() {
        preCreateRegistry();
        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSchema(new RegistryId("reg", null),
                        "users", "AVRO", "WAT", null, AVRO_V1, null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void createSchemaAllowsDotAndHashCharacters() {
        preCreateRegistry();
        for (String name : List.of("valid.name", "valid#name")) {
            Schema schema = service.createSchema(new RegistryId("reg", null),
                    name, "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).schema();
            assertEquals(name, schema.getSchemaName());
        }
    }

    @Test
    void registerSchemaVersionAppendsBackwardCompatibleVersion() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        SchemaVersion v2 = service.registerSchemaVersion(
                new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_OK, REGION);

        assertEquals(2L, v2.getVersionNumber());
        assertEquals("AVAILABLE", v2.getStatus());
    }

    @Test
    void registerSchemaVersionRejectsBackwardIncompatibleEvolution() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.registerSchemaVersion(
                        new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_BAD, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void registerSchemaVersionDuplicateDefinitionReturnsExistingId() {
        preCreateRegistry();
        SchemaVersion v1 = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();

        SchemaVersion same = service.registerSchemaVersion(
                new SchemaId("reg", "users", null), AVRO_V1, REGION);

        assertEquals(v1.getSchemaVersionId(), same.getSchemaVersionId());
        assertEquals(1L, same.getVersionNumber());
    }

    @Test
    void recordLevelCustomAttributesReuseExistingVersionAndRemainDiscoverable() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();
        SchemaId schemaId = new SchemaId("reg", "users", null);

        for (String attribute : List.of("\"order\":\"descending\"", "\"default\":\"unused\"",
                "\"symbols\":[\"IGNORED\"]", "\"size\":8")) {
            String definition = AVRO_V1.replace("\"fields\"", attribute + ",\"fields\"");
            assertEquals(first.getSchemaVersionId(),
                    service.registerSchemaVersion(schemaId, definition, REGION).getSchemaVersionId(), attribute);
            assertEquals(first.getSchemaVersionId(),
                    service.getSchemaByDefinition(schemaId, definition, REGION).getSchemaVersionId(), attribute);
        }
        assertEquals(1, service.listSchemaVersions(schemaId, REGION).size());
    }

    @Test
    void legacyAvroDuplicatesPreferEarliestVersionAndSurviveDeletion() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        InMemoryStorage<String, Registry> registryStore = new InMemoryStorage<>();
        InMemoryStorage<String, Schema> schemaStore = new InMemoryStorage<>();
        InMemoryStorage<String, SchemaVersion> versionStore = new InMemoryStorage<>() {
            @Override
            public List<SchemaVersion> scan(Predicate<String> keyFilter) {
                List<SchemaVersion> versions = super.scan(keyFilter);
                versions.sort((left, right) -> left.getVersionNumber().compareTo(right.getVersionNumber()));
                return versions;
            }
        };
        service = new GlueSchemaRegistryService(registryStore, schemaStore, versionStore,
                new InMemoryStorage<>(), regionResolver);
        service.createRegistry("reg", null, null, REGION);
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();
        SchemaId schemaId = new SchemaId("reg", "users", null);
        SchemaVersion second = service.registerSchemaVersion(schemaId, AVRO_V2_BACKWARD_OK, REGION);
        String withCustomAttribute = AVRO_V1.replace("\"type\":\"long\"",
                "\"type\":\"long\",\"x-field\":\"legacy\"");
        second.setSchemaDefinition(withCustomAttribute);
        versionStore.put(second.getSchemaVersionId(), second);

        service = new GlueSchemaRegistryService(registryStore, schemaStore, versionStore,
                new InMemoryStorage<>(), regionResolver);
        service.afterCdiInit();

        assertEquals(first.getSchemaVersionId(),
                service.getSchemaByDefinition(schemaId, withCustomAttribute, REGION).getSchemaVersionId());
        assertEquals(first.getSchemaVersionId(),
                service.registerSchemaVersion(schemaId, withCustomAttribute, REGION).getSchemaVersionId());

        service.updateSchema(schemaId, null, null, 2L, REGION);
        assertNull(service.deleteSchemaVersions(schemaId, "1", REGION).get(0).errorCode());
        assertEquals(second.getSchemaVersionId(),
                service.getSchemaByDefinition(schemaId, AVRO_V1, REGION).getSchemaVersionId());
        assertEquals(second.getSchemaVersionId(),
                service.registerSchemaVersion(schemaId, AVRO_V1, REGION).getSchemaVersionId());
    }

    @Test
    void lookupWaitsForLegacyDuplicateHashReplacement() throws Exception {
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch finishReplacement = new CountDownLatch(1);
        AtomicReference<String> replacementId = new AtomicReference<>();
        AtomicReference<Thread> deletingThread = new AtomicReference<>();
        OrderedVersionStore versionStore = new OrderedVersionStore() {
            @Override
            public Optional<SchemaVersion> get(String key) {
                if (Thread.currentThread() == deletingThread.get() && key.equals(replacementId.get())) {
                    replacementStarted.countDown();
                    awaitRelease(finishReplacement);
                }
                return super.get(key);
            }
        };
        LegacyAvroVersions versions = legacyAvroVersions(versionStore);
        replacementId.set(versions.second().getSchemaVersionId());
        service.updateSchema(versions.schemaId(), null, null, 2L, REGION);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<GlueSchemaRegistryService.VersionDeletionResult>> deletion = pool.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return service.deleteSchemaVersions(versions.schemaId(), "1", REGION);
            });
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));
            CountDownLatch lookupStarted = new CountDownLatch(1);
            AtomicReference<Thread> lookupThread = new AtomicReference<>();
            Future<SchemaVersion> lookup = pool.submit(() -> {
                lookupThread.set(Thread.currentThread());
                lookupStarted.countDown();
                return service.getSchemaByDefinition(versions.schemaId(), AVRO_V1, REGION);
            });
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
            assertBlockedOnService(lookupThread.get(), lookup);

            finishReplacement.countDown();
            assertNull(deletion.get(5, TimeUnit.SECONDS).get(0).errorCode());
            assertEquals(versions.second().getSchemaVersionId(),
                    lookup.get(5, TimeUnit.SECONDS).getSchemaVersionId());
        } finally {
            finishReplacement.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void deletionWaitsForLookupThatAlreadyReadLegacyVersionId() throws Exception {
        CountDownLatch lookupReadingVersion = new CountDownLatch(1);
        CountDownLatch finishLookup = new CountDownLatch(1);
        AtomicReference<String> firstId = new AtomicReference<>();
        AtomicReference<Thread> lookupThread = new AtomicReference<>();
        OrderedVersionStore versionStore = new OrderedVersionStore() {
            @Override
            public Optional<SchemaVersion> get(String key) {
                if (Thread.currentThread() == lookupThread.get() && key.equals(firstId.get())) {
                    lookupReadingVersion.countDown();
                    awaitRelease(finishLookup);
                }
                return super.get(key);
            }
        };
        LegacyAvroVersions versions = legacyAvroVersions(versionStore);
        firstId.set(versions.first().getSchemaVersionId());
        service.updateSchema(versions.schemaId(), null, null, 2L, REGION);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SchemaVersion> lookup = pool.submit(() -> {
                lookupThread.set(Thread.currentThread());
                return service.getSchemaByDefinition(versions.schemaId(), AVRO_V1, REGION);
            });
            assertTrue(lookupReadingVersion.await(5, TimeUnit.SECONDS));
            CountDownLatch deletionStarted = new CountDownLatch(1);
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<List<GlueSchemaRegistryService.VersionDeletionResult>> deletion = pool.submit(() -> {
                deletingThread.set(Thread.currentThread());
                deletionStarted.countDown();
                return service.deleteSchemaVersions(versions.schemaId(), "1", REGION);
            });
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS));
            assertBlockedOnService(deletingThread.get(), deletion);

            finishLookup.countDown();
            assertEquals(versions.first().getSchemaVersionId(),
                    lookup.get(5, TimeUnit.SECONDS).getSchemaVersionId());
            assertNull(deletion.get(5, TimeUnit.SECONDS).get(0).errorCode());
            assertEquals(versions.second().getSchemaVersionId(),
                    service.getSchemaByDefinition(versions.schemaId(), AVRO_V1, REGION).getSchemaVersionId());
        } finally {
            finishLookup.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void lookupRehashesAfterSchemaIsRecreatedWithDifferentFormat() throws Exception {
        String definition = "{\"type\":\"string\",\"x-note\":\"both formats\"}";
        assertTrue(service.checkSchemaVersionValidity("AVRO", definition).valid());
        assertTrue(service.checkSchemaVersionValidity("JSON", definition).valid());
        assertLookupFindsRecreatedSchema(definition, definition);
    }

    @Test
    void lookupFindsDefinitionInvalidForOldFormatAfterRecreation() throws Exception {
        String jsonDefinition = "{ \"type\": \"object\", \"properties\": { \"k\": { \"type\": \"string\" } } }";
        assertFalse(service.checkSchemaVersionValidity("AVRO", jsonDefinition).valid());
        assertTrue(service.checkSchemaVersionValidity("JSON", jsonDefinition).valid());
        assertFalse(jsonDefinition.equals(SchemaCompatibilityChecker.canonicalize(jsonDefinition, "JSON")));
        assertLookupFindsRecreatedSchema(AVRO_V1, jsonDefinition);
    }

    @Test
    void lookupReportsInternalErrorWhenSchemaFormatKeepsChanging() {
        String definition = "{\"type\":\"string\"}";
        AtomicBoolean alternateFormat = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        InMemoryStorage<String, Schema> schemaStore = new InMemoryStorage<>() {
            @Override
            public Optional<Schema> get(String key) {
                Optional<Schema> schema = super.get(key);
                if (alternateFormat.get() && schema.isPresent()) {
                    schema.get().setDataFormat(reads.getAndIncrement() % 2 == 0 ? "AVRO" : "JSON");
                }
                return schema;
            }
        };
        service = new GlueSchemaRegistryService(new InMemoryStorage<>(), schemaStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT_ID));
        service.createRegistry("reg", null, null, REGION);
        service.createSchema(new RegistryId("reg", null), "users", "AVRO", "NONE",
                null, definition, null, REGION);

        alternateFormat.set(true);
        AwsException error = assertThrows(AwsException.class, () ->
                service.getSchemaByDefinition(new SchemaId("reg", "users", null), definition, REGION));
        assertEquals("InternalServiceException", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    private void assertLookupFindsRecreatedSchema(String oldDefinition, String replacementDefinition) throws Exception {
        CountDownLatch firstSchemaRead = new CountDownLatch(1);
        CountDownLatch finishLookup = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        AtomicReference<Thread> lookupThread = new AtomicReference<>();
        InMemoryStorage<String, Schema> schemaStore = new InMemoryStorage<>() {
            @Override
            public Optional<Schema> get(String key) {
                Optional<Schema> schema = super.get(key);
                if (Thread.currentThread() == lookupThread.get()
                        && key.equals("reg:users") && paused.compareAndSet(false, true)) {
                    firstSchemaRead.countDown();
                    awaitRelease(finishLookup);
                }
                return schema;
            }
        };
        service = new GlueSchemaRegistryService(new InMemoryStorage<>(), schemaStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT_ID));
        service.createRegistry("reg", null, null, REGION);
        SchemaId schemaId = new SchemaId("reg", "users", null);
        service.createSchema(new RegistryId("reg", null), "users", "AVRO", "BACKWARD",
                null, oldDefinition, null, REGION);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SchemaVersion> lookup = pool.submit(() -> {
                lookupThread.set(Thread.currentThread());
                return service.getSchemaByDefinition(schemaId, replacementDefinition, REGION);
            });
            assertTrue(firstSchemaRead.await(5, TimeUnit.SECONDS));
            service.deleteSchema(schemaId, REGION);
            SchemaVersion replacement = service.createSchema(new RegistryId("reg", null),
                    "users", "JSON", "BACKWARD", null, replacementDefinition, null, REGION).firstVersion();

            finishLookup.countDown();
            assertEquals(replacement.getSchemaVersionId(), lookup.get(5, TimeUnit.SECONDS).getSchemaVersionId());
        } finally {
            finishLookup.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private LegacyAvroVersions legacyAvroVersions(OrderedVersionStore versionStore) {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        InMemoryStorage<String, Registry> registryStore = new InMemoryStorage<>();
        InMemoryStorage<String, Schema> schemaStore = new InMemoryStorage<>();
        service = new GlueSchemaRegistryService(registryStore, schemaStore, versionStore,
                new InMemoryStorage<>(), regionResolver);
        service.createRegistry("reg", null, null, REGION);
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();
        SchemaId schemaId = new SchemaId("reg", "users", null);
        SchemaVersion second = service.registerSchemaVersion(schemaId, AVRO_V2_BACKWARD_OK, REGION);
        String withCustomAttribute = AVRO_V1.replace("\"type\":\"long\"",
                "\"type\":\"long\",\"x-field\":\"legacy\"");
        second.setSchemaDefinition(withCustomAttribute);
        versionStore.put(second.getSchemaVersionId(), second);

        service = new GlueSchemaRegistryService(registryStore, schemaStore, versionStore,
                new InMemoryStorage<>(), regionResolver);
        service.afterCdiInit();
        return new LegacyAvroVersions(schemaId, first, second);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertTrue(release.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void assertBlockedOnService(Thread worker, Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !task.isDone()) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(worker.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(service)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Expected worker to block on the schema service monitor");
    }

    private record LegacyAvroVersions(SchemaId schemaId, SchemaVersion first, SchemaVersion second) {}

    private static class OrderedVersionStore extends InMemoryStorage<String, SchemaVersion> {
        @Override
        public List<SchemaVersion> scan(Predicate<String> keyFilter) {
            List<SchemaVersion> versions = super.scan(keyFilter);
            versions.sort((left, right) -> left.getVersionNumber().compareTo(right.getVersionNumber()));
            return versions;
        }
    }

    @Test
    void registerSchemaVersionWithDisabledCompatRejectsNewVersions() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "DISABLED", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.registerSchemaVersion(
                        new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_OK, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void registerSchemaVersionWithNoneCompatAcceptsAnyEvolution() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "NONE", null, AVRO_V1, null, REGION);

        SchemaVersion v2 = service.registerSchemaVersion(
                new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_BAD, REGION);

        assertEquals(2L, v2.getVersionNumber());
    }

    @Test
    void getSchemaVersionByLatest() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();
        SchemaVersion v2 = service.registerSchemaVersion(
                new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_OK, REGION);

        SchemaVersion latest = service.getSchemaVersion(
                new SchemaId("reg", "users", null), null, null, true, REGION);

        assertEquals(v2.getSchemaVersionId(), latest.getSchemaVersionId());
        assertEquals(2L, latest.getVersionNumber());
        assertNotNull(first.getSchemaVersionId());
    }

    @Test
    void getSchemaVersionByNumber() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_OK, REGION);

        SchemaVersion v1 = service.getSchemaVersion(
                new SchemaId("reg", "users", null), null, 1L, false, REGION);

        assertEquals(1L, v1.getVersionNumber());
        assertEquals(AVRO_V1, v1.getSchemaDefinition());
    }

    @Test
    void getSchemaVersionByVersionId() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();

        SchemaVersion fetched = service.getSchemaVersion(
                null, first.getSchemaVersionId(), null, false, REGION);

        assertEquals(first.getSchemaVersionId(), fetched.getSchemaVersionId());
    }

    @Test
    void getSchemaVersionWithoutSelectorThrows() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.getSchemaVersion(new SchemaId("reg", "users", null), null, null, false, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void getSchemaByDefinitionFindsExisting() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();

        SchemaVersion found = service.getSchemaByDefinition(
                new SchemaId("reg", "users", null), AVRO_V1, REGION);

        assertEquals(first.getSchemaVersionId(), found.getSchemaVersionId());
    }

    @Test
    void getSchemaByDefinitionMatchesDespiteWhitespace() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();

        // Same Avro schema but with extra whitespace
        String formatted = AVRO_V1.replace(",", ", ").replace(":", " : ");
        SchemaVersion found = service.getSchemaByDefinition(
                new SchemaId("reg", "users", null), formatted, REGION);

        assertEquals(first.getSchemaVersionId(), found.getSchemaVersionId());
    }

    @Test
    void getSchemaByDefinitionNotFoundThrows() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.getSchemaByDefinition(
                        new SchemaId("reg", "users", null), AVRO_V2_BACKWARD_OK, REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void getSchemaByArnRoundTrips() {
        preCreateRegistry();
        GlueSchemaRegistryService.SchemaWithFirstVersion result = service.createSchema(new RegistryId("reg", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        Schema fetched = service.getSchema(
                new SchemaId(null, null, result.schema().getSchemaArn()), REGION);

        assertEquals("users", fetched.getSchemaName());
    }

    @Test
    void registerSchemaVersionInUnknownRegistryThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.registerSchemaVersion(
                        new SchemaId("missing", "users", null), AVRO_V1, REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    // ---- PR 3: admin actions ----

    @Test
    void listSchemasReturnsSchemasInRegistry() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.createSchema(new RegistryId("reg", null), "b", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        List<Schema> schemas = service.listSchemas(new RegistryId("reg", null), REGION);
        assertEquals(2, schemas.size());
    }

    @Test
    void listSchemasOnlyReturnsTargetRegistry() {
        preCreateRegistry();
        service.createRegistry("other", null, null, REGION);
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.createSchema(new RegistryId("other", null), "x", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        List<Schema> regSchemas = service.listSchemas(new RegistryId("reg", null), REGION);
        assertEquals(1, regSchemas.size());
        assertEquals("a", regSchemas.get(0).getSchemaName());
    }

    @Test
    void listSchemasPaginatesWithinRegistry() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.createSchema(new RegistryId("reg", null), "b", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.createSchema(new RegistryId("reg", null), "c", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        GlueSchemaRegistryService.Page<Schema> first = service.listSchemas(new RegistryId("reg", null), REGION, 2, null);
        GlueSchemaRegistryService.Page<Schema> second = service.listSchemas(new RegistryId("reg", null), REGION, 2, first.nextToken());

        assertEquals(2, first.items().size());
        assertEquals("2", first.nextToken());
        assertEquals(1, second.items().size());
        assertEquals("c", second.items().get(0).getSchemaName());
    }

    @Test
    void updateSchemaChangesCompatibility() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        Schema updated = service.updateSchema(new SchemaId("reg", "a", null), "FORWARD", null, REGION);
        assertEquals("FORWARD", updated.getCompatibility());
    }

    @Test
    void updateSchemaChangesDescription() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", "old", AVRO_V1, null, REGION);

        Schema updated = service.updateSchema(new SchemaId("reg", "a", null), null, "new", REGION);
        assertEquals("new", updated.getDescription());
        assertEquals("BACKWARD", updated.getCompatibility());
    }

    @Test
    void updateSchemaChangesCheckpointVersion() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);

        Schema updated = service.updateSchema(new SchemaId("reg", "a", null), null, null, 2L, REGION);

        assertEquals(2L, updated.getSchemaCheckpoint());
    }

    @Test
    void updateSchemaRejectsUnknownCompatibility() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.updateSchema(new SchemaId("reg", "a", null), "BOGUS", null, REGION));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void listSchemaVersionsReturnsInVersionOrder() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);

        List<SchemaVersion> versions = service.listSchemaVersions(new SchemaId("reg", "a", null), REGION);
        assertEquals(2, versions.size());
        assertEquals(1L, versions.get(0).getVersionNumber());
        assertEquals(2L, versions.get(1).getVersionNumber());
    }

    @Test
    void listSchemaVersionsPaginatesInVersionOrder() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "NONE", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null),
                AVRO_V2_BACKWARD_OK.replace("email", "phone"), REGION);

        GlueSchemaRegistryService.Page<SchemaVersion> first = service.listSchemaVersions(new SchemaId("reg", "a", null), REGION, 2, null);
        GlueSchemaRegistryService.Page<SchemaVersion> second = service.listSchemaVersions(new SchemaId("reg", "a", null), REGION, 2, first.nextToken());

        assertEquals(2, first.items().size());
        assertEquals("2", first.nextToken());
        assertEquals(1, second.items().size());
        assertEquals(3L, second.items().get(0).getVersionNumber());
    }

    @Test
    void deleteSchemaCascadesToVersions() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();

        service.deleteSchema(new SchemaId("reg", "a", null), REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.getSchema(new SchemaId("reg", "a", null), REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
        AwsException ex2 = assertThrows(AwsException.class, () ->
                service.getSchemaVersion(null, first.getSchemaVersionId(), null, false, REGION));
        assertEquals("EntityNotFoundException", ex2.getErrorCode());
    }

    @Test
    void deleteSchemaVersionsRemovesGivenVersions() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);
        service.updateSchema(new SchemaId("reg", "a", null), null, null, 2L, REGION);

        List<GlueSchemaRegistryService.VersionDeletionResult> results = service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1", REGION);
        assertEquals(1, results.size());
        assertNull(results.get(0).errorCode());

        List<SchemaVersion> remaining = service.listSchemaVersions(new SchemaId("reg", "a", null), REGION);
        assertEquals(1, remaining.size());
        assertEquals(2L, remaining.get(0).getVersionNumber());
    }

    @Test
    void deleteSchemaVersionsParsesRanges() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "NONE", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null),
                AVRO_V2_BACKWARD_OK.replace("email", "phone"), REGION);
        service.updateSchema(new SchemaId("reg", "a", null), null, null, 3L, REGION);

        List<GlueSchemaRegistryService.VersionDeletionResult> results = service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1-2", REGION);
        assertEquals(2, results.size());
        assertEquals(1, service.listSchemaVersions(new SchemaId("reg", "a", null), REGION).size());
    }

    @Test
    void deleteSchemaVersionsRejectsCheckpointVersion() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);

        List<GlueSchemaRegistryService.VersionDeletionResult> results = service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1", REGION);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).versionNumber());
        assertEquals("InvalidInputException", results.get(0).errorCode());
        assertEquals(2, service.listSchemaVersions(new SchemaId("reg", "a", null), REGION).size());
    }

    @Test
    void deleteSchemaVersionsRejectsExpandedRangesOverTwentyFiveVersions() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1-26", REGION));

        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void deleteSchemaVersionsRejectsHugeRangeBeforeExpansion() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1-9223372036854775807", REGION));

        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void deleteSchemaVersionsAcceptsRangeOfExactlyTwentyFiveVersions() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        List<GlueSchemaRegistryService.VersionDeletionResult> results = service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1-25", REGION);

        assertEquals(25, results.size());
    }

    @Test
    void deleteSchemaVersionsReportsErrorsForMissingVersions() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        List<GlueSchemaRegistryService.VersionDeletionResult> results = service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1,99", REGION);
        assertEquals(2, results.size());
        // Version 1 is the latest and only — cannot delete (latest constraint).
        assertEquals(99L, results.get(1).versionNumber());
        assertNotNull(results.get(1).errorCode());
    }

    @Test
    void getSchemaVersionsDiffReturnsTextDiff() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);

        String diff = service.getSchemaVersionsDiff(new SchemaId("reg", "a", null), 1L, 2L, REGION);
        assertTrue(diff.contains("---"));
        assertTrue(diff.contains("+++"));
    }

    @Test
    void getSchemaVersionsDiffIdenticalReturnsEmpty() {
        preCreateRegistry();
        service.createSchema(new RegistryId("reg", null), "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        String diff = service.getSchemaVersionsDiff(new SchemaId("reg", "a", null), 1L, 1L, REGION);
        assertEquals("", diff);
    }

    @Test
    void checkSchemaVersionValidityForValidAvro() {
        GlueSchemaRegistryService.CheckValidityResult r = service.checkSchemaVersionValidity("AVRO", AVRO_V1);
        assertTrue(r.valid());
        assertNull(r.error());
    }

    @Test
    void checkSchemaVersionValidityForInvalidAvro() {
        GlueSchemaRegistryService.CheckValidityResult r = service.checkSchemaVersionValidity("AVRO", "{not-valid-avro");
        assertFalse(r.valid());
        assertNotNull(r.error());
    }

    @Test
    void checkSchemaVersionValidityRejectsUnknownDataFormat() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.checkSchemaVersionValidity("BOGUS", AVRO_V1));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    // ---- PR 4: metadata + tags ----

    private String firstVersionId() {
        preCreateRegistry();
        return service.createSchema(new RegistryId("reg", null),
                "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion().getSchemaVersionId();
    }

    @Test
    void putSchemaVersionMetadataStoresKeyValue() {
        String svId = firstVersionId();

        GlueSchemaRegistryService.MetadataPutResult r = service.putSchemaVersionMetadata(svId, "team", "platform");

        assertEquals("team", r.metadataKey());
        assertEquals("platform", r.metadataValue());
        Map<String, MetadataInfo> map = service.querySchemaVersionMetadata(svId, null);
        assertEquals("platform", map.get("team").getMetadataValue());
    }

    @Test
    void putSchemaVersionMetadataDuplicateKeyValueRejected() {
        String svId = firstVersionId();
        service.putSchemaVersionMetadata(svId, "team", "platform");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.putSchemaVersionMetadata(svId, "team", "platform"));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
    }

    @Test
    void putSchemaVersionMetadataSameKeyNewValueDemotesOld() {
        String svId = firstVersionId();
        service.putSchemaVersionMetadata(svId, "team", "platform");

        service.putSchemaVersionMetadata(svId, "team", "data");

        Map<String, MetadataInfo> map = service.querySchemaVersionMetadata(svId, null);
        assertEquals("data", map.get("team").getMetadataValue());
        assertEquals(1, map.get("team").getOtherMetadataValueList().size());
        assertEquals("platform", map.get("team").getOtherMetadataValueList().get(0).getMetadataValue());
    }

    @Test
    void putSchemaVersionMetadataForUnknownVersionThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.putSchemaVersionMetadata("does-not-exist", "team", "platform"));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void removeSchemaVersionMetadataCurrentPromotesNext() {
        String svId = firstVersionId();
        service.putSchemaVersionMetadata(svId, "team", "platform");
        service.putSchemaVersionMetadata(svId, "team", "data");

        service.removeSchemaVersionMetadata(svId, "team", "data");

        Map<String, MetadataInfo> map = service.querySchemaVersionMetadata(svId, null);
        assertEquals("platform", map.get("team").getMetadataValue());
        assertNull(map.get("team").getOtherMetadataValueList());
    }

    @Test
    void removeSchemaVersionMetadataLastValueDeletesKey() {
        String svId = firstVersionId();
        service.putSchemaVersionMetadata(svId, "team", "platform");

        service.removeSchemaVersionMetadata(svId, "team", "platform");

        assertTrue(service.querySchemaVersionMetadata(svId, null).isEmpty());
    }

    @Test
    void removeSchemaVersionMetadataNotFoundThrows() {
        String svId = firstVersionId();
        AwsException ex = assertThrows(AwsException.class, () ->
                service.removeSchemaVersionMetadata(svId, "missing", "x"));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void querySchemaVersionMetadataWithFilterReturnsSubset() {
        String svId = firstVersionId();
        service.putSchemaVersionMetadata(svId, "team", "platform");
        service.putSchemaVersionMetadata(svId, "owner", "alice");

        Map<String, MetadataInfo> filtered = service.querySchemaVersionMetadata(svId,
                List.of(new GlueSchemaRegistryService.MetadataKeyValueFilter("team", null)));

        assertEquals(1, filtered.size());
        assertTrue(filtered.containsKey("team"));
    }

    @Test
    void deletingSchemaVersionRemovesMetadata() {
        preCreateRegistry();
        SchemaVersion first = service.createSchema(new RegistryId("reg", null),
                "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).firstVersion();
        service.registerSchemaVersion(new SchemaId("reg", "a", null), AVRO_V2_BACKWARD_OK, REGION);
        service.updateSchema(new SchemaId("reg", "a", null), null, null, 2L, REGION);
        service.putSchemaVersionMetadata(first.getSchemaVersionId(), "team", "platform");

        service.deleteSchemaVersions(new SchemaId("reg", "a", null), "1", REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.querySchemaVersionMetadata(first.getSchemaVersionId(), null));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void tagAndGetTagsForRegistry() {
        Registry r = service.createRegistry("reg", null, null, REGION);

        service.tagResource(r.getRegistryArn(), Map.of("env", "prod", "team", "platform"));

        Map<String, String> tags = service.getTags(r.getRegistryArn());
        assertEquals(2, tags.size());
        assertEquals("prod", tags.get("env"));
    }

    @Test
    void tagAndGetTagsForSchema() {
        preCreateRegistry();
        Schema schema = service.createSchema(new RegistryId("reg", null),
                "a", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION).schema();

        service.tagResource(schema.getSchemaArn(), Map.of("owner", "alice"));

        Map<String, String> tags = service.getTags(schema.getSchemaArn());
        assertEquals("alice", tags.get("owner"));
    }

    @Test
    void untagResourceRemovesKeys() {
        Registry r = service.createRegistry("reg", null, null, REGION);
        service.tagResource(r.getRegistryArn(), Map.of("env", "prod", "team", "platform"));

        service.untagResource(r.getRegistryArn(), List.of("env"));

        Map<String, String> tags = service.getTags(r.getRegistryArn());
        assertEquals(1, tags.size());
        assertTrue(tags.containsKey("team"));
    }

    @Test
    void tagResourceWithMalformedArnThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.tagResource("not-an-arn", Map.of("a", "b")));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void tagResourceUnknownRegistryThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.tagResource(
                        "arn:aws:glue:us-east-1:" + ACCOUNT_ID + ":registry/missing",
                        Map.of("a", "b")));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }
}
