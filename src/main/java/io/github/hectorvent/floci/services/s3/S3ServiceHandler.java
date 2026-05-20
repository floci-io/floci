package io.github.hectorvent.floci.services.s3;

import java.util.*;
import main.java.io.github.hectorvent.floci.services.s3.model.handlers.*;
import main.java.io.github.hectorvent.floci.services.s3.model.RequestContext;

import jakarta.ws.rs.core.UriInfo;

public class S3ServiceHandler {

    private final Map<String, Handler> handlers = new HashMap<>();

    /*
     * Cria as instancias que realizam Handler
     */
    public S3ServiceHandler() {
        handlers.put("uploads", new UploadsHandler());
        handlers.put("notification", new NotificationHandler());
        handlers.put("versioning", new VersioningHandler());
        handlers.put("versions", new VersionsHandler());
        handlers.put("location", new LocationHandler());
        handlers.put("tagging", new TaggingHandler());
        handlers.put("object-lock", new ObjectLockHandler());
        handlers.put("website", new WebsiteHandler());
        handlers.put("policy", new PolicyHandler());
        handlers.put("cors", new CorsHandler());
        handlers.put("lifecycle", new LifecycleHandler());
        handlers.put("acl", new AclHandler());
        handlers.put("encryption", new EncryptionHandler());
        handlers.put("publicAccessBlock", new PublicAccessBlockHandler());
        handlers.put("ownershipControls", new OwnershipControlsHandler());
    }

    private boolean hasQueryParam(UriInfo uriInfo, String param) {
        if (uriInfo.getQueryParameters().containsKey(param))
            return true;
        String query = uriInfo.getRequestUri().getQuery();
        if (query == null)
            return false;
        return query.equals(param) || query.contains(param + "&") || query.contains("&" + param);
    }

    /*
     * Response: Caso a Uri-Info tenha um dos parametros determinados.
     * null: Caso não apresente um dos parametros determinados
     */
    public Response handle(S3Service service, UriInfo uriInfo, RequestContext context) {
        for (String key : handlers.keySet()) {
            if (hasQueryParam(uriInfo, key)) {
                return handlers.get(key).handleGet(service, context);
            }
        }

        return null;
    }

}
