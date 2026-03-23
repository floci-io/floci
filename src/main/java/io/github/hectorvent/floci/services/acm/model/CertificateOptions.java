package io.github.hectorvent.floci.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record CertificateOptions(
    String certificateTransparencyLoggingPreference,
    String export
) {
    public static CertificateOptions defaultOptions() {
        return new CertificateOptions("ENABLED", "DISABLED");
    }
}
