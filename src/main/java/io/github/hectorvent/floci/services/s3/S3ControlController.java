package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S3 Control API endpoints used by Terraform AWS provider v6.x and other tools.
 * All endpoints are under /v20180820 matching the S3 Control API version.
 *
 * Protocol: REST-XML
 * Namespace: http://awss3control.amazonaws.com/doc/2018-08-20/
 */
@Path("/v20180820")
@Produces(MediaType.APPLICATION_XML)
public class S3ControlController {

    private static final Logger LOG = Logger.getLogger(S3ControlController.class);

    private static final String AMZ_REQUEST_ID = "x-amz-request-id";
    private static final String AMZN_REQUEST_ID = "x-amzn-RequestId";
    private static final String AMZ_ID_2 = "x-amz-id-2";

    private final S3Service s3Service;

    @Inject
    public S3ControlController(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    /**
     * ListTagsForResource — returns all tags on the specified S3 bucket.
     * Used by Terraform AWS provider v6.x during bucket read-back.
     *
     * GET /v20180820/tags/{resourceArn+}
     * Header: x-amz-account-id
     */
    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId) {

        try {
            String bucketName = extractBucketName(resourceArn);
            Map<String, String> tags = s3Service.getBucketTagging(bucketName);

            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("ListTagsForResourceResult", AwsNamespaces.S3_CONTROL)
                    .start("Tags");
            tags.forEach((k, v) ->
                    xml.start("Tag").elem("Key", k).elem("Value", v).end("Tag"));
            xml.end("Tags").end("ListTagsForResourceResult");
            return Response.ok(xml.build()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * ListAccessPoints — returns the account's S3 access points.
     *
     * <p>The emulator has no access-point store. Per the S3 Control API, the only success
     * response for ListAccessPoints is HTTP 200 with a {@code <ListAccessPointsResult>}
     * carrying an {@code <AccessPointList>} — empty when the account has none — and an
     * optional {@code <NextToken>}. 404 is not a documented outcome for this operation, so
     * without this route an AWS SDK client cannot map the response to the expected result
     * or to a known error and the read fails (and, depending on the client's retry config,
     * may burn its retry budget on the unexpected status first). We therefore return the
     * documented empty list: an {@code <AccessPointList/>} with no {@code <NextToken>},
     * which ends pagination immediately.
     *
     * GET /v20180820/accesspoint
     * Header: x-amz-account-id
     */
    @GET
    @Path("/accesspoint")
    public Response listAccessPoints(
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("bucket") String bucket,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {

        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListAccessPointsResult", AwsNamespaces.S3_CONTROL)
                .start("AccessPointList")
                .end("AccessPointList")
                .end("ListAccessPointsResult")
                .build();
        return Response.ok(xml).build();
    }

    /**
     * TagResource — replaces all tags on the specified S3 bucket.
     *
     * POST /v20180820/tags/{resourceArn+}
     * Header: x-amz-account-id
     * Body: XML containing {@code <Tags><Tag><Key>…</Key><Value>…</Value></Tag></Tags>}
     */
    @POST
    @Path("/tags/{resourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response tagResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {

        try {
            String bucketName = extractBucketName(resourceArn);
            String xml = new String(body, StandardCharsets.UTF_8);
            Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
            s3Service.putBucketTagging(bucketName, tags);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * UntagResource — removes specific tags from the specified S3 bucket.
     *
     * DELETE /v20180820/tags/{resourceArn+}?tagKeys=Key1&tagKeys=Key2
     * Header: x-amz-account-id
     */
    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("tagKeys") List<String> tagKeys) {

        try {
            String bucketName = extractBucketName(resourceArn);
            Map<String, String> existing = new HashMap<>(s3Service.getBucketTagging(bucketName));
            tagKeys.forEach(existing::remove);
            s3Service.putBucketTagging(bucketName, existing);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Parse the bucket name out of an S3 bucket ARN path parameter.
     *
     * <p>The AWS Go SDK v2 (used by Terraform) percent-encodes the ARN's
     * colons and slashes in the request path, while the Java SDK sends them
     * literally. We decode defensively so both forms work, and so routing
     * frameworks that leave {@code %2F} encoded in path segments don't break
     * us.
     *
     * <p>Two valid ARN forms are accepted:
     * <ul>
     *   <li>S3 Control ARN: {@code arn:aws:s3:<region>:<account>:bucket/<name>}</li>
     *   <li>Plain S3 ARN:   {@code arn:aws:s3:::<name>} — sent by Go SDK v2 / Terraform provider v6</li>
     * </ul>
     */
    private String extractBucketName(String resourceArn) {
        String decoded;
        try {
            decoded = URLDecoder.decode(resourceArn, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequest",
                    "Malformed percent-encoding in resource ARN: " + e.getMessage(), 400);
        }

        // Form 1: arn:<partition>:s3:<region>:<account>:bucket/<name>
        int idx = decoded.lastIndexOf(":bucket/");
        if (idx >= 0) {
            return decoded.substring(idx + ":bucket/".length());
        }

        // Form 2: arn:<partition>:s3:::<name>  (plain S3 ARN — no region, no account)
        // Go SDK v2 / Terraform provider v6 sends this form for general-purpose buckets.
        String[] parts = decoded.split(":", 6);
        if (parts.length == 6 && "s3".equals(parts[2])
                && parts[3].isEmpty() && parts[4].isEmpty()
                && !parts[5].isEmpty() && !parts[5].contains("/")) {
            return parts[5];
        }

        throw new AwsException("InvalidRequest",
                "Unsupported resource type. Only S3 bucket ARNs are supported " +
                "(arn:aws:s3:<region>:<account>:bucket/<name> or arn:aws:s3:::<name>).", 400);
    }

    /**
     * S3 Control is a REST-XML protocol, so error responses must also be XML.
     * AWS S3 Control wraps errors in an {@code <ErrorResponse xmlns=...>} envelope
     * containing the inner {@code <Error>} block and a top-level {@code <RequestId>}.
     *
     * <p>References: AWS Go SDK v2 s3control error deserializer expects this wrapper;
     * bare {@code <Error>} collapses to "UnknownError" at the SDK layer.
     * See issue #557.
     */
    // Same protocol safety net as S3Controller: unhandled failures on S3 Control routes
    // must render the REST-XML error contract, not Quarkus's plain-text error page.
    @ServerExceptionMapper
    public Response mapUnhandledThrowable(Throwable t) {
        LOG.error("Unhandled exception processing S3 Control request", t);
        return xmlErrorResponse(new AwsException("InternalError",
                "We encountered an internal error. Please try again.", 500));
    }

    @ServerExceptionMapper
    public Response mapEscapedAwsException(AwsException e) {
        return xmlErrorResponse(e);
    }

    private Response xmlErrorResponse(AwsException e) {
        String requestId = UUID.randomUUID().toString();
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ErrorResponse", AwsNamespaces.S3_CONTROL)
                .start("Error")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .elem("RequestId", requestId)
                .end("Error")
                .elem("RequestId", requestId)
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus())
                .type(MediaType.APPLICATION_XML)
                .header(AMZ_REQUEST_ID, requestId)
                .header(AMZN_REQUEST_ID, requestId)
                .header(AMZ_ID_2, requestId)
                .entity(xml)
                .build();
    }
}
