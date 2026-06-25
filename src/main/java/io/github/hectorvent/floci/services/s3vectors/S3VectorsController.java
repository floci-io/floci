package io.github.hectorvent.floci.services.s3vectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.s3vectors.model.VectorBucket;
import io.github.hectorvent.floci.services.s3vectors.model.VectorData;
import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
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

import java.util.*;
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
            Object encryptionConfiguration
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
            Object encryptionConfiguration
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
            List<VectorBucketRepresentation> vectorBuckets,
            String nextToken
    ) {}

    @RegisterForReflection
    public record DeleteVectorBucketRequest(
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
            Object metadataConfiguration,
            Object encryptionConfiguration
    ) {}

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
            List<IndexRepresentation> indexes,
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
        VectorBucket bucket = service.createVectorBucket(request.vectorBucketName(), request.encryptionConfiguration(), region);
        return Response.ok(new CreateVectorBucketResponse(bucket.getVectorBucketArn())).build();
    }

    @POST
    @Path("/GetVectorBucket")
    public Response getVectorBucket(@Context HttpHeaders headers, GetVectorBucketRequest request) {
        String region = regionResolver.resolveRegion(headers);
        VectorBucket bucket = service.getVectorBucket(request.vectorBucketName(), region);
        VectorBucketRepresentation rep = new VectorBucketRepresentation(bucket.getVectorBucketName(), bucket.getVectorBucketArn(), bucket.getEncryptionConfiguration());
        return Response.ok(new GetVectorBucketResponse(rep)).build();
    }

    @POST
    @Path("/ListVectorBuckets")
    public Response listVectorBuckets(@Context HttpHeaders headers, ListVectorBucketsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        List<VectorBucket> buckets = service.listVectorBuckets(region);
        List<VectorBucketRepresentation> reps = buckets.stream()
                .map(b -> new VectorBucketRepresentation(b.getVectorBucketName(), b.getVectorBucketArn(), b.getEncryptionConfiguration()))
                .toList();
        return Response.ok(new ListVectorBucketsResponse(reps, null)).build();
    }

    @POST
    @Path("/DeleteVectorBucket")
    public Response deleteVectorBucket(@Context HttpHeaders headers, DeleteVectorBucketRequest request) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteVectorBucket(request.vectorBucketName(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/CreateIndex")
    public Response createIndex(@Context HttpHeaders headers, CreateIndexRequest request) {
        String region = regionResolver.resolveRegion(headers);
        VectorIndex index = service.createIndex(
                request.vectorBucketName(),
                request.indexName(),
                request.dimension(),
                request.dataType() != null ? request.dataType() : "float32",
                request.distanceMetric() != null ? request.distanceMetric() : "cosine",
                List.of(),
                region
        );
        return Response.ok(new CreateIndexResponse(index.getIndexArn())).build();
    }

    @POST
    @Path("/GetIndex")
    public Response getIndex(@Context HttpHeaders headers, GetIndexRequest request) {
        String region = regionResolver.resolveRegion(headers);
        VectorIndex index = service.getIndex(request.vectorBucketName(), request.indexName(), region);
        IndexRepresentation rep = new IndexRepresentation(
                index.getVectorBucketName(),
                index.getIndexName(),
                index.getIndexArn(),
                index.getDataType(),
                index.getDimension(),
                index.getDistanceMetric(),
                null,
                null
        );
        return Response.ok(new GetIndexResponse(rep)).build();
    }

    @POST
    @Path("/ListIndexes")
    public Response listIndexes(@Context HttpHeaders headers, ListIndexesRequest request) {
        String region = regionResolver.resolveRegion(headers);
        List<VectorIndex> indexes = service.listIndexes(request.vectorBucketName(), region);
        List<IndexRepresentation> reps = indexes.stream()
                .map(i -> new IndexRepresentation(
                        i.getVectorBucketName(),
                        i.getIndexName(),
                        i.getIndexArn(),
                        i.getDataType(),
                        i.getDimension(),
                        i.getDistanceMetric(),
                        null,
                        null
                ))
                .toList();
        return Response.ok(new ListIndexesResponse(reps, null)).build();
    }

    @POST
    @Path("/DeleteIndex")
    public Response deleteIndex(@Context HttpHeaders headers, DeleteIndexRequest request) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteIndex(request.vectorBucketName(), request.indexName(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/PutVectors")
    public Response putVectors(@Context HttpHeaders headers, PutVectorsRequest request) {
        String region = regionResolver.resolveRegion(headers);
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
        service.putVectors(request.vectorBucketName(), request.indexName(), vectors, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/GetVectors")
    public Response getVectors(@Context HttpHeaders headers, GetVectorsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        List<VectorData> vectors = service.getVectors(request.vectorBucketName(), request.indexName(), request.keys(), region);

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
    @Path("/DeleteVectors")
    public Response deleteVectors(@Context HttpHeaders headers, DeleteVectorsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteVectors(request.vectorBucketName(), request.indexName(), request.keys(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/QueryVectors")
    public Response queryVectors(@Context HttpHeaders headers, QueryVectorsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        List<Float> queryVector = (request.queryVector() != null && request.queryVector().float32() != null)
                ? request.queryVector().float32()
                : List.of();
        List<S3VectorsService.QueryResult> results = service.queryVectors(
                request.vectorBucketName(),
                request.indexName(),
                queryVector,
                request.topK() > 0 ? request.topK() : 10,
                region
        );

        boolean returnMetadata = request.returnMetadata() != null && request.returnMetadata();

        List<QueryResultRepresentation> reps = results.stream()
                .map(res -> {
                    Map<String, Object> metadata = returnMetadata ? res.getVector().getMetadata() : null;
                    return new QueryResultRepresentation(res.getVector().getKey(), res.getDistance(), metadata);
                })
                .toList();

        VectorIndex index = service.getIndex(request.vectorBucketName(), request.indexName(), region);
        return Response.ok(new QueryVectorsResponse(reps, index.getDistanceMetric())).build();
    }
}
