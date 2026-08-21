package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code runtimePlatform} of an ECS task definition:
 * {@code {"cpuArchitecture": "X86_64"|"ARM64", "operatingSystemFamily": "LINUX"|"WINDOWS_SERVER_*"}}.
 *
 * <p>Modelled for RegisterTaskDefinition/DescribeTaskDefinition round-trip fidelity — a client that
 * reads back what it registered (Terraform, or a deploy tool verifying its own write) must see the
 * platform it asked for. Floci runs every task on the host's own architecture, so the value has no
 * effect on where a local task is placed.
 */
@RegisterForReflection
public record RuntimePlatform(String cpuArchitecture, String operatingSystemFamily) {
}
