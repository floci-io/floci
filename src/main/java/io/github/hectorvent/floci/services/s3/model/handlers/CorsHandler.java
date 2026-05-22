package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;

public class CorsHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        return Response.ok(service.getBucketCors(context.getBucket())).build();
    }

}
