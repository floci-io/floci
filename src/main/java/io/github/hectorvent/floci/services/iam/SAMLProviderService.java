package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.SAMLProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Minimal IAM SAML provider registry used by STS assertion verification. */
@ApplicationScoped
public class SAMLProviderService {
    private static final Pattern ARN = Pattern.compile(
            "^arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::(\\d{12}):saml-provider/[A-Za-z0-9+=,.@_-]{1,128}$");
    /** {@code tagListType} / {@code tagKeyListType} are both {@code max: 50}, as elsewhere in IAM. */
    static final int MAX_TAGS_PER_SAML_PROVIDER = 50;
    private final StorageBackend<String, SAMLProvider> providers;
    private final Object providerLock = new Object();

    @Inject
    public SAMLProviderService(StorageFactory storageFactory) {
        this(storageFactory.create("iam", "iam-saml-providers.json", new TypeReference<>() {}));
    }

    SAMLProviderService(StorageBackend<String, SAMLProvider> providers) {
        this.providers = providers;
    }

    public SAMLProvider create(String partition, String accountId, String name, String metadata,
                               Map<String, String> tags) {
        String arn = AwsArnUtils.Arn.global(partition, "iam", accountId, "saml-provider/" + name).toString();
        if (!ARN.matcher(arn).matches()) {
            throw new AwsException("InvalidInput", "Invalid SAML provider name.", 400);
        }
        if (metadata == null || metadata.isBlank()) {
            throw new AwsException("InvalidInput", "SAML metadata document must not be empty.", 400);
        }
        // Checked before anything is stored: AWS documents that if any tag is invalid or the list
        // exceeds the maximum, "the entire request fails and the resource is not created". The
        // sent-list length is a wire-shape rule enforced in IamQueryHandler, which can still see
        // the members before duplicate keys collapse; what is left here is the quota on what the
        // provider would actually end up holding.
        if (tags != null && tags.size() > MAX_TAGS_PER_SAML_PROVIDER) {
            throw new AwsException("LimitExceeded",
                    "Cannot exceed quota for TagsPerSAMLProvider: " + MAX_TAGS_PER_SAML_PROVIDER, 409);
        }
        SAMLMetadata.Parsed parsed;
        try {
            parsed = SAMLMetadata.parse(metadata);
        } catch (Exception e) {
            throw new AwsException("InvalidInput", "The SAML metadata document is invalid.", 400);
        }
        SAMLProvider provider = new SAMLProvider();
        provider.setArn(arn);
        provider.setEntityId(parsed.entityId());
        provider.setCertificate(parsed.certificateBase64());
        provider.setTags(tags);
        synchronized (providerLock) {
            if (findForAccount(accountId, arn).isPresent()) {
                throw new AwsException("EntityAlreadyExists",
                        "SAML provider " + arn + " already exists.", 409);
            }
            putBack(accountId, arn, provider);
        }
        return provider;
    }

    public Optional<SAMLProvider> find(String arn) {
        var matcher = ARN.matcher(arn == null ? "" : arn);
        if (matcher.matches()) {
            return findForAccount(matcher.group(1), arn);
        }
        return providers.get(arn);
    }

    public Optional<SAMLProvider> findForAccount(String accountId, String arn) {
        var matcher = ARN.matcher(arn == null ? "" : arn);
        if (!matcher.matches() || !matcher.group(1).equals(accountId)) {
            return Optional.empty();
        }
        if (providers instanceof AccountAwareStorageBackend<SAMLProvider> aware) {
            return aware.getForAccount(accountId, arn);
        }
        return providers.get(arn);
    }

    public List<SAMLProvider> list(String accountId) {
        if (providers instanceof AccountAwareStorageBackend<SAMLProvider> aware) {
            return aware.scanForAccount(accountId, k -> true);
        }
        return providers.scan(k -> true);
    }

    public SAMLProvider getForAccount(String accountId, String arn) {
        return findForAccount(accountId, arn).orElseThrow(() -> new AwsException("NoSuchEntity",
                "The SAML provider with ARN " + arn + " cannot be found.", 404));
    }

    /**
     * Replaces the provider's metadata document when one is given. AWS's current
     * UpdateSAMLProvider also manages an {@code AssertionEncryptionMode} and a private-key
     * list for decrypting encrypted assertions; Floci's assertion verifier only checks
     * signatures against a single certificate and does not model encrypted assertions at all,
     * so those inputs are not modeled here either.
     */
    public SAMLProvider update(String accountId, String arn, String metadata) {
        if (metadata == null) {
            return getForAccount(accountId, arn);
        }
        // Request shape (parsing) before the read-modify-write, matching tag/untag's order.
        // An explicitly empty document is invalid input, the same as CreateSAMLProvider already
        // rejects it as; only an omitted document (metadata == null, above) is a no-op.
        if (metadata.isBlank()) {
            throw new AwsException("InvalidInput", "SAML metadata document must not be empty.", 400);
        }
        SAMLMetadata.Parsed parsed;
        try {
            parsed = SAMLMetadata.parse(metadata);
        } catch (Exception e) {
            throw new AwsException("InvalidInput", "The SAML metadata document is invalid.", 400);
        }
        synchronized (providerLock) {
            SAMLProvider existing = getForAccount(accountId, arn);
            // A new object, not an in-place mutation of the existing one: assertion verification
            // (StsQueryHandler) reads a provider via find()/findForAccount() without taking this
            // lock, so setting entityId and certificate as two separate writes on the live,
            // already-published object could let it observe one field updated and the other not.
            // Publishing a fully-built replacement through the same ConcurrentHashMap-backed
            // store means any reader, synchronized or not, sees either the old provider intact
            // or the new one intact, never a mix.
            SAMLProvider updated = new SAMLProvider();
            updated.setArn(existing.getArn());
            updated.setCreateDate(existing.getCreateDate());
            updated.setTags(existing.getTags());
            updated.setEntityId(parsed.entityId());
            updated.setCertificate(parsed.certificateBase64());
            putBack(accountId, arn, updated);
            return updated;
        }
    }

    /**
     * Deleting the provider does not check or update any role that references its ARN in a
     * trust policy: AWS documents that deletion succeeds regardless, and that an AssumeRole
     * against the now-dangling ARN is what fails afterward, not this call.
     */
    public void delete(String accountId, String arn) {
        // Existence check under the same lock as the delete: two concurrent deletes must not
        // both pass the check before either removes the provider, and a delete racing a
        // recreate of the same ARN must not remove the newly created one.
        synchronized (providerLock) {
            getForAccount(accountId, arn);
            if (providers instanceof AccountAwareStorageBackend<SAMLProvider> aware) {
                aware.deleteForAccount(accountId, arn);
            } else {
                providers.delete(arn);
            }
        }
    }

    public void tag(String accountId, String arn, Map<String, String> newTags) {
        synchronized (providerLock) {
            SAMLProvider provider = getForAccount(accountId, arn);
            Map<String, String> merged = new LinkedHashMap<>(provider.getTags());
            merged.putAll(newTags == null ? Map.of() : newTags);
            if (merged.size() > MAX_TAGS_PER_SAML_PROVIDER) {
                throw new AwsException("LimitExceeded",
                        "Cannot exceed quota for TagsPerSAMLProvider: " + MAX_TAGS_PER_SAML_PROVIDER, 409);
            }
            provider.setTags(merged);
            putBack(accountId, arn, provider);
        }
    }

    public void untag(String accountId, String arn, List<String> tagKeys) {
        synchronized (providerLock) {
            SAMLProvider provider = getForAccount(accountId, arn);
            Map<String, String> remaining = new LinkedHashMap<>(provider.getTags());
            if (tagKeys != null) {
                tagKeys.forEach(remaining::remove);
            }
            provider.setTags(remaining);
            putBack(accountId, arn, provider);
        }
    }

    public Map<String, String> listTags(String accountId, String arn) {
        return getForAccount(accountId, arn).getTags();
    }

    private void putBack(String accountId, String arn, SAMLProvider provider) {
        if (providers instanceof AccountAwareStorageBackend<SAMLProvider> aware) {
            aware.putForAccount(accountId, arn, provider);
        } else {
            providers.put(arn, provider);
        }
    }

    /** Metadata parser shared by provider registration and the assertion verifier. */
    static final class SAMLMetadata {
        private SAMLMetadata() {
        }
        record Parsed(String entityId, String certificateBase64) {}

        static Parsed parse(String metadata) throws Exception {
            var doc = SAMLXml.document(metadata);
            var entity = doc.getDocumentElement().getAttribute("entityID");
            var cert = SAMLXml.text(doc, "X509Certificate");
            if (entity == null || entity.isBlank() || cert == null || cert.isBlank()) {
                throw new Exception();
            }
            Base64.getDecoder().decode(cert.replaceAll("\\s+", ""));
            return new Parsed(entity, cert.replaceAll("\\s+", ""));
        }
    }
}
