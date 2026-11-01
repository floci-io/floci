package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Account-level block public access settings, scoped to one region. */
@ApplicationScoped
public class Ec2BlockPublicAccessService {

    private static final Logger LOG = Logger.getLogger(Ec2BlockPublicAccessService.class);

    public static final String UNBLOCKED = "unblocked";
    static final String BLOCK_ALL_SHARING = "block-all-sharing";
    static final String BLOCK_NEW_SHARING = "block-new-sharing";

    public enum Setting {
        IMAGE("AMI", "ImageBlockPublicAccessState", "ec2-image-block-public-access.json",
                List.of(BLOCK_NEW_SHARING)),
        SNAPSHOT("Snapshot", "State", "ec2-snapshot-block-public-access.json",
                List.of(BLOCK_ALL_SHARING, BLOCK_NEW_SHARING));

        private final String displayName;
        private final String parameterName;
        private final String storageFile;
        private final List<String> blockingStates;

        Setting(String displayName, String parameterName, String storageFile, List<String> blockingStates) {
            this.displayName = displayName;
            this.parameterName = parameterName;
            this.storageFile = storageFile;
            this.blockingStates = blockingStates;
        }

        String parameterName() {
            return parameterName;
        }

        private String validValuesMessage() {
            String label = blockingStates.size() == 1 ? "Valid value is " : "Valid values are ";
            return label + String.join(" and ", blockingStates);
        }
    }

    private final Map<Setting, StorageBackend<String, String>> states;

    @Inject
    public Ec2BlockPublicAccessService(StorageFactory storageFactory) {
        this(createStorage(storageFactory));
    }

    Ec2BlockPublicAccessService(StorageBackend<String, String> imageStates,
                                StorageBackend<String, String> snapshotStates) {
        EnumMap<Setting, StorageBackend<String, String>> configuredStates = new EnumMap<>(Setting.class);
        configuredStates.put(Setting.IMAGE, imageStates);
        configuredStates.put(Setting.SNAPSHOT, snapshotStates);
        this.states = configuredStates;
    }

    private Ec2BlockPublicAccessService(Map<Setting, StorageBackend<String, String>> states) {
        this.states = states;
    }

    public void validateEnableState(Setting setting, String state) {
        if (state == null || state.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter " + setting.parameterName, 400);
        }
        if (!setting.blockingStates.contains(state)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + state + ") for parameter " + setting.parameterName + " is invalid. "
                            + setting.validValuesMessage(), 400);
        }
    }

    public String enable(Setting setting, String region, String state) {
        validateEnableState(setting, state);
        states.get(setting).put(region, state);
        LOG.infov("{0} block public access in {1} set to {2}", setting.displayName, region, state);
        return state;
    }

    public String disable(Setting setting, String region) {
        states.get(setting).put(region, UNBLOCKED);
        LOG.infov("{0} block public access in {1} set to {2}", setting.displayName, region, UNBLOCKED);
        return UNBLOCKED;
    }

    public String getState(Setting setting, String region) {
        return states.get(setting).get(region).orElse(UNBLOCKED);
    }

    private static Map<Setting, StorageBackend<String, String>> createStorage(StorageFactory storageFactory) {
        EnumMap<Setting, StorageBackend<String, String>> states = new EnumMap<>(Setting.class);
        for (Setting setting : Setting.values()) {
            StorageBackend<String, String> backend = storageFactory.create("ec2", setting.storageFile,
                    new TypeReference<Map<String, String>>() {});
            states.put(setting, backend);
        }
        return states;
    }
}
