package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ssm.model.Parameter;
import io.floci.aws.services.ssm.model.ParameterHistory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeds retained records that the current PutParameter API correctly refuses to create. */
final class SsmLegacyParameterFixture implements AutoCloseable {

    private final AccountAwareStorageBackend<Parameter> parameters;
    private final AccountAwareStorageBackend<List<ParameterHistory>> history;
    private final String account;
    private final String key;
    private final String arn;

    SsmLegacyParameterFixture(StorageFactory storage, String account, String region, String name) {
        parameters = storage.create("ssm", "ssm-parameters.json", new TypeReference<>() {});
        history = storage.create("ssm", "ssm-history.json", new TypeReference<>() {});
        this.account = account;
        key = region + "::" + name;
        arn = "arn:" + AwsRegions.partitionFor(region) + ":ssm:" + region + ":" + account
                + ":" + SsmService.parameterResource(name);
        assertTrue(parameters.getForAccount(account, key).isEmpty(), "Fixture must not overwrite a parameter");
        assertTrue(history.getForAccount(account, key).isEmpty(), "Fixture must not overwrite history");
        Parameter current = new Parameter(name, "v2", "String");
        current.setArn(arn);
        current.setVersion(2);
        ParameterHistory previous = new ParameterHistory(current);
        previous.setVersion(1);
        previous.setValue("v1");
        previous.setLabels(List.of("stable"));
        parameters.putForAccount(account, key, current);
        history.putForAccount(account, key, List.of(previous, new ParameterHistory(current)));
    }

    String arn() {
        return arn;
    }

    @Override
    public void close() {
        parameters.deleteForAccount(account, key);
        history.deleteForAccount(account, key);
    }
}
