package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KinesisSchemaRegistry extends SchemaBindingRegistry<KinesisSchemaBinding> {
    @Inject
    public KinesisSchemaRegistry(StorageFactory storageFactory) {
        super(storageFactory.<KinesisSchemaBinding>create("redshift", "redshift-kinesis-schemas.json",
                new TypeReference<>() { }));
    }

    KinesisSchemaRegistry(AccountAwareStorageBackend<KinesisSchemaBinding> bindings) {
        super(bindings);
    }
}
