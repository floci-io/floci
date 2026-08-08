package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the address Lambda pods use to reach the Floci process: the Runtime API
 * port range (runtime-api-base-port..max-port) and the main emulator port. Pods talk
 * straight to this address; there is no Docker-style host gateway or embedded DNS,
 * so a wrong value surfaces only as {@code Function.TimedOut}. That is why an
 * unresolvable address fails fast instead of guessing.
 */
@ApplicationScoped
public class KubernetesFlociAddressResolver {

    private static final Logger LOG = Logger.getLogger(KubernetesFlociAddressResolver.class);
    private static final Path SERVICE_ACCOUNT_TOKEN =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");

    private final EmulatorConfig config;
    private String cachedAddress;

    @Inject
    public KubernetesFlociAddressResolver(EmulatorConfig config) {
        this.config = config;
    }

    /** Host or IP only, e.g. {@code 10.42.0.7}. */
    public synchronized String resolve() {
        if (cachedAddress == null) {
            cachedAddress = doResolve();
            // Ports go in as strings: MessageFormat would render 9200 as "9,200".
            LOG.infov("Lambda pods will reach Floci at {0} (runtime API ports {1}-{2}, main port {3})",
                    cachedAddress,
                    Integer.toString(config.services().lambda().runtimeApiBasePort()),
                    Integer.toString(config.services().lambda().runtimeApiMaxPort()),
                    Integer.toString(mainPort()));
        }
        return cachedAddress;
    }

    /**
     * Base URL for FLOCI_ENDPOINT / AWS_ENDPOINT_URL inside the pod. Built from
     * {@link #resolve()} rather than {@code ContainerReachableEndpoint}, which is
     * Docker/embedded-DNS specific.
     */
    public String flociBaseUrl() {
        var scheme = config.tls().enabled() ? "https" : "http";
        return scheme + "://" + resolve() + ":" + mainPort();
    }

    /**
     * Base URL for init-container code and layer downloads: always plain HTTP.
     * busybox wget's built-in TLS cannot complete a handshake with Floci's TLS
     * stack, and Floci's TLS proxy serves both protocols on the emulator port.
     * The runtime API traffic on this same pod network is plain HTTP too, so
     * TLS here would add no trust the executor doesn't already assume.
     */
    public String downloadBaseUrl() {
        return "http://" + resolve() + ":" + mainPort();
    }

    private int mainPort() {
        // The port Floci actually listens on. base-url may describe a proxied
        // client-facing endpoint; pods talk to this process directly.
        return config.port();
    }

    private String doResolve() {
        var override = config.services().lambda().kubernetes().flociAddress()
                .filter(s -> !s.isBlank());
        if (override.isPresent()) {
            var address = override.get().trim();
            // Reachability can only be judged from the pods' network, not from here,
            // but anything beyond hostname/IP characters is always a mistake worth
            // failing on: the value is embedded in URLs and in single-quoted
            // init-container shell commands.
            if (!address.matches("[A-Za-z0-9.-]+")) {
                throw new IllegalStateException(
                        "floci.services.lambda.kubernetes.floci-address must be a bare host or IP "
                                + "(no scheme, port or path), got '" + address + "'. Ports are "
                                + "derived from floci.port and the runtime-api port range.");
            }
            return address;
        }
        if (!isRunningInCluster()) {
            throw new IllegalStateException(
                    "The kubernetes Lambda executor needs an address that pods can use to reach "
                            + "Floci, and Floci is not running inside the cluster. Set "
                            + "FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS "
                            + "(floci.services.lambda.kubernetes.floci-address) to a host/IP "
                            + "reachable from the cluster's pods, e.g. this machine's LAN IP.");
        }
        return ownPodAddress();
    }

    private boolean isRunningInCluster() {
        return System.getenv("KUBERNETES_SERVICE_HOST") != null
                || Files.exists(SERVICE_ACCOUNT_TOKEN);
    }

    /**
     * Inside a pod the hostname resolves to the pod IP, so getLocalHost is authoritative.
     * The interface scan is a fallback for images with an unresolvable hostname.
     */
    private String ownPodAddress() {
        // IPv4 only: an IPv6 literal would need bracketing in every URL this address
        // feeds, and busybox wget cannot fetch from unbracketed IPv6 hosts.
        try {
            var local = InetAddress.getLocalHost();
            if (local instanceof Inet4Address && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception e) {
            LOG.debugv("getLocalHost failed, scanning interfaces: {0}", e.getMessage());
        }
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                var addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnv("Interface scan for pod IP failed: {0}", e.getMessage());
        }
        throw new IllegalStateException(
                "Could not determine an IPv4 address of this pod for Lambda pods to connect "
                        + "back to (IPv6-only clusters are not supported yet). Set "
                        + "FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS explicitly.");
    }
}
