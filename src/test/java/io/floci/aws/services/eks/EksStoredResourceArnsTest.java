package io.floci.aws.services.eks;

import io.floci.aws.core.common.RegionResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksStoredResourceArnsTest {

    private static final String ACCOUNT = "123456789012";

    /** A resource's tags are read from EKS by its whole ARN, in the request's account. */
    @Test
    void aWholeArnReadsTheTagsEksHolds() {
        EksService eks = mock(EksService.class);
        String entry = "arn:aws:eks:us-east-1:" + ACCOUNT + ":access-entry/prod/role/" + ACCOUNT + "/admin/1a2b";
        when(eks.tagsInAccount(ACCOUNT, entry)).thenReturn(Map.of("team", "data"));
        EksStoredResourceArns stored = new EksStoredResourceArns(eks, new RegionResolver("us-east-1", ACCOUNT));

        assertEquals(Map.of("team", "data"), stored.storedTags(entry));
    }

    /**
     * An access entry or Pod Identity association a request names by no id is checked with that part open, which names
     * no resource EKS holds, so EKS is not asked: finding an access entry by its ARN walks its cluster's entries.
     */
    @Test
    void anArnWithAPartLeftOpenIsNotLookedUp() {
        EksService eks = mock(EksService.class);
        EksStoredResourceArns stored = new EksStoredResourceArns(eks, new RegionResolver("us-east-1", ACCOUNT));

        assertEquals(Map.of(), stored.storedTags("arn:aws:eks:us-east-1:" + ACCOUNT + ":access-entry/prod/*"));
        assertEquals(Map.of(), stored.storedTags("arn:aws:eks:us-east-1:" + ACCOUNT
                + ":podidentityassociation/prod/*"));
        verify(eks, never()).tagsInAccount(anyString(), anyString());
    }
}
