package io.github.hectorvent.floci.services.cloudmap;

import io.github.hectorvent.floci.core.common.dns.DnsRecordSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Backs a Cloud Map DNS namespace with real DNS answers.
 *
 * <p>Cloud Map's control plane accepts namespaces, services and instances and reads them back
 * correctly, but nothing answered DNS for {@code <service>.<namespace>}, so a container that
 * registered fine still failed to resolve its peers. Registering this with the embedded DNS
 * server closes that gap for every caller of the embedded resolver, including containers Floci
 * did not launch and instances registered directly through RegisterInstance.
 */
@ApplicationScoped
public class CloudMapDnsRecordSource implements DnsRecordSource {

    private final CloudMapService cloudMapService;

    @Inject
    public CloudMapDnsRecordSource(CloudMapService cloudMapService) {
        this.cloudMapService = cloudMapService;
    }

    @Override
    public Optional<List<String>> resolveIpv4(String name) {
        return cloudMapService.resolveDnsNameIfOwned(name);
    }
}
