package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;

public class VersioningHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        String status = service.getBucketVersioning(context.getBucket());

        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("VersioningConfiguration", AwsNamespaces.S3);

        if (status != null) {
            xml.elem("Status", status);
        }

        xml.end("VersioningConfiguration");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }
}
