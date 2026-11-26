package io.floci.aws.services.cloudmap;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.dns.DnsRecordSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Backs a Cloud Map DNS namespace with real DNS answers.
 *
 * <p>Cloud Map's control plane accepts namespaces, services and instances and reads them back
 * correctly, but nothing answered DNS for {@code <service>.<namespace>}, so a container that
 * registered fine still failed to resolve its peers. Registering this with the embedded DNS
 * server closes that gap. Private namespace answers require the querying VPC's association with
 * the namespace's backing Route 53 zone, including for instances registered through RegisterInstance.
 */
@ApplicationScoped
public class CloudMapDnsRecordSource implements DnsRecordSource {

    private final CloudMapService cloudMapService;

    @Inject
    public CloudMapDnsRecordSource(CloudMapService cloudMapService) {
        this.cloudMapService = cloudMapService;
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        return resolve(name, 1, null);
    }

    @Override
    public Optional<DnsAnswer> resolve(String name, int type) {
        return resolve(name, type, null);
    }

    @Override
    public Optional<DnsAnswer> resolve(String name, int type, ClientVpc origin) {
        return cloudMapService.resolveDnsNameIfOwned(name, type, origin);
    }
}
