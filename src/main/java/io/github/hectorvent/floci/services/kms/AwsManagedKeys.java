package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Catalog of the AWS managed KMS keys every account has, loaded from
 * {@code kms/aws-managed-keys.yaml}.
 *
 * <p>These keys are reference data no customer creates: AWS ships one per service per region
 * and publishes it under the reserved {@code alias/aws/<service>} alias. Nothing in a Terraform
 * or CloudFormation template declares them, which is exactly why their absence is invisible to
 * dependency analysis and fatal at plan time: a module that leaves a service's encryption at its
 * default reads {@code alias/aws/s3} or {@code alias/aws/ebs} through a data source, and that
 * lookup succeeds against any real account.
 *
 * <p>The catalog is data rather than a list in Java so that adding a service is a line in the
 * YAML. It is parsed once into an immutable list. The YAML is read through a constant, which
 * Quarkus's native resource analysis cannot follow, so it is registered under
 * {@code quarkus.native.resources.includes} in {@code application.yml}.
 */
final class AwsManagedKeys {

    private static final Logger LOG = Logger.getLogger(AwsManagedKeys.class);

    /** The alias prefix AWS reserves for its own keys. A customer alias may not use it. */
    static final String RESERVED_ALIAS_PREFIX = "alias/aws/";

    private static final String CATALOG_RESOURCE_NAME = "kms/aws-managed-keys.yaml";

    /**
     * One AWS managed key.
     *
     * @param aliasName   the reserved alias AWS publishes the key under, e.g. {@code alias/aws/s3}
     * @param description the description DescribeKey reports for it
     */
    record AwsManagedKeyDef(String aliasName, String description) {
    }

    static final List<AwsManagedKeyDef> KEYS = load();

    private AwsManagedKeys() {
    }

    /** Whether an alias name falls in the {@code alias/aws/} namespace AWS reserves for itself. */
    static boolean isReservedAlias(String aliasName) {
        return aliasName != null && aliasName.startsWith(RESERVED_ALIAS_PREFIX);
    }

    private static List<AwsManagedKeyDef> load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CATALOG_RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(
                        "AWS managed key catalog not found on the classpath: " + CATALOG_RESOURCE_NAME);
            }
            Catalog catalog = new ObjectMapper(new YAMLFactory()).readValue(in, Catalog.class);
            List<AwsManagedKeyDef> defs = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            Set<String> seenDescriptions = new LinkedHashSet<>();
            for (CatalogEntry entry : catalog.keys == null ? List.<CatalogEntry>of() : catalog.keys) {
                if (entry.alias == null || entry.alias.isBlank()) {
                    continue;
                }
                String alias = entry.alias.trim();
                if (!isReservedAlias(alias)) {
                    // A name outside the reserved namespace would mint a key an ordinary customer
                    // alias could then collide with. Drop it rather than seed it.
                    LOG.warnv("Ignoring AWS managed key {0}: the alias is not under {1}",
                            alias, RESERVED_ALIAS_PREFIX);
                    continue;
                }
                if (!seen.add(alias)) {
                    LOG.warnv("Ignoring duplicate AWS managed key entry for {0}", alias);
                    continue;
                }
                String description = entry.description == null ? "" : entry.description;
                if (!seenDescriptions.add(description)) {
                    // Surviving keys are re-aliased by description, so a shared one could hand
                    // a key the other entry's alias.
                    LOG.warnv("Ignoring AWS managed key {0}: its description duplicates another entry", alias);
                    continue;
                }
                defs.add(new AwsManagedKeyDef(alias, description));
            }
            LOG.debugv("Loaded {0} AWS managed keys from {1}", defs.size(), CATALOG_RESOURCE_NAME);
            return List.copyOf(defs);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read the AWS managed key catalog: " + CATALOG_RESOURCE_NAME, e);
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Catalog {
        public List<CatalogEntry> keys;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CatalogEntry {
        public String alias;
        public String description;
    }
}
