package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Settles the deployment's partition at startup and refuses a configuration that contradicts
 * itself. {@code floci.partitions.id} must name a published partition, and when
 * {@code floci.default-region} is a region the vendored catalog can place, the two must agree:
 * an operator who sets {@code FLOCI_DEFAULT_REGION=cn-north-1} with {@code FLOCI_PARTITIONS_ID=aws}
 * would otherwise get China endpoints with commercial ARNs and never a message saying why.
 *
 * <p>A default region the catalog does not know is a warning, not an error: AWS launches
 * regions faster than the vendored data is refreshed, and the {@code regionRegex} rules or the
 * commercial fallback still place it somewhere. Startup rather than first use, so the one
 * INFO line naming the partition is in every log.
 */
@ApplicationScoped
public class PartitionStartupValidator {

    private static final Logger LOG = Logger.getLogger(PartitionStartupValidator.class);

    private final EmulatorConfig config;

    @Inject
    public PartitionStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        AwsPartition partition = validate(config.defaultRegion(), config.partitions().id());
        LOG.infov("Partition: {0} (default-region {1}, dns suffix {2})",
                partition.id(), config.defaultRegion(), partition.dnsSuffix());
    }

    /**
     * The partition the deployment serves.
     *
     * @throws IllegalStateException when {@code configuredPartition} names no published partition,
     *                               or disagrees with a default region the catalog recognises
     */
    static AwsPartition validate(String defaultRegion, Optional<String> configuredPartition) {
        String effective = RegionResolver.effectivePartition(defaultRegion, configuredPartition);
        AwsPartition partition = AwsPartitions.find(effective).orElseThrow(() -> new IllegalStateException(
                "floci.partitions.id names no AWS partition: '" + effective + "'. Published partitions: "
                        + AwsPartitions.ids()));
        Optional<AwsPartition> regionPartition = AwsPartitions.forRegion(defaultRegion);
        if (regionPartition.isEmpty()) {
            LOG.warnv("floci.default-region {0} is not a published AWS region in the vendored partition data; "
                    + "treating it as partition {1}. Run 'make aws-data-sync' if AWS has launched it since.",
                    defaultRegion, partition.id());
        } else if (!regionPartition.get().id().equals(partition.id())) {
            throw new IllegalStateException("floci.partitions.id is '" + partition.id()
                    + "' but floci.default-region " + defaultRegion + " belongs to partition '"
                    + regionPartition.get().id() + "'. Set both to the same partition, or unset "
                    + "floci.partitions.id to derive it from the region.");
        }
        return partition;
    }
}
