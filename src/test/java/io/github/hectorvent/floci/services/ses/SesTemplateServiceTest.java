package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted email-template domain: CRUD plus the template-shape validation and
 * duplicate/not-found handling. The service is constructed with just its own store — no 14-argument
 * SesService needed.
 */
class SesTemplateServiceTest {

    private static final String REGION = "us-east-1";
    private SesTemplateService service;

    @BeforeEach
    void setUp() {
        service = new SesTemplateService(new InMemoryStorage<>(), new ObjectMapper(), new SecureRandom());
    }

    private static EmailTemplate template(String name) {
        return new EmailTemplate(name, "Subject", "text body", "<p>html</p>");
    }

    @Test
    void create_thenGet_roundTrips() {
        EmailTemplate created = service.createTemplate(template("welcome"), REGION);
        assertNotNull(created.getCreatedTimestamp());
        assertNotNull(created.getLastUpdatedTimestamp());

        EmailTemplate fetched = service.getTemplate("welcome", REGION);
        assertEquals("Subject", fetched.getSubject());
    }

    @Test
    void create_rejectsDuplicate() {
        service.createTemplate(template("welcome"), REGION);
        assertThrows(AwsException.class, () -> service.createTemplate(template("welcome"), REGION));
    }

    @Test
    void create_rejectsBlankName() {
        assertThrows(AwsException.class, () -> service.createTemplate(template(" "), REGION));
    }

    @Test
    void create_rejectsEmptyBody() {
        assertThrows(AwsException.class,
                () -> service.createTemplate(new EmailTemplate("empty", null, null, null), REGION));
    }

    @Test
    void get_missingThrows() {
        assertThrows(AwsException.class, () -> service.getTemplate("ghost", REGION));
    }

    @Test
    void update_preservesCreatedTimestamp_missingThrows() {
        EmailTemplate created = service.createTemplate(template("welcome"), REGION);

        EmailTemplate update = template("welcome");
        update.setSubject("Updated");
        EmailTemplate updated = service.updateTemplate(update, REGION);
        assertEquals(created.getCreatedTimestamp(), updated.getCreatedTimestamp());
        assertEquals("Updated", service.getTemplate("welcome", REGION).getSubject());

        assertThrows(AwsException.class, () -> service.updateTemplate(template("ghost"), REGION));
    }

    @Test
    void delete_removesTemplate_missingThrows() {
        service.createTemplate(template("welcome"), REGION);
        service.deleteTemplate("welcome", REGION);
        assertThrows(AwsException.class, () -> service.getTemplate("welcome", REGION));
        assertThrows(AwsException.class, () -> service.deleteTemplate("welcome", REGION));
    }

    @Test
    void list_isSortedByCreationAndPerRegion() {
        service.createTemplate(template("a"), REGION);
        service.createTemplate(template("b"), REGION);
        service.createTemplate(template("other"), "eu-west-1");

        List<EmailTemplate> list = service.listTemplates(REGION);
        assertEquals(2, list.size());
        assertEquals("a", list.get(0).getTemplateName());
        assertEquals("b", list.get(1).getTemplateName());
    }

    @Test
    void findAndSave_supportFacadeTagging() {
        service.createTemplate(template("welcome"), REGION);
        assertTrue(service.find("ghost", REGION).isEmpty());

        EmailTemplate found = service.find("welcome", REGION).orElseThrow();
        found.setTags(List.of(new Tag("team", "ses")));
        service.save(found, REGION);

        EmailTemplate reloaded = service.find("welcome", REGION).orElseThrow();
        assertEquals(1, reloaded.getTags().size());
        assertEquals("team", reloaded.getTags().get(0).key());
        assertEquals("ses", reloaded.getTags().get(0).value());
    }
}
