package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.resteasy.reactive.jackson.runtime.serialisers.FullyFeaturedServerJacksonMessageBodyWriter;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;

@Provider
@Produces({"application/x-amz-json-1.0", "application/x-amz-json-1.1"})
public class AwsJsonMessageBodyWriter extends FullyFeaturedServerJacksonMessageBodyWriter {

    @Inject
    public AwsJsonMessageBodyWriter(Instance<ObjectMapper> mapper, Providers providers) {
        super(mapper, providers);
    }
}
