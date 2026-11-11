package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SignedMarkers;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.rekognition.model.FaceCollection;
import io.floci.aws.services.rekognition.model.FaceCollection.IndexedFace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Collection metadata is real state; face detection and matching use deterministic mock data. */
@ApplicationScoped
public class RekognitionCollectionService implements Resettable {
    static final String MODEL_VERSION = "7.0";
    private final StorageBackend<String, FaceCollection> collections;
    private final AccountAwareStorageBackend<Map<String, IndexedFace>> indexedFaces;
    private final SignedMarkers markers = new SignedMarkers(255);
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final AiMockConfigLoader mocks;

    @Inject
    public RekognitionCollectionService(StorageFactory storageFactory, ObjectMapper mapper,
                                        RegionResolver regionResolver, AiMockConfigLoader mocks) {
        this.collections = storageFactory.create("rekognition", "rekognition-collections.json", new TypeReference<>() {});
        this.indexedFaces = storageFactory.createPartitioned("rekognition", "rekognition-faces.json",
                new TypeReference<Map<String, Map<String, IndexedFace>>>() {}, IndexedFace.class, 32);
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.mocks = mocks;
    }

    public synchronized ObjectNode createCollection(JsonNode request, String region) {
        String id = request.path("CollectionId").asText();
        Map<String, String> tags = tags(request);
        String key = key(region, id);
        if (collections.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Collection already exists: " + id, 400);
        }
        Instant now = Instant.now();
        FaceCollection collection = new FaceCollection(id,
                regionResolver.buildArn("rekognition", region, "collection/" + id), MODEL_VERSION,
                now.getEpochSecond() + now.getNano() / 1_000_000_000.0, tags);
        collections.put(key, collection);
        return mapper.createObjectNode().put("StatusCode", 200).put("CollectionArn", collection.arn())
                .put("FaceModelVersion", collection.modelVersion());
    }

    public synchronized ObjectNode describeCollection(JsonNode request, String region) {
        FaceCollection collection = requireCollection(request, region);
        return mapper.createObjectNode().put("CollectionARN", collection.arn())
                .put("CreationTimestamp", collection.creationTimestamp())
                .put("FaceCount", faces(region, collection).size()).put("UserCount", 0)
                .put("FaceModelVersion", collection.modelVersion());
    }

    public synchronized ObjectNode deleteCollection(JsonNode request, String region) {
        FaceCollection collection = requireCollection(request, region);
        indexedFaces.delete(key(region, collection.id()));
        collections.delete(key(region, collection.id()));
        return mapper.createObjectNode().put("StatusCode", 200);
    }

    public synchronized ObjectNode listCollections(JsonNode request, String region) {
        List<FaceCollection> all = collections.scan(key -> key.startsWith(region + ":"));
        PaginatedResult<FaceCollection> page = page(all, FaceCollection::id, request,
                "collections:" + regionResolver.getAccountId() + ":" + region);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode ids = result.putArray("CollectionIds");
        ArrayNode versions = result.putArray("FaceModelVersions");
        page.items().forEach(collection -> {
            ids.add(collection.id());
            versions.add(collection.modelVersion());
        });
        putToken(result, page.nextToken());
        return result;
    }

    public synchronized ObjectNode indexFaces(JsonNode request, String region, String mockKey) {
        FaceCollection collection = requireCollection(request, region);
        String externalId = request.path("ExternalImageId").asText(null);
        int maxFaces = request.path("MaxFaces").asInt(100);
        String imageKey = imageKey(request.get("Image"));
        List<ObjectNode> details = faceDetails(mockKey);
        details.sort(Comparator.comparingDouble(RekognitionCollectionService::area).reversed());
        String collectionKey = key(region, collection.id());
        Map<String, IndexedFace> faces = new HashMap<>(faces(region, collection));
        Map<FaceIdentity, IndexedFace> existing = new HashMap<>();
        faces.values().forEach(face -> existing.put(new FaceIdentity(face.imageKey(), face.position(),
                face.face().path("ExternalImageId").asText(null)), face));
        String imageId = faces.values().stream().filter(face -> face.imageKey().equals(imageKey))
                .map(face -> face.face().path("ImageId").asText()).findFirst().orElseGet(() -> UUID.randomUUID().toString());
        Map<String, IndexedFace> additions = new HashMap<>();
        ObjectNode result = mapper.createObjectNode().put("FaceModelVersion", collection.modelVersion());
        ArrayNode records = result.putArray("FaceRecords");
        ArrayNode unindexed = result.putArray("UnindexedFaces");
        for (int i = 0; i < details.size(); i++) {
            ObjectNode detail = details.get(i);
            if (i >= Math.min(maxFaces, 100)) {
                ObjectNode rejected = unindexed.addObject();
                rejected.set("FaceDetail", detail.deepCopy());
                rejected.putArray("Reasons").add("EXCEEDS_MAX_FACES");
                continue;
            }
            String position = position(detail);
            FaceIdentity identity = new FaceIdentity(imageKey, position, externalId);
            IndexedFace indexed = existing.get(identity);
            if (indexed == null) {
                ObjectNode face = mapper.createObjectNode().put("FaceId", UUID.randomUUID().toString())
                        .put("ImageId", imageId).put("Confidence", detail.path("Confidence").asDouble(99.0))
                        .put("IndexFacesModelVersion", collection.modelVersion());
                face.set("BoundingBox", detail.path("BoundingBox").deepCopy());
                if (externalId != null) {
                    face.put("ExternalImageId", externalId);
                }
                indexed = new IndexedFace(imageKey, position, face, detail.deepCopy());
                String id = face.path("FaceId").asText();
                faces.put(id, indexed);
                additions.put(id, indexed);
                existing.put(identity, indexed);
            }
            ObjectNode record = records.addObject();
            record.set("Face", indexed.face().deepCopy());
            record.set("FaceDetail", indexed.detail().deepCopy());
        }
        if (indexedFaces.writesEntries()) {
            additions.forEach((id, face) -> indexedFaces.putEntry(collectionKey, id, face));
        } else if (!additions.isEmpty()) {
            indexedFaces.put(collectionKey, Map.copyOf(faces));
        }
        return result;
    }

    public synchronized ObjectNode listFaces(JsonNode request, String region) {
        FaceCollection collection = requireCollection(request, region);
        List<String> ids = faceIds(request);
        String userId = request.path("UserId").asText(null);
        List<IndexedFace> faces = faces(region, collection).values().stream()
                .filter(face -> ids == null || ids.contains(face.face().path("FaceId").asText()))
                .filter(face -> userId == null || userId.equals(face.face().path("UserId").asText(null))).toList();
        PaginatedResult<IndexedFace> page = page(faces, face -> face.face().path("FaceId").asText(), request,
                "faces:" + collection.arn() + ":" + Digests.sha256Hex(String.valueOf(ids) + ":" + userId));
        ObjectNode result = mapper.createObjectNode().put("FaceModelVersion", collection.modelVersion());
        ArrayNode entries = result.putArray("Faces");
        page.items().forEach(face -> entries.add(face.face().deepCopy()));
        putToken(result, page.nextToken());
        return result;
    }

    public synchronized ObjectNode deleteFaces(JsonNode request, String region) {
        FaceCollection collection = requireCollection(request, region);
        List<String> ids = faceIds(request);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode deleted = result.putArray("DeletedFaces");
        ArrayNode unsuccessful = result.putArray("UnsuccessfulFaceDeletions");
        String collectionKey = key(region, collection.id());
        Map<String, IndexedFace> remaining = new HashMap<>(faces(region, collection));
        for (String id : new LinkedHashSet<>(ids)) {
            if (remaining.remove(id) != null) {
                deleted.add(id);
                if (indexedFaces.writesEntries()) {
                    indexedFaces.removeEntry(collectionKey, id);
                }
            } else {
                unsuccessful.addObject().put("FaceId", id).putArray("Reasons").add("FACE_NOT_FOUND");
            }
        }
        if (!indexedFaces.writesEntries() && !deleted.isEmpty()) {
            indexedFaces.put(collectionKey, Map.copyOf(remaining));
        }
        return result;
    }

    public synchronized ObjectNode searchFaces(JsonNode request, String region) {
        FaceCollection collection = requireCollection(request, region);
        String id = request.path("FaceId").asText();
        Map<String, IndexedFace> faces = faces(region, collection);
        IndexedFace source = faces.get(id);
        if (source == null) {
            throw invalid("FaceId is not indexed in this collection.");
        }
        ObjectNode result = matches(collection, faces, source.imageKey(), source.position(), id, request);
        return result.put("SearchedFaceId", id);
    }

    public synchronized ObjectNode searchFacesByImage(JsonNode request, String region, String mockKey) {
        FaceCollection collection = requireCollection(request, region);
        ObjectNode detail = faceDetails(mockKey).stream().max(Comparator.comparingDouble(RekognitionCollectionService::area))
                .orElseThrow(() -> invalid("There are no faces in the image. Should be at least 1."));
        ObjectNode result = matches(collection, faces(region, collection), imageKey(request.get("Image")),
                position(detail), null, request);
        result.set("SearchedFaceBoundingBox", detail.path("BoundingBox").deepCopy());
        return result.put("SearchedFaceConfidence", detail.path("Confidence").asDouble(99.0));
    }

    private ObjectNode matches(FaceCollection collection, Map<String, IndexedFace> faces, String imageKey,
                               String position, String excludedId, JsonNode request) {
        int limit = request.path("MaxFaces").asInt(4096);
        ObjectNode result = mapper.createObjectNode().put("FaceModelVersion", collection.modelVersion());
        ArrayNode matches = result.putArray("FaceMatches");
        faces.values().stream().filter(face -> face.imageKey().equals(imageKey) && face.position().equals(position))
                .filter(face -> !face.face().path("FaceId").asText().equals(excludedId))
                .sorted(Comparator.comparing(face -> face.face().path("FaceId").asText())).limit(limit)
                .forEach(face -> {
                    ObjectNode match = matches.addObject().put("Similarity", 100.0);
                    match.set("Face", face.face().deepCopy());
                });
        return result;
    }

    private List<ObjectNode> faceDetails(String mockKey) {
        JsonNode details = mocks.lookup("rekognition", mockKey, "DetectFaces")
                .map(response -> response.path("FaceDetails")).orElse(mapper.createArrayNode());
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode detail : details) {
            if (detail instanceof ObjectNode object && object.path("BoundingBox").isObject()) {
                result.add(object.deepCopy());
            }
        }
        return result;
    }

    private static double area(JsonNode detail) {
        return detail.path("BoundingBox").path("Width").asDouble() * detail.path("BoundingBox").path("Height").asDouble();
    }

    private static String position(JsonNode detail) {
        JsonNode box = detail.path("BoundingBox");
        return box.path("Left").asDouble() + ":" + box.path("Top").asDouble() + ":"
                + box.path("Width").asDouble() + ":" + box.path("Height").asDouble();
    }

    private static String imageKey(JsonNode image) {
        if (image.hasNonNull("Bytes")) {
            return Digests.sha256Hex(Base64.getEncoder().encodeToString(Base64.getDecoder().decode(image.get("Bytes").asText())));
        }
        JsonNode s3 = image.path("S3Object");
        return Digests.sha256Hex(s3.path("Bucket").asText() + "\n" + s3.path("Name").asText() + "\n" + s3.path("Version").asText());
    }

    private <T> PaginatedResult<T> page(List<T> all, Function<T, String> cursor, JsonNode request, String scope) {
        int limit = request.path("MaxResults").asInt(1000);
        String token = request.path("NextToken").asText(null);
        PaginatedResult<T> page = Pagination.slice(all, cursor, Math.max(1, limit), token, markers, scope,
                ignored -> new AwsException("InvalidPaginationTokenException", "Invalid NextToken.", 400));
        if (limit == 0) {
            String continuation = page.items().isEmpty() ? null : token == null ? markers.issue(scope, "") : token;
            return new PaginatedResult<>(List.of(), continuation);
        }
        return page;
    }

    private static void putToken(ObjectNode result, String token) {
        if (token != null) {
            result.put("NextToken", token);
        }
    }

    private FaceCollection requireCollection(JsonNode request, String region) {
        String id = request.path("CollectionId").asText();
        return collections.get(key(region, id)).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Collection not found: " + id, 400));
    }

    private Map<String, IndexedFace> faces(String region, FaceCollection collection) {
        return indexedFaces.get(key(region, collection.id())).orElse(Map.of());
    }

    private record FaceIdentity(String imageKey, String position, String externalId) {}

    private static String key(String region, String id) {
        return region + ":" + id;
    }

    private static List<String> faceIds(JsonNode request) {
        JsonNode ids = request.get("FaceIds");
        if (ids == null || ids.isNull()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        ids.forEach(id -> result.add(id.asText()));
        return result;
    }

    private static Map<String, String> tags(JsonNode request) {
        Map<String, String> result = new HashMap<>();
        request.path("Tags").properties().forEach(tag -> result.put(tag.getKey(), tag.getValue().asText()));
        return Map.copyOf(result);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    @Override
    public synchronized void clear() {
        indexedFaces.clear();
        collections.clear();
    }
}
