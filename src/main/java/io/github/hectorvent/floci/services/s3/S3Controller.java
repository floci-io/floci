package io.github.hectorvent.floci.services.s3;

import static io.github.hectorvent.floci.services.s3.S3RequestParser.hasQueryParam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.sns.SnsQueryHandler;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesParts;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.ObjectLockRetention;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import io.github.hectorvent.floci.services.s3.model.WebsiteConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
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
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

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
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter RFC_822 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .withZone(ZoneId.of("GMT"));
    private static final XMLInputFactory NOTIFICATION_XML_FACTORY;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        NOTIFICATION_XML_FACTORY = XMLInputFactory.newInstance();
        NOTIFICATION_XML_FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        NOTIFICATION_XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        NOTIFICATION_XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private final S3Service s3Service;
    private final S3SelectService s3SelectService;
    private final RegionResolver regionResolver;
    private final SnsQueryHandler snsQueryHandler;
    private final io.quarkus.vertx.http.runtime.CurrentVertxRequest currentVertxRequest;
    private final io.github.hectorvent.floci.services.floci.ui.UiPages uiPages;

    @Inject
    public S3Controller(S3Service s3Service, S3SelectService s3SelectService,
                        RegionResolver regionResolver,
                        SnsQueryHandler snsQueryHandler,
                        io.quarkus.vertx.http.runtime.CurrentVertxRequest currentVertxRequest,
                        io.github.hectorvent.floci.services.floci.ui.UiPages uiPages) {
        this.s3Service = s3Service;
        this.s3SelectService = s3SelectService;
        this.regionResolver = regionResolver;
        this.snsQueryHandler = snsQueryHandler;
        this.currentVertxRequest = currentVertxRequest;
        this.uiPages = uiPages;
    }

    // --- Bucket operations ---

    @GET
    @Produces({MediaType.APPLICATION_XML, MediaType.TEXT_HTML})
    public Response listBuckets(@HeaderParam("X-Amz-Target") String target,
                                @HeaderParam("Accept") String accept,
                                @Context HttpHeaders httpHeaders,
                                @Context UriInfo uriInfo) {
        if (target != null) {
            return null;
        }
        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        String action = queryParams.getFirst("Action");
        // SNS emits SubscribeURL/UnsubscribeURL as plain GET links against the
        // service root, which collides with the S3 ListBuckets endpoint. Delegate
        // those confirmation/unsubscribe GETs to SNS before falling through to S3.
        // The links are unsigned (no Authorization header), so the region must come
        // from the region-bearing ARN they carry, not the request headers.
        if ("ConfirmSubscription".equals(action) || "Unsubscribe".equals(action)) {
            String snsArn = "Unsubscribe".equals(action)
                    ? queryParams.getFirst("SubscriptionArn")
                    : queryParams.getFirst("TopicArn");
            String region = AwsArnUtils.regionOrDefault(snsArn, regionResolver.resolveRegion(httpHeaders));
            return snsQueryHandler.handle(action, queryParams, region);
        }
        // A browser hitting the root endpoint (Accept: text/html) gets the Floci
        // landing page; SDK/CLI callers (no Accept, */*, or an XML/JSON Accept) fall
        // through to the normal S3 ListBuckets behavior untouched.
        if (accept != null && accept.contains(MediaType.TEXT_HTML)) {
            return Response.ok(uiPages.landingHtml(), MediaType.TEXT_HTML).build();
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
            return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @HEAD
    @Path("/{bucket}")
    public Response headBucket(@PathParam("bucket") String bucket,
                               @Context UriInfo uriInfo,
                               @Context HttpHeaders httpHeaders) {
        try {
            S3Service.RequestAuthorization authorization = S3RequestAuthorizationParser.parseIfRequired(
                    s3Service.isAuthEnforced(), httpHeaders, uriInfo);
            s3Service.authorizeBucketRead(bucket, "s3:ListBucket", authorization);
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
            validateRawUri();
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
            if (hasQueryParam(uriInfo, "website")) {
                return handlePutBucketWebsite(bucket, body);
            }
            if (hasQueryParam(uriInfo, "logging")) {
                s3Service.putBucketLogging(bucket, new String(body, StandardCharsets.UTF_8));
                return Response.ok().build();
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
                String requestedSize = httpHeaders.getHeaderString("x-amz-transition-default-minimum-object-size");
                String storedSize = s3Service.putBucketLifecycle(bucket,
                        new String(body, StandardCharsets.UTF_8), requestedSize);
                return Response.ok()
                        .header("x-amz-transition-default-minimum-object-size", storedSize)
                        .build();
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
            if (hasQueryParam(uriInfo, "requestPayment")) {
                s3Service.putBucketRequestPayment(bucket, new String(body, StandardCharsets.UTF_8));
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
            validateRawUri();
            if (hasQueryParam(uriInfo, "tagging")) {
                s3Service.deleteBucketTagging(bucket);
                return Response.noContent().build();
            }
            if (hasQueryParam(uriInfo, "website")) {
                s3Service.deleteBucketWebsite(bucket);
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
            if (hasQueryParam(uriInfo, "replication")) {
                // Floci does not model bucket replication; DeleteBucketReplication is a
                // no-op that always returns 204, matching real S3. Crucially it must be
                // handled here so it does NOT fall through to deleting the whole bucket.
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
                                @QueryParam("marker") String marker,
                                @Context UriInfo uriInfo,
                                @Context HttpHeaders httpHeaders) {
        try {
            validateRawUri();
            S3Service.RequestAuthorization authorization = S3RequestAuthorizationParser.parseIfRequired(
                    s3Service.isAuthEnforced(), httpHeaders, uriInfo);
            if (hasQueryParam(uriInfo, "uploads")) {
                s3Service.authorizeBucketRead(bucket, "s3:ListBucketMultipartUploads", authorization);
                return handleListMultipartUploads(bucket);
            }
            if (hasQueryParam(uriInfo, "notification")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketNotification", authorization);
                return handleGetBucketNotification(bucket);
            }
            if (hasQueryParam(uriInfo, "versioning")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketVersioning", authorization);
                return handleGetBucketVersioning(bucket);
            }
            if (hasQueryParam(uriInfo, "versions")) {
                s3Service.authorizeBucketRead(bucket, "s3:ListBucketVersions", authorization);
                return handleListObjectVersions(bucket, prefix, maxKeys, keyMarker);
            }
            if (hasQueryParam(uriInfo, "location")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketLocation", authorization);
                return handleGetBucketLocation(bucket);
            }
            if (hasQueryParam(uriInfo, "tagging")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketTagging", authorization);
                return handleGetBucketTagging(bucket);
            }
            if (hasQueryParam(uriInfo, "object-lock")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketObjectLockConfiguration", authorization);
                return handleGetObjectLockConfiguration(bucket);
            }
            if (hasQueryParam(uriInfo, "website")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketWebsite", authorization);
                return handleGetBucketWebsite(bucket);
            }
            if (hasQueryParam(uriInfo, "logging")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketLogging", authorization);
                return Response.ok(s3Service.getBucketLogging(bucket))
                        .type("application/xml")
                        .build();
            }
            if (hasQueryParam(uriInfo, "policy")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketPolicy", authorization);
                return Response.ok(s3Service.getBucketPolicy(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "cors")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketCORS", authorization);
                return Response.ok(s3Service.getBucketCors(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "lifecycle")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetLifecycleConfiguration", authorization);
                S3Service.LifecycleConfigurationResult lc = s3Service.getBucketLifecycle(bucket);
                return Response.ok(lc.xml())
                        .header("x-amz-transition-default-minimum-object-size", lc.transitionDefaultMinimumObjectSize())
                        .build();
            }
            if (hasQueryParam(uriInfo, "acl")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketAcl", authorization);
                return Response.ok(s3Service.getBucketAcl(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "encryption")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetEncryptionConfiguration", authorization);
                return Response.ok(s3Service.getBucketEncryption(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "publicAccessBlock")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketPublicAccessBlock", authorization);
                return Response.ok(s3Service.getPublicAccessBlock(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "ownershipControls")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketOwnershipControls", authorization);
                return Response.ok(s3Service.getBucketOwnershipControls(bucket)).build();
            }
            if (hasQueryParam(uriInfo, "requestPayment")) {
                s3Service.authorizeBucketRead(bucket, "s3:GetBucketRequestPayment", authorization);
                return Response.ok(s3Service.getBucketRequestPayment(bucket)).build();
            }

            // --- Website Hosting Redirection Logic ---
            if (isWebsiteRequest(httpHeaders) && (uriInfo.getQueryParameters().isEmpty() || (uriInfo.getQueryParameters().size() == 1 && hasQueryParam(uriInfo, "list-type")))) {
                try {
                    WebsiteConfiguration webConfig = s3Service.getBucketWebsite(bucket);
                    if (webConfig.getIndexDocument() != null) {
                        try {
                            s3Service.authorizeGetObject(bucket, webConfig.getIndexDocument(), null, authorization);
                            S3Object indexObj = s3Service.getObject(bucket, webConfig.getIndexDocument());
                            return Response.ok(indexObj.getData())
                                    .type(indexObj.getContentType())
                                    .header("Content-Length", indexObj.getSize())
                                    .header("ETag", indexObj.getETag())
                                    .header("x-amz-website-redirect-location", "index")
                                    .build();
                        } catch (AwsException e) {
                            if (!isWebsiteErrorDocumentTrigger(e)) {
                                throw e;
                            }
                            Response r = serveErrorDocument(bucket, webConfig, authorization, e.getHttpStatus());
                            if (r != null) {
                                return r;
                            }
                        }
                    }
                } catch (AwsException e) {
                    if (!"NoSuchWebsiteConfiguration".equals(e.getErrorCode())) {
                        throw e;
                    }
                    // Bucket is not a website, continue to listObjects
                }
            }

            s3Service.authorizeListBucket(bucket, authorization);

            int max = (maxKeys != null && maxKeys > 0) ? maxKeys : 1000;
            boolean v1 = !"2".equals(listType);
            String effectiveStartAfter = v1 && marker != null ? marker : startAfter;
            String effectiveContinuationToken = v1 ? null : continuationToken;
            S3Service.ListObjectsResult result = s3Service.listObjectsWithPrefixes(
                    bucket, prefix, delimiter, max, effectiveContinuationToken, effectiveStartAfter);
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
            } else {
                xml.elem("Marker", marker != null ? marker : "");
                if (result.isTruncated() && result.nextContinuationToken() != null) {
                    xml.elem("NextMarker", result.nextContinuationToken());
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
                              @HeaderParam("x-amz-tagging") String tagging,
                              @HeaderParam("If-Match") String ifMatch,
                              @HeaderParam("If-None-Match") String ifNoneMatch,
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
                validateChecksumHeaders(httpHeaders, partData, getChecksumAlgorithm(httpHeaders));
                String eTag = s3Service.uploadPart(bucket, key, uploadId, partNumber, partData,
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key"),
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"));
                Response.ResponseBuilder response = Response.ok().header("ETag", eTag);
                appendSseCustomerHeaders(response,
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"));
                return response.build();
            }

            if (copySource != null && !copySource.isEmpty()) {
                return handleCopyObject(copySource, bucket, key, contentType, httpHeaders);
            }

            Map<String, String> inlineTags = parseInlineTaggingHeader(tagging);

            String lockMode = httpHeaders.getHeaderString("x-amz-object-lock-mode");
            String retainUntilStr = httpHeaders.getHeaderString("x-amz-object-lock-retain-until-date");
            String legalHold = httpHeaders.getHeaderString("x-amz-object-lock-legal-hold");
            Instant retainUntil = retainUntilStr != null ? Instant.parse(retainUntilStr) : null;

            byte[] data = decodeAwsChunked(body, contentEncoding, contentSha256);
            String checksumAlgorithm = getChecksumAlgorithm(httpHeaders);
            validateChecksumHeaders(httpHeaders, data, checksumAlgorithm);
            String persistedEncoding = toPersistedContentEncoding(contentEncoding);
            String contentDisposition = httpHeaders.getHeaderString("Content-Disposition");
            String cacheControl = httpHeaders.getHeaderString("Cache-Control");
            String serverSideEncryption = httpHeaders.getHeaderString("x-amz-server-side-encryption");
            String sseCustomerAlgorithm = httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm");
            String sseCustomerKey = httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key");
            String sseCustomerKeyMd5 = httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5");
            String cannedAcl = httpHeaders.getHeaderString("x-amz-acl");
            S3Object obj = s3Service.putObject(bucket, key, data, contentType, extractUserMetadata(httpHeaders),
                    new PutObjectOptions()
                            .withStorageClass(httpHeaders.getHeaderString("x-amz-storage-class"))
                            .withContentEncoding(persistedEncoding)
                            .withObjectLockMode(lockMode)
                            .withRetainUntilDate(retainUntil)
                            .withLegalHoldStatus(legalHold)
                            .withContentDisposition(contentDisposition)
                            .withCacheControl(cacheControl)
                            .withServerSideEncryption(serverSideEncryption)
                            .withSseCustomerAlgorithm(sseCustomerAlgorithm)
                            .withSseCustomerKey(sseCustomerKey)
                            .withSseCustomerKeyMd5(sseCustomerKeyMd5)
                            .withAcl(cannedAcl)
                            .withChecksumAlgorithm(checksumAlgorithm)
                            .withClientChecksum(extractChecksumFromHeaders(httpHeaders))
                            .withIfMatch(ifMatch)
                            .withIfNoneMatch(ifNoneMatch)
                            .withTagging(inlineTags));
            var resp = Response.ok().header("ETag", obj.getETag());
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            appendPutObjectResponseHeaders(resp, obj);
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
                              @QueryParam("response-content-type") String responseContentType,
                              @QueryParam("response-content-language") String responseContentLanguage,
                              @QueryParam("response-expires") String responseExpires,
                              @QueryParam("response-cache-control") String responseCacheControl,
                              @QueryParam("response-content-disposition") String responseContentDisposition,
                              @QueryParam("response-content-encoding") String responseContentEncoding,
                              @HeaderParam("x-amz-object-attributes") String objectAttributesHeader,
                              @HeaderParam("x-amz-max-parts") Integer maxParts,
                              @HeaderParam("x-amz-part-number-marker") Integer partNumberMarker,
                              @HeaderParam("If-Match") String ifMatch,
                              @HeaderParam("If-None-Match") String ifNoneMatch,
                              @HeaderParam("If-Modified-Since") String ifModifiedSince,
                              @HeaderParam("If-Unmodified-Since") String ifUnmodifiedSince,
                              @HeaderParam("Range") String rangeHeader,
                              @HeaderParam("x-amz-checksum-mode") String checksumMode,
                              @Context UriInfo uriInfo,
                              @Context HttpHeaders httpHeaders) {
        S3Service.RequestAuthorization authorization = S3Service.RequestAuthorization.unsigned();
        try {
            key = extractObjectKey(uriInfo, bucket);
            authorization = S3RequestAuthorizationParser.parseIfRequired(
                    s3Service.isAuthEnforced(), httpHeaders, uriInfo);

            if (uploadId != null) {
                s3Service.authorizeObjectRead(bucket, key, versionId, "s3:ListMultipartUploadParts", authorization);
                return handleListParts(bucket, key, uploadId, maxPartsQuery, partNumberMarkerQuery);
            }

            if (hasQueryParam(uriInfo, "tagging")) {
                s3Service.authorizeObjectRead(bucket, key, versionId, "s3:GetObjectTagging", authorization);
                return handleGetObjectTagging(bucket, key);
            }
            if (hasQueryParam(uriInfo, "retention")) {
                s3Service.authorizeObjectRead(bucket, key, versionId, "s3:GetObjectRetention", authorization);
                return handleGetObjectRetention(bucket, key, versionId);
            }
            if (hasQueryParam(uriInfo, "legal-hold")) {
                s3Service.authorizeObjectRead(bucket, key, versionId, "s3:GetObjectLegalHold", authorization);
                return handleGetObjectLegalHold(bucket, key, versionId);
            }
            if (hasQueryParam(uriInfo, "acl")) {
                s3Service.authorizeObjectRead(bucket, key, versionId, "s3:GetObjectAcl", authorization);
                return Response.ok(s3Service.getObjectAcl(bucket, key, versionId)).build();
            }
            if (hasQueryParam(uriInfo, "attributes")) {
                s3Service.authorizeObjectRead(bucket, key, versionId, "s3:GetObjectAttributes", authorization);
                // Merge all x-amz-object-attributes header values (SDK may send multiple lines)
                List<String> attrHeaders = httpHeaders.getRequestHeader("x-amz-object-attributes");
                String mergedAttributes = attrHeaders != null ? String.join(",", attrHeaders) : objectAttributesHeader;
                return handleGetObjectAttributes(bucket, key, versionId,
                        mergedAttributes, maxParts, partNumberMarker);
            }
            s3Service.authorizeGetObject(bucket, key, versionId, authorization);
            if (hasPreconditions(ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince)) {
                // Fetch metadata only to evaluate preconditions, avoiding loading the full object unnecessarily.
                S3Object metadata = s3Service.headObject(bucket, key, versionId);
                Response preconditionResponse = checkPreconditions(metadata, ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince);
                if (preconditionResponse != null) {
                    return preconditionResponse;
                }
            }
            S3Object obj = s3Service.headObject(bucket, key, versionId);
            S3Service.validateSseCustomerAccess(
                    obj,
                    httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                    httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key"),
                    httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"));
            ResponseHeaderOverrides overrides = new ResponseHeaderOverrides(
                    responseContentType, responseContentLanguage, responseExpires,
                    responseCacheControl, responseContentDisposition, responseContentEncoding);
            if (overrides.hasAny() && !S3RequestAuthorizationParser.isSigned(httpHeaders, uriInfo)) {
                return xmlErrorResponse(new AwsException("InvalidRequest",
                        "Request specific response headers cannot be used for anonymous GET requests.", 400));
            }

            boolean includeChecksum = "ENABLED".equalsIgnoreCase(checksumMode);
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                return handleRangeRequest(bucket, key, versionId, obj, rangeHeader, overrides, includeChecksum);
            }

            return fullObjectResponse(bucket, key, versionId, obj, overrides, includeChecksum);
        } catch (AwsException e) {
            if (isWebsiteErrorDocumentTrigger(e) && isWebsiteRequest(httpHeaders)) {
                try {
                    WebsiteConfiguration webConfig = s3Service.getBucketWebsite(bucket);
                    Response r = serveErrorDocument(bucket, webConfig, authorization, e.getHttpStatus());
                    if (r != null) {
                        return r;
                    }
                } catch (AwsException websiteException) {
                    if (!"NoSuchWebsiteConfiguration".equals(websiteException.getErrorCode())) {
                        return xmlErrorResponse(websiteException);
                    }
                }
            }
            return xmlErrorResponse(e);
        }
    }

    private Response fullObjectResponse(String bucket, String key, String versionId,
                                        S3Object obj, ResponseHeaderOverrides overrides,
                                        boolean includeChecksum) {
        StreamingOutput stream = output -> {
            try (InputStream input = s3Service.openObjectStream(bucket, key, versionId)) {
                input.transferTo(output);
            }
        };
        var resp = Response.ok(stream)
                .header("Content-Type", overrides.contentType() != null ? overrides.contentType() : obj.getContentType())
                .header("Content-Length", obj.getSize())
                .header("ETag", obj.getETag())
                .header("Last-Modified", RFC_822.format(obj.getLastModified()))
                .header("Accept-Ranges", "bytes");
        if (obj.getVersionId() != null) {
            resp.header("x-amz-version-id", obj.getVersionId());
        }
        appendObjectHeaders(resp, obj, overrides, includeChecksum);
        return resp.build();
    }

    private Response handleRangeRequest(String bucket, String key, String versionId,
                                        S3Object obj, String rangeHeader,
                                        ResponseHeaderOverrides overrides,
                                        boolean includeChecksum) {
        long totalSize = obj.getSize();
        String rangeSpec = rangeHeader.substring("bytes=".length()).trim();

        long start, end;
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
                long suffix = Long.parseLong(after);
                if (suffix <= 0) {
                    return invalidRangeResponse(totalSize);
                }
                start = Math.max(0, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Long.parseLong(before);
                end = after.isEmpty() ? totalSize - 1 : Math.min(Long.parseLong(after), totalSize - 1);
            }
        } catch (NumberFormatException e) {
            return invalidRangeResponse(totalSize);
        }

        if (start < 0 || start >= totalSize || start > end) {
            if (totalSize == 0 && rangeSpec.startsWith("-")) {
                return fullObjectResponse(bucket, key, versionId, obj, overrides, includeChecksum);
            }
            return invalidRangeResponse(totalSize);
        }

        long length = end - start + 1;
        StreamingOutput stream = output -> {
            try (InputStream input = s3Service.openObjectStream(bucket, key, versionId)) {
                input.skipNBytes(start);
                transferLimited(input, output, length);
            }
        };
        var resp = Response.status(206)
                .entity(stream)
                .header("Content-Type", overrides.contentType() != null ? overrides.contentType() : obj.getContentType())
                .header("Content-Length", length)
                .header("Content-Range", "bytes " + start + "-" + end + "/" + totalSize)
                .header("ETag", obj.getETag())
                .header("Last-Modified", RFC_822.format(obj.getLastModified()))
                .header("Accept-Ranges", "bytes");
        if (obj.getVersionId() != null) {
            resp.header("x-amz-version-id", obj.getVersionId());
        }
        // includeChecksum=false: the stored checksum covers the whole object, not this range.
        appendObjectHeaders(resp, obj, overrides, false);
        return resp.build();
    }

    private static void transferLimited(InputStream input, java.io.OutputStream output, long bytes)
            throws java.io.IOException {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new java.io.EOFException("Object stream ended before the requested range was fully written.");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private Response invalidRangeResponse(long totalSize) {
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
                               @QueryParam("response-content-type") String responseContentType,
                               @QueryParam("response-content-language") String responseContentLanguage,
                               @QueryParam("response-expires") String responseExpires,
                               @QueryParam("response-cache-control") String responseCacheControl,
                               @QueryParam("response-content-disposition") String responseContentDisposition,
                               @QueryParam("response-content-encoding") String responseContentEncoding,
                               @HeaderParam("If-Match") String ifMatch,
                               @HeaderParam("If-None-Match") String ifNoneMatch,
                               @HeaderParam("If-Modified-Since") String ifModifiedSince,
                               @HeaderParam("If-Unmodified-Since") String ifUnmodifiedSince,
                               @HeaderParam("x-amz-checksum-mode") String checksumMode,
                               @Context UriInfo uriInfo,
                               @Context HttpHeaders httpHeaders) {
        try {
            key = extractObjectKey(uriInfo, bucket);
            S3Service.RequestAuthorization authorization = S3RequestAuthorizationParser.parseIfRequired(
                    s3Service.isAuthEnforced(), httpHeaders, uriInfo);
            s3Service.authorizeGetObject(bucket, key, versionId, authorization);

            S3Object obj = s3Service.headObject(bucket, key, versionId);
            S3Service.validateSseCustomerAccess(
                    obj,
                    httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                    httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key"),
                    httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"));
            Response preconditionResponse = checkPreconditions(obj, ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince);
            if (preconditionResponse != null) {
                return preconditionResponse;
            }
            ResponseHeaderOverrides overrides = new ResponseHeaderOverrides(
                    responseContentType, responseContentLanguage, responseExpires,
                    responseCacheControl, responseContentDisposition, responseContentEncoding);
            if (overrides.hasAny() && !S3RequestAuthorizationParser.isSigned(httpHeaders, uriInfo)) {
                return xmlErrorResponse(new AwsException("InvalidRequest",
                        "Request specific response headers cannot be used for anonymous GET requests.", 400));
            }
            var resp = Response.ok()
                    .header("Content-Type", overrides.contentType() != null ? overrides.contentType() : obj.getContentType())
                    .header("Content-Length", obj.getSize())
                    .header("ETag", obj.getETag())
                    .header("Last-Modified", RFC_822.format(obj.getLastModified()))
                    .header("Accept-Ranges", "bytes");
            if (obj.getVersionId() != null) {
                resp.header("x-amz-version-id", obj.getVersionId());
            }
            boolean includeChecksum = "ENABLED".equalsIgnoreCase(checksumMode);
            appendObjectHeaders(resp, obj, overrides, includeChecksum);
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
            if (result != null) {
                if (result.isDeleteMarker()) {
                    resp.header("x-amz-delete-marker", "true");
                }
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
                                         @HeaderParam("If-Match") String ifMatch,
                                         @HeaderParam("If-None-Match") String ifNoneMatch,
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
                        httpHeaders.getHeaderString("x-amz-server-side-encryption"),
                        httpHeaders.getHeaderString("x-amz-acl"),
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key"),
                        httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"),
                        getChecksumAlgorithm(httpHeaders));
                String xml = new XmlBuilder()
                        .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        .start("InitiateMultipartUploadResult", AwsNamespaces.S3)
                        .elem("Bucket", bucket)
                        .elem("Key", key)
                        .elem("UploadId", upload.getUploadId())
                        .end("InitiateMultipartUploadResult")
                        .build();
                Response.ResponseBuilder response = Response.ok(xml);
                appendSseCustomerHeaders(response, upload);
                return response.build();
            }

            if (hasQueryParam(uriInfo, "restore")) {
                s3Service.restoreObject(bucket, key, versionId, new String(body, StandardCharsets.UTF_8));
                return Response.accepted().build();
            }

            if (hasQueryParam(uriInfo, "select")) {
                S3Service.RequestAuthorization authorization = S3RequestAuthorizationParser.parseIfRequired(
                        s3Service.isAuthEnforced(), httpHeaders, uriInfo);
                s3Service.authorizeGetObject(bucket, key, versionId, authorization);
                S3Object obj = s3Service.getObject(bucket, key, versionId);
                byte[] result = s3SelectService.select(obj, new String(body, StandardCharsets.UTF_8));
                return Response.ok(result)
                        .type("application/octet-stream")
                        .build();
            }

            if (uploadId != null) {
                List<Integer> partNumbers = parseCompleteMultipartBody(new String(body));
                Response preconditionResponse = checkWritePreconditions(bucket, key, ifMatch, ifNoneMatch);
                if (preconditionResponse != null) {
                    return preconditionResponse;
                }
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
                appendSseCustomerHeaders(resp, obj);
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
        List<ParsedNotificationGroup> result = new ArrayList<>();
        if (xml == null || xml.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader reader = NOTIFICATION_XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && groupElement.equals(reader.getLocalName())) {
                    ParsedNotificationGroup parsed = readNotificationGroup(reader, groupElement, arnElement);
                    if (parsed.arn() != null && !parsed.events().isEmpty()) {
                        result.add(parsed);
                    }
                }
            }
            reader.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private static ParsedNotificationGroup readNotificationGroup(
            XMLStreamReader reader, String groupElement, String arnElement) throws XMLStreamException {
        String id = "";
        String arn = null;
        List<String> events = new ArrayList<>();
        List<FilterRule> filterRules = new ArrayList<>();
        int depth = 1;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();
                if (depth == 1 && "Id".equals(local)) {
                    id = reader.getElementText();
                } else if (depth == 1 && arnElement.equals(local)) {
                    arn = reader.getElementText();
                } else if (depth == 1 && "Event".equals(local)) {
                    events.add(reader.getElementText());
                } else if ("FilterRule".equals(local)) {
                    FilterRule rule = readFilterRule(reader);
                    if (rule != null) {
                        filterRules.add(rule);
                    }
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String local = reader.getLocalName();
                if (groupElement.equals(local) && depth == 1) {
                    break;
                }
                depth--;
            }
        }

        return new ParsedNotificationGroup(id, arn, events, filterRules);
    }

    private static FilterRule readFilterRule(XMLStreamReader reader) throws XMLStreamException {
        String name = null;
        String value = null;
        int depth = 1;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();
                if (depth == 1 && "Name".equals(local)) {
                    name = reader.getElementText();
                } else if (depth == 1 && "Value".equals(local)) {
                    value = reader.getElementText();
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("FilterRule".equals(reader.getLocalName()) && depth == 1) {
                    break;
                }
                depth--;
            }
        }

        return name != null && value != null ? new FilterRule(name, value) : null;
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

    private record ResponseHeaderOverrides(
            String contentType,
            String contentLanguage,
            String expires,
            String cacheControl,
            String contentDisposition,
            String contentEncoding) {
        boolean hasAny() {
            return contentType != null || contentLanguage != null || expires != null
                    || cacheControl != null || contentDisposition != null || contentEncoding != null;
        }

        ResponseHeaderOverrides {
            // Real S3 ignores empty `response-*` values; @QueryParam binds "?foo=" as "" rather than null.
            contentType = emptyToNull(contentType);
            contentLanguage = emptyToNull(contentLanguage);
            expires = emptyToNull(expires);
            cacheControl = emptyToNull(cacheControl);
            contentDisposition = emptyToNull(contentDisposition);
            contentEncoding = emptyToNull(contentEncoding);
        }

        private static String emptyToNull(String s) {
            return s == null || s.isEmpty() ? null : s;
        }
    }

    // PutObject's response body is empty — body-describing headers and x-amz-meta-*
    // must not be emitted, or SDK clients will try to decompress and fail.
    private void appendPutObjectResponseHeaders(Response.ResponseBuilder resp, S3Object obj) {
        if (obj.getStorageClass() != null) {
            resp.header("x-amz-storage-class", obj.getStorageClass());
        }
        if (obj.getServerSideEncryption() != null) {
            resp.header("x-amz-server-side-encryption", obj.getServerSideEncryption());
        }
        appendSseCustomerHeaders(resp, obj);
        appendChecksumHeaders(resp, obj.getChecksum());
        appendLockHeaders(resp, obj);
    }

    // includeChecksum must be false for partial (206) responses: obj.getChecksum() is the
    // whole-object checksum, which does not match the range bytes returned. SDKs that
    // validate it against the received body fail. Real S3 omits it on ranged responses.
    private void appendObjectHeaders(Response.ResponseBuilder resp, S3Object obj, ResponseHeaderOverrides overrides,
                                     boolean includeChecksum) {
        if (obj.getStorageClass() != null) {
            resp.header("x-amz-storage-class", obj.getStorageClass());
        }
        String contentEncoding = overrides.contentEncoding() != null ? overrides.contentEncoding() : obj.getContentEncoding();
        if (contentEncoding != null) {
            resp.header("Content-Encoding", contentEncoding);
        }
        String contentDisposition = overrides.contentDisposition() != null ? overrides.contentDisposition() : obj.getContentDisposition();
        if (contentDisposition != null) {
            resp.header("Content-Disposition", contentDisposition);
        }
        String cacheControl = overrides.cacheControl() != null ? overrides.cacheControl() : obj.getCacheControl();
        if (cacheControl != null) {
            resp.header("Cache-Control", cacheControl);
        }
        if (obj.getServerSideEncryption() != null) {
            resp.header("x-amz-server-side-encryption", obj.getServerSideEncryption());
        }
        appendSseCustomerHeaders(resp, obj);
        if (overrides.contentLanguage() != null) {
            resp.header("Content-Language", overrides.contentLanguage());
        }
        if (overrides.expires() != null) {
            resp.header("Expires", overrides.expires());
        }
        if (obj.getMetadata() != null) {
            for (Map.Entry<String, String> entry : obj.getMetadata().entrySet()) {
                resp.header("x-amz-meta-" + entry.getKey(), entry.getValue());
            }
        }
        if (includeChecksum) {
            appendChecksumHeaders(resp, obj.getChecksum());
        }
        appendLockHeaders(resp, obj);
    }

    private void appendSseCustomerHeaders(Response.ResponseBuilder resp, S3Object obj) {
        appendSseCustomerHeaders(resp, obj.getSseCustomerAlgorithm(), obj.getSseCustomerKeyMd5());
    }

    private void appendSseCustomerHeaders(Response.ResponseBuilder resp, MultipartUpload upload) {
        appendSseCustomerHeaders(resp, upload.getSseCustomerAlgorithm(), upload.getSseCustomerKeyMd5());
    }

    private void appendSseCustomerHeaders(Response.ResponseBuilder resp, String algorithm, String keyMd5) {
        if (hasText(algorithm) && hasText(keyMd5)) {
            resp.header("x-amz-server-side-encryption-customer-algorithm", algorithm.trim());
            resp.header("x-amz-server-side-encryption-customer-key-MD5", keyMd5.trim());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
        String pathAfterBucket = decodedSource.substring(slashIndex + 1);
        ParsedCopySource sourceObject = parseCopySourceObject(pathAfterBucket);
        String copyContentEncoding = toPersistedContentEncoding(httpHeaders.getHeaderString("Content-Encoding"));
        String copyContentDisposition = httpHeaders.getHeaderString("Content-Disposition");
        String copyCacheControl = httpHeaders.getHeaderString("Cache-Control");
        String copyServerSideEncryption = httpHeaders.getHeaderString("x-amz-server-side-encryption");
        String cannedAcl = httpHeaders.getHeaderString("x-amz-acl");
        String taggingDirective = httpHeaders.getHeaderString("x-amz-tagging-directive");
        String taggingHeader = httpHeaders.getHeaderString("x-amz-tagging");
        Map<String, String> replacementTagging = "REPLACE".equalsIgnoreCase(taggingDirective)
                ? (taggingHeader != null ? parseInlineTaggingHeader(taggingHeader) : Map.of())
                : null;
        S3Object copy = s3Service.copyObject(sourceBucket, sourceObject.objectKey(), destBucket, destKey,
                sourceObject.versionId(),
                new CopyObjectOptions()
                        .withMetadataDirective(httpHeaders.getHeaderString("x-amz-metadata-directive"))
                        .withReplacementMetadata(extractUserMetadata(httpHeaders))
                        .withTaggingDirective(taggingDirective)
                        .withReplacementTagging(replacementTagging)
                        .withStorageClass(httpHeaders.getHeaderString("x-amz-storage-class"))
                        .withContentType(contentType)
                        .withContentEncoding(copyContentEncoding)
                        .withContentDisposition(copyContentDisposition)
                        .withCacheControl(copyCacheControl)
                        .withServerSideEncryption(copyServerSideEncryption)
                        .withSseCustomerAlgorithm(httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"))
                        .withSseCustomerKey(httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key"))
                        .withSseCustomerKeyMd5(httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"))
                        .withCopySourceSseCustomerAlgorithm(httpHeaders.getHeaderString("x-amz-copy-source-server-side-encryption-customer-algorithm"))
                        .withCopySourceSseCustomerKey(httpHeaders.getHeaderString("x-amz-copy-source-server-side-encryption-customer-key"))
                        .withCopySourceSseCustomerKeyMd5(httpHeaders.getHeaderString("x-amz-copy-source-server-side-encryption-customer-key-MD5"))
                        .withChecksumAlgorithm(getChecksumAlgorithm(httpHeaders))
                        .withAcl(cannedAcl));
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("CopyObjectResult", AwsNamespaces.S3)
                .elem("LastModified", ISO_FORMAT.format(copy.getLastModified()))
                .elem("ETag", copy.getETag())
                .end("CopyObjectResult")
                .build();
        Response.ResponseBuilder response = Response.ok(xml);
        appendSseCustomerHeaders(response, copy);
        return response.build();
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
        String pathAfterBucket = decodedSource.substring(slashIndex + 1);
        ParsedCopySource sourceObject = parseCopySourceObject(pathAfterBucket);
        String copySourceRange = httpHeaders.getHeaderString("x-amz-copy-source-range");
        String eTag = s3Service.uploadPartCopy(destBucket, destKey, uploadId, partNumber,
                sourceBucket, sourceObject.objectKey(), sourceObject.versionId(), copySourceRange,
                copySourceSseCustomerHeaders(httpHeaders),
                sseCustomerHeaders(httpHeaders));
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("CopyPartResult", AwsNamespaces.S3)
                .elem("LastModified", ISO_FORMAT.format(java.time.Instant.now()))
                .elem("ETag", eTag)
                .end("CopyPartResult")
                .build();
        Response.ResponseBuilder response = Response.ok(xml).type(MediaType.APPLICATION_XML);
        appendSseCustomerHeaders(response,
                httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"));
        return response.build();
    }

    private S3Service.SseCustomerHeaders copySourceSseCustomerHeaders(HttpHeaders httpHeaders) {
        return new S3Service.SseCustomerHeaders(
                httpHeaders.getHeaderString("x-amz-copy-source-server-side-encryption-customer-algorithm"),
                httpHeaders.getHeaderString("x-amz-copy-source-server-side-encryption-customer-key"),
                httpHeaders.getHeaderString("x-amz-copy-source-server-side-encryption-customer-key-MD5"));
    }

    private S3Service.SseCustomerHeaders sseCustomerHeaders(HttpHeaders httpHeaders) {
        return new S3Service.SseCustomerHeaders(
                httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-algorithm"),
                httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key"),
                httpHeaders.getHeaderString("x-amz-server-side-encryption-customer-key-MD5"));
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
        if (checksum.getChecksumCRC32() != null) {
            resp.header("x-amz-checksum-crc32", checksum.getChecksumCRC32());
        }
        if (checksum.getChecksumCRC32C() != null) {
            resp.header("x-amz-checksum-crc32c", checksum.getChecksumCRC32C());
        }
        if (checksum.getChecksumCRC64NVME() != null) {
            resp.header("x-amz-checksum-crc64nvme", checksum.getChecksumCRC64NVME());
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

    private S3Checksum extractChecksumFromHeaders(HttpHeaders httpHeaders) {
        String crc32 = httpHeaders.getHeaderString("x-amz-checksum-crc32");
        String crc32c = httpHeaders.getHeaderString("x-amz-checksum-crc32c");
        String crc64nvme = httpHeaders.getHeaderString("x-amz-checksum-crc64nvme");
        String sha1 = httpHeaders.getHeaderString("x-amz-checksum-sha1");
        String sha256 = httpHeaders.getHeaderString("x-amz-checksum-sha256");
        if (crc32 == null && crc32c == null && crc64nvme == null && sha1 == null && sha256 == null) {
            return null;
        }
        S3Checksum checksum = new S3Checksum();
        checksum.setChecksumCRC32(crc32);
        checksum.setChecksumCRC32C(crc32c);
        checksum.setChecksumCRC64NVME(crc64nvme);
        checksum.setChecksumSHA1(sha1);
        checksum.setChecksumSHA256(sha256);
        checksum.setChecksumType("FULL_OBJECT");
        return checksum;
    }

    private String getChecksumAlgorithm(HttpHeaders httpHeaders) {
        String algorithm = httpHeaders.getHeaderString("x-amz-checksum-algorithm");
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = httpHeaders.getHeaderString("x-amz-sdk-checksum-algorithm");
        }
        return algorithm;
    }

    private void validateChecksumHeaders(HttpHeaders httpHeaders, byte[] data, String algorithm) {
        String sha1 = httpHeaders.getHeaderString("x-amz-checksum-sha1");
        if (sha1 != null && !sha1.equals(S3Checksum.sha1Base64(data))) {
            throw new AwsException("BadDigest", "The SHA1 checksum you specified did not match the payload.", 400);
        }

        String sha256 = httpHeaders.getHeaderString("x-amz-checksum-sha256");
        if (sha256 != null && !sha256.equals(S3Checksum.sha256Base64(data))) {
            throw new AwsException("BadDigest", "The SHA256 checksum you specified did not match the payload.", 400);
        }

        String crc32 = httpHeaders.getHeaderString("x-amz-checksum-crc32");
        if (crc32 != null && !crc32.equals(S3Checksum.crc32Base64(data))) {
            throw new AwsException("BadDigest", "The CRC32 checksum you specified did not match the payload.", 400);
        }

        String crc32c = httpHeaders.getHeaderString("x-amz-checksum-crc32c");
        if (crc32c != null && !crc32c.equals(S3Checksum.crc32cBase64(data))) {
            throw new AwsException("BadDigest", "The CRC32C checksum you specified did not match the payload.", 400);
        }

        String crc64nvme = httpHeaders.getHeaderString("x-amz-checksum-crc64nvme");
        if (crc64nvme != null && !crc64nvme.equals(S3Checksum.crc64NvmeBase64(data))) {
            throw new AwsException("BadDigest", "The CRC64NVME checksum you specified did not match the payload.", 400);
        }
    }

    private static boolean isWebsiteRequest(HttpHeaders httpHeaders) {
        String host = httpHeaders.getHeaderString("Host");
        return host != null && host.contains("s3-website");
    }

    private Response serveErrorDocument(String bucket, WebsiteConfiguration cfg,
                                        S3Service.RequestAuthorization authorization, int status) {
        if (cfg.getErrorDocument() == null) {
            return null;
        }
        int responseStatus = status == 403 ? 403 : 404;
        try {
            s3Service.authorizeGetObject(bucket, cfg.getErrorDocument(), null, authorization);
            S3Object err = s3Service.getObject(bucket, cfg.getErrorDocument());
            return Response.status(responseStatus)
                    .entity(err.getData())
                    .type(err.getContentType())
                    .header("Content-Length", err.getSize())
                    .header("x-amz-error-code", websiteErrorCode(responseStatus))
                    .header("x-amz-error-message", websiteErrorMessage(responseStatus))
                    .build();
        } catch (AwsException e) {
            if (!isWebsiteErrorDocumentTrigger(e)) {
                throw e;
            }
            return Response.status(responseStatus)
                    .entity(defaultWebsiteErrorBody(responseStatus))
                    .type(MediaType.TEXT_HTML)
                    .header("x-amz-error-code", websiteErrorCode(responseStatus))
                    .header("x-amz-error-message", websiteErrorMessage(responseStatus))
                    .build();
        }
    }

    private static boolean isWebsiteErrorDocumentTrigger(AwsException e) {
        return "NoSuchKey".equals(e.getErrorCode()) || "AccessDenied".equals(e.getErrorCode());
    }

    private static String websiteErrorCode(int status) {
        return status == 403 ? "AccessDenied" : "NoSuchKey";
    }

    private static String websiteErrorMessage(int status) {
        return status == 403 ? "Access Denied" : "The specified key does not exist.";
    }

    private static String defaultWebsiteErrorBody(int status) {
        String title = status + (status == 403 ? " Forbidden" : " Not Found");
        String code = websiteErrorCode(status);
        String message = websiteErrorMessage(status);
        return "<html><head><title>" + title + "</title></head>\n"
                + "<body><h1>" + title + "</h1>\n"
                + "<ul><li>Code: " + code + "</li><li>Message: " + message + "</li></ul></body></html>";
    }

    private Response xmlErrorResponse(AwsException e) {
        String condition = e instanceof S3PreconditionFailedException preconditionFailedException
                ? preconditionFailedException.condition()
                : null;
        return xmlErrorResponse(e, condition);
    }

    private Response xmlErrorResponse(AwsException e, String condition) {
        if (e.getMessage() == null) {
            return Response.status(e.getHttpStatus()).build();
        }
        XmlBuilder xmlBuilder = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage());
        if (condition != null) {
            xmlBuilder.elem("Condition", condition);
        }
        String xml = xmlBuilder
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

    private Response checkWritePreconditions(String bucket, String key, String ifMatch, String ifNoneMatch) {
        if (ifMatch == null && ifNoneMatch == null) {
            return null;
        }

        S3Object existing;
        try {
            existing = s3Service.headObject(bucket, key);
        } catch (AwsException e) {
            if ("NoSuchKey".equals(e.getErrorCode()) && ifMatch == null) {
                return null;
            }
            throw e;
        }

        if (ifMatch != null && !eTagMatches(ifMatch, existing.getETag())) {
            return preconditionFailedResponse("If-Match");
        }
        if (ifNoneMatch != null && eTagMatches(ifNoneMatch, existing.getETag())) {
            return preconditionFailedResponse("If-None-Match");
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
        return preconditionFailedResponse(null);
    }

    private Response preconditionFailedResponse(String condition) {
        return xmlErrorResponse(new AwsException("PreconditionFailed",
                S3PreconditionFailedException.MESSAGE, 412), condition);
    }

    private boolean eTagMatches(String headerValue, String eTag) {
        String normalizedETag = normalizeEntityTag(eTag);
        for (String candidate : headerValue.split(",")) {
            String normalizedCandidate = normalizeEntityTag(candidate);
            if ("*".equals(normalizedCandidate) || normalizedCandidate.equals(normalizedETag)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeEntityTag(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
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

        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String fieldName = entry.getKey().toLowerCase(Locale.ROOT);
            if (fieldName.startsWith("x-amz-meta-")) {
                String metaKey = fieldName.substring("x-amz-meta-".length());
                if (!metaKey.isBlank()) {
                    metadata.put(metaKey, entry.getValue());
                }
            }
        }

        S3Object obj = s3Service.putObject(bucket, key, fileData, objectContentType,
                metadata.isEmpty() ? null : metadata);
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

    private static final int MAX_INLINE_TAGS = 10;
    private static final int MAX_INLINE_TAGGING_HEADER_BYTES = 8 * 1024;

    /**
     * Parses an {@code x-amz-tagging} request-header value (URL-encoded
     * {@code k=v&k=v}) into a tag map. Returns an empty map for null or blank input.
     *
     * <p>Note: the error codes thrown here ({@code InvalidArgument} for malformed input,
     * {@code BadRequest} for exceeding the 10-tag limit) match real-AWS S3 behavior
     * observed in practice but are not in the S3 Smithy service model.
     */
    private static Map<String, String> parseInlineTaggingHeader(String header) {
        if (header == null || header.isEmpty()) {
            return Map.of();
        }
        if (header.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_TAGGING_HEADER_BYTES) {
            throw new AwsException("InvalidArgument",
                    "The x-amz-tagging header exceeds the 8 KB limit.", 400);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String pair : header.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new AwsException("InvalidArgument",
                        "The x-amz-tagging header is malformed: missing '=' in pair.", 400);
            }
            String rawKey = pair.substring(0, eq);
            String rawValue = pair.substring(eq + 1);
            if (rawKey.isEmpty()) {
                throw new AwsException("InvalidArgument",
                        "The x-amz-tagging header is malformed: empty tag key.", 400);
            }
            String tagKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String tagValue = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            if (tags.put(tagKey, tagValue) != null) {
                throw new AwsException("InvalidArgument",
                        "The x-amz-tagging header contains duplicate tag key: " + tagKey, 400);
            }
        }
        if (tags.size() > MAX_INLINE_TAGS) {
            throw new AwsException("BadRequest",
                    "Object tags cannot be greater than " + MAX_INLINE_TAGS, 400);
        }
        return tags;
    }

    /**
     * Extracts the object key from the raw Vert.x request URI, preserving leading slashes
     * that JAX-RS path normalization would otherwise strip.
     */
    private String extractObjectKey(UriInfo uriInfo, String bucket) {
        validateRawUri();
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
        int queryIndex = rawUri.indexOf('?');
        String rawPath = queryIndex >= 0 ? rawUri.substring(0, queryIndex) : rawUri;
        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
            throw new AwsException("BadRequest", null, 400);
        }

        String[] segments = decodedPath.split("/", -1);
        int firstKeySegment = decodedPath.startsWith("/") ? 2 : 1;
        if (segments.length <= firstKeySegment) {
            return;
        }

        int depth = 0;
        for (int index = firstKeySegment; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (depth == 0) {
                    throw new AwsException("BadRequest", null, 400);
                }
                depth--;
            }
            else {
                depth++;
            }
        }
    }

    private Response handleGetBucketWebsite(String bucket) {
        WebsiteConfiguration config = s3Service.getBucketWebsite(bucket);
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("WebsiteConfiguration", AwsNamespaces.S3)
                .start("IndexDocument")
                .elem("Suffix", config.getIndexDocument())
                .end("IndexDocument");
        if (config.getErrorDocument() != null) {
            xml.start("ErrorDocument")
               .elem("Key", config.getErrorDocument())
               .end("ErrorDocument");
        }
        xml.end("WebsiteConfiguration");
        return Response.ok(xml.build()).build();
    }

    private Response handlePutBucketWebsite(String bucket, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        String indexDoc = XmlParser.extractFirst(xml, "Suffix", null);
        String errorDoc = XmlParser.extractFirst(xml, "Key", null);
        if (indexDoc == null) {
            throw new AwsException("MalformedXML", "IndexDocument.Suffix is required.", 400);
        }
        s3Service.putBucketWebsite(bucket, new WebsiteConfiguration(indexDoc, errorDoc));
        return Response.ok().build();
    }

    /**
     * Splits the {@code CopyObject}/{@code UploadPartCopy} copy-source remainder into S3 object key and
     * optional source {@code versionId}.
     * <ul>
     *   <li><b>Input:</b> decoded {@code x-amz-copy-source} with bucket already removed (substring after
     *   the {@code '/'} that follows the bucket). Both {@code handleCopyObject} and
     *   {@code handleUploadPartCopy} compute this as {@code pathAfterBucket}.</li>
     *   <li><b>Key:</b> substring before the first {@code '?'} if any; keys may contain more {@code '/'}
     *   segments.</li>
     *   <li><b>{@code versionId}:</b> first {@code versionId} query pair, when present (raw value after
     *   {@code '='}). Other query pairs are ignored.</li>
     * </ul>
     *
     * @param pathAfterBucket object key alone, or key with query (for example {@code dir/k.txt?versionId=uuid})
     * @return key without trailing query plus {@code versionId} value, or {@code null} version when absent
     */
    private ParsedCopySource parseCopySourceObject(String pathAfterBucket) {
        int queryStart = pathAfterBucket.indexOf('?');
        if (queryStart < 0) {
            return new ParsedCopySource(pathAfterBucket, null);
        }
        String objectKey = pathAfterBucket.substring(0, queryStart);
        String query = pathAfterBucket.substring(queryStart + 1);
        String versionId = null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if ("versionId".equals(name)) {
                versionId = value;
                break;
            }
        }
        return new ParsedCopySource(objectKey, versionId);
    }

    private record ParsedCopySource(String objectKey, String versionId) {
    }
}
