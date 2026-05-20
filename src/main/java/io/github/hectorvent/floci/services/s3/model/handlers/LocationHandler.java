package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;

public class LocationHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        String region = service.getBucketRegion(context.getBucket());
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
}