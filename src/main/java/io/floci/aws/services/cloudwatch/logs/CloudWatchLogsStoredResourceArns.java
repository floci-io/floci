package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks CloudWatch Logs of the resources a request names: the caller's delivery of the id it was created
 * with, and the delivery destinations the deliveries from a delivery source send to, which the Service Reference
 * checks a {@code DeleteDeliverySource} on.
 */
@ApplicationScoped
public class CloudWatchLogsStoredResourceArns implements StoredResourceArns {

    private final CloudWatchLogsDeliveryService deliveries;

    @Inject
    public CloudWatchLogsStoredResourceArns(CloudWatchLogsDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public String namespace() {
        return "logs";
    }

    /** The ARN of the caller's delivery of the id {@code names} gives, as the service stored it; empty for none. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"delivery".equals(type) || names.size() != 1) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(deliveries.getDelivery(names.getFirst(), region).getArn());
        } catch (AwsException notFound) {
            return Optional.empty();
        }
    }

    /** The delivery destinations the caller's deliveries from the delivery source a {@code name} names send to. */
    @Override
    public List<String> through(String member, String value, String region) {
        return "name".equals(member) ? deliveries.destinationArnsFrom(value, region) : List.of();
    }
}
