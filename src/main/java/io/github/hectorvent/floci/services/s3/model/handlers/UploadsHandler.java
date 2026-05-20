package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;

public class UploadsHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        String bucket = context.getBucket();
        List<MultipartUpload> uploads = service.listMultipartUploads(bucket);

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
}