package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code runtimePlatform} of an ECS task definition:
 * {@code {"cpuArchitecture": "X86_64"|"ARM64", "operatingSystemFamily": "LINUX"|"WINDOWS_SERVER_*"}}.
 *
 * <p>Modelled for RegisterTaskDefinition/DescribeTaskDefinition round-trip fidelity — a client that
 * reads back what it registered (Terraform, or a deploy tool verifying its own write) must see the
 * platform it asked for. A task launch also runs its images for the architecture named here, so an
 * {@code X86_64} task runs an amd64 image on an arm64 Docker host under emulation, as it would run on
 * an x86_64 Fargate host.
 */
@RegisterForReflection
public record RuntimePlatform(String cpuArchitecture, String operatingSystemFamily) {

    /**
     * The Docker platform a task with this runtime platform runs its images for, or null when no
     * architecture is named and the task runs on the Docker host's own.
     */
    public String dockerPlatform() {
        if (cpuArchitecture == null) {
            return null;
        }
        return switch (cpuArchitecture) {
            case "X86_64" -> "linux/amd64";
            case "ARM64" -> "linux/arm64";
            default -> null;
        };
    }
}
