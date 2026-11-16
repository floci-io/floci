package io.floci.aws.services.athena;

import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.StorageDescriptor;

import java.util.List;

/** Glue partitions for the Athena tests. */
final class GluePartitions {

    private GluePartitions() {
    }

    /** A partition with {@code values}, at {@code location} or, when it is null, with no location of its own. */
    static Partition partition(String location, String... values) {
        Partition partition = new Partition();
        partition.setValues(List.of(values));
        if (location != null) {
            StorageDescriptor descriptor = new StorageDescriptor();
            descriptor.setLocation(location);
            partition.setStorageDescriptor(descriptor);
        }
        return partition;
    }
}
