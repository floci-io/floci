package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.Map;

/**
 * The values a service reads the labels of its REST requests' paths as, where they differ from what the router's
 * normalized path gives the labels of the request's vendored route: S3 reads an object's key from the path as the
 * client sent it, which keeps the key's own slashes ({@code PUT /bucket//email.txt} writes {@code /email.txt}, where
 * the normalized path gives {@code email.txt}). The request-resources reader lays them over the route's labels, so a
 * request is checked on the resource its handler acts on.
 *
 * <p>Implemented by the service whose handlers read their path so (S3); looked up lazily by namespace, as
 * {@link StoredResourceArns} is.
 */
public interface RequestPathLabels {

    /** The namespace whose requests it reads, as the request-resources table names it. */
    String namespace();

    /**
     * The labels the request's path gives, by the label's name, that the service reads otherwise than {@code routed},
     * the values the router's path gives the labels of the request's route; empty when it reads them alike.
     */
    Map<String, String> labels(ContainerRequestContext ctx, Map<String, String> routed);
}
