package io.github.hectorvent.floci.services.ecs.model;

/**
 * An address a running task holds. A task's own address on a Docker network reaches only that
 * task, so {@code port} is {@code null} when any port there counts as the task's. A loopback
 * address is shared by every task Floci publishes on the host, so it is the task's only together
 * with one of the host ports its containers were published on. An ENI address is unique only
 * within its VPC, so {@code vpcId} names the VPC it belongs to; it is {@code null} for a Docker
 * or loopback address, which no VPC scopes.
 */
public record EcsTaskAddress(String ip, Integer port, String vpcId) {

    public EcsTaskAddress(String ip, Integer port) {
        this(ip, port, null);
    }

    public static EcsTaskAddress of(String ip, int hostPort) {
        return isLoopback(ip) ? new EcsTaskAddress(ip, hostPort) : new EcsTaskAddress(ip, null);
    }

    public static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "localhost".equals(ip) || "::1".equals(ip);
    }

    /** Whether an endpoint registered at {@code ip} and {@code port} reaches this task. */
    public boolean matches(String ip, Integer port) {
        return this.ip.equals(ip) && (this.port == null || this.port.equals(port));
    }

    /** Whether this address means this task in {@code vpc}, the VPC an endpoint is scoped to. */
    public boolean appliesTo(String vpc) {
        return vpcId == null || vpcId.equals(vpc);
    }
}
