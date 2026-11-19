package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The streaming materialized views over Kinesis streams, keyed by cluster, database, schema and view name. */
@ApplicationScoped
public class KinesisViewRegistry extends SchemaBindingRegistry<KinesisViewBinding> {

    @Inject
    public KinesisViewRegistry(StorageFactory storageFactory) {
        super(storageFactory.<KinesisViewBinding>create("redshift", "redshift-kinesis-views.json",
                new TypeReference<>() { }));
    }

    KinesisViewRegistry(AccountAwareStorageBackend<KinesisViewBinding> bindings) {
        super(bindings);
    }
}
