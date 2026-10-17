package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.redshiftserverless.model.CustomDomainAssociation;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Redshift Serverless custom domain associations. An association is recorded and echoed: Floci
 * serves no traffic on the domain, but reads the ACM certificate to report expiry when available.
 *
 * <p>An association belongs to a workgroup but lives in its own store, so deleting the workgroup
 * needs no hook here. Each read checks that the workgroup is still there and drops the association
 * otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessCustomDomainService implements Resettable {

    private final AccountAwareStorageBackend<CustomDomainAssociation> associations;
    private final RedshiftServerlessService serverless;
    private final AcmService acmService;

    @Inject
    public RedshiftServerlessCustomDomainService(StorageFactory storageFactory,
                                                 RedshiftServerlessService serverless,
                                                 AcmService acmService) {
        this.associations = storageFactory.create("redshiftserverless",
                "redshiftserverless-customdomains.json",
                new TypeReference<Map<String, CustomDomainAssociation>>() {});
        this.serverless = serverless;
        this.acmService = acmService;
    }

    public synchronized CustomDomainAssociation createCustomDomainAssociation(
            String workgroupName, String customDomainName, String certificateArn, String region) {
        Workgroup workgroup = serverless.findWorkgroup(workgroupName, region)
                .orElseThrow(() -> ServerlessSupport.notFound("The workgroup " + workgroupName + " was not found."));
        CustomDomainAssociation forWorkgroup = findForWorkgroup(workgroupName, region).orElse(null);
        if (forWorkgroup != null) {
            if (customDomainName.equals(forWorkgroup.getCustomDomainName())) {
                throw ServerlessSupport.conflict("The custom domain " + customDomainName
                        + " is already associated with the workgroup " + workgroupName + ".");
            }
            throw ServerlessSupport.conflict("The workgroup " + workgroupName
                    + " already has a custom domain associated with it.");
        }
        CustomDomainAssociation existing = findByDomain(customDomainName, region);
        if (existing != null) {
            throw ServerlessSupport.conflict("The custom domain " + customDomainName
                    + " is already associated with the workgroup " + existing.getWorkgroupName() + ".");
        }
        CustomDomainAssociation association = new CustomDomainAssociation();
        association.setWorkgroupName(workgroupName);
        association.setOwnerId(workgroup.getWorkgroupId());
        association.setCustomDomainName(customDomainName);
        association.setCustomDomainCertificateArn(certificateArn);
        associations.put(storageKey(region, workgroupName, customDomainName), association);
        return withCertificateExpiry(association, region);
    }

    public synchronized CustomDomainAssociation getCustomDomainAssociation(
            String workgroupName, String customDomainName, String region) {
        return withCertificateExpiry(require(workgroupName, customDomainName, region), region);
    }

    public synchronized Optional<CustomDomainAssociation> findForWorkgroup(String workgroupName, String region) {
        return live(region).stream()
                .filter(association -> association.getWorkgroupName().equals(workgroupName))
                .findFirst()
                .map(association -> withCertificateExpiry(association, region));
    }

    public synchronized Optional<CustomDomainAssociation> findForCustomDomain(String customDomainName, String region) {
        return Optional.ofNullable(findByDomain(customDomainName, region))
                .map(association -> withCertificateExpiry(association, region));
    }

    public synchronized PaginatedResult<CustomDomainAssociation> listCustomDomainAssociations(
            String customDomainName, String certificateArn, String region, Integer maxResults, String nextToken) {
        int limit = ServerlessSupport.pageSize(maxResults);
        List<CustomDomainAssociation> matching = live(region).stream()
                .filter(association -> customDomainName == null
                        || customDomainName.equals(association.getCustomDomainName()))
                .filter(association -> certificateArn == null
                        || certificateArn.equals(association.getCustomDomainCertificateArn()))
                .sorted(Comparator.comparing(CustomDomainAssociation::getWorkgroupName)
                        .thenComparing(CustomDomainAssociation::getCustomDomainName))
                .map(association -> withCertificateExpiry(association, region))
                .toList();
        return Pagination.paginate(matching,
                association -> association.getWorkgroupName() + "|" + association.getCustomDomainName(),
                limit, nextToken, "custom-domain",
                token -> ServerlessSupport.invalidPagination("Invalid nextToken."));
    }

    /** Points the association at another certificate, the only member AWS lets an update change. */
    public synchronized CustomDomainAssociation updateCustomDomainAssociation(
            String workgroupName, String customDomainName, String certificateArn, String region) {
        CustomDomainAssociation updated = new CustomDomainAssociation(require(workgroupName, customDomainName, region));
        updated.setCustomDomainCertificateArn(certificateArn);
        updated.setCustomDomainCertificateExpiryTime(null);
        associations.put(storageKey(region, workgroupName, customDomainName), updated);
        return withCertificateExpiry(updated, region);
    }

    public synchronized void deleteCustomDomainAssociation(String workgroupName, String customDomainName,
                                                           String region) {
        require(workgroupName, customDomainName, region);
        associations.delete(storageKey(region, workgroupName, customDomainName));
    }

    @Override
    public void clear() {
        associations.clear();
    }

    private CustomDomainAssociation require(String workgroupName, String customDomainName, String region) {
        CustomDomainAssociation association = find(workgroupName, customDomainName, region);
        if (association == null) {
            throw ServerlessSupport.notFound("The custom domain " + customDomainName
                    + " is not associated with the workgroup " + workgroupName + ".");
        }
        return association;
    }

    /** The association, or null when there is none or its workgroup is gone, in which case it is dropped. */
    private CustomDomainAssociation find(String workgroupName, String customDomainName, String region) {
        String key = storageKey(region, workgroupName, customDomainName);
        CustomDomainAssociation association = associations.get(key).orElse(null);
        if (association != null && !isOnSameWorkgroup(association, region)) {
            associations.delete(key);
            return null;
        }
        return association;
    }

    /** The region's associations whose workgroup is still there; any other is deleted as it is found. */
    private List<CustomDomainAssociation> live(String region) {
        return ServerlessSupport.live(associations, region,
                association -> isOnSameWorkgroup(association, region),
                association -> storageKey(region, association.getWorkgroupName(), association.getCustomDomainName()));
    }

    /** A workgroup recreated under the same name has a new id, so it never inherits its predecessor's domains. */
    private boolean isOnSameWorkgroup(CustomDomainAssociation association, String region) {
        return serverless.findWorkgroup(association.getWorkgroupName(), region)
                .map(workgroup -> Objects.equals(workgroup.getWorkgroupId(), association.getOwnerId()))
                .orElse(false);
    }

    /** The live association claiming this custom domain in the region, or null when none does. */
    private CustomDomainAssociation findByDomain(String customDomainName, String region) {
        return live(region).stream()
                .filter(association -> customDomainName.equals(association.getCustomDomainName()))
                .findFirst()
                .orElse(null);
    }

    private Instant certificateExpiryTime(String certificateArn, String region) {
        if (certificateArn == null || certificateArn.isBlank()) {
            return null;
        }
        String certRegion = region;
        if (AwsArnUtils.isArn(certificateArn)) {
            String arnRegion = AwsArnUtils.parse(certificateArn).region();
            if (arnRegion != null && !arnRegion.isBlank()) {
                certRegion = arnRegion;
            }
        }
        try {
            Certificate certificate = acmService.describeCertificate(certificateArn, certRegion);
            return certificate != null ? certificate.getNotAfter() : null;
        } catch (AwsException ignored) {
            // A missing certificate in ACM leaves the expiry time unknown.
            return null;
        }
    }

    private CustomDomainAssociation withCertificateExpiry(CustomDomainAssociation association, String region) {
        CustomDomainAssociation copy = new CustomDomainAssociation(association);
        copy.setCustomDomainCertificateExpiryTime(certificateExpiryTime(copy.getCustomDomainCertificateArn(), region));
        return copy;
    }

    private static String storageKey(String region, String workgroupName, String customDomainName) {
        return region + "::" + workgroupName + "::" + customDomainName;
    }
}
