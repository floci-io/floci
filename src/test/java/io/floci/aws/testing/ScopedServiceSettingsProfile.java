package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Settings that each reach only the class relying on them, with every service on and EC2 mocked.
 * Quarkus builds the application once per distinct profile class, and keeps every one it built for
 * the rest of the test JVM, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.ecs.mock=false} runs ECS tasks as Docker containers.
 *   {@code EcsImagePullDockerIntegrationTest}, {@code EcsEssentialContainerExitDockerNightlyIntegrationTest}
 *   and {@code EcsLinuxParametersDockerNightlyIntegrationTest} rely on it.
 *   {@code floci.network.security-group-enforcement.enabled=false} puts security-group enforcement
 *   back to its shipped default of off, and {@code floci.services.ecs.image-pull-behavior=default}
 *   back to the shipped pull on every launch, which the shared test config swaps for
 *   {@code prefer-cached}. The image pull class relies on them.
 *   {@code floci.services.ecs.reconcile-containers-on-startup=false} keeps the application from
 *   sweeping the Docker daemon for ECS containers and volumes a previous run left when it starts,
 *   which a class that runs no container would otherwise trigger too. Each ECS class stops its own
 *   tasks, so none needs the sweep. {@code floci.docker.resource-namespace} names their containers
 *   in this run's {@link SidecarContainersProfile#namespace() sidecar namespace}, so another test
 *   JVM on the same Docker daemon and port, whose startup sweep is on, never takes them for its own
 *   leftovers. {@code LambdaLeftoverContainersDockerIntegrationTest} relies on the namespace too:
 *   it sweeps the Lambda environments its deployment left on an earlier run, and the namespace,
 *   which only this test JVM uses, keeps that sweep away from another Floci's containers.</li>
 *   <li>{@code floci.services.ecs.host-volume-roots=/dev/null} makes {@code /dev/null} the one host
 *   path a device may take, since devices are refused by default, and
 *   {@code floci.services.ecs.allow-privileged-containers=true} lets a task definition ask for
 *   privilege. {@code EcsLinuxParametersDockerNightlyIntegrationTest} relies on them.</li>
 *   <li>The extra CORS origins, allowed and exposed headers, and
 *   {@code floci.security.cors-allow-private-network=true} are what a browser's preflight is
 *   answered with. {@code GlobalCorsFilterIntegrationTest} relies on them.</li>
 *   <li>{@code floci.partitions.strict=true} refuses a request signed for a service AWS does not
 *   publish in the request's partition. {@code PartitionStrictModeIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.ec2.mock-public-addresses=fixed}, with
 *   {@code floci.services.ec2.mock-public-address} set to {@value #MOCK_PUBLIC_ADDRESS}, gives that
 *   address to every mock instance whose launch asks for a public one, and an Elastic IP on its
 *   primary private address shows it too. {@code Ec2MockFixedPublicAddressIntegrationTest} relies on
 *   them.</li>
 * </ul>
 *
 * <p>Each is inert for the other classes. Only the three ECS classes and the Lambda leftovers class
 * start containers, so only theirs carry the namespace. The leftovers class sweeps only Lambda
 * environments, which no other class here starts, and drives the Lambda launcher directly, without
 * sending a request, so none of the ECS, CORS, partition or EC2 settings reaches it. The ECS tasks
 * run in bridge mode, which security-group enforcement never protects, and only the Linux
 * parameters class asks for a device or privilege. The pull on every launch reaches
 * the other two ECS classes' tasks too, and changes nothing they check: their busybox tasks run
 * the same way on the image the tag names now, and a failed pull runs the cached image. Only
 * {@code GlobalCorsFilterIntegrationTest} sends an {@code Origin} header, the only request the CORS
 * settings answer differently. Every other class signs its requests for the commercial partition,
 * where AWS publishes each service it calls. Only
 * {@code Ec2MockFixedPublicAddressIntegrationTest} launches an instance. It stays apart from
 * {@link IndependentServiceSettingsProfile}, which runs EC2 for real.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class ScopedServiceSettingsProfile implements QuarkusTestProfile {

    /** The public address a mock instance gets when its launch asks for one. */
    public static final String MOCK_PUBLIC_ADDRESS = "192.168.65.2";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("floci.services.ecs.mock", "false"),
                Map.entry("floci.network.security-group-enforcement.enabled", "false"),
                Map.entry("floci.services.ecs.image-pull-behavior", "default"),
                Map.entry("floci.services.ecs.reconcile-containers-on-startup", "false"),
                Map.entry("floci.docker.resource-namespace", SidecarContainersProfile.namespace()),
                Map.entry("floci.services.ecs.host-volume-roots", "/dev/null"),
                Map.entry("floci.services.ecs.allow-privileged-containers", "true"),
                Map.entry("floci.security.extra-cors-allowed-origins", "http://localhost:3000,https://ui.example.test"),
                Map.entry("floci.security.extra-cors-allowed-headers", "x-added-header"),
                Map.entry("floci.security.extra-cors-expose-headers", "x-visible-header"),
                Map.entry("floci.security.cors-allow-private-network", "true"),
                Map.entry("floci.partitions.strict", "true"),
                Map.entry("floci.services.ec2.mock-public-addresses", "fixed"),
                Map.entry("floci.services.ec2.mock-public-address", MOCK_PUBLIC_ADDRESS));
    }
}
