package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted CVET storage domain. The identity-dependent
 * validation lives in the SesService facade, so this service is a pure store — constructed with just
 * its own store and unit-tested without a 14-argument SesService.
 */
class SesCvetServiceTest {

    private static final String REGION = "us-east-1";
    private SesCvetService service;

    @BeforeEach
    void setUp() {
        service = new SesCvetService(new InMemoryStorage<>());
    }

    private static CustomVerificationEmailTemplate template(String name) {
        CustomVerificationEmailTemplate t = new CustomVerificationEmailTemplate();
        t.setTemplateName(name);
        t.setFromEmailAddress("verify@example.com");
        t.setTemplateSubject("Verify");
        t.setTemplateContent("Hello");
        t.setSuccessRedirectionURL("https://example.com/ok");
        t.setFailureRedirectionURL("https://example.com/no");
        return t;
    }

    @Test
    void create_thenGet_roundTrips() {
        service.createCustomVerificationEmailTemplate(template("t1"), REGION);
        assertEquals("t1", service.getCustomVerificationEmailTemplate("t1", REGION).getTemplateName());
    }

    @Test
    void create_duplicate_throwsAlreadyExists() {
        service.createCustomVerificationEmailTemplate(template("t1"), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createCustomVerificationEmailTemplate(template("t1"), REGION));
        assertEquals("CustomVerificationEmailTemplateAlreadyExists", e.getErrorCode());
    }

    @Test
    void get_unknown_throwsDoesNotExist() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getCustomVerificationEmailTemplate("ghost", REGION));
        assertEquals("CustomVerificationEmailTemplateDoesNotExist", e.getErrorCode());
    }

    @Test
    void delete_removesIt_andFindReflectsIt() {
        service.createCustomVerificationEmailTemplate(template("t1"), REGION);
        assertTrue(service.find("t1", REGION).isPresent());

        service.deleteCustomVerificationEmailTemplate("t1", REGION);
        assertTrue(service.find("t1", REGION).isEmpty());
    }

    @Test
    void update_unknown_throwsDoesNotExist() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.updateCustomVerificationEmailTemplate(template("ghost"), REGION));
        assertEquals("CustomVerificationEmailTemplateDoesNotExist", e.getErrorCode());
    }
}
