package io.floci.aws.services.rds;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsRecordSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers the endpoint names of DB instances served in their VPC, with {@code endpoints-in-vpc} on: an instance's
 * {@code <instance>.<identifier>.<region>.rds.<dnsSuffix>} resolves to its address while a relay serves it there, and
 * to no address while it is stopped. A DB cluster's endpoint names resolve to the address of an instance they point
 * at. RDS owns these names, so the answer comes before Floci's own AWS names, which would otherwise resolve them to
 * Floci. The names come from {@link RdsEndpointAddresses}' index, so a lookup reads no stored instance.
 *
 * <p>The answer lives five seconds, as "Aurora DNS zones use a short TTL of five seconds" (AWS Knowledge Center), so
 * a client follows a failover, or the next random pick of a custom endpoint, as it would on AWS. An account's
 * instance and cluster endpoints of a Region share one zone, the identifier in their names.
 */
@ApplicationScoped
public class RdsDnsRecordSource implements DnsRecordSource {

    static final int TTL_SECONDS = 5;

    private final RdsEndpointAddresses endpointAddresses;

    public RdsDnsRecordSource(RdsEndpointAddresses endpointAddresses) {
        this.endpointAddresses = endpointAddresses;
    }

    @Override
    public Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
        if (name == null || !name.toLowerCase(Locale.ROOT).contains(".rds.") || !endpointAddresses.enabled()) {
            return Optional.empty();
        }
        String host = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (!endpointAddresses.names(host)) {
            return Optional.empty();
        }
        Optional<String> address = endpointAddresses.addressOf(host);
        if (type != 1 || address.isEmpty()) {
            return Optional.of(DnsAnswer.noData());
        }
        return Optional.of(DnsAnswer.records(List.of(address.get()), TTL_SECONDS));
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        return Optional.empty();
    }
}
