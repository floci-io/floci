package io.floci.aws.services.rekognition;

import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CreateCollectionResponse;
import software.amazon.awssdk.services.rekognition.model.DescribeCollectionResponse;
import software.amazon.awssdk.services.rekognition.model.Face;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.IndexFacesResponse;
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException;
import software.amazon.awssdk.services.rekognition.model.ListCollectionsResponse;
import software.amazon.awssdk.services.rekognition.model.ListFacesResponse;
import software.amazon.awssdk.services.rekognition.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.rekognition.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageResponse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RekognitionCollectionIntegrationTest.PersistentCollectionsProfile.class)
class RekognitionCollectionIntegrationTest {
    private static final String STORAGE_PATH = "target/rekognition-collections-it";

    @TestHTTPResource
    URI endpoint;

    @Inject
    ServiceConfigAccess serviceConfigAccess;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sdkExercisesCollectionAndFaceLifecycle() {
        given().put("/mock-bucket");
        given().body(TestImages.jpeg(100, 100)).put("/mock-bucket/collection-face.jpg").then().statusCode(200);
        String id = "collection-" + UUID.randomUUID();
        try (RekognitionClient client = client(Region.US_EAST_1, "test")) {
            Instant before = Instant.now().minusSeconds(1);
            CreateCollectionResponse created = client.createCollection(r -> r.collectionId(id));
            try {
                assertEquals("persistent", serviceConfigAccess.storageMode("rekognition"));
                assertTrue(Files.exists(Path.of(STORAGE_PATH, "rekognition-collections.json")));
                assertEquals(200, created.statusCode());
                assertThrows(ResourceAlreadyExistsException.class, () -> client.createCollection(r -> r.collectionId(id)));
                DescribeCollectionResponse described = client.describeCollection(r -> r.collectionId(id));
                assertEquals(created.collectionArn(), described.collectionARN());
                assertEquals(0L, described.faceCount());
                assertTrue(described.creationTimestamp().isAfter(before));
                assertTrue(described.creationTimestamp().isBefore(Instant.now().plusSeconds(1)));
                assertTrue(client.listCollectionsPaginator(r -> r.maxResults(1)).collectionIds().stream().anyMatch(id::equals));
                ListCollectionsResponse emptyCollections = client.listCollections(r -> r.maxResults(0));
                assertTrue(emptyCollections.collectionIds().isEmpty());
                assertNotNull(emptyCollections.nextToken());
                assertTrue(client.listCollections(r -> r.nextToken(emptyCollections.nextToken())).collectionIds().contains(id));
                Image image = Image.builder().s3Object(s3 -> s3.bucket("mock-bucket").name("collection-face.jpg")).build();
                IndexFacesResponse indexed = client.indexFaces(r -> r.collectionId(id).image(image).externalImageId("photo-1"));
                assertEquals(2, indexed.faceRecords().size());
                Face face = indexed.faceRecords().getFirst().face();
                assertEquals(0.4f, face.boundingBox().width());
                assertEquals(created.faceModelVersion(), face.indexFacesModelVersion());
                assertEquals(face.faceId(), client.indexFaces(r -> r.collectionId(id).image(image).externalImageId("photo-1"))
                        .faceRecords().getFirst().face().faceId());
                client.indexFaces(r -> r.collectionId(id).image(image).externalImageId("photo-2").maxFaces(1));
                assertEquals(3L, client.describeCollection(r -> r.collectionId(id)).faceCount());
                Set<String> allIds = new HashSet<>();
                client.listFacesPaginator(r -> r.collectionId(id).maxResults(1)).faces().forEach(f -> assertTrue(allIds.add(f.faceId())));
                assertEquals(3, allIds.size());
                List<String> orderedIds = client.listFacesPaginator(r -> r.collectionId(id).maxResults(1))
                        .faces().stream().map(Face::faceId).toList();
                assertEquals(orderedIds.stream().sorted().toList(), orderedIds);
                ListFacesResponse emptyFaces = client.listFaces(r -> r.collectionId(id).maxResults(0));
                assertTrue(emptyFaces.faces().isEmpty());
                assertNotNull(emptyFaces.nextToken());
                assertEquals(3, client.listFaces(r -> r.collectionId(id).nextToken(emptyFaces.nextToken())).faces().size());
                ListFacesResponse filtered = client.listFaces(r -> r.collectionId(id).faceIds(face.faceId()));
                assertEquals(1, filtered.faces().size());
                assertEquals("photo-1", filtered.faces().getFirst().externalImageId());
                assertEquals(1, client.searchFaces(r -> r.collectionId(id).faceId(face.faceId())).faceMatches().size());
                SearchFacesByImageResponse searched = client.searchFacesByImage(r -> r.collectionId(id).image(image).faceMatchThreshold(100f));
                assertEquals(2, searched.faceMatches().size());
                assertEquals(100f, searched.faceMatches().getFirst().similarity());
                assertEquals(face.boundingBox(), searched.searchedFaceBoundingBox());
                assertEquals(1, client.searchFacesByImage(r -> r.collectionId(id).image(image).maxFaces(1)).faceMatches().size());
                assertEquals(Set.of(face.faceId()), Set.copyOf(client.deleteFaces(r -> r.collectionId(id).faceIds(face.faceId())).deletedFaces()));
                assertEquals(2L, client.describeCollection(r -> r.collectionId(id)).faceCount());
                assertThrows(InvalidParameterException.class, () -> client.searchFaces(r -> r.collectionId(id).faceId(face.faceId())));
            } finally {
                assertEquals(200, client.deleteCollection(r -> r.collectionId(id)).statusCode());
            }
            assertThrows(ResourceNotFoundException.class, () -> client.describeCollection(r -> r.collectionId(id)));
            client.createCollection(r -> r.collectionId(id));
            try {
                assertEquals(0L, client.describeCollection(r -> r.collectionId(id)).faceCount());
            } finally {
                client.deleteCollection(r -> r.collectionId(id));
            }
        }
    }

    @Test
    void sdkCollectionsAreIsolatedByAccountAndRegion() {
        String id = "isolated-" + UUID.randomUUID();
        try (RekognitionClient owner = client(Region.US_EAST_1, "000000000001");
             RekognitionClient otherAccount = client(Region.US_EAST_1, "000000000002");
             RekognitionClient otherRegion = client(Region.EU_WEST_1, "000000000001")) {
            owner.createCollection(r -> r.collectionId(id));
            try {
                assertThrows(ResourceNotFoundException.class, () -> otherAccount.describeCollection(r -> r.collectionId(id)));
                assertThrows(ResourceNotFoundException.class, () -> otherRegion.describeCollection(r -> r.collectionId(id)));
                assertFalse(otherAccount.listCollections().collectionIds().contains(id));
                assertFalse(otherRegion.listCollections().collectionIds().contains(id));
            } finally {
                owner.deleteCollection(r -> r.collectionId(id));
            }
        }
    }

    @Test
    void bytesImagesKeepEmptyDetectionAndCollectionCallsValidateImages() {
        String id = "empty-" + UUID.randomUUID();
        try (RekognitionClient client = client(Region.US_EAST_1, "test")) {
            client.createCollection(r -> r.collectionId(id));
            try {
                Image image = Image.builder().bytes(SdkBytes.fromByteArray(TestImages.jpeg(100, 100))).build();
                assertTrue(client.indexFaces(r -> r.collectionId(id).image(image)).faceRecords().isEmpty());
                assertTrue(client.listFaces(r -> r.collectionId(id)).faces().isEmpty());
                assertThrows(InvalidParameterException.class, () -> client.searchFacesByImage(r -> r.collectionId(id).image(image)));
                for (String action : new String[] {"IndexFaces", "SearchFacesByImage"}) {
                    given().contentType("application/x-amz-json-1.1")
                            .header("X-Amz-Target", "RekognitionService." + action)
                            .body("{\"CollectionId\":\"" + id + "\",\"Image\":{\"Bytes\":\"not base64!\"}}")
                            .post("/").then().statusCode(400).body("__type", equalTo("SerializationException"));
                }
            } finally {
                client.deleteCollection(r -> r.collectionId(id));
            }
        }
    }

    @Test
    void collectionRequestsRejectModeledConstraintViolations() {
        given().put("/mock-bucket");
        given().body(TestImages.jpeg(100, 100)).put("/mock-bucket/collection-face.jpg").then().statusCode(200);
        String image = "\"Image\":{\"S3Object\":{\"Bucket\":\"mock-bucket\",\"Name\":\"collection-face.jpg\"}}";
        String[][] cases = {
                {"CreateCollection", "{\"CollectionId\":\"bad/id\"}", "InvalidParameterException"},
                {"CreateCollection", "{\"CollectionId\":\"tenant\",\"Tags\":{\"aws:owner\":\"team\"}}", "InvalidParameterException"},
                {"DeleteCollection", "{}", "InvalidParameterException"},
                {"DescribeCollection", "{\"CollectionId\":1}", "SerializationException"},
                {"ListCollections", "{\"MaxResults\":4097}", "InvalidParameterException"},
                {"ListCollections", "{\"MaxResults\":2147483648}", "SerializationException"},
                {"IndexFaces", "{\"CollectionId\":\"tenant\"," + image + ",\"DetectionAttributes\":[\"UNKNOWN\"]}", "InvalidParameterException"},
                {"IndexFaces", "{\"CollectionId\":\"tenant\"," + image + ",\"QualityFilter\":\"BAD\"}", "InvalidParameterException"},
                {"ListFaces", "{\"CollectionId\":\"tenant\",\"FaceIds\":[]}", "InvalidParameterException"},
                {"DeleteFaces", "{\"CollectionId\":\"tenant\",\"FaceIds\":[\"bad-id\"]}", "InvalidParameterException"},
                {"SearchFaces", "{\"CollectionId\":\"tenant\",\"FaceId\":\"00000000-0000-0000-0000-000000000000\",\"FaceMatchThreshold\":-0.1}", "InvalidParameterException"},
                {"SearchFacesByImage", "{\"CollectionId\":\"tenant\"," + image + ",\"FaceMatchThreshold\":100.1}", "InvalidParameterException"},
                {"SearchFacesByImage", "{\"CollectionId\":\"tenant\"," + image + ",\"FaceMatchThreshold\":\"50\"}", "SerializationException"}
        };
        for (String[] testCase : cases) {
            given().contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "RekognitionService." + testCase[0])
                    .body(testCase[1]).post("/").then().statusCode(400).body("__type", equalTo(testCase[2]));
        }
    }

    private RekognitionClient client(Region region, String account) {
        return AwsTestClients.create(RekognitionClient.builder(), account, region, endpoint);
    }

    public static class PersistentCollectionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.storage.services.rekognition.mode", "persistent",
                    "floci.storage.persistent-path", STORAGE_PATH);
        }
    }
}
