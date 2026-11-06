package io.floci.aws.core.common;

import jakarta.enterprise.inject.IllegalProductException;
import jakarta.ws.rs.container.ResourceInfo;

/**
 * Reads the resource a request was routed to from a response filter, which also runs for requests
 * routing turned away. For most of those {@link ResourceInfo} names no class, but not for all: when
 * several resource methods share a path and HTTP method and differ only in what they consume,
 * RESTEasy Reactive routes to a placeholder with no method of its own, which picks one by content
 * type. When none consumes the request's content type it answers 415 from that placeholder, the
 * {@code ResourceInfo} producer returns its missing method, and CDI refuses the null with
 * {@link IllegalProductException} on the first call, turning the 415 into a 500.
 */
public final class RoutedResource {

    private RoutedResource() {
    }

    /** The class of the resource method the request was routed to, or null when routing chose none. */
    public static Class<?> resourceClass(ResourceInfo resourceInfo) {
        if (resourceInfo == null) {
            return null;
        }
        try {
            return resourceInfo.getResourceClass();
        } catch (IllegalProductException noMethodChosen) {
            return null;
        }
    }
}
