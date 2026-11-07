package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.organizations.OrganizationsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the report service does with an Organizations read that fails for a reason other than the
 * caller having no organization. The end-to-end behaviour is in
 * {@link OrganizationsAccessReportIntegrationTest}; this is the one case a real organization
 * cannot be made to produce.
 */
class OrganizationsAccessReportServiceTest {

    private static final String CALLER = "111122223333";
    private static final String ENTITY_PATH = "o-a1b2c3d4e5/r-abcd";

    @Test
    void anOrganizationsReadThatFailsForAnotherReasonKeepsItsOwnError() {
        OrganizationsService organizations = mock(OrganizationsService.class);
        when(organizations.describeOrganization(CALLER))
                .thenThrow(new AwsException("ServiceFailure",
                        "The request processing has failed because of an unknown error.", 500));
        OrganizationsAccessReportService service = new OrganizationsAccessReportService(
                new InMemoryStorage<>(), organizations,
                new IamPolicyEvaluator(new ObjectMapper()), Clock.systemUTC());

        AwsException thrown = assertThrows(AwsException.class,
                () -> service.generate(CALLER, ENTITY_PATH, null));

        // Reading it as AccessDenied would send the caller after a permission they already have.
        assertEquals("ServiceFailure", thrown.getErrorCode());
        assertEquals(500, thrown.getHttpStatus());
    }

    @Test
    void anAccountInNoOrganizationIsNotAManagementAccount() {
        OrganizationsService organizations = mock(OrganizationsService.class);
        when(organizations.describeOrganization(CALLER))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException",
                        "Your account is not a member of an organization.", 400));
        OrganizationsAccessReportService service = new OrganizationsAccessReportService(
                new InMemoryStorage<>(), organizations,
                new IamPolicyEvaluator(new ObjectMapper()), Clock.systemUTC());

        AwsException thrown = assertThrows(AwsException.class,
                () -> service.generate(CALLER, ENTITY_PATH, null));

        // AWSOrganizationsNotInUse is an Organizations code this operation does not publish.
        assertEquals("AccessDenied", thrown.getErrorCode());
        assertEquals(403, thrown.getHttpStatus());
    }
}
