package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesParts;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private final S3Service s3Service;
    private final S3SelectService s3SelectService;
    private final RegionResolver regionResolver;

    @Inject
    public S3Controller(S3Service s3Service, S3SelectService s3SelectService,
                        RegionResolver regionResolver) {
        this.s3Service = s3Service;
        this.s3SelectService = s3SelectService;
        this.regionResolver = regionResolver;
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
            return Response.ok()
                    .header("x-amz-bucket-region", regionResolver.getDefaultRegion())
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

            String locationConstraint = null;
            if (body != null && body.length > 0) {
                locationConstraint = XmlParser.extractFirst(new String(body, StandardCharsets.UTF_8),
                        "LocationConstraint", null);
            }
            String region = locationConstraint != null ? locationConstraint : regionResolver.getDefaultRegion();
            s3Service.createBucket(bucket, region);
            String lockEnabled = httpHeaders.getHeaderString("x-amz-bucket-object-lock-enabled");
            if ("true".equalsIgnoreCase(lockEnabled)) {
                s3Service.putBucketVersioning(bucket, "Enabled");
                s3Service.setBucketObjectLockEnabled(bucket);
            }
            return Response.ok().build();
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
                                @Context UriInfo uriInfo) {
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
                return handleListObjectVersions(bucket, prefix, maxKeys);
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

            int max = (maxKeys != null && maxKeys > 0) ? maxKeys : 1000;
            List<S3Object> objects = s3Service.listObjects(bucket, prefix, delimiter, max);
            boolean v2 = "2".equals(listType);

            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("ListBucketResult", AwsNamespaces.S3)
                    .elem("Name", bucket)
                    .elem("Prefix", prefix)
                    .elem("Delimiter", delimiter)
                    .elem("MaxKeys", max);
            if (v2) {
                xml.elem("KeyCount", objects.size());
            }
            xml.elem("IsTruncated", false);
            for (S3Object obj : objects) {
                xml.start("Contents")
                   .elem("Key", obj.getKey())
                   .elem("LastModified", ISO_FORMAT.format(obj.getLastModified()))
                   .elem("ETag", obj.getETag())
                   .elem("Size", obj.getSize())
                   .elem("StorageClass", obj.getStorageClass())
                   .end("Contents");
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
            S3Object obj = s3Service.putObject(bucket, key, data, contentType, extractUserMetadata(httpHeaders),
                    httpHeaders.getHeaderString("x-amz-storage-class"),
                    lockMode, retainUntil, legalHold);
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
                              @HeaderParam("x-amz-object-attributes") String objectAttributesHeader,
                              @QueryParam("max-parts") Integer maxParts,
                              @QueryParam("part-number-marker") Integer partNumberMarker,
                              @HeaderParam("range") String rangeHeader,
                              @Context UriInfo uriInfo) {
        try {
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
                return handleGetObjectAttributes(bucket, key, versionId,
                        objectAttributesHeader, maxParts, partNumberMarker);
            }
            S3Object obj = s3Service.getObject(bucket, key, versionId);
            var data = obj.getData();
            long totalSize = data.length;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                var rangeSpec = rangeHeader.substring(6);
                long start, end;
                try {
                    if (rangeSpec.startsWith("-")) {
                        // suffix range: bytes=-N (last N bytes)
                        var suffixLen = Long.parseLong(rangeSpec.substring(1));
                        if (suffixLen <= 0) {
                            throw new AwsException("InvalidRange",
                                    "The requested range is not satisfiable.", 416);
                        }
                        start = Math.max(0, totalSize - suffixLen);
                        end = totalSize - 1;
                    } else if (rangeSpec.endsWith("-")) {
                        // open-ended: bytes=N-
                        start = Long.parseLong(rangeSpec.substring(0, rangeSpec.length() - 1));
                        end = totalSize - 1;
                    } else {
                        // explicit: bytes=N-M
                        var parts = rangeSpec.split("-");
                        if (parts.length != 2) {
                            throw new AwsException("InvalidRange",
                                    "The requested range is not satisfiable.", 416);
                        }
                        start = Long.parseLong(parts[0]);
                        end = Long.parseLong(parts[1]);
                        if (start > end) {
                            throw new AwsException("InvalidRange",
                                    "The requested range is not satisfiable.", 416);
                        }
                        end = Math.min(end, totalSize - 1);
                    }
                } catch (NumberFormatException e) {
                    throw new AwsException("InvalidRange",
                            "The requested range is not satisfiable.", 416);
                }
                if (start < 0 || start >= totalSize) {
                    throw new AwsException("InvalidRange",
                            "The requested range is not satisfiable.", 416);
                }
                long length = end - start + 1;
                var slice = java.util.Arrays.copyOfRange(data, (int) start, (int) (start + length));
                var resp = Response.status(206)
                        .entity(slice)
                        .header("Content-Type", obj.getContentType())
                        .header("Content-Length", length)
                        .header("Content-Range", "bytes " + start + "-" + end + "/" + totalSize)
                        .header("Accept-Ranges", "bytes")
                        .header("ETag", obj.getETag())
                        .header("Last-Modified", RFC_822.format(obj.getLastModified()));
                if (obj.getVersionId() != null) {
                    resp.header("x-amz-version-id", obj.getVersionId());
                }
                appendLockHeaders(resp, obj);
                return resp.build();
            }

            var resp = Response.ok(data)
                    .header("Content-Type", obj.getContentType())
                    .header("Content-Length", totalSize)
                    .header("Accept-Ranges", "bytes")
                    .header("ETag", obj.getETag())
                    .header("Last-Modified", RFC_822.format(obj.getLastModified()));
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            appendObjectHeaders(resp, obj);
            return resp.build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @HEAD
    @Path("/{bucket}/{key:.+}")
    public Response headObject(@PathParam("bucket") String bucket,
                               @PathParam("key") String key,
                               @QueryParam("versionId") String versionId) {
        try {
            S3Object obj = s3Service.headObject(bucket, key, versionId);
            var resp = Response.ok()
                    .header("Content-Type", obj.getContentType())
                    .header("Content-Length", obj.getSize())
                    .header("Accept-Ranges", "bytes")
                    .header("ETag", obj.getETag())
                    .header("Last-Modified", RFC_822.format(obj.getLastModified()));
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            appendObjectHeaders(resp, obj);
            return resp.build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
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
                                      @Context UriInfo uriInfo,
                                      byte[] body) {
        try {
            if (hasQueryParam(uriInfo, "delete")) {
                return handleDeleteObjects(bucket, body);
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
            if (hasQueryParam(uriInfo, "uploads")) {
                MultipartUpload upload = s3Service.initiateMultipartUpload(bucket, key, contentType,
                        extractUserMetadata(httpHeaders), httpHeaders.getHeaderString("x-amz-storage-class"));
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

    private Response handleListObjectVersions(String bucket, String prefix, Integer maxKeys) {
        int max = (maxKeys != null && maxKeys > 0) ? maxKeys : 1000;
        List<S3Object> versions = s3Service.listObjectVersions(bucket, prefix, max);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListVersionsResult", AwsNamespaces.S3)
                .elem("Name", bucket)
                .elem("Prefix", prefix)
                .elem("MaxKeys", max);
        for (S3Object obj : versions) {
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
                   .elem("VersionId", obj.getVersionId())
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
                xml.end("QueueConfiguration");
            }
            for (TopicNotification tn : config.getTopicConfigurations()) {
                xml.start("TopicConfiguration")
                   .elem("Id", tn.id())
                   .elem("Topic", tn.topicArn());
                for (String event : tn.events()) {
                    xml.elem("Event", event);
                }
                xml.end("TopicConfiguration");
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

            var queueConfigs = XmlParser.extractGroupsMulti(xml, "QueueConfiguration");
            for (var group : queueConfigs) {
                String id = group.getOrDefault("Id", List.of("")).get(0);
                List<String> queueArns = group.get("Queue");
                List<String> events = group.get("Event");
                if (queueArns != null && !queueArns.isEmpty() && events != null && !events.isEmpty()) {
                    config.getQueueConfigurations().add(new QueueNotification(id, queueArns.get(0), events));
                }
            }

            var topicConfigs = XmlParser.extractGroupsMulti(xml, "TopicConfiguration");
            for (var group : topicConfigs) {
                String id = group.getOrDefault("Id", List.of("")).get(0);
                List<String> topicArns = group.get("Topic");
                List<String> events = group.get("Event");
                if (topicArns != null && !topicArns.isEmpty() && events != null && !events.isEmpty()) {
                    config.getTopicConfigurations().add(new TopicNotification(id, topicArns.get(0), events));
                }
            }

            s3Service.putBucketNotificationConfiguration(bucket, config);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
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
        if (region == null) {
            region = regionResolver.getDefaultRegion();
        }
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("LocationConstraint", AwsNamespaces.S3)
                .raw(XmlBuilder.escape(region))
                .end("LocationConstraint")
                .build();
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
        String source = copySource.startsWith("/") ? copySource.substring(1) : copySource;
        int slashIndex = source.indexOf('/');
        if (slashIndex < 0) {
            throw new AwsException("InvalidArgument", "Invalid copy source: " + copySource, 400);
        }
        String sourceBucket = source.substring(0, slashIndex);
        String sourceKey = source.substring(slashIndex + 1);

        S3Object copy = s3Service.copyObject(sourceBucket, sourceKey, destBucket, destKey,
                httpHeaders.getHeaderString("x-amz-metadata-directive"),
                extractUserMetadata(httpHeaders),
                httpHeaders.getHeaderString("x-amz-storage-class"),
                contentType);
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("CopyObjectResult", AwsNamespaces.S3)
                .elem("LastModified", ISO_FORMAT.format(copy.getLastModified()))
                .elem("ETag", copy.getETag())
                .end("CopyObjectResult")
                .build();
        return Response.ok(xml).build();
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

    private boolean hasQueryParam(UriInfo uriInfo, String param) {
        if (uriInfo.getQueryParameters().containsKey(param)) return true;
        String query = uriInfo.getRequestUri().getQuery();
        if (query == null) return false;
        return query.equals(param) || query.contains(param + "&") || query.contains("&" + param);
    }
}
