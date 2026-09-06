package io.github.hectorvent.floci.services.verifiedpermissions;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers the Cedar Java request and response types that Jackson accesses reflectively.
 * Cedar's authorization engine wraps requests in a private inner type before serializing
 * them across JNI, so GraalVM cannot infer those annotated fields from Floci call sites.
 */
@RegisterForReflection(classNames = {
        "com.cedarpolicy.BasicAuthorizationEngine$AuthorizationRequest",
        "com.cedarpolicy.model.AuthorizationRequest",
        "com.cedarpolicy.model.AuthorizationResponse",
        "com.cedarpolicy.model.AuthorizationSuccessResponse",
        "com.cedarpolicy.model.DetailedError",
        "com.cedarpolicy.serializer.JsonEUID"
})
final class CedarNativeReflectionSupport {
    private CedarNativeReflectionSupport() {
    }
}
