package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.S3Service.LifecycleConfigurationResult;
import io.github.hectorvent.floci.services.s3.model.RequestContext;

public class LifecycleHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        LifecycleConfigurationResult lc = service.getBucketLifecycle(context.getBucket());
        return Response.ok(lc.xml())
                .header("x-amz-transition-default-minimum-object-size", lc.transitionDefaultMinimumObjectSize())
                .build();
    }
}
