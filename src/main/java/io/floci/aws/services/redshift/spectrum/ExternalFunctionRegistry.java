package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ExternalFunctionRegistry extends SchemaBindingRegistry<ExternalFunctionBinding> {
    @Inject
    public ExternalFunctionRegistry(StorageFactory storageFactory) {
        super(storageFactory.<ExternalFunctionBinding>create("redshift", "redshift-external-functions.json",
                new TypeReference<>() { }));
    }

    ExternalFunctionRegistry(AccountAwareStorageBackend<ExternalFunctionBinding> bindings) {
        super(bindings);
    }

    public Optional<ExternalFunctionBinding> findFunction(SpectrumSession session, String schemaName,
                                                          String functionName, List<String> argumentTypes) {
        return find(session.accountId(), session.clusterKey(), session.databaseName(),
                ExternalFunctionBinding.nameOf(schemaName, functionName, argumentTypes));
    }

    public void unbindFunction(ExternalFunctionBinding binding) {
        unbind(binding.accountId(), binding.clusterKey(), binding.databaseName(), binding.bindingName());
    }
}
