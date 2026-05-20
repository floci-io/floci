package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.services.s3.model.WebsiteConfiguration;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;

public class WebsiteHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        String bucket = context.getBucket();
        WebsiteConfiguration config = service.getBucketWebsite(bucket);

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
}
