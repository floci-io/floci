package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ExternalCatalogRegistry extends SchemaBindingRegistry<ExternalSchemaBinding> {
    public ExternalCatalogRegistry(StorageFactory storageFactory) {
        super(storageFactory.<ExternalSchemaBinding>create("redshift", "redshift-external-schemas.json",
                new TypeReference<>() { }));
    }
}
