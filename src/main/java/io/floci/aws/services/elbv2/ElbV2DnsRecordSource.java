package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsRecordSource;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/** Resolves an ALB alias to the emulator address serving its listeners. */
@ApplicationScoped
public class ElbV2DnsRecordSource implements DnsRecordSource {
    private final ElbV2Service service;

    public ElbV2DnsRecordSource(ElbV2Service service) {
        this.service = service;
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, String listenerAddress) {
        List<LoadBalancer> matches = service.findByDnsName(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.of(DnsAnswer.refused());
        }
        LoadBalancer target = matches.getFirst();
        if (!"application".equals(target.getType()) || hostedZoneId == null
                || !hostedZoneId.equals(target.getCanonicalHostedZoneId()) || (type != 1 && type != 28)) {
            return Optional.of(DnsAnswer.refused());
        }
        if (type == 28 || listenerAddress == null || listenerAddress.isBlank()) {
            return Optional.of(DnsAnswer.noData());
        }
        return Optional.of(DnsAnswer.records(List.of(listenerAddress), 60));
    }
}
