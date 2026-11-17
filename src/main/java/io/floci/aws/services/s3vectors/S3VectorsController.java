package io.floci.aws.services.s3vectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorData;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class S3VectorsController {

    private static final Logger LOG = Logger.getLogger(S3VectorsController.class);

    private final S3VectorsService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public S3VectorsController(S3VectorsService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @RegisterForReflection
    public record CreateVectorBucketRequest(
            String vectorBucketName,
            Object encryptionConfiguration,
            Map<String, String> tags
    ) {}

    @RegisterForReflection
    public record CreateVectorBucketResponse(
            String vectorBucketArn
    ) {}

    @RegisterForReflection
    public record GetVectorBucketRequest(
            String vectorBucketName,
            String vectorBucketArn
    ) {}

    @RegisterForReflection
    public record GetVectorBucketResponse(
            VectorBucketRepresentation vectorBucket
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VectorBucketRepresentation(
            String vectorBucketName,
            String vectorBucketArn,
            double creationTime,
            Object encryptionConfiguration
    ) {}

    @RegisterForReflection
    public record VectorBucketSummary(
            String vectorBucketName,
            String vectorBucketArn,
            double creationTime
    ) {}

    @RegisterForReflection
    public record ListVectorBucketsRequest(
            Integer maxResults,
            String nextToken,
            String prefix
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListVectorBucketsResponse(
            List<VectorBucketSummary> vectorBuckets,
            String nextToken
    ) {}

    @RegisterForReflection
    public record DeleteVectorBucketRequest(
            String vectorBucketName,
            String vectorBucketArn
    ) {}

    @RegisterForReflection
    public record PutVectorBucketPolicyRequest(
            String vectorBucketName,
            String vectorBucketArn,
            String policy
    ) {}

    @RegisterForReflection
    public record GetVectorBucketPolicyRequest(
            String vectorBucketName,
            String vectorBucketArn
    ) {}

    @RegisterForReflection
    public record GetVectorBucketPolicyResponse(
            String policy
    ) {}

    @RegisterForReflection
    public record DeleteVectorBucketPolicyRequest(
            String vectorBucketName,
            String vectorBucketArn
    ) {}

    @RegisterForReflection
    public record CreateIndexRequest(
            String vectorBucketName,
            String vectorBucketArn,
            String indexName,
            String dataType,
            int dimension,
            String distanceMetric,
            MetadataConfiguration metadataConfiguration,
            Object encryptionConfiguration,
            Map<String, String> tags
    ) {}

    @RegisterForReflection
    public record MetadataConfiguration(List<String> nonFilterableMetadataKeys) {}

    @RegisterForReflection
    public record CreateIndexResponse(
            String indexArn
    ) {}

    @RegisterForReflection
    public record GetIndexRequest(
            String vectorBucketName,
            String indexName,
            String indexArn
    ) {}

    @RegisterForReflection
    public record GetIndexResponse(
            IndexRepresentation index
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexRepresentation(
            double creationTime,
            String vectorBucketName,
            String indexName,
            String indexArn,
            String dataType,
            int dimension,
            String distanceMetric,
            Object metadataConfiguration,
            Object encryptionConfiguration
    ) {}

    @RegisterForReflection
    public record IndexSummary(
            double creationTime,
            String vectorBucketName,
            String indexName,
            String indexArn
    ) {}

    @RegisterForReflection
    public record ListIndexesRequest(
            String vectorBucketName,
            String vectorBucketArn,
            Integer maxResults,
            String nextToken,
            String prefix
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListIndexesResponse(
            List<IndexSummary> indexes,
            String nextToken
    ) {}

    @RegisterForReflection
    public record DeleteIndexRequest(
            String vectorBucketName,
            String indexName,
            String indexArn
    ) {}

    @RegisterForReflection
    public record PutVectorsRequest(
            String vectorBucketName,
            String indexName,
            String indexArn,
            List<VectorRepresentation> vectors
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VectorRepresentation(
            String key,
            VectorDataRepresentation data,
            Map<String, Object> metadata
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VectorDataRepresentation(
            List<Float> float32
    ) {}

    @RegisterForReflection
    public record GetVectorsRequest(
            String vectorBucketName,
            String indexName,
            String indexArn,
            List<String> keys,
            Boolean returnData,
            Boolean returnMetadata
    ) {}

    @RegisterForReflection
    public record GetVectorsResponse(
            List<VectorGetResponseRepresentation> vectors
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VectorGetResponseRepresentation(
            String key,
            VectorDataRepresentation data,
            Map<String, Object> metadata
    ) {}

    @RegisterForReflection
    public record ListVectorsRequest(
            String vectorBucketName,
            String indexName,
            String indexArn,
            Integer maxResults,
            String nextToken,
            Boolean returnData,
            Boolean returnMetadata
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListVectorsResponse(
            List<VectorGetResponseRepresentation> vectors,
            String nextToken
    ) {}

    @RegisterForReflection
    public record DeleteVectorsRequest(
            String vectorBucketName,
            String indexName,
            String indexArn,
            List<String> keys
    ) {}

    @RegisterForReflection
    public record QueryVectorsRequest(
            String vectorBucketName,
            String indexName,
            String indexArn,
            int topK,
            VectorDataRepresentation queryVector,
            Object filter,
            Boolean returnMetadata,
            Boolean returnDistance
    ) {}

    @RegisterForReflection
    public record QueryVectorsResponse(
            List<QueryResultRepresentation> vectors,
            String distanceMetric
    ) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryResultRepresentation(
            String key,
            Double distance,
            Map<String, Object> metadata
    ) {}

    @POST
    @Path("/CreateVectorBucket")
    public Response createVectorBucket(@Context HttpHeaders headers, CreateVectorBucketRequest request) {
        String region = regionResolver.resolveRegion(headers);
        VectorBucket bucket = service.createVectorBucket(request.vectorBucketName(), request.encryptionConfiguration(),
                request.tags() != null ? request.tags() : Map.of(), region);
        return Response.ok(new CreateVectorBucketResponse(bucket.getVectorBucketArn())).build();
    }

    @POST
    @Path("/GetVectorBucket")
    public Response getVectorBucket(@Context HttpHeaders headers, GetVectorBucketRequest request) {
        S3VectorsService.ResolvedBucket target = bucketOf(headers, request.vectorBucketName(),
                request.vectorBucketArn());
        VectorBucket bucket = service.getVectorBucket(target.bucketName(), target.region());
        VectorBucketRepresentation rep = new VectorBucketRepresentation(bucket.getVectorBucketName(),
                bucket.getVectorBucketArn(), EpochSeconds.fromInstant(bucket.getCreationTime()), bucket.getEncryptionConfiguration());
        return Response.ok(new GetVectorBucketResponse(rep)).build();
    }

    @POST
    @Path("/ListVectorBuckets")
    public Response listVectorBuckets(@Context HttpHeaders headers, ListVectorBucketsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        List<VectorBucket> buckets = service.listVectorBuckets(region);
        List<VectorBucketSummary> reps = buckets.stream()
                .map(b -> new VectorBucketSummary(b.getVectorBucketName(), b.getVectorBucketArn(),
                        EpochSeconds.fromInstant(b.getCreationTime())))
                .toList();
        return Response.ok(new ListVectorBucketsResponse(reps, null)).build();
    }

    @POST
    @Path("/DeleteVectorBucket")
    public Response deleteVectorBucket(@Context HttpHeaders headers, DeleteVectorBucketRequest request) {
        S3VectorsService.ResolvedBucket target = bucketOf(headers, request.vectorBucketName(),
                request.vectorBucketArn());
        service.deleteVectorBucket(target.bucketName(), target.region());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/PutVectorBucketPolicy")
    public Response putVectorBucketPolicy(@Context HttpHeaders headers, PutVectorBucketPolicyRequest request) {
        String region = regionResolver.resolveRegion(headers);
        service.putVectorBucketPolicy(
                request != null ? request.vectorBucketName() : null,
                request != null ? request.vectorBucketArn() : null,
                request != null ? request.policy() : null,
                region
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/GetVectorBucketPolicy")
    public Response getVectorBucketPolicy(@Context HttpHeaders headers, GetVectorBucketPolicyRequest request) {
        String region = regionResolver.resolveRegion(headers);
        String policy = service.getVectorBucketPolicy(
                request != null ? request.vectorBucketName() : null,
                request != null ? request.vectorBucketArn() : null,
                region
        );
        return Response.ok(new GetVectorBucketPolicyResponse(policy)).build();
    }

    @POST
    @Path("/DeleteVectorBucketPolicy")
    public Response deleteVectorBucketPolicy(@Context HttpHeaders headers, DeleteVectorBucketPolicyRequest request) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteVectorBucketPolicy(
                request != null ? request.vectorBucketName() : null,
                request != null ? request.vectorBucketArn() : null,
                region
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/CreateIndex")
    public Response createIndex(@Context HttpHeaders headers, CreateIndexRequest request) {
        S3VectorsService.ResolvedBucket target = bucketOf(headers, request.vectorBucketName(),
                request.vectorBucketArn());
        VectorIndex index = service.createIndex(
                target.bucketName(),
                request.indexName(),
                request.dimension(),
                request.dataType() != null ? request.dataType() : "float32",
                request.distanceMetric() != null ? request.distanceMetric() : "cosine",
                request.metadataConfiguration() != null && request.metadataConfiguration().nonFilterableMetadataKeys() != null
                        ? request.metadataConfiguration().nonFilterableMetadataKeys() : List.of(),
                request.encryptionConfiguration(),
                request.tags() != null ? request.tags() : Map.of(),
                target.region()
        );
        return Response.ok(new CreateIndexResponse(index.getIndexArn())).build();
    }

    @POST
    @Path("/GetIndex")
    public Response getIndex(@Context HttpHeaders headers, GetIndexRequest request) {
        S3VectorsService.ResolvedIndex target = indexOf(headers, request.vectorBucketName(), request.indexName(),
                request.indexArn());
        VectorIndex index = service.getIndex(target.bucketName(), target.indexName(), target.region());
        IndexRepresentation rep = new IndexRepresentation(
                EpochSeconds.fromInstant(index.getCreationTime()),
                index.getVectorBucketName(),
                index.getIndexName(),
                index.getIndexArn(),
                index.getDataType(),
                index.getDimension(),
                index.getDistanceMetric(),
                index.getNonFilterableMetadataKeys().isEmpty()
                        ? null : new MetadataConfiguration(index.getNonFilterableMetadataKeys()),
                index.getEncryptionConfiguration()
        );
        return Response.ok(new GetIndexResponse(rep)).build();
    }

    @POST
    @Path("/ListIndexes")
    public Response listIndexes(@Context HttpHeaders headers, ListIndexesRequest request) {
        S3VectorsService.ResolvedBucket target = bucketOf(headers, request.vectorBucketName(),
                request.vectorBucketArn());
        List<VectorIndex> indexes = service.listIndexes(target.bucketName(), target.region());
        List<IndexSummary> reps = indexes.stream()
                .map(i -> new IndexSummary(
                        EpochSeconds.fromInstant(i.getCreationTime()),
                        i.getVectorBucketName(),
                        i.getIndexName(),
                        i.getIndexArn()
                ))
                .toList();
        return Response.ok(new ListIndexesResponse(reps, null)).build();
    }

    @POST
    @Path("/DeleteIndex")
    public Response deleteIndex(@Context HttpHeaders headers, DeleteIndexRequest request) {
        S3VectorsService.ResolvedIndex target = indexOf(headers, request.vectorBucketName(), request.indexName(),
                request.indexArn());
        service.deleteIndex(target.bucketName(), target.indexName(), target.region());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/PutVectors")
    public Response putVectors(@Context HttpHeaders headers, PutVectorsRequest request) {
        S3VectorsService.ResolvedIndex target = indexOf(headers, request.vectorBucketName(), request.indexName(),
                request.indexArn());
        List<VectorData> vectors = new ArrayList<>();
        if (request.vectors() != null) {
            for (VectorRepresentation vRep : request.vectors()) {
                List<Float> floatList = (vRep.data() != null && vRep.data().float32() != null)
                        ? vRep.data().float32()
                        : List.of();
                Map<String, Object> metadata = vRep.metadata() != null ? vRep.metadata() : Map.of();
                vectors.add(new VectorData(vRep.key(), floatList, metadata));
            }
        }
        service.putVectors(target.bucketName(), target.indexName(), vectors, target.region());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/GetVectors")
    public Response getVectors(@Context HttpHeaders headers, GetVectorsRequest request) {
        S3VectorsService.ResolvedIndex target = indexOf(headers, request.vectorBucketName(), request.indexName(),
                request.indexArn());
        List<VectorData> vectors = service.getVectors(target.bucketName(), target.indexName(), request.keys(),
                target.region());

        boolean returnData = request.returnData() != null && request.returnData();
        boolean returnMetadata = request.returnMetadata() != null && request.returnMetadata();

        List<VectorGetResponseRepresentation> reps = vectors.stream()
                .map(v -> {
                    VectorDataRepresentation data = returnData ? new VectorDataRepresentation(v.getData()) : null;
                    Map<String, Object> metadata = returnMetadata ? v.getMetadata() : null;
                    return new VectorGetResponseRepresentation(v.getKey(), data, metadata);
                })
                .toList();

        return Response.ok(new GetVectorsResponse(reps)).build();
    }

    @POST
    @Path("/ListVectors")
    public Response listVectors(@Context HttpHeaders headers, ListVectorsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        int maxResults = request.maxResults() != null ? request.maxResults() : 0;
        S3VectorsService.ListVectorsResult result = service.listVectors(
                request.vectorBucketName(), request.indexName(), request.indexArn(), maxResults, request.nextToken(), region);

        boolean returnData = request.returnData() != null && request.returnData();
        boolean returnMetadata = request.returnMetadata() != null && request.returnMetadata();

        List<VectorGetResponseRepresentation> reps = result.vectors().stream()
                .map(v -> {
                    VectorDataRepresentation data = returnData ? new VectorDataRepresentation(v.getData()) : null;
                    Map<String, Object> metadata = returnMetadata ? v.getMetadata() : null;
                    return new VectorGetResponseRepresentation(v.getKey(), data, metadata);
                })
                .toList();

        return Response.ok(new ListVectorsResponse(reps, result.nextToken())).build();
    }

    @POST
    @Path("/DeleteVectors")
    public Response deleteVectors(@Context HttpHeaders headers, DeleteVectorsRequest request) {
        S3VectorsService.ResolvedIndex target = indexOf(headers, request.vectorBucketName(), request.indexName(),
                request.indexArn());
        service.deleteVectors(target.bucketName(), target.indexName(), request.keys(), target.region());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/QueryVectors")
    public Response queryVectors(@Context HttpHeaders headers, QueryVectorsRequest request) {
        List<Float> queryVector = (request.queryVector() != null && request.queryVector().float32() != null)
                ? request.queryVector().float32()
                : List.of();
        S3VectorsService.ResolvedIndex target = indexOf(headers, request.vectorBucketName(), request.indexName(),
                request.indexArn());
        List<S3VectorsService.QueryResult> results = service.queryVectors(
                target.bucketName(),
                target.indexName(),
                queryVector,
                request.filter(),
                request.topK() > 0 ? request.topK() : 10,
                target.region()
        );

        boolean returnMetadata = request.returnMetadata() != null && request.returnMetadata();
        boolean returnDistance = request.returnDistance() != null && request.returnDistance();

        List<QueryResultRepresentation> reps = results.stream()
                .map(res -> {
                    Map<String, Object> metadata = returnMetadata ? res.getVector().getMetadata() : null;
                    Double distance = returnDistance ? res.getDistance() : null;
                    return new QueryResultRepresentation(res.getVector().getKey(), distance, metadata);
                })
                .toList();

        VectorIndex index = service.getIndex(target.bucketName(), target.indexName(), target.region());
        return Response.ok(new QueryVectorsResponse(reps, index.getDistanceMetric())).build();
    }

    /** The vector bucket a request names by its name or by its ARN. */
    private S3VectorsService.ResolvedBucket bucketOf(HttpHeaders headers, String bucketName, String bucketArn) {
        return service.bucketTarget(bucketName, bucketArn, regionResolver.resolveRegion(headers));
    }

    /** The index a request names by its bucket's name and its own, or by its ARN. */
    private S3VectorsService.ResolvedIndex indexOf(HttpHeaders headers, String bucketName, String indexName,
                                                   String indexArn) {
        return service.resolveIndexTarget(bucketName, indexName, indexArn, regionResolver.resolveRegion(headers));
    }
}
