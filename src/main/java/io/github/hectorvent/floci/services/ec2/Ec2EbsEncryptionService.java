package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Region-scoped EBS account-level encryption defaults: whether new volumes are
 * encrypted by default and which KMS key is used when no key is specified.
 *
 * <p>LZA's SecurityStack {@code Custom::EnableEbsEncryptionByDefault} Lambda
 * calls EnableEbsEncryptionByDefault + ModifyEbsDefaultKmsKeyId on create and
 * DisableEbsEncryptionByDefault on delete; the module runner additionally reads
 * the state back with the Get variants, which fail hard on a missing KmsKeyId,
 * so the default key falls back to the AWS-managed {@code alias/aws/ebs}.</p>
 */
@ApplicationScoped
public class Ec2EbsEncryptionService {

    private static final Logger LOG = Logger.getLogger(Ec2EbsEncryptionService.class);

    /** The AWS-managed EBS key every account starts with. */
    static final String AWS_MANAGED_KEY_ALIAS = "alias/aws/ebs";

    private static final String ENABLED_SUFFIX = "enabled";
    private static final String KMS_KEY_SUFFIX = "kmsKeyId";

    // key(region, setting) -> value
    private final StorageBackend<String, String> settings;

    @Inject
    public Ec2EbsEncryptionService(StorageFactory storageFactory) {
        this(storageFactory.create("ec2", "ec2-ebs-encryption.json", new TypeReference<Map<String, String>>() {}));
    }

    // Package-private for hermetic tests (pass an in-memory StorageBackend directly).
    Ec2EbsEncryptionService(StorageBackend<String, String> settings) {
        this.settings = settings;
    }

    public boolean getEbsEncryptionByDefault(String region) {
        return settings.get(key(region, ENABLED_SUFFIX)).map(Boolean::parseBoolean).orElse(false);
    }

    public boolean enableEbsEncryptionByDefault(String region) {
        settings.put(key(region, ENABLED_SUFFIX), "true");
        LOG.infov("Enabled EBS encryption by default in {0}", region);
        return true;
    }

    public boolean disableEbsEncryptionByDefault(String region) {
        settings.put(key(region, ENABLED_SUFFIX), "false");
        LOG.infov("Disabled EBS encryption by default in {0}", region);
        return false;
    }

    public String getEbsDefaultKmsKeyId(String region) {
        return settings.get(key(region, KMS_KEY_SUFFIX)).orElse(AWS_MANAGED_KEY_ALIAS);
    }

    public String modifyEbsDefaultKmsKeyId(String region, String kmsKeyId) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter KmsKeyId", 400);
        }
        settings.put(key(region, KMS_KEY_SUFFIX), kmsKeyId);
        LOG.infov("EBS default KMS key in {0} set to {1}", region, kmsKeyId);
        return kmsKeyId;
    }

    public String resetEbsDefaultKmsKeyId(String region) {
        settings.delete(key(region, KMS_KEY_SUFFIX));
        LOG.infov("EBS default KMS key in {0} reset to {1}", region, AWS_MANAGED_KEY_ALIAS);
        return AWS_MANAGED_KEY_ALIAS;
    }

    private static String key(String region, String setting) {
        return region + "|" + setting;
    }
}
