package io.github.hectorvent.floci.services.ec2;

public record ResolvedAmiImage(String dockerImage, String guestRuntime, boolean cloudInit) {
    public static final String DEFAULT_RUNTIME = "minimal";
    public static final String SYSTEMD_RUNTIME = "systemd";

    public static ResolvedAmiImage minimal(String dockerImage) {
        return new ResolvedAmiImage(dockerImage, DEFAULT_RUNTIME, false);
    }

    public boolean systemd() {
        return SYSTEMD_RUNTIME.equals(guestRuntime);
    }
}
