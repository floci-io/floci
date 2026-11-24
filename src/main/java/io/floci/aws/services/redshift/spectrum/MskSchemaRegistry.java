package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MskSchemaRegistry extends SchemaBindingRegistry<MskSchemaBinding> {
    @Inject
    public MskSchemaRegistry(StorageFactory storageFactory) {
        super(storageFactory.<MskSchemaBinding>create("redshift", "redshift-msk-schemas.json",
                new TypeReference<>() { }));
    }

    MskSchemaRegistry(AccountAwareStorageBackend<MskSchemaBinding> bindings) {
        super(bindings);
    }
}
