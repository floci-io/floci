package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.S3Service.ListVersionsResult;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;

public class VersionsHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        Integer maxKeys = context.getMaxKeys();
        String bucket = context.getBucket();
        String prefix = context.getPrefix();
        String keyMarker = context.getKeyMarker();

        int max = (maxKeys != null && maxKeys > 0) ? maxKeys : 1000;
        ListVersionsResult result = service.listObjectVersions(bucket, prefix, max, keyMarker);

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
                        // ISO_FORMAT deve ser uma constante estática compartilhada ou injetada
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
}