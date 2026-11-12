package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RekognitionCollectionServiceTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiMockConfigLoader mocks = mock(AiMockConfigLoader.class);
    private final RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
    private RekognitionCollectionService service;

    @BeforeEach
    void setUp() throws Exception {
        when(mocks.lookup("rekognition", "photos/face.jpg", "DetectFaces"))
                .thenReturn(Optional.of(mapper.readTree("""
                        {"FaceDetails":[
                          {"Confidence":99.5,"BoundingBox":{"Width":0.1,"Height":0.1,"Top":0.5,"Left":0.5}},
                          {"Confidence":99.9,"BoundingBox":{"Width":0.4,"Height":0.4,"Top":0.1,"Left":0.1}}
                        ]}
                        """)));
        service = new RekognitionCollectionService(storage("memory", Path.of("target/rekognition-unit")),
                mapper, resolver, mocks);
    }

    @Test
    void collectionLifecycleAndRegionIsolation() {
        ObjectNode created = service.createCollection(request(), REGION);
        assertEquals("arn:aws:rekognition:us-east-1:" + ACCOUNT + ":collection/tenant", created.path("CollectionArn").asText());
        assertEquals(200, created.path("StatusCode").asInt());
        assertError("ResourceAlreadyExistsException", () -> service.createCollection(request(), REGION));
        ObjectNode described = service.describeCollection(request(), REGION);
        assertTrue(described.path("CreationTimestamp").asDouble() > 1_700_000_000);
        assertEquals(created.path("CollectionArn"), described.path("CollectionARN"));
        assertEquals(0, described.path("FaceCount").asInt());
        assertEquals(0, service.listCollections(mapper.createObjectNode(), "eu-west-1").path("CollectionIds").size());
        assertError("ResourceNotFoundException", () -> service.describeCollection(request(), "eu-west-1"));
        service.createCollection(request(), "eu-west-1");
        service.deleteCollection(request(), REGION);
        assertError("ResourceNotFoundException", () -> service.deleteCollection(request(), REGION));
        assertEquals(0, service.describeCollection(request(), "eu-west-1").path("FaceCount").asInt());
    }

    @Test
    void indexedRecordsAreStableAndSearchDoesNotMatchOtherPositions() {
        service.createCollection(request(), REGION);
        ObjectNode indexed = service.indexFaces(imageRequest().put("ExternalImageId", "first"), REGION, "photos/face.jpg");
        assertEquals(2, indexed.path("FaceRecords").size());
        assertEquals(0.4, indexed.at("/FaceRecords/0/Face/BoundingBox/Width").asDouble());
        String faceId = indexed.at("/FaceRecords/0/Face/FaceId").asText();
        ObjectNode repeated = service.indexFaces(imageRequest().put("ExternalImageId", "first"), REGION, "photos/face.jpg");
        assertEquals(indexed, repeated);
        service.indexFaces(imageRequest().put("ExternalImageId", "second"), REGION, "photos/face.jpg");
        assertEquals(4, service.describeCollection(request(), REGION).path("FaceCount").asInt());
        ObjectNode found = service.searchFaces(request().put("FaceId", faceId), REGION);
        assertEquals(1, found.path("FaceMatches").size());
        assertEquals("second", found.at("/FaceMatches/0/Face/ExternalImageId").asText());
        assertEquals(100.0, found.at("/FaceMatches/0/Similarity").asDouble());
        assertEquals(2, service.searchFacesByImage(imageRequest(), REGION, "photos/face.jpg").path("FaceMatches").size());
        ObjectNode deletes = request();
        String unknown = "00000000-0000-0000-0000-000000000000";
        deletes.putArray("FaceIds").add(faceId).add(unknown);
        ObjectNode deleted = service.deleteFaces(deletes, REGION);
        assertEquals(faceId, deleted.at("/DeletedFaces/0").asText());
        assertEquals(unknown, deleted.at("/UnsuccessfulFaceDeletions/0/FaceId").asText());
        assertEquals("FACE_NOT_FOUND", deleted.at("/UnsuccessfulFaceDeletions/0/Reasons/0").asText());
        assertError("InvalidParameterException", () -> service.searchFaces(request().put("FaceId", faceId), REGION));
        service.deleteCollection(request(), REGION);
        service.createCollection(request(), REGION);
        assertEquals(0, service.listFaces(request(), REGION).path("Faces").size());
    }

    @Test
    void maxFacesReportsUnindexedFacesAndEmptyDetectionDoesNotInventFaces() {
        service.createCollection(request(), REGION);
        ObjectNode result = service.indexFaces(imageRequest().put("MaxFaces", 1), REGION, "photos/face.jpg");
        assertEquals(1, result.path("FaceRecords").size());
        assertEquals("EXCEEDS_MAX_FACES", result.at("/UnindexedFaces/0/Reasons/0").asText());
        ObjectNode empty = service.indexFaces(imageRequest(), REGION, null);
        assertEquals(0, empty.path("FaceRecords").size());
        assertError("InvalidParameterException", () -> service.searchFacesByImage(imageRequest(), REGION, null));
    }

    @Test
    void paginationIsCompleteScopedAndSupportsMaximumLengthNames() {
        for (int i = 0; i < 3; i++) {
            service.createCollection(request().put("CollectionId", "x".repeat(254) + i), REGION);
        }
        ObjectNode pageRequest = mapper.createObjectNode().put("MaxResults", 1);
        Set<String> ids = new HashSet<>();
        String token = null;
        do {
            if (token != null) {
                pageRequest.put("NextToken", token);
            }
            ObjectNode result = service.listCollections(pageRequest, REGION);
            assertEquals(result.path("CollectionIds").size(), result.path("FaceModelVersions").size());
            result.path("CollectionIds").forEach(id -> assertTrue(ids.add(id.asText())));
            token = result.path("NextToken").asText(null);
            if (token != null) {
                assertTrue(token.length() <= 255);
                String cursor = token;
                assertError("InvalidPaginationTokenException", () -> service.listCollections(
                        mapper.createObjectNode().put("NextToken", cursor), "eu-west-1"));
            }
        } while (token != null);
        assertEquals(3, ids.size());
        service.createCollection(request(), REGION);
        service.indexFaces(imageRequest(), REGION, "photos/face.jpg");
        ObjectNode first = service.listFaces(request().put("MaxResults", 1), REGION);
        ObjectNode second = service.listFaces(request().put("NextToken", first.path("NextToken").asText()), REGION);
        assertEquals(1, second.path("Faces").size());
        assertNotEquals(first.at("/Faces/0/FaceId"), second.at("/Faces/0/FaceId"));
        ObjectNode filtered = request();
        filtered.putArray("FaceIds").add(first.at("/Faces/0/FaceId").asText());
        assertEquals(1, service.listFaces(filtered, REGION).path("Faces").size());
        assertError("InvalidPaginationTokenException", () -> service.listFaces(
                filtered.put("NextToken", first.path("NextToken").asText()), REGION));
    }

    @Test
    void resetClearsCollections() {
        service.createCollection(request(), REGION);
        service.indexFaces(imageRequest(), REGION, "photos/face.jpg");
        service.clear();
        assertError("ResourceNotFoundException", () -> service.describeCollection(request(), REGION));
    }

    @Test
    void zeroSizedPagesPreserveTheCursorAndValidateTokens() {
        assertFalse(service.listCollections(request().put("MaxResults", 0), REGION).has("NextToken"));
        service.createCollection(request(), REGION);
        service.createCollection(request().put("CollectionId", "other"), REGION);
        ObjectNode empty = service.listCollections(request().put("MaxResults", 0), REGION);
        assertTrue(empty.path("CollectionIds").isEmpty());
        assertTrue(empty.hasNonNull("NextToken"));
        assertEquals(service.listCollections(request(), REGION).path("CollectionIds"),
                service.listCollections(request().put("NextToken", empty.path("NextToken").asText()), REGION)
                        .path("CollectionIds"));
        String token = service.listCollections(request().put("MaxResults", 1), REGION).path("NextToken").asText();
        ObjectNode paused = service.listCollections(request().put("MaxResults", 0).put("NextToken", token), REGION);
        assertEquals(token, paused.path("NextToken").asText());
        assertEquals(1, service.listCollections(request().put("NextToken", paused.path("NextToken").asText()), REGION)
                .path("CollectionIds").size());
        assertError("InvalidPaginationTokenException", () -> service.listCollections(
                request().put("MaxResults", 0).put("NextToken", "garbage"), REGION));
        assertError("InvalidPaginationTokenException", () -> service.listCollections(
                request().put("MaxResults", 0).put("NextToken", token), "eu-west-1"));

        assertFalse(service.listFaces(request().put("MaxResults", 0), REGION).has("NextToken"));
        service.indexFaces(imageRequest(), REGION, "photos/face.jpg");
        ObjectNode emptyFaces = service.listFaces(request().put("MaxResults", 0), REGION);
        assertTrue(emptyFaces.path("Faces").isEmpty());
        assertTrue(emptyFaces.hasNonNull("NextToken"));
        assertEquals(service.listFaces(request(), REGION).path("Faces"), service.listFaces(
                request().put("NextToken", emptyFaces.path("NextToken").asText()), REGION).path("Faces"));
        String faceToken = service.listFaces(request().put("MaxResults", 1), REGION).path("NextToken").asText();
        assertEquals(faceToken, service.listFaces(request().put("MaxResults", 0).put("NextToken", faceToken), REGION)
                .path("NextToken").asText());
        assertEquals(1, service.listFaces(request().put("NextToken", faceToken), REGION).path("Faces").size());
        assertError("InvalidPaginationTokenException", () -> service.listFaces(
                request().put("MaxResults", 0).put("NextToken", "garbage"), REGION));
        assertError("InvalidPaginationTokenException", () -> service.listFaces(
                request().put("CollectionId", "other").put("MaxResults", 0).put("NextToken", faceToken), REGION));
    }

    @Test
    void listsUseIdOrderAndResumeAfterDeletedIds() {
        List<String> expected = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot");
        for (String id : expected.reversed()) {
            service.createCollection(request().put("CollectionId", id), REGION);
        }
        List<String> actual = new ArrayList<>();
        service.listCollections(request(), REGION).path("CollectionIds").forEach(id -> actual.add(id.asText()));
        assertEquals(expected, actual);
        ObjectNode first = service.listCollections(request().put("MaxResults", 1), REGION);
        service.deleteCollection(request().put("CollectionId", "alpha"), REGION);
        service.createCollection(request().put("CollectionId", "aardvark"), REGION);
        assertEquals("bravo", service.listCollections(request().put("MaxResults", 1)
                .put("NextToken", first.path("NextToken").asText()), REGION).at("/CollectionIds/0").asText());

        service.createCollection(request(), REGION);
        for (int i = 0; i < 8; i++) {
            service.indexFaces(imageRequest().put("ExternalImageId", "photo-" + i), REGION, "photos/face.jpg");
        }
        List<String> faceIds = new ArrayList<>();
        String token = null;
        do {
            ObjectNode pageRequest = request().put("MaxResults", 1);
            if (token != null) {
                pageRequest.put("NextToken", token);
            }
            ObjectNode page = service.listFaces(pageRequest, REGION);
            faceIds.add(page.at("/Faces/0/FaceId").asText());
            token = page.path("NextToken").asText(null);
            ObjectNode deletion = request();
            deletion.putArray("FaceIds").add(faceIds.getLast());
            service.deleteFaces(deletion, REGION);
        } while (token != null);
        assertEquals(16, faceIds.size());
        assertEquals(faceIds.stream().sorted().toList(), faceIds);
    }

    @Test
    void faceWritesLeaveMetadataAndOtherCollectionsUntouched(@TempDir Path path) throws Exception {
        StorageFactory storage = storage("persistent", path);
        try {
            RekognitionCollectionService writer = new RekognitionCollectionService(storage, mapper, resolver, mocks);
            writer.createCollection(request(), REGION);
            writer.createCollection(request().put("CollectionId", "other"), REGION);
            writer.indexFaces(imageRequest().put("CollectionId", "other"), REGION, "photos/face.jpg");
            Map<Path, FileTime> untouched = markFiles(path);
            ObjectNode indexed = writer.indexFaces(imageRequest(), REGION, "photos/face.jpg");
            for (Map.Entry<Path, FileTime> file : untouched.entrySet()) {
                assertEquals(file.getValue(), Files.getLastModifiedTime(file.getKey()), file.getKey().toString());
            }
            assertTrue(Files.isDirectory(path.resolve("rekognition-faces")));
            assertFalse(mapper.readTree(path.resolve("rekognition-collections.json").toFile()).toString().contains("FaceId"));
            Map<Path, FileTime> unchanged = markFiles(path);
            assertEquals(indexed, writer.indexFaces(imageRequest(), REGION, "photos/face.jpg"));
            for (Map.Entry<Path, FileTime> file : unchanged.entrySet()) {
                assertEquals(file.getValue(), Files.getLastModifiedTime(file.getKey()), file.getKey().toString());
            }
            ObjectNode deletion = request();
            deletion.putArray("FaceIds").add(indexed.at("/FaceRecords/0/Face/FaceId").asText());
            writer.deleteFaces(deletion, REGION);
            for (Map.Entry<Path, FileTime> file : untouched.entrySet()) {
                assertEquals(file.getValue(), Files.getLastModifiedTime(file.getKey()), file.getKey().toString());
            }
            writer.deleteCollection(request(), REGION);
            writer.createCollection(request(), REGION);
            assertEquals(0, writer.listFaces(request(), REGION).path("Faces").size());
            assertEquals(2, writer.listFaces(request().put("CollectionId", "other"), REGION).path("Faces").size());
            writer.clear();
            writer.createCollection(request().put("CollectionId", "other"), REGION);
            assertEquals(0, writer.listFaces(request().put("CollectionId", "other"), REGION).path("Faces").size());
        } finally {
            storage.shutdownAll();
        }
    }

    private Map<Path, FileTime> markFiles(Path path) throws Exception {
        Map<Path, FileTime> result = new HashMap<>();
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(1_000));
                result.put(file, Files.getLastModifiedTime(file));
            }
        }
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void collectionAndFaceMetadataSurviveStorageRestart(String mode, @TempDir Path path) {
        StorageFactory storage = storage(mode, path);
        RekognitionCollectionService writer = new RekognitionCollectionService(storage, mapper, resolver, mocks);
        writer.createCollection(request(), REGION);
        ObjectNode indexed = writer.indexFaces(imageRequest(), REGION, "photos/face.jpg");
        storage.shutdownAll();
        StorageFactory restored = storage(mode, path);
        try {
            RekognitionCollectionService reader = new RekognitionCollectionService(restored, mapper, resolver, mocks);
            assertEquals(2, reader.describeCollection(request(), REGION).path("FaceCount").asInt());
            String faceId = indexed.at("/FaceRecords/0/Face/FaceId").asText();
            assertEquals(faceId, reader.searchFaces(request().put("FaceId", faceId), REGION).path("SearchedFaceId").asText());
            assertEquals(1, reader.searchFacesByImage(imageRequest(), REGION, "photos/face.jpg").path("FaceMatches").size());
        } finally {
            restored.shutdownAll();
        }
    }

    private StorageFactory storage(String mode, Path path) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(path.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("rekognition")).thenReturn(mode);
        when(access.storageFlushInterval("rekognition")).thenReturn(60_000L);
        return new StorageFactory(config, access);
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("CollectionId", "tenant");
    }

    private ObjectNode imageRequest() {
        ObjectNode request = request();
        request.putObject("Image").putObject("S3Object").put("Bucket", "photos").put("Name", "face.jpg");
        return request;
    }

    private static void assertError(String code, Runnable action) {
        assertEquals(code, assertThrows(AwsException.class, action::run).getErrorCode());
    }
}
