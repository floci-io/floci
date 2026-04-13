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
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        String bucketName = extractBucketName(resourceArn);
        String xml = new String(body, StandardCharsets.UTF_8);
        Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
        s3Service.putBucketTagging(bucketName, tags);
        return Response.noContent().build();
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

        String bucketName = extractBucketName(resourceArn);
        Map<String, String> existing = new HashMap<>(s3Service.getBucketTagging(bucketName));
        tagKeys.forEach(existing::remove);
        s3Service.putBucketTagging(bucketName, existing);
        return Response.noContent().build();
    }

    private String extractBucketName(String resourceArn) {
        int idx = resourceArn.lastIndexOf(":bucket/");
        if (idx < 0) {
            throw new AwsException("InvalidRequest",
                    "Unsupported resource type. Only S3 bucket ARNs are supported " +
                    "(arn:aws:s3:<region>:<account>:bucket/<name>).", 400);
        }
        return resourceArn.substring(idx + ":bucket/".length());
    }
}
