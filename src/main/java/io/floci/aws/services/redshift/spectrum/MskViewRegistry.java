package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The streaming materialized views over MSK topics, keyed by cluster, database, schema and view name. */
@ApplicationScoped
public class MskViewRegistry extends SchemaBindingRegistry<MskViewBinding> {

    @Inject
    public MskViewRegistry(StorageFactory storageFactory) {
        super(storageFactory.<MskViewBinding>create("redshift", "redshift-msk-views.json",
                new TypeReference<>() { }));
    }

    MskViewRegistry(AccountAwareStorageBackend<MskViewBinding> bindings) {
        super(bindings);
    }
}
