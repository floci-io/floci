package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.services.s3.model.ObjectLockRetention;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;

public class ObjectLockHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        String bucket = context.getBucket();
        ObjectLockRetention retention = service.getObjectLockConfiguration(bucket);

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
}