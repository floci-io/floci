package io.floci.aws.services.rds.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * RDS zero-ETL integrations into Amazon Redshift: {@code CreateIntegration},
 * {@code DescribeIntegrations}, {@code ModifyIntegration} and {@code DeleteIntegration}, from an
 * Aurora PostgreSQL cluster or an RDS for PostgreSQL instance into a Redshift Serverless namespace
 * or a provisioned Redshift cluster.
 *
 * <p>The record, its validation and its lifecycle are kept here. Nothing is copied into the target
 * yet: a created integration reports {@code creating} and is {@code active} on the next read, and a
 * modified one reports {@code modifying} and is {@code active} again on the next read. Deviations
 * are documented in {@code docs/services/rds.md}.
 */
@ApplicationScoped
public class RdsIntegrationService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsIntegrationService.class);

    private static final String KEY_PREFIX = "integration::";
    // The IntegrationName shape's modelled pattern and length.
    private static final Pattern NAME = Pattern.compile("[a-zA-Z](?:-?[a-zA-Z0-9]+)*");
    private static final int NAME_MAX_LENGTH = 63;
    private static final int DESCRIPTION_MAX_LENGTH = 1000;
    private static final String AURORA_POSTGRESQL = "aurora-postgresql";
    private static final String POSTGRES = "postgres";
    private static final Set<String> FILTER_NAMES =
            Set.of("integration-identifier", "integration-name", "source-arn", "target-arn", "status");

    private final StorageBackend<String, Integration> integrations;
    private final RdsService rdsService;
    private final RedshiftService redshiftService;
    private final RedshiftServerlessService redshiftServerlessService;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final Object lock = new Object();

    @Inject
    public RdsIntegrationService(StorageFactory storageFactory, RdsService rdsService,
                                 RedshiftService redshiftService, RedshiftServerlessService redshiftServerlessService,
                                 RegionResolver regionResolver, Clock clock) {
        this(storageFactory.create("rds", "rds-integrations.json",
                        new TypeReference<Map<String, Integration>>() {}),
                rdsService, redshiftService, redshiftServerlessService, regionResolver, clock);
    }

    RdsIntegrationService(StorageBackend<String, Integration> integrations, RdsService rdsService,
                          RedshiftService redshiftService, RedshiftServerlessService redshiftServerlessService,
                          RegionResolver regionResolver, Clock clock) {
        this.integrations = integrations;
        this.rdsService = rdsService;
        this.redshiftService = redshiftService;
        this.redshiftServerlessService = redshiftServerlessService;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    public record CreateRequest(String integrationName, String sourceArn, String targetArn, String kmsKeyId,
                                Map<String, String> additionalEncryptionContext, Map<String, String> tags,
                                String dataFilter, String description) {}

    public Integration createIntegration(CreateRequest request, String region) {
        String effectiveRegion = effectiveRegion(region);
        String accountId = regionResolver.getAccountId();
        requireValidName(request.integrationName());
        requireValidDescription(request.description());
        requireSource(request.sourceArn(), effectiveRegion, accountId);
        requireTarget(request.targetArn(), effectiveRegion, accountId);
        IntegrationDataFilter.parsePostgres(request.dataFilter());
        Map<String, String> encryptionContext = request.additionalEncryptionContext() != null
                ? request.additionalEncryptionContext() : Map.of();
        if (!encryptionContext.isEmpty() && (request.kmsKeyId() == null || request.kmsKeyId().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "AdditionalEncryptionContext can only be specified together with KMSKeyId.", 400);
        }

        Integration integration = new Integration();
        integration.setIntegrationArn(regionResolver.buildArn("rds", effectiveRegion, accountId,
                "integration:" + UUID.randomUUID()));
        integration.setIntegrationName(request.integrationName());
        integration.setSourceArn(request.sourceArn());
        integration.setTargetArn(request.targetArn());
        integration.setKmsKeyId(blankToNull(request.kmsKeyId()));
        integration.setAdditionalEncryptionContext(encryptionContext);
        integration.setTags(request.tags());
        integration.setDataFilter(request.dataFilter());
        integration.setDescription(request.description());
        integration.setCreateTime(Instant.now(clock));
        integration.setRegion(effectiveRegion);
        integration.setAccountId(accountId);
        // Nothing is copied yet, so there is no setup to wait for: the record is active, and only the
        // create response reports the creating status AWS returns.
        integration.setStatus(Integration.STATUS_ACTIVE);

        synchronized (lock) {
            if (findByName(effectiveRegion, request.integrationName()).isPresent()) {
                throw alreadyExists(request.integrationName());
            }
            if (findBySourceAndTarget(effectiveRegion, request.sourceArn(), request.targetArn()).isPresent()) {
                throw new AwsException("IntegrationAlreadyExistsFault",
                        "An integration between source " + request.sourceArn() + " and target "
                                + request.targetArn() + " already exists.", 400);
            }
            integrations.put(key(effectiveRegion, integration.integrationId()), integration);
        }
        LOG.infov("Created RDS integration {0} from {1} to {2}", integration.getIntegrationArn(),
                integration.getSourceArn(), integration.getTargetArn());
        return withStatus(integration, Integration.STATUS_CREATING);
    }

    /** The Region's integrations, oldest first, narrowed by an identifier and the filters. */
    public List<Integration> describeIntegrations(String integrationIdentifier, Map<String, List<String>> filters,
                                                  String region) {
        String effectiveRegion = effectiveRegion(region);
        for (String name : filters.keySet()) {
            if (!FILTER_NAMES.contains(name)) {
                throw new AwsException("InvalidParameterValue", "Unrecognized filter name: " + name, 400);
            }
        }
        if (integrationIdentifier != null && !integrationIdentifier.isBlank()) {
            Integration found = requireIntegration(effectiveRegion, integrationIdentifier);
            return matchesFilters(found, filters) ? List.of(new Integration(found)) : List.of();
        }
        return regionIntegrations(effectiveRegion).stream()
                .filter(integration -> matchesFilters(integration, filters))
                .map(Integration::new)
                .toList();
    }

    public Integration modifyIntegration(String integrationIdentifier, String integrationName, String dataFilter,
                                         String description, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (integrationName != null) {
            requireValidName(integrationName);
        }
        requireValidDescription(description);
        if (dataFilter != null) {
            IntegrationDataFilter.parsePostgres(dataFilter);
        }
        synchronized (lock) {
            Integration integration = requireIntegration(effectiveRegion, integrationIdentifier);
            if (!Integration.STATUS_ACTIVE.equals(integration.getStatus())) {
                throw new AwsException("InvalidIntegrationStateFault", "The integration "
                        + integration.getIntegrationName() + " can't be modified because its status is "
                        + integration.getStatus() + ".", 400);
            }
            Integration updated = new Integration(integration);
            if (integrationName != null && !integrationName.equals(updated.getIntegrationName())) {
                if (findByName(effectiveRegion, integrationName).isPresent()) {
                    throw alreadyExists(integrationName);
                }
                updated.setIntegrationName(integrationName);
            }
            if (dataFilter != null) {
                updated.setDataFilter(dataFilter);
            }
            if (description != null) {
                updated.setDescription(description);
            }
            integrations.put(key(effectiveRegion, updated.integrationId()), updated);
            LOG.infov("Modified RDS integration {0}", updated.getIntegrationArn());
            return withStatus(updated, Integration.STATUS_MODIFYING);
        }
    }

    public Integration deleteIntegration(String integrationIdentifier, String region) {
        String effectiveRegion = effectiveRegion(region);
        synchronized (lock) {
            Integration integration = requireIntegration(effectiveRegion, integrationIdentifier);
            integrations.delete(key(effectiveRegion, integration.integrationId()));
            LOG.infov("Deleted RDS integration {0}", integration.getIntegrationArn());
            return withStatus(integration, Integration.STATUS_DELETING);
        }
    }

    private void requireSource(String sourceArn, String region, String accountId) {
        AwsArnUtils.Arn arn = parseArn(sourceArn, "SourceArn");
        String resource = arn.resource();
        int separator = resource.indexOf(':');
        if (!"rds".equals(arn.service()) || separator < 0) {
            throw invalidSource();
        }
        requireInScope(arn, region, accountId, "SourceArn");
        String type = resource.substring(0, separator);
        String identifier = resource.substring(separator + 1);
        if (identifier.isBlank()) {
            throw invalidSource();
        }
        switch (type) {
            case "cluster" -> requireClusterSource(rdsService.getDbCluster(identifier, region));
            case "db" -> requireInstanceSource(rdsService.getDbInstance(identifier, region));
            default -> throw invalidSource();
        }
    }

    private static AwsException invalidSource() {
        return new AwsException("InvalidParameterValue",
                "SourceArn must identify an RDS DB instance or an Aurora DB cluster.", 400);
    }

    private static void requireClusterSource(DbCluster cluster) {
        String engine = engineName(cluster.getEngineIdentifier(), cluster.getEngine(), AURORA_POSTGRESQL);
        if (!AURORA_POSTGRESQL.equals(engine)) {
            throw unsupportedSource("DB cluster " + cluster.getDbClusterIdentifier(), engine);
        }
    }

    private static void requireInstanceSource(DbInstance instance) {
        if (instance.getDbClusterIdentifier() != null && !instance.getDbClusterIdentifier().isBlank()) {
            throw new AwsException("InvalidParameterValue", "DB instance " + instance.getDbInstanceIdentifier()
                    + " is a member of DB cluster " + instance.getDbClusterIdentifier()
                    + ": use the DB cluster ARN as the SourceArn.", 400);
        }
        String engine = engineName(instance.getEngineIdentifier(), instance.getEngine(), POSTGRES);
        if (!POSTGRES.equals(engine)) {
            throw unsupportedSource("DB instance " + instance.getDbInstanceIdentifier(), engine);
        }
    }

    /**
     * The engine name the resource was created with. A record persisted before the name was kept
     * only has the enum, and a PostgreSQL one is taken to be the engine this source type allows.
     */
    private static String engineName(String engineIdentifier, DatabaseEngine engine, String postgresName) {
        if (engineIdentifier != null && !engineIdentifier.isBlank()) {
            return engineIdentifier.toLowerCase(Locale.ROOT);
        }
        if (engine == DatabaseEngine.POSTGRES) {
            return postgresName;
        }
        return engine != null ? engine.name().toLowerCase(Locale.ROOT) : "";
    }

    private static AwsException unsupportedSource(String source, String engine) {
        return new AwsException("InvalidParameterValue", source + " uses engine " + engine
                + ", which is not a supported zero-ETL source: use an aurora-postgresql DB cluster"
                + " or a postgres DB instance.", 400);
    }

    private void requireTarget(String targetArn, String region, String accountId) {
        AwsArnUtils.Arn arn = parseArn(targetArn, "TargetArn");
        boolean namespace = RedshiftZeroEtlEndpoints.isNamespaceArn(targetArn);
        boolean cluster = "redshift".equals(arn.service()) && arn.resource().startsWith("cluster:");
        if (!namespace && !cluster) {
            throw new AwsException("InvalidParameterValue",
                    "TargetArn must identify a Redshift Serverless namespace or a provisioned Redshift cluster.", 400);
        }
        requireInScope(arn, region, accountId, "TargetArn");
        boolean found = namespace
                ? redshiftServerlessService.findNamespaceByArn(targetArn, region).isPresent()
                : redshiftService.findClusterByArn(targetArn, region).isPresent();
        if (!found) {
            throw new AwsException("InvalidParameterValue",
                    "The integration target " + targetArn + " can't be found.", 400);
        }
    }

    private void requireInScope(AwsArnUtils.Arn arn, String region, String accountId, String parameter) {
        if (!region.equals(arn.region()) || !accountId.equals(arn.accountId())
                || !regionResolver.partitionForRegion(region).equals(arn.partition())) {
            throw new AwsException("InvalidParameterValue", parameter + " " + arn
                    + " must be in the integration's account and Region.", 400);
        }
    }

    private static AwsArnUtils.Arn parseArn(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParameterValue", parameter + " is required.", 400);
        }
        try {
            return AwsArnUtils.parse(value);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", parameter + " " + value + " is not a valid ARN.", 400);
        }
    }

    private static void requireValidName(String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationName is required.", 400);
        }
        if (integrationName.length() > NAME_MAX_LENGTH || !NAME.matcher(integrationName).matches()) {
            throw new AwsException("InvalidParameterValue", "The integration name " + integrationName
                    + " is not valid: use 1 to 63 letters, digits or single hyphens, starting with a letter"
                    + " and not ending with a hyphen.", 400);
        }
    }

    private static void requireValidDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new AwsException("InvalidParameterValue",
                    "Description can be at most " + DESCRIPTION_MAX_LENGTH + " characters.", 400);
        }
    }

    /** An integration by its ARN, the id at the end of its ARN, or its name. */
    private Integration requireIntegration(String region, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationIdentifier is required.", 400);
        }
        List<Integration> all = regionIntegrations(region);
        return all.stream().filter(integration -> identifier.equals(integration.getIntegrationArn())).findFirst()
                .or(() -> all.stream().filter(integration -> identifier.equals(integration.integrationId())).findFirst())
                .or(() -> all.stream()
                        .filter(integration -> identifier.equals(integration.getIntegrationName())).findFirst())
                .orElseThrow(() -> new AwsException("IntegrationNotFoundFault",
                        "Integration " + identifier + " not found.", 404));
    }

    private Optional<Integration> findByName(String region, String integrationName) {
        return regionIntegrations(region).stream()
                .filter(integration -> integrationName.equals(integration.getIntegrationName()))
                .findFirst();
    }

    private Optional<Integration> findBySourceAndTarget(String region, String sourceArn, String targetArn) {
        return regionIntegrations(region).stream()
                .filter(integration -> sourceArn.equals(integration.getSourceArn())
                        && targetArn.equals(integration.getTargetArn()))
                .findFirst();
    }

    private List<Integration> regionIntegrations(String region) {
        return integrations.scan(key -> key.startsWith(KEY_PREFIX + region + "::")).stream()
                .sorted(Comparator.comparing(Integration::getCreateTime)
                        .thenComparing(Integration::getIntegrationArn))
                .toList();
    }

    private static boolean matchesFilters(Integration integration, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            List<String> values = filter.getValue();
            if (values.isEmpty()) {
                continue;
            }
            boolean matched = switch (filter.getKey()) {
                case "integration-identifier" -> values.contains(integration.getIntegrationArn())
                        || values.contains(integration.integrationId());
                case "integration-name" -> values.contains(integration.getIntegrationName());
                case "source-arn" -> values.contains(integration.getSourceArn());
                case "target-arn" -> values.contains(integration.getTargetArn());
                case "status" -> values.contains(integration.getStatus());
                default -> throw new IllegalStateException("Unvalidated filter name: " + filter.getKey());
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static Integration withStatus(Integration integration, String status) {
        Integration reported = new Integration(integration);
        reported.setStatus(status);
        return reported;
    }

    private static AwsException alreadyExists(String integrationName) {
        return new AwsException("IntegrationAlreadyExistsFault",
                "An integration named " + integrationName + " already exists.", 400);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String effectiveRegion(String region) {
        return region != null && !region.isBlank() ? region : regionResolver.getDefaultRegion();
    }

    private static String key(String region, String integrationId) {
        return KEY_PREFIX + region + "::" + integrationId;
    }

    @Override
    public void clear() {
        integrations.clear();
        LOG.info("Cleared all RDS integrations");
    }
}
