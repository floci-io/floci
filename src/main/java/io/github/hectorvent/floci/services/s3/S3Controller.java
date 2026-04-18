package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesParts;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.ObjectLockRetention;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * S3 controller handling REST-style S3 API requests.
 * Routes: /{bucket} for bucket ops, /{bucket}/{key+} for object ops.
 */
@Path("/")
public class S3Controller {

    private static final Logger LOG = Logger.getLogger(S3Controller.class);
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter RFC_822 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final S3Service s3Service;
    private final S3SelectService s3SelectService;
    private final RegionResolver regionResolver;
    private final io.quarkus.vertx.http.runtime.CurrentVertxRequest currentVertxRequest;

    @Inject
    public S3Controller(S3Service s3Service, S3SelectService s3SelectService,
                        RegionResolver regionResolver,
                        io.quarkus.vertx.http.runtime.CurrentVertxRequest currentVertxRequest) {
        this.s3Service = s3Service;
        this.s3SelectService = s3SelectService;
        this.regionResolver = regionResolver;
        this.currentVertxRequest = currentVertxRequest;
    }

    // --- Bucket operations ---

    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response listBuckets(@HeaderParam("X-Amz-Target") String target) {
        if (target != null) {
            return null;
        }
        try {
            List<Bucket> buckets = s3Service.listBuckets();
            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("ListAllMyBucketsResult", AwsNamespaces.S3)
                    .start("Owner")
                    .elem("ID", "owner")
                    .elem("DisplayName", "owner")
                    .end("Owner")
                    .start("Buckets");
            for (Bucket b : buckets) {
                xml.start("Bucket")
                   .elem("Name", b.getName())
                   .elem("CreationDate", ISO_FORMAT.format(b.getCreationDate()))
                   .end("Bucket");
            }
            xml.end("Buckets").end("ListAllMyBucketsResult");
            return Response.ok(xml.build()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @HEAD
    @Path("/{bucket}")
    public Response headBucket(@PathParam("bucket") String bucket) {
        try {
            s3Service.headBucket(bucket);
            String bucketRegion = s3Service.getBucketRegion(bucket);
            if (bucketRegion == null || bucketRegion.isBlank()) {
                bucketRegion = regionResolver.getDefaultRegion();
            }
            return Response.ok()
                    .header("x-amz-bucket-region", bucketRegion)
                    .build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).build();
        }
    }

    @PUT
    @Path("/{bucket}")
    public Response createBucket(@PathParam("bucket") String bucket,
                                  @Context UriInfo uriInfo,
                                  @Context HttpHeaders httpHeaders,
                                  byte[] body) {
        try {
            if (hasQueryParam(uriInfo, "notification")) {
                return handlePutBucketNotification(bucket, body);
            }
            if (hasQueryParam(uriInfo, "versioning")) {
                return handlePutBucketVersioning(bucket, body);
            }
            if (hasQueryParam(uriInfo, "tagging")) {
                return handlePutBucketTagging(bucket, body);
            }
            if (hasQueryParam(uriInfo, "object-lock")) {
                return handlePutObjectLockConfiguration(bucket, body);
            }
            if (hasQueryParam(uriInfo, "policy")) {
                s3Service.putBucketPolicy(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }
            if (hasQueryParam(uriInfo, "cors")) {
                s3Service.putBucketCors(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }
            if (hasQueryParam(uriInfo, "lifecycle")) {
                s3Service.putBucketLifecycle(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }
            if (hasQueryParam(uriInfo, "acl")) {
                s3Service.putBucketAcl(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }
            if (hasQueryParam(uriInfo, "encryption")) {
                s3Service.putBucketEncryption(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }
            if (hasQueryParam(uriInfo, "publicAccessBlock")) {
                s3Service.putPublicAccessBlock(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }
            if (hasQueryParam(uriInfo, "ownershipControls")) {
                s3Service.putBucketOwnershipControls(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }

            String locationConstraint = null;
            if (body != null && body.length > 0) {
                locationConstraint = XmlParser.extractFirst(new String(body, StandardCharsets.UTF_8),
                        "LocationConstraint", null);
            }
            if (locationConstraint != null) {
                locationConstraint = locationConstraint.trim();
                if (locationConstraint.isEmpty()) {
                    locationConstraint = null;
                } else if ("us-east-1".equalsIgnoreCase(locationConstraint)) {
                    throw new AwsException("InvalidLocationConstraint",
                            "The specified location-constraint is not valid.", 400);
                }
            }
            String region = locationConstraint != null ? locationConstraint : regionResolver.resolveRegion(httpHeaders);
            s3Service.createBucket(bucket, region);
            String lockEnabled = httpHeaders.getHeaderString("x-amz-bucket-object-lock-enabled");
            if ("true".equalsIgnoreCase(lockEnabled)) {
                s3Service.putBucketVersioning(bucket, "Enabled");
                s3Service.setBucketObjectLockEnabled(bucket);
            }
            return Response.ok()
                    .header("Location", "/" + bucket)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/{bucket}")
    public Response deleteBucket(@PathParam("bucket") String bucket,
                                  @Context UriInfo uriInfo) {
        try {
            if (hasQueryParam(uriInfo, "tagging")) {
                s3Service.deleteBucketTagging(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "policy")) {
                s3Service.deleteBucketPolicy(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "cors")) {
                s3Service.deleteBucketCors(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "lifecycle")) {
                s3Service.deleteBucketLifecycle(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "encryption")) {
                s3Service.deleteBucketEncryption(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "publicAccessBlock")) {
                s3Service.deletePublicAccessBlock(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "ownershipControls")) {
                s3Service.deleteBucketOwnershipControls(bucket);
                return Response.noContent().build();
            }
            s3Service.deleteBucket(bucket);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/{bucket}")
    @Produces(MediaType.APPLICATION_XML)
    public Response listObjects(@PathParam("bucket") String bucket,
                                @QueryParam("prefix") String prefix,
                                @QueryParam("delimiter") String delimiter,
                                @QueryParam("max-keys") Integer maxKeys,
                                @QueryParam("list-type") String listType,
                                @QueryParam("continuation-token") String continuationToken,
                                @QueryParam("start-after") String startAfter,
                                @QueryParam("encoding-type") String encodingType,
                                @QueryParam("key-marker") String keyMarker,
                                @Context UriInfo uriInfo) {
        validateRawUri();
        try {
            if (hasQueryParam(uriInfo, "uploads")) {
                return handleListMultipartUploads(bucket);
            }
            if (hasQueryParam(uriInfo, "notification")) {
                return handleGetBucketNotification(bucket);
            }
            if (hasQueryParam(uriInfo, "versioning")) {
                return handleGetBucketVersioning(bucket);
            }
            if (hasQueryParam(uriInfo, "versions")) {
                return handleListObjectVersions(bucket, prefix, maxKeys, keyMarker);
            }
            if (hasQueryParam(uriInfo, "location")) {
                return handleGetBucketLocation(bucket);
            }
            if (hasQueryParam(uriInfo, "tagging")) {
                return handleGetBucketTagging(bucket);
            }
            if (hasQueryParam(uriInfo, "object-lock")) {
                return handleGetObjectLockConfiguration(bucket);
            }
            if (hasQueryParam(uriInfo, "policy")) {
                return Response.ok(s3Service.getBucketPolicy(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "cors")) {
                return Response.ok(s3Service.getBucketCors(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "lifecycle")) {
                return Response.ok(s3Service.getBucketLifecycle(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "acl")) {
                return Response.ok(s3Service.getBucketAcl(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "encryption")) {
                return Response.ok(s3Service.getBucketEncryption(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "publicAccessBlock")) {
                return Response.ok(s3Service.getPublicAccessBlock(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "ownershipControls")) {
                return Response.ok(s3Service.getBucketOwnershipControls(bucket)).build();
            }

            int max = (maxKeys != null && maxKeys > 0) ? maxKeys : 1000;
            S3Service.ListObjectsResult result = s3Service.listObjectsWithPrefixes(
                    bucket, prefix, delimiter, max, continuationToken, startAfter);
            List<S3Object> objects = result.objects();
            List<String> commonPrefixes = result.commonPrefixes();
            boolean v2 = "2".equals(listType);

            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("ListBucketResult", AwsNamespaces.S3)
                    .elem("Name", bucket)
                    .elem("Prefix", prefix != null ? prefix : "")
                    .elem("Delimiter", delimiter)
                    .elem("MaxKeys", max);
            if (v2) {
                xml.elem("KeyCount", objects.size() + commonPrefixes.size());
            }
            xml.elem("IsTruncated", result.isTruncated());
            for (S3Object obj : objects) {
                xml.start("Contents")
                   .elem("Key", obj.getKey())
                   .elem("LastModified", ISO_FORMAT.format(obj.getLastModified()))
                   .elem("ETag", obj.getETag())
                   .elem("Size", obj.getSize())
                   .elem("StorageClass", obj.getStorageClass())
                   .end("Contents");
            }
            for (String cp : commonPrefixes) {
                xml.start("CommonPrefixes")
                   .elem("Prefix", cp)
                   .end("CommonPrefixes");
            }
            if (encodingType != null) {
                xml.elem("EncodingType", encodingType);
            }
            if (v2) {
                if (continuationToken != null) {
                    xml.elem("ContinuationToken", continuationToken);
                }
                if (result.isTruncated()) {
                    xml.elem("NextContinuationToken", result.nextContinuationToken());
                }
                if (startAfter != null) {
                    xml.elem("StartAfter", startAfter);
                }
            }
            xml.end("ListBucketResult");
            return Response.ok(xml.build()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // --- Object operations ---

    @PUT
    @Path("/{bucket}/{key:.+}")
    public Response putObject(@PathParam("bucket") String bucket,
                              @PathParam("key") String key,
                              @HeaderParam("Content-Type") String contentType,
                              @HeaderParam("Content-Encoding") String contentEncoding,
                              @HeaderParam("x-amz-content-sha256") String contentSha256,
                              @HeaderParam("x-amz-copy-source") String copySource,
                              @QueryParam("uploadId") String uploadId,
                              @QueryParam("partNumber") Integer partNumber,
                              @Context UriInfo uriInfo,
                              @Context HttpHeaders httpHeaders,
                              byte[] body) {
        try {
            key = extractObjectKey(uriInfo, bucket);

            if (hasQueryParam(uriInfo, "tagging")) {
                return handlePutObjectTagging(bucket, key, body);
            }
            if (hasQueryParam(uriInfo, "retention")) {
                return handlePutObjectRetention(bucket, key,
                        uriInfo.getQueryParameters().getFirst("versionId"), httpHeaders, body);
            }
            if (hasQueryParam(uriInfo, "legal-hold")) {
                return handlePutObjectLegalHold(bucket, key,
                        uriInfo.getQueryParameters().getFirst("versionId"), body);
            }
            if (hasQueryParam(uriInfo, "acl")) {
                s3Service.putObjectAcl(bucket, key, uriInfo.getQueryParameters().getFirst("versionId"),
                        new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
            }

            if (uploadId != null && partNumber != null) {
                if (copySource != null && !copySource.isEmpty()) {
                    return handleUploadPartCopy(copySource, bucket, key, uploadId, partNumber, httpHeaders);
                }
                byte[] partData = decodeAwsChunked(body, contentEncoding, contentSha256);
                validateChecksumHeaders(httpHeaders, partData);
                String eTag = s3Service.uploadPart(bucket, key, uploadId, partNumber, partData);
                return Response.ok().header("ETag", eTag).build();
            }

            if (copySource != null && !copySource.isEmpty()) {
                return handleCopyObject(copySource, bucket, key, contentType, httpHeaders);
            }

            String lockMode = httpHeaders.getHeaderString("x-amz-object-lock-mode");
            String retainUntilStr = httpHeaders.getHeaderString("x-amz-object-lock-retain-until-date");
            String legalHold = httpHeaders.getHeaderString("x-amz-object-lock-legal-hold");
            Instant retainUntil = retainUntilStr != null ? Instant.parse(retainUntilStr) : null;

            byte[] data = decodeAwsChunked(body, contentEncoding, contentSha256);
            validateChecksumHeaders(httpHeaders, data);
            String persistedEncoding = toPersistedContentEncoding(contentEncoding);
            String contentDisposition = httpHeaders.getHeaderString("Content-Disposition");
            String cacheControl = httpHeaders.getHeaderString("Cache-Control");
            String cannedAcl = httpHeaders.getHeaderString("x-amz-acl");
            S3Object obj = s3Service.putObject(bucket, key, data, contentType, extractUserMetadata(httpHeaders),
                    httpHeaders.getHeaderString("x-amz-storage-class"),
                    persistedEncoding,
                    lockMode, retainUntil, legalHold,
                    contentDisposition,
                    cacheControl,
                    cannedAcl);
            var resp = Response.ok().header("ETag", obj.getETag());
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            appendObjectHeaders(resp, obj);
            return resp.build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/{bucket}/{key:.+}")
    public Response getObject(@PathParam("bucket") String bucket,
                              @PathParam("key") String key,
                              @QueryParam("versionId") String versionId,
                              @QueryParam("uploadId") String uploadId,
                              @QueryParam("max-parts") Integer maxPartsQuery,
                              @QueryParam("part-number-marker") String partNumberMarkerQuery,
                              @HeaderParam("x-amz-object-attributes") String objectAttributesHeader,
                              @HeaderParam("x-amz-max-parts") Integer maxParts,
                              @HeaderParam("x-amz-part-number-marker") Integer partNumberMarker,
                              @HeaderParam("If-Match") String ifMatch,
                              @HeaderParam("If-None-Match") String ifNoneMatch,
                              @HeaderParam("If-Modified-Since") String ifModifiedSince,
                              @HeaderParam("If-Unmodified-Since") String ifUnmodifiedSince,
                              @HeaderParam("Range") String rangeHeader,
                              @Context UriInfo uriInfo,
                              @Context HttpHeaders httpHeaders) {
        try {
            key = extractObjectKey(uriInfo, bucket);

            if (uploadId != null) {
                return handleListParts(bucket, key, uploadId, maxPartsQuery, partNumberMarkerQuery);
            }
            if (hasQueryParam(uriInfo, "tagging")) {
                return handleGetObjectTagging(bucket, key);
            }
            if (hasQueryParam(uriInfo, "retention")) {
                return handleGetObjectRetention(bucket, key, versionId);
            }
            if (hasQueryParam(uriInfo, "legal-hold")) {
                return handleGetObjectLegalHold(bucket, key, versionId);
            }
            if (hasQueryParam(uriInfo, "acl")) {
                return Response.ok(s3Service.getObjectAcl(bucket, key, versionId)).build();
            }
            if (hasQueryParam(uriInfo, "attributes")) {
                // Merge all x-amz-object-attributes header values (SDK may send multiple lines)
                List<String> attrHeaders = httpHeaders.getRequestHeader("x-amz-object-attributes");
                String mergedAttributes = attrHeaders != null ? String.join(",", attrHeaders) : objectAttributesHeader;
                return handleGetObjectAttributes(bucket, key, versionId,
                        mergedAttributes, maxParts, partNumberMarker);
            }
            if (hasPreconditions(ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince)) {
                // Fetch metadata only to evaluate preconditions, avoiding loading the full object unnecessarily.
                S3Object metadata = s3Service.headObject(bucket, key, versionId);
                Response preconditionResponse = checkPreconditions(metadata, ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince);
                if (preconditionResponse != null) {
                    return preconditionResponse;
                }
            }
            S3Object obj = s3Service.getObject(bucket, key, versionId);

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                return handleRangeRequest(obj, rangeHeader);
            }

            var resp = Response.ok(obj.getData())
                    .header("Content-Type", obj.getContentType())
                    .header("Content-Length", obj.getSize())
                    .header("ETag", obj.getETag())
                    .header("Last-Modified", RFC_822.format(obj.getLastModified()))
                    .header("Accept-Ranges", "bytes");
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            appendObjectHeaders(resp, obj);
            return resp.build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    private Response handleRangeRequest(S3Object obj, String rangeHeader) {
        byte[] data = obj.getData();
        int totalSize = data.length;
        String rangeSpec = rangeHeader.substring("bytes=".length()).trim();

        int start, end;
        try {
            int dash = rangeSpec.indexOf('-');
            if (dash < 0) {
                return invalidRangeResponse(totalSize);
            }
            String before = rangeSpec.substring(0, dash);
            String after = rangeSpec.substring(dash + 1);
            if (before.isEmpty() && after.isEmpty()) {
                return invalidRangeResponse(totalSize);
            }
            if (before.isEmpty()) {
                int suffix = Integer.parseInt(after);
                if (suffix <= 0) {
                    return invalidRangeResponse(totalSize);
                }
                start = Math.max(0, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Integer.parseInt(before);
                end = after.isEmpty() ? totalSize - 1 : Math.min(Integer.parseInt(after), totalSize - 1);
            }
        } catch (NumberFormatException e) {
            return invalidRangeResponse(totalSize);
        }

        if (start < 0 || start >= totalSize || start > end) {
            return invalidRangeResponse(totalSize);
        }

        byte[] rangeData = java.util.Arrays.copyOfRange(data, start, end + 1);
        return Response.status(206)
                .entity(rangeData)
                .header("Content-Type", obj.getContentType())
                .header("Content-Length", rangeData.length)
                .header("Content-Range", "bytes " + start + "-" + end + "/" + totalSize)
                .header("ETag", obj.getETag())
                .header("Last-Modified", RFC_822.format(obj.getLastModified()))
                .header("Accept-Ranges", "bytes")
                .build();
    }

    private Response invalidRangeResponse(int totalSize) {
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                .elem("Code", "InvalidRange")
                .elem("Message", "The requested range is not satisfiable.")
                .elem("RequestId", java.util.UUID.randomUUID().toString())
                .end("Error")
                .build();
        return Response.status(416)
                .entity(xml)
                .type(MediaType.APPLICATION_XML)
                .header("Content-Range", "bytes */" + totalSize)
                .build();
    }

    @HEAD
    @Path("/{bucket}/{key:.+}")
    public Response headObject(@PathParam("bucket") String bucket,
                               @PathParam("key") String key,
                               @QueryParam("versionId") String versionId,
                               @HeaderParam("If-Match") String ifMatch,
                               @HeaderParam("If-None-Match") String ifNoneMatch,
                               @HeaderParam("If-Modified-Since") String ifModifiedSince,
                               @HeaderParam("If-Unmodified-Since") String ifUnmodifiedSince,
                               @Context UriInfo uriInfo) {
        try {
            key = extractObjectKey(uriInfo, bucket);
            S3Object obj = s3Service.headObject(bucket, key, versionId);
            Response preconditionResponse = checkPreconditions(obj, ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince);
            if (preconditionResponse != null) {
                return preconditionResponse;
            }
            var resp = Response.ok()
                    .header("Content-Type", obj.getContentType())
                    .header("Content-Length", obj.getSize())
                    .header("ETag", obj.getETag())
                    .header("Last-Modified", RFC_822.format(obj.getLastModified()))
                    .header("Accept-Ranges", "bytes");
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            appendObjectHeaders(resp, obj);
            return resp.build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // --- CORS preflight ---

    @OPTIONS
    @Path("/{bucket}")
    public Response handleOptionsBucket(@PathParam("bucket") String bucket,
                                         @HeaderParam("Origin") String origin,
                                         @HeaderParam("Access-Control-Request-Method") String requestMethod,
                                         @HeaderParam("Access-Control-Request-Headers") String requestHeadersStr) {
        return handleCorsPreFlight(bucket, origin, requestMethod, requestHeadersStr);
    }

    @OPTIONS
    @Path("/{bucket}/{key:.+}")
    public Response handleOptionsObject(@PathParam("bucket") String bucket,
                                         @PathParam("key") String key,
                                         @HeaderParam("Origin") String origin,
                                         @HeaderParam("Access-Control-Request-Method") String requestMethod,
                                         @HeaderParam("Access-Control-Request-Headers") String requestHeadersStr) {
        return handleCorsPreFlight(bucket, origin, requestMethod, requestHeadersStr);
    }

    private Response handleCorsPreFlight(String bucket, String origin,
                                          String requestMethod, String requestHeadersStr) {
        if (origin == null || origin.isBlank()
                || requestMethod == null || requestMethod.isBlank()) {
            // Not a valid CORS preflight — return a plain 200 with no CORS headers
            return Response.ok().build();
        }
        List<String> requestHeaders = (requestHeadersStr != null && !requestHeadersStr.isBlank())
                ? Arrays.stream(requestHeadersStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList())
                : List.of();

        Optional<S3Service.CorsEvalResult> evalResult =
                s3Service.evaluateCors(bucket, origin, requestMethod, requestHeaders);

        if (evalResult.isEmpty()) {
            String body = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("Error")
                    .elem("Code", "CORSResponse")
                    .elem("Message", "This CORS request is not allowed.")
                    .end("Error")
                    .build();
            return Response.status(403)
                    .entity(body)
                    .type(MediaType.APPLICATION_XML)
                    .build();
        }

        S3Service.CorsEvalResult cors = evalResult.get();
        var builder = Response.ok()
                .header("Access-Control-Allow-Origin", cors.allowedOrigin())
                .header("Access-Control-Allow-Methods", String.join(", ", cors.allowedMethods()));

        if (cors.maxAgeSeconds() > 0) {
            builder.header("Access-Control-Max-Age", cors.maxAgeSeconds());
        }
        if (!cors.allowedHeaders().isEmpty()) {
            String hdrs = cors.allowedHeaders().contains("*")
                    ? "*"
                    : String.join(", ", cors.allowedHeaders());
            builder.header("Access-Control-Allow-Headers", hdrs);
        }
        if (!cors.exposeHeaders().isEmpty()) {
            builder.header("Access-Control-Expose-Headers", String.join(", ", cors.exposeHeaders()));
        }
        return builder.build();
    }

    @DELETE
    @Path("/{bucket}/{key:.+}")
    public Response deleteObject(@PathParam("bucket") String bucket,
                                 @PathParam("key") String key,
                                 @QueryParam("uploadId") String uploadId,
                                 @QueryParam("versionId") String versionId,
                                 @Context UriInfo uriInfo,
                                 @Context HttpHeaders httpHeaders) {
        try {
            key = extractObjectKey(uriInfo, bucket);

            if (hasQueryParam(uriInfo, "tagging")) {
                s3Service.deleteObjectTagging(bucket, key);
                return Response.noContent().build();
            }
            if (uploadId != null) {
                s3Service.abortMultipartUpload(bucket, key, uploadId);
                return Response.noContent().build();
            }
            boolean bypass = "true".equalsIgnoreCase(
                    httpHeaders.getHeaderString("x-amz-bypass-governance-retention"));
            S3Object result = s3Service.deleteObject(bucket, key, versionId, bypass);
            var resp = Response.noContent();
            if (result != null && result.isDeleteMarker()) {
                resp.header("x-amz-delete-marker", "true");
                resp.header("x-amz-version-id", result.getVersionId());
            }
            return resp.build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // --- Batch Delete (DeleteObjects) ---

    @POST
    @Path("/{bucket}")
    @Produces(MediaType.APPLICATION_XML)
    public Response handleBucketPost(@PathParam("bucket") String bucket,
                                      @HeaderParam("Content-Type") String contentType,
                                      @Context UriInfo uriInfo,
                                      byte[] body) {
        try {
            if (hasQueryParam(uriInfo, "delete")) {
                return handleDeleteObjects(bucket, body);
            }
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                return handlePresignedPost(bucket, contentType, body);
            }
            return xmlErrorResponse(new AwsException("InvalidArgument",
                    "POST on bucket requires ?delete parameter.", 400));
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/{bucket}/{key:.+}")
    @Produces(MediaType.APPLICATION_XML)
    public Response handleMultipartPost(@PathParam("bucket") String bucket,
                                         @PathParam("key") String key,
                                         @QueryParam("uploadId") String uploadId,
                                         @QueryParam("versionId") String versionId,
                                         @HeaderParam("Content-Type") String contentType,
                                         @Context HttpHeaders httpHeaders,
                                         @Context UriInfo uriInfo,
                                         byte[] body) {
        try {
            key = extractObjectKey(uriInfo, bucket);

            if (hasQueryParam(uriInfo, "uploads")) {
                MultipartUpload upload = s3Service.initiateMultipartUpload(bucket, key, contentType,
                        extractUserMetadata(httpHeaders),
                        httpHeaders.getHeaderString("x-amz-storage-class"),
                        httpHeaders.getHeaderString("Content-Disposition"),
                        httpHeaders.getHeaderString("x-amz-acl"));
                String xml = new XmlBuilder()
                        .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        .start("InitiateMultipartUploadResult", AwsNamespaces.S3)
                        .elem("Bucket", bucket)
                        .elem("Key", key)
                        .elem("UploadId", upload.getUploadId())
                        .end("InitiateMultipartUploadResult")
                        .build();
                return Response.ok(xml).build();
            }

            if (hasQueryParam(uriInfo, "restore")) {
                s3Service.restoreObject(bucket, key, versionId, new String(body, StandardCharsets.UTF_8));
                return Response.accepted().build();
            }

            if (hasQueryParam(uriInfo, "select")) {
                S3Object obj = s3Service.getObject(bucket, key, versionId);
                byte[] result = s3SelectService.select(obj, new String(body, StandardCharsets.UTF_8));
                return Response.ok(result)
                        .type("application/octet-stream")
                        .build();
            }

            if (uploadId != null) {
                List<Integer> partNumbers = parseCompleteMultipartBody(new String(body));
                S3Object obj = s3Service.completeMultipartUpload(bucket, key, uploadId, partNumbers);
                String baseUrl = uriInfo.getBaseUri().toString();
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                XmlBuilder xmlBuilder = new XmlBuilder()
                        .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        .start("CompleteMultipartUploadResult", AwsNamespaces.S3)
                        .elem("Location", baseUrl + "/" + bucket + "/" + key)
                        .elem("Bucket", bucket)
                        .elem("Key", key)
                        .elem("ETag", obj.getETag());
                if (obj.getVersionId() != null) {
                    xmlBuilder.elem("VersionId", obj.getVersionId());
                }
                String xml = xmlBuilder.end("CompleteMultipartUploadResult").build();
                var resp = Response.ok(xml);
                if (obj.getVersionId() != null) {
                    resp.header("x-amz-version-id", obj.getVersionId());
                }
                return resp.build();
            }

            return xmlErrorResponse(new AwsException("InvalidArgument",
                    "POST requires either ?uploads, ?uploadId, ?restore or ?select parameter.", 400));
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    private Response handleDeleteObjects(String bucket, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        List<String> keys = XmlParser.extractAll(xml, "Key");
        boolean quiet = XmlParser.containsValue(xml, "Quiet", "true");
        S3Service.DeleteObjectsResult result = s3Service.deleteObjects(bucket, keys);

        XmlBuilder builder = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("DeleteResult", AwsNamespaces.S3);
        if (!quiet) {
            for (S3Service.DeleteResult d : result.deleted()) {
                builder.start("Deleted").elem("Key", d.key());
                if (d.deleteMarker()) {
                    builder.elem("DeleteMarker", true);
                    if (d.deleteMarkerVersionId() != null) {
                        builder.elem("DeleteMarkerVersionId", d.deleteMarkerVersionId());
                    }
                }
                builder.end("Deleted");
            }
        }
        for (S3Service.DeleteError e : result.errors()) {
            builder.start("Error")
                   .elem("Key", e.key())
                   .elem("Code", e.code())
                   .elem("Message", e.message())
                   .end("Error");
        }
        builder.end("DeleteResult");
        return Response.ok(builder.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleListParts(String bucket, String key, String uploadId,
                                      Integer maxPartsParam, String partNumberMarkerParam) {
        MultipartUpload upload = s3Service.listParts(bucket, key, uploadId);
        int maxPartsLimit = (maxPartsParam != null && maxPartsParam > 0) ? maxPartsParam : 1000;
        int markerValue = 0;
        if (partNumberMarkerParam != null && !partNumberMarkerParam.isBlank()) {
            try {
                markerValue = Integer.parseInt(partNumberMarkerParam.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        final int marker = markerValue;

        List<Part> sortedParts = upload.getParts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> e.getKey() > marker)
                .limit(maxPartsLimit + 1L)
                .map(Map.Entry::getValue)
                .toList();

        boolean truncated = sortedParts.size() > maxPartsLimit;
        List<Part> page = truncated ? sortedParts.subList(0, maxPartsLimit) : sortedParts;
        String nextMarker = truncated ? String.valueOf(page.getLast().getPartNumber()) : null;

        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListPartsResult", AwsNamespaces.S3)
                .elem("Bucket", bucket)
                .elem("Key", key)
                .elem("UploadId", uploadId)
                .elem("PartNumberMarker", String.valueOf(marker))
                .elem("MaxParts", maxPartsLimit)
                .elem("IsTruncated", truncated);
        if (truncated) {
            xml.elem("NextPartNumberMarker", nextMarker);
        }
        for (Part part : page) {
            xml.start("Part")
               .elem("PartNumber", part.getPartNumber())
               .elem("LastModified", ISO_FORMAT.format(part.getLastModified()))
               .elem("ETag", part.getETag())
               .elem("Size", part.getSize())
               .end("Part");
        }
        xml.start("Initiator")
           .elem("ID", "owner")
           .elem("DisplayName", "owner")
           .end("Initiator")
           .start("Owner")
           .elem("ID", "owner")
           .elem("DisplayName", "owner")
           .end("Owner")
           .elem("StorageClass", upload.getStorageClass());
        xml.end("ListPartsResult");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleListMultipartUploads(String bucket) {
        List<MultipartUpload> uploads = s3Service.listMultipartUploads(bucket);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListMultipartUploadsResult", AwsNamespaces.S3)
                .elem("Bucket", bucket);
        for (MultipartUpload upload : uploads) {
            xml.start("Upload")
               .elem("Key", upload.getKey())
               .elem("UploadId", upload.getUploadId())
               .elem("Initiated", ISO_FORMAT.format(upload.getInitiated()))
               .end("Upload");
        }
        xml.end("ListMultipartUploadsResult");
        return Response.ok(xml.build()).build();
    }

    private List<Integer> parseCompleteMultipartBody(String xml) {
        List<String> parts = XmlParser.extractAll(xml, "PartNumber");
        if (parts.isEmpty()) {
            throw new AwsException("MalformedXML",
                    "The XML you provided was not well-formed.", 400);
        }
        return parts.stream().map(Integer::parseInt).toList();
    }

    // --- Versioning Operations ---

    private Response handlePutBucketVersioning(String bucket, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        String status = XmlParser.extractFirst(xml, "Status", null);
        if (status == null) {
            throw new AwsException("MalformedXML",
                    "The XML you provided was not well-formed.", 400);
        }
        s3Service.putBucketVersioning(bucket, status);
        return Response.ok().build();
    }

    private Response handleGetBucketVersioning(String bucket) {
        String status = s3Service.getBucketVersioning(bucket);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("VersioningConfiguration", AwsNamespaces.S3);
        if (status != null) {
            xml.elem("Status", status);
        }
        xml.end("VersioningConfiguration");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleListObjectVersions(String bucket, String prefix, Integer maxKeys, String keyMarker) {
        int max = (maxKeys != null && maxKeys > 0) ? maxKeys : 1000;
        S3Service.ListVersionsResult result = s3Service.listObjectVersions(bucket, prefix, max, keyMarker);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListVersionsResult", AwsNamespaces.S3)
                .elem("Name", bucket)
                .elem("Prefix", prefix)
                .elem("KeyMarker", keyMarker)
                .elem("MaxKeys", max)
                .elem("IsTruncated", result.isTruncated());
        if (result.isTruncated()) {
            xml.elem("NextKeyMarker", result.nextKeyMarker());
        }
        for (S3Object obj : result.versions()) {
            if (obj.isDeleteMarker()) {
                xml.start("DeleteMarker")
                   .elem("Key", obj.getKey())
                   .elem("VersionId", obj.getVersionId())
                   .elem("IsLatest", obj.isLatest())
                   .elem("LastModified", ISO_FORMAT.format(obj.getLastModified()))
                   .end("DeleteMarker");
            } else {
                xml.start("Version")
                   .elem("Key", obj.getKey())
                   .elem("VersionId", obj.getVersionId() != null ? obj.getVersionId() : "null")
                   .elem("IsLatest", obj.isLatest())
                   .elem("LastModified", ISO_FORMAT.format(obj.getLastModified()))
                   .elem("ETag", obj.getETag())
                   .elem("Size", obj.getSize())
                   .elem("StorageClass", obj.getStorageClass())
                   .end("Version");
            }
        }
        xml.end("ListVersionsResult");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // --- Notification Configuration ---

    private Response handleGetBucketNotification(String bucket) {
        try {
            NotificationConfiguration config = s3Service.getBucketNotificationConfiguration(bucket);
            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("NotificationConfiguration", AwsNamespaces.S3);
            for (QueueNotification qn : config.getQueueConfigurations()) {
                xml.start("QueueConfiguration")
                   .elem("Id", qn.id())
                   .elem("Queue", qn.queueArn());
                for (String event : qn.events()) {
                    xml.elem("Event", event);
                }
                appendFilterRules(xml, qn.filterRules());
                xml.end("QueueConfiguration");
            }
            for (TopicNotification tn : config.getTopicConfigurations()) {
                xml.start("TopicConfiguration")
                   .elem("Id", tn.id())
                   .elem("Topic", tn.topicArn());
                for (String event : tn.events()) {
                    xml.elem("Event", event);
                }
                appendFilterRules(xml, tn.filterRules());
                xml.end("TopicConfiguration");
            }
            for (LambdaNotification ln : config.getLambdaFunctionConfigurations()) {
                xml.start("CloudFunctionConfiguration")
                   .elem("Id", ln.id())
                   .elem("CloudFunction", ln.functionArn());
                for (String event : ln.events()) {
                    xml.elem("Event", event);
                }
                appendFilterRules(xml, ln.filterRules());
                xml.end("CloudFunctionConfiguration");
            }
            xml.end("NotificationConfiguration");
            return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    private Response handlePutBucketNotification(String bucket, byte[] body) {
        try {
            String xml = new String(body, StandardCharsets.UTF_8);
            NotificationConfiguration config = new NotificationConfiguration();

            for (var parsed : parseNotificationGroups(xml, "QueueConfiguration", "Queue")) {
                config.getQueueConfigurations().add(
                        new QueueNotification(parsed.id, parsed.arn, parsed.events, parsed.filterRules));
            }
            for (var parsed : parseNotificationGroups(xml, "TopicConfiguration", "Topic")) {
                config.getTopicConfigurations().add(
                        new TopicNotification(parsed.id, parsed.arn, parsed.events, parsed.filterRules));
            }
            for (var parsed : parseNotificationGroups(xml, "LambdaFunctionConfiguration", "LambdaFunctionArn")) {
                config.getLambdaFunctionConfigurations().add(
                        new LambdaNotification(parsed.id, parsed.arn, parsed.events, parsed.filterRules));
            }
            for (var parsed : parseNotificationGroups(xml, "CloudFunctionConfiguration", "CloudFunction")) {
                config.getLambdaFunctionConfigurations().add(
                        new LambdaNotification(parsed.id, parsed.arn, parsed.events, parsed.filterRules));
            }

            config.setEventBridgeEnabled(xml.contains("<EventBridgeConfiguration"));

            s3Service.putBucketNotificationConfiguration(bucket, config);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    private record ParsedNotificationGroup(String id, String arn, List<String> events,
                                            List<FilterRule> filterRules) {}

    private static List<ParsedNotificationGroup> parseNotificationGroups(
            String xml, String groupElement, String arnElement) {
        var groups = XmlParser.extractGroupsMulti(xml, groupElement);
        var filters = XmlParser.extractPairsPerGroup(xml, groupElement,
                "FilterRule", "Name", "Value");
        List<ParsedNotificationGroup> result = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            String id = group.getOrDefault("Id", List.of("")).getFirst();
            List<String> arns = group.get(arnElement);
            List<String> events = group.get("Event");
            if (arns != null && !arns.isEmpty() && events != null && !events.isEmpty()) {
                List<FilterRule> rules = i < filters.size()
                        ? filters.get(i).entrySet().stream()
                            .map(e -> new FilterRule(e.getKey(), e.getValue()))
                            .toList()
                        : List.of();
                result.add(new ParsedNotificationGroup(id, arns.getFirst(), events, rules));
            }
        }
        return result;
    }

    private static void appendFilterRules(XmlBuilder xml, List<FilterRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        xml.start("Filter").start("S3Key");
        for (FilterRule rule : rules) {
            xml.start("FilterRule")
               .elem("Name", rule.name())
               .elem("Value", rule.value())
               .end("FilterRule");
        }
        xml.end("S3Key").end("Filter");
    }

    /**
     * Strips the {@code aws-chunked} token from a {@code Content-Encoding} value before persisting it.
     * {@code aws-chunked} is a transfer-protocol marker used by AWS SDK v2 streaming uploads and is not
     * a real content encoding. For example, {@code gzip,aws-chunked} persists as {@code gzip};
     * a value of only {@code aws-chunked} persists as {@code null}.
     */
    private static String toPersistedContentEncoding(String contentEncoding) {
        if (contentEncoding == null) {
            return null;
        }
        String[] tokens = contentEncoding.split(",");
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (!trimmed.equalsIgnoreCase("aws-chunked")) {
                if (!result.isEmpty()) {
                    result.append(",");
                }
                result.append(trimmed);
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    // --- AWS Chunked Decoding ---

    /**
     * Decodes aws-chunked transfer encoding used by AWS SDK v2 with SigV4 chunk signing.
     * Format: hex-size;chunk-signature=sig\r\n data \r\n ... 0;chunk-signature=sig\r\n
     */
    private byte[] decodeAwsChunked(byte[] body, String contentEncoding, String contentSha256) {
        boolean isAwsChunked = (contentEncoding != null && contentEncoding.contains("aws-chunked"))
                || "STREAMING-AWS4-HMAC-SHA256-PAYLOAD".equals(contentSha256)
                || "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER".equals(contentSha256);
        if (!isAwsChunked) {
            return body;
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String raw = new String(body, StandardCharsets.ISO_8859_1);
            int pos = 0;
            while (pos < raw.length()) {
                int lineEnd = raw.indexOf('\n', pos);
                if (lineEnd < 0) break;
                String line = raw.substring(pos, lineEnd).trim();
                int semiColon = line.indexOf(';');
                String hexSize = semiColon >= 0 ? line.substring(0, semiColon) : line;
                int chunkSize = Integer.parseInt(hexSize.trim(), 16);
                if (chunkSize == 0) break;

                int dataStart = lineEnd + 1;
                byte[] chunkData = new byte[chunkSize];
                System.arraycopy(body, dataStart, chunkData, 0, chunkSize);
                out.write(chunkData);

                pos = dataStart + chunkSize;
                if (pos < raw.length() && raw.charAt(pos) == '\r') pos++;
                if (pos < raw.length() && raw.charAt(pos) == '\n') pos++;
            }
            return out.toByteArray();
        } catch (Exception e) {
            LOG.debugv("Failed to decode aws-chunked body, using raw: {0}", e.getMessage());
            return body;
        }
    }

    // --- Bucket Location ---

    private Response handleGetBucketLocation(String bucket) {
        String region = s3Service.getBucketRegion(bucket);
        String xml;
        if (region == null || "us-east-1".equals(region)) {
            xml = "<LocationConstraint xmlns=\"" + AwsNamespaces.S3 + "\"/>";
        } else {
            xml = new XmlBuilder()
                    .start("LocationConstraint", AwsNamespaces.S3)
                    .raw(XmlBuilder.escape(region))
                    .end("LocationConstraint")
                    .build();
        }
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    // --- Bucket Tagging ---

    private Response handlePutBucketTagging(String bucket, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
        s3Service.putBucketTagging(bucket, tags);
        return Response.noContent().build();
    }

    private Response handleGetBucketTagging(String bucket) {
        Map<String, String> tags = s3Service.getBucketTagging(bucket);
        return Response.ok(buildTaggingXml(tags)).type(MediaType.APPLICATION_XML).build();
    }

    // --- Object Tagging ---

    private Response handlePutObjectTagging(String bucket, String key, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
        s3Service.putObjectTagging(bucket, key, tags);
        return Response.ok().build();
    }

    private Response handleGetObjectTagging(String bucket, String key) {
        Map<String, String> tags = s3Service.getObjectTagging(bucket, key);
        return Response.ok(buildTaggingXml(tags)).type(MediaType.APPLICATION_XML).build();
    }

    private String buildTaggingXml(Map<String, String> tags) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Tagging", AwsNamespaces.S3)
                .start("TagSet");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            xml.start("Tag")
               .elem("Key", entry.getKey())
               .elem("Value", entry.getValue())
               .end("Tag");
        }
        xml.end("TagSet").end("Tagging");
        return xml.build();
    }

    // --- Object Lock Configuration ---

    private Response handlePutObjectLockConfiguration(String bucket, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        String mode = XmlParser.extractFirst(xml, "Mode", null);
        String daysStr = XmlParser.extractFirst(xml, "Days", null);
        String yearsStr = XmlParser.extractFirst(xml, "Years", null);
        String unit = null;
        int value = 0;
        if (daysStr != null) {
            unit = "Days";
            value = Integer.parseInt(daysStr);
        } else if (yearsStr != null) {
            unit = "Years";
            value = Integer.parseInt(yearsStr);
        }
        s3Service.putObjectLockConfiguration(bucket, mode, unit, value);
        return Response.ok().build();
    }

    private Response handleGetObjectLockConfiguration(String bucket) {
        ObjectLockRetention retention =
                s3Service.getObjectLockConfiguration(bucket);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ObjectLockConfiguration", AwsNamespaces.S3)
                .elem("ObjectLockEnabled", "Enabled");
        if (retention != null) {
            xml.start("Rule").start("DefaultRetention")
               .elem("Mode", retention.mode());
            if ("Days".equals(retention.unit())) {
                xml.elem("Days", retention.value());
            } else {
                xml.elem("Years", retention.value());
            }
            xml.end("DefaultRetention").end("Rule");
        }
        xml.end("ObjectLockConfiguration");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // --- Object Retention ---

    private Response handlePutObjectRetention(String bucket, String key, String versionId,
                                               HttpHeaders httpHeaders, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        String mode = XmlParser.extractFirst(xml, "Mode", null);
        String dateStr = XmlParser.extractFirst(xml, "RetainUntilDate", null);
        Instant retainUntil = dateStr != null ? Instant.parse(dateStr) : null;
        boolean bypass = "true".equalsIgnoreCase(
                httpHeaders.getHeaderString("x-amz-bypass-governance-retention"));
        s3Service.putObjectRetention(bucket, key, versionId, mode, retainUntil, bypass);
        return Response.ok().build();
    }

    private Response handleGetObjectRetention(String bucket, String key, String versionId) {
        S3Object obj = s3Service.getObjectRetention(bucket, key, versionId);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Retention", AwsNamespaces.S3)
                .elem("Mode", obj.getObjectLockMode());
        if (obj.getRetainUntilDate() != null) {
            xml.elem("RetainUntilDate", ISO_FORMAT.format(obj.getRetainUntilDate()));
        }
        xml.end("Retention");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // --- Legal Hold ---

    private Response handlePutObjectLegalHold(String bucket, String key, String versionId, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        String status = XmlParser.extractFirst(xml, "Status", null);
        if (status == null) {
            return xmlErrorResponse(new AwsException("MalformedXML",
                    "The XML you provided was not well-formed.", 400));
        }
        s3Service.putObjectLegalHold(bucket, key, versionId, status);
        return Response.ok().build();
    }

    private Response handleGetObjectLegalHold(String bucket, String key, String versionId) {
        S3Object obj = s3Service.getObjectLegalHold(bucket, key, versionId);
        String status = obj.getLegalHoldStatus() != null ? obj.getLegalHoldStatus() : "OFF";
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("LegalHold", AwsNamespaces.S3)
                .elem("Status", status)
                .end("LegalHold")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private void appendObjectHeaders(Response.ResponseBuilder resp, S3Object obj) {
        if (obj.getStorageClass() != null) {
            resp.header("x-amz-storage-class", obj.getStorageClass());
        }
        if (obj.getContentEncoding() != null) {
            resp.header("Content-Encoding", obj.getContentEncoding());
        }
        if (obj.getContentDisposition() != null) {
            resp.header("Content-Disposition", obj.getContentDisposition());
        }
        if (obj.getCacheControl() != null) {
            resp.header("Cache-Control", obj.getCacheControl());
        }
        if (obj.getMetadata() != null) {
            for (Map.Entry<String, String> entry : obj.getMetadata().entrySet()) {
                resp.header("x-amz-meta-" + entry.getKey(), entry.getValue());
            }
        }
        appendChecksumHeaders(resp, obj.getChecksum());
        appendLockHeaders(resp, obj);
    }

    private void appendLockHeaders(Response.ResponseBuilder resp, S3Object obj) {
        if (obj.getObjectLockMode() != null) {
            resp.header("x-amz-object-lock-mode", obj.getObjectLockMode());
        }
        if (obj.getRetainUntilDate() != null) {
            resp.header("x-amz-object-lock-retain-until-date",
                    DateTimeFormatter.ISO_INSTANT.format(obj.getRetainUntilDate()));
        }
        if (obj.getLegalHoldStatus() != null) {
            resp.header("x-amz-object-lock-legal-hold", obj.getLegalHoldStatus());
        }
    }

    // --- Helpers ---

    private Response handleCopyObject(String copySource, String destBucket, String destKey,
                                      String contentType, HttpHeaders httpHeaders) {
        // copySource format: /bucket/key or bucket/key, where key is URL-encoded
        String source = copySource.startsWith("/") ? copySource.substring(1) : copySource;
        
        // URL decode the entire source first, then split
        String decodedSource;
        try {
            decodedSource = URLDecoder.decode(source, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgument", "Invalid copy source: " + copySource, 400);
        }
        int slashIndex = decodedSource.indexOf('/');
        if (slashIndex <= 0) {
            throw new AwsException("InvalidArgument", "Invalid copy source: " + copySource, 400);
        }
        String sourceBucket = decodedSource.substring(0, slashIndex);
        String sourceKey = decodedSource.substring(slashIndex + 1);

        String copyContentEncoding = toPersistedContentEncoding(httpHeaders.getHeaderString("Content-Encoding"));
        String copyContentDisposition = httpHeaders.getHeaderString("Content-Disposition");
        String copyCacheControl = httpHeaders.getHeaderString("Cache-Control");
        String cannedAcl = httpHeaders.getHeaderString("x-amz-acl");
        S3Object copy = s3Service.copyObject(sourceBucket, sourceKey, destBucket, destKey,
                httpHeaders.getHeaderString("x-amz-metadata-directive"),
                extractUserMetadata(httpHeaders),
                httpHeaders.getHeaderString("x-amz-storage-class"),
                contentType,
                copyContentEncoding,
                copyContentDisposition,
                copyCacheControl,
                cannedAcl);
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("CopyObjectResult", AwsNamespaces.S3)
                .elem("LastModified", ISO_FORMAT.format(copy.getLastModified()))
                .elem("ETag", copy.getETag())
                .end("CopyObjectResult")
                .build();
        return Response.ok(xml).build();
    }

    private Response handleUploadPartCopy(String copySource, String destBucket, String destKey,
                                           String uploadId, int partNumber, HttpHeaders httpHeaders) {
        // copySource format: /bucket/key or bucket/key, where key is URL-encoded
        String source = copySource.startsWith("/") ? copySource.substring(1) : copySource;

        // URL decode the entire source first, then split.
        String decodedSource;
        try {
            decodedSource = URLDecoder.decode(source, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgument", "Invalid copy source: " + copySource, 400);
        }
        int slashIndex = decodedSource.indexOf('/');
        if (slashIndex <= 0) {
            throw new AwsException("InvalidArgument", "Invalid copy source: " + copySource, 400);
        }
        String sourceBucket = decodedSource.substring(0, slashIndex);
        String sourceKey = decodedSource.substring(slashIndex + 1);
        String copySourceRange = httpHeaders.getHeaderString("x-amz-copy-source-range");
        String eTag = s3Service.uploadPartCopy(destBucket, destKey, uploadId, partNumber,
                sourceBucket, sourceKey, copySourceRange);
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("CopyPartResult", AwsNamespaces.S3)
                .elem("LastModified", ISO_FORMAT.format(java.time.Instant.now()))
                .elem("ETag", eTag)
                .end("CopyPartResult")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleGetObjectAttributes(String bucket, String key, String versionId,
                                               String objectAttributesHeader, Integer maxParts,
                                               Integer partNumberMarker) {
        Set<ObjectAttributeName> attributes = ObjectAttributeName.parseHeader(objectAttributesHeader);
        GetObjectAttributesResult result = s3Service.getObjectAttributes(bucket, key, versionId,
                attributes, maxParts, partNumberMarker);

        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("GetObjectAttributesResponse", AwsNamespaces.S3)
                .elem("ETag", result.getETag());
        appendChecksum(xml, result.getChecksum());
        appendObjectParts(xml, result.getObjectParts());
        if (result.getStorageClass() != null) {
            xml.elem("StorageClass", result.getStorageClass());
        }
        if (result.getObjectSize() != null) {
            xml.elem("ObjectSize", result.getObjectSize());
        }
        xml.end("GetObjectAttributesResponse");

        Response.ResponseBuilder response = Response.ok(xml.build()).type(MediaType.APPLICATION_XML);
        if (result.getLastModified() != null) {
            response.header("Last-Modified", RFC_822.format(result.getLastModified()));
        }
        if (result.getVersionId() != null) {
            response.header("x-amz-version-id", result.getVersionId());
        }
        return response.build();
    }

    private void appendChecksum(XmlBuilder xml, S3Checksum checksum) {
        if (checksum == null || !checksum.hasAnyValue()) {
            return;
        }
        xml.start("Checksum")
                .elem("ChecksumCRC32", checksum.getChecksumCRC32())
                .elem("ChecksumCRC32C", checksum.getChecksumCRC32C())
                .elem("ChecksumCRC64NVME", checksum.getChecksumCRC64NVME())
                .elem("ChecksumSHA1", checksum.getChecksumSHA1())
                .elem("ChecksumSHA256", checksum.getChecksumSHA256())
                .elem("ChecksumType", checksum.getChecksumType())
                .end("Checksum");
    }

    private void appendObjectParts(XmlBuilder xml, GetObjectAttributesParts objectParts) {
        if (objectParts == null) {
            return;
        }
        xml.start("ObjectParts")
                .elem("IsTruncated", objectParts.isTruncated())
                .elem("MaxParts", objectParts.getMaxParts())
                .elem("NextPartNumberMarker", objectParts.getNextPartNumberMarker())
                .elem("PartNumberMarker", objectParts.getPartNumberMarker());
        for (Part part : objectParts.getParts()) {
            xml.start("Part")
                    .elem("ChecksumCRC32", part.getChecksum().getChecksumCRC32())
                    .elem("ChecksumCRC32C", part.getChecksum().getChecksumCRC32C())
                    .elem("ChecksumCRC64NVME", part.getChecksum().getChecksumCRC64NVME())
                    .elem("ChecksumSHA1", part.getChecksum().getChecksumSHA1())
                    .elem("ChecksumSHA256", part.getChecksum().getChecksumSHA256())
                    .elem("PartNumber", part.getPartNumber())
                    .elem("Size", part.getSize())
                    .end("Part");
        }
        xml.elem("PartsCount", objectParts.getPartsCount())
                .end("ObjectParts");
    }

    private void appendChecksumHeaders(Response.ResponseBuilder resp, S3Checksum checksum) {
        if (checksum == null) {
            return;
        }
        if (checksum.getChecksumSHA1() != null) {
            resp.header("x-amz-checksum-sha1", checksum.getChecksumSHA1());
        }
        if (checksum.getChecksumSHA256() != null) {
            resp.header("x-amz-checksum-sha256", checksum.getChecksumSHA256());
        }
    }

    private Map<String, String> extractUserMetadata(HttpHeaders httpHeaders) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : httpHeaders.getRequestHeaders().entrySet()) {
            String headerName = entry.getKey().toLowerCase(Locale.ROOT);
            if (!headerName.startsWith("x-amz-meta-")) {
                continue;
            }
            String key = headerName.substring("x-amz-meta-".length());
            if (!key.isBlank() && !entry.getValue().isEmpty()) {
                metadata.put(key, entry.getValue().get(0));
            }
        }
        return metadata;
    }

    private void validateChecksumHeaders(HttpHeaders httpHeaders, byte[] data) {
        String sha1 = httpHeaders.getHeaderString("x-amz-checksum-sha1");
        if (sha1 != null && !sha1.equals(S3Checksum.sha1Base64(data))) {
            throw new AwsException("BadDigest", "The SHA1 checksum you specified did not match the payload.", 400);
        }

        String sha256 = httpHeaders.getHeaderString("x-amz-checksum-sha256");
        if (sha256 != null && !sha256.equals(S3Checksum.sha256Base64(data))) {
            throw new AwsException("BadDigest", "The SHA256 checksum you specified did not match the payload.", 400);
        }
    }

    private Response xmlErrorResponse(AwsException e) {
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .elem("RequestId", java.util.UUID.randomUUID().toString())
                .end("Error")
                .build();
        return Response.status(e.getHttpStatus()).entity(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response checkPreconditions(S3Object obj, String ifMatch, String ifNoneMatch,
                                         String ifModifiedSince, String ifUnmodifiedSince) {
        String eTag = obj.getETag();
        Instant lastModified = obj.getLastModified();

        if (ifMatch != null && !eTagMatches(ifMatch, eTag)) {
            return preconditionFailedResponse();
        }

        if (ifUnmodifiedSince != null && ifMatch == null) {
            Instant since = parseHttpDate(ifUnmodifiedSince);
            if (since != null && lastModified.isAfter(since)) {
                return preconditionFailedResponse();
            }
        }

        if (ifNoneMatch != null && eTagMatches(ifNoneMatch, eTag)) {
            return notModifiedResponse(eTag, lastModified);
        }

        if (ifModifiedSince != null && ifNoneMatch == null) {
            Instant since = parseHttpDate(ifModifiedSince);
            if (since != null && !lastModified.isAfter(since)) {
                return notModifiedResponse(eTag, lastModified);
            }
        }

        return null;
    }

    private boolean hasPreconditions(String ifMatch, String ifNoneMatch,
                                      String ifModifiedSince, String ifUnmodifiedSince) {
        return ifMatch != null || ifNoneMatch != null || ifModifiedSince != null || ifUnmodifiedSince != null;
    }

    private Response notModifiedResponse(String eTag, Instant lastModified) {
        return Response.notModified()
                .header("ETag", eTag)
                .header("Last-Modified", RFC_822.format(lastModified))
                .build();
    }

    private Response preconditionFailedResponse() {
        return xmlErrorResponse(new AwsException("PreconditionFailed",
                "At least one of the pre-conditions you specified did not hold.", 412));
    }

    private boolean eTagMatches(String headerValue, String eTag) {
        if ("*".equals(headerValue.trim())) {
            return true;
        }
        for (String candidate : headerValue.split(",")) {
            if (candidate.trim().equals(eTag)) {
                return true;
            }
        }
        return false;
    }

    private Instant parseHttpDate(String dateStr) {
        try {
            return RFC_822.parse(dateStr.trim(), Instant::from);
        } catch (Exception e) {
            try {
                return Instant.parse(dateStr.trim());
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private Response handlePresignedPost(String bucket, String contentType, byte[] body) {
        try {
            return doHandlePresignedPost(bucket, contentType, body);
        } catch (AwsException e) {
            // Presigned POST errors must be returned as XML (matching LocalStack/AWS),
            // not JSON which is what the global AwsExceptionMapper would produce.
            return xmlErrorResponse(e);
        }
    }

    private Response doHandlePresignedPost(String bucket, String contentType, byte[] body) {
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new AwsException("InvalidArgument",
                    "Could not determine multipart boundary from Content-Type.", 400);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        byte[] fileData = null;
        String fileContentType = null;

        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = splitMultipartParts(body, boundaryBytes);

        for (byte[] part : parts) {
            int headerEnd = indexOfDoubleNewline(part);
            if (headerEnd < 0) {
                continue;
            }
            String headers = new String(part, 0, headerEnd, StandardCharsets.UTF_8);
            int bodyStart = headerEnd + 4; // skip \r\n\r\n
            byte[] partBody = Arrays.copyOfRange(part, bodyStart, part.length);

            // Trim trailing \r\n from part body
            if (partBody.length >= 2
                    && partBody[partBody.length - 2] == '\r'
                    && partBody[partBody.length - 1] == '\n') {
                partBody = Arrays.copyOf(partBody, partBody.length - 2);
            }

            String disposition = extractHeaderValue(headers, "Content-Disposition");
            if (disposition == null) {
                continue;
            }
            String fieldName = extractDispositionParam(disposition, "name");
            if (fieldName == null) {
                continue;
            }

            String filename = extractDispositionParam(disposition, "filename");
            if (filename != null) {
                fileData = partBody;
                String partContentType = extractHeaderValue(headers, "Content-Type");
                if (partContentType != null) {
                    fileContentType = partContentType.trim();
                }
            } else {
                fields.put(fieldName, new String(partBody, StandardCharsets.UTF_8));
            }
        }

        String key = fields.get("key");
        if (key == null || key.isEmpty()) {
            throw new AwsException("InvalidArgument",
                    "Bucket POST must contain a field named 'key'.", 400);
        }
        validateKeyNoTraversal(key);

        if (fileData == null) {
            throw new AwsException("InvalidArgument",
                    "Bucket POST must contain a file field.", 400);
        }

        // Build a case-insensitive (lowercased) view of the form fields for policy
        // validation, matching the behaviour of LocalStack and real AWS S3.
        // The AWS SDK sends "Policy" (capital P) while some clients use "policy".
        Map<String, String> lcFields = new LinkedHashMap<>(fields.size());
        for (Map.Entry<String, String> e : fields.entrySet()) {
            lcFields.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }

        // Validate policy conditions if present
        String policy = lcFields.get("policy");
        if (policy != null && !policy.isEmpty()) {
            validatePolicyConditions(policy, bucket, lcFields, fileData.length);
        }

        // Use Content-Type from form fields, fall back to file part Content-Type
        String objectContentType = fields.get("Content-Type");
        if (objectContentType == null || objectContentType.isEmpty()) {
            objectContentType = fileContentType;
        }
        if (objectContentType == null || objectContentType.isEmpty()) {
            objectContentType = "application/octet-stream";
        }

        S3Object obj = s3Service.putObject(bucket, key, fileData, objectContentType, null);
        LOG.infov("Presigned POST upload: {0}/{1} ({2} bytes)", bucket, key, fileData.length);

        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("PostResponse")
                .elem("Location", bucket + "/" + key)
                .elem("Bucket", bucket)
                .elem("Key", key)
                .elem("ETag", obj.getETag())
                .end("PostResponse")
                .build();
        return Response.status(204)
                .header("ETag", obj.getETag())
                .header("Location", bucket + "/" + key)
                .build();
    }

    private void validatePolicyConditions(String policyBase64, String bucket,
                                           Map<String, String> fields, int contentLength) {
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(policyBase64);
            JsonNode policy = OBJECT_MAPPER.readTree(decoded);
            JsonNode conditions = policy.get("conditions");
            if (conditions == null || !conditions.isArray()) {
                return;
            }
            for (JsonNode condition : conditions) {
                if (condition.isObject()) {
                    validateExactMatchCondition(condition, bucket, fields);
                } else if (condition.isArray()) {
                    validateArrayCondition(condition, bucket, fields, contentLength);
                }
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv("Failed to parse presigned POST policy: {0}", e.getMessage());
        }
    }

    private void validateExactMatchCondition(JsonNode condition, String bucket, Map<String, String> fields) {
        Iterator<Map.Entry<String, JsonNode>> fieldIter = condition.fields();
        while (fieldIter.hasNext()) {
            Map.Entry<String, JsonNode> entry = fieldIter.next();
            String fieldName = entry.getKey();
            String expectedValue = entry.getValue().asText();
            String actualValue;
            String lookupKey = fieldName.toLowerCase(Locale.ROOT);
            if ("bucket".equals(lookupKey)) {
                actualValue = bucket;
            } else {
                actualValue = fields.get(lookupKey);
            }
            if (actualValue == null || !actualValue.equals(expectedValue)) {
                throw new AwsException("AccessDenied",
                        "Invalid according to Policy: Policy Condition failed: "
                                + "[\"eq\", \"$" + fieldName + "\", \"" + expectedValue + "\"]", 403);
            }
        }
    }

    private void validateArrayCondition(JsonNode condition, String bucket,
                                        Map<String, String> fields, int contentLength) {
        if (condition.size() < 3) {
            return;
        }
        String operator = condition.get(0).asText().toLowerCase(Locale.ROOT);
        if ("content-length-range".equals(operator)) {
            long min = condition.get(1).asLong();
            long max = condition.get(2).asLong();
            if (contentLength < min || contentLength > max) {
                throw new AwsException("EntityTooLarge",
                        "Your proposed upload exceeds the maximum allowed size.", 400);
            }
        } else if ("eq".equals(operator)) {
            String fieldRef = condition.get(1).asText();
            String expectedValue = condition.get(2).asText();
            String fieldName = fieldRef.startsWith("$") ? fieldRef.substring(1) : fieldRef;
            String actualValue = resolveFieldValue(fieldName.toLowerCase(Locale.ROOT), bucket, fields);
            if (actualValue == null || !actualValue.equals(expectedValue)) {
                throw new AwsException("AccessDenied",
                        "Invalid according to Policy: Policy Condition failed: "
                                + "[\"eq\", \"$" + fieldName + "\", \"" + expectedValue + "\"]", 403);
            }
        } else if ("starts-with".equals(operator)) {
            String fieldRef = condition.get(1).asText();
            String prefix = condition.get(2).asText();
            String fieldName = fieldRef.startsWith("$") ? fieldRef.substring(1) : fieldRef;
            String actualValue = resolveFieldValue(fieldName.toLowerCase(Locale.ROOT), bucket, fields);
            if (actualValue == null || !actualValue.startsWith(prefix)) {
                throw new AwsException("AccessDenied",
                        "Invalid according to Policy: Policy Condition failed: "
                                + "[\"starts-with\", \"$" + fieldName + "\", \"" + prefix + "\"]", 403);
            }
        }
    }

    private static String resolveFieldValue(String fieldName, String bucket, Map<String, String> fields) {
        if ("bucket".equals(fieldName)) {
            return bucket;
        }
        return fields.get(fieldName);
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    private static List<byte[]> splitMultipartParts(byte[] body, byte[] boundary) {
        java.util.ArrayList<byte[]> parts = new java.util.ArrayList<>();
        int pos = indexOf(body, boundary, 0);
        if (pos < 0) {
            return parts;
        }
        // Skip past the first boundary line
        pos += boundary.length;
        // Skip the CRLF or -- after boundary
        if (pos < body.length - 1 && body[pos] == '-' && body[pos + 1] == '-') {
            return parts; // closing boundary immediately
        }
        if (pos < body.length - 1 && body[pos] == '\r' && body[pos + 1] == '\n') {
            pos += 2;
        }

        while (pos < body.length) {
            int nextBoundary = indexOf(body, boundary, pos);
            if (nextBoundary < 0) {
                break;
            }
            parts.add(Arrays.copyOfRange(body, pos, nextBoundary));
            pos = nextBoundary + boundary.length;
            // Check for closing boundary --
            if (pos < body.length - 1 && body[pos] == '-' && body[pos + 1] == '-') {
                break;
            }
            // Skip CRLF after boundary
            if (pos < body.length - 1 && body[pos] == '\r' && body[pos + 1] == '\n') {
                pos += 2;
            }
        }
        return parts;
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int indexOfDoubleNewline(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String extractHeaderValue(String headers, String headerName) {
        String lowerHeaders = headers.toLowerCase(Locale.ROOT);
        String lowerName = headerName.toLowerCase(Locale.ROOT) + ":";
        int idx = lowerHeaders.indexOf(lowerName);
        if (idx < 0) {
            return null;
        }
        int valueStart = idx + lowerName.length();
        int lineEnd = headers.indexOf('\r', valueStart);
        if (lineEnd < 0) {
            lineEnd = headers.indexOf('\n', valueStart);
        }
        if (lineEnd < 0) {
            lineEnd = headers.length();
        }
        return headers.substring(valueStart, lineEnd).trim();
    }

    private static String extractDispositionParam(String disposition, String paramName) {
        String search = paramName + "=";
        int idx = disposition.indexOf(search);
        if (idx < 0) {
            return null;
        }
        int valueStart = idx + search.length();
        if (valueStart >= disposition.length()) {
            return null;
        }
        if (disposition.charAt(valueStart) == '"') {
            valueStart++;
            int valueEnd = disposition.indexOf('"', valueStart);
            if (valueEnd < 0) {
                return disposition.substring(valueStart);
            }
            return disposition.substring(valueStart, valueEnd);
        } else {
            int valueEnd = disposition.indexOf(';', valueStart);
            if (valueEnd < 0) {
                valueEnd = disposition.length();
            }
            return disposition.substring(valueStart, valueEnd).trim();
        }
    }

    private boolean hasQueryParam(UriInfo uriInfo, String param) {
        if (uriInfo.getQueryParameters().containsKey(param)) return true;
        String query = uriInfo.getRequestUri().getQuery();
        if (query == null) return false;
        return query.equals(param) || query.contains(param + "&") || query.contains("&" + param);
    }

    /**
     * Extracts the object key from the raw Vert.x request URI, preserving leading slashes
     * that JAX-RS path normalization would otherwise strip.
     */
    private String extractObjectKey(UriInfo uriInfo, String bucket) {
        String rawUri = currentVertxRequest.getCurrent().request().uri();
        int qIdx = rawUri.indexOf('?');
        String rawPath = qIdx >= 0 ? rawUri.substring(0, qIdx) : rawUri;
        String bucketPrefix = "/" + bucket + "/";
        int prefixIndex = rawPath.indexOf(bucketPrefix);
        if (prefixIndex < 0) {
            // Should not happen — route already matched /{bucket}/{key:.+}
            return uriInfo.getPathParameters().getFirst("key");
        }
        String rawKey = rawPath.substring(prefixIndex + bucketPrefix.length());
        String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
        validateKeyNoTraversal(key);
        return key;
    }

    private void validateKeyNoTraversal(String key) {
        if (key == null) return;
        try {
            // Use a dummy root to mirror the service pattern, allowing leading slashes but keeping them in sandbox
            java.nio.file.Path dummyRoot = java.nio.file.Path.of("/s3-sandbox");
            String safeKey = key;
            while (safeKey.startsWith("/")) {
                safeKey = safeKey.substring(1);
            }
            java.nio.file.Path resolved = dummyRoot.resolve(safeKey).normalize();
            if (!resolved.startsWith(dummyRoot)) {
                throw new AwsException("InvalidKey", "The specified key is invalid.", 400);
            }
        } catch (java.nio.file.InvalidPathException e) {
            throw new AwsException("InvalidKey", "The specified key contains invalid characters.", 400);
        }
    }

    private void validateRawUri() {
        String rawUri = currentVertxRequest.getCurrent().request().uri();
        String lower = rawUri.toLowerCase();
        if (lower.contains("/..") || lower.contains("../") || lower.contains("%2e%2e") || lower.contains("%2e.") || lower.contains(".%2e")) {
            throw new AwsException("InvalidKey", "The specified key is invalid.", 400);
        }
    }
}
