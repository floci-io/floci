package io.floci.aws.services.s3;

import io.floci.aws.core.common.RoutedResource;
import jakarta.ws.rs.container.ResourceInfo;

final class S3SignatureFilterScope {

    private S3SignatureFilterScope() {
    }

    static boolean verifiesSignatures(S3Service s3Service, PreSignedUrlGenerator presignGenerator) {
        return (s3Service != null && s3Service.isAuthEnforced())
                || (presignGenerator != null && presignGenerator.shouldValidateSignatures());
    }

    /** Safe from a response filter too, which also sees requests routing turned away. */
    static boolean routedToS3(ResourceInfo resourceInfo) {
        return S3Controller.class.equals(RoutedResource.resourceClass(resourceInfo));
    }
}
