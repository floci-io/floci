package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;

import java.util.Map;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import jakarta.ws.rs.core.MediaType;

public class TaggingHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        Map<String, String> tags = service.getBucketTagging(context.getBucket());
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
}