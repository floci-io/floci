package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.services.lakeformation.model.*;
import java.util.List;
import java.util.Optional;

public interface LakeFormationStorage {

    void putDataLakeSettings(String catalogId, DataLakeSettings settings);
    Optional<DataLakeSettings> getDataLakeSettings(String catalogId);

    void registerResource(String resourceArn, String roleArn, boolean useServiceLinkedRole, Boolean withFederation);
    void deregisterResource(String resourceArn);
    List<ResourceInfo> listResources(FilterCondition filterCondition, Integer maxResults, String nextToken);
    Optional<ResourceInfo> describeResource(String resourceArn);

    void grantPermissions(String catalogId, PrincipalResourcePermissions permissions);
    void revokePermissions(String catalogId, PrincipalResourcePermissions permissions);
    List<PrincipalResourcePermissions> listPermissions(String catalogId, DataLakePrincipal principal,
                                                       Resource resource, String resourceType,
                                                       boolean includeRelated, Integer maxResults, String nextToken);

    void createLFTag(String catalogId, String tagKey, List<String> tagValues);
    Optional<LFTag> getLFTag(String catalogId, String tagKey);
    void updateLFTag(String catalogId, String tagKey, List<String> tagValuesToAdd, List<String> tagValuesToDelete);
    void deleteLFTag(String catalogId, String tagKey);
    List<LFTagPair> listLFTags(String catalogId, String resourceShareType, Integer maxResults, String nextToken);

    void addLFTagsToResource(String catalogId, Resource resource, List<LFTagPair> lfTags);
    void removeLFTagsFromResource(String catalogId, Resource resource, List<LFTagPair> lfTags);
}
