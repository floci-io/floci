package io.github.hectorvent.floci.services.ses.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SendEmailRequestTest {

    // Iterates the record components so a field added to the record but not to toBuilder() fails
    // here instead of silently resetting to its default whenever SesService rebuilds the request
    // around rendered or sanitized content.
    @Test
    void toBuilderCopiesEveryComponent() throws Exception {
        SendEmailRequest.Builder builder = SendEmailRequest.builder();
        for (RecordComponent component : SendEmailRequest.class.getRecordComponents()) {
            Method setter = SendEmailRequest.Builder.class.getMethod(component.getName(), component.getType());
            setter.invoke(builder, sampleValue(component));
        }
        SendEmailRequest original = builder.build();
        SendEmailRequest defaults = SendEmailRequest.builder().content(new EmailContent.Raw("raw")).build();
        for (RecordComponent component : SendEmailRequest.class.getRecordComponents()) {
            assertNotEquals(component.getAccessor().invoke(defaults), component.getAccessor().invoke(original),
                    component.getName() + " must differ from its default for the round trip to prove anything");
        }

        assertEquals(original, original.toBuilder().build());
    }

    @Test
    void buildWithoutContent_fails() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> SendEmailRequest.builder().source("from@example.com").build());
        assertEquals("content", e.getMessage());
    }

    @Test
    void recipients_concatenatesToCcBccAndSkipsNullLists() {
        SendEmailRequest request = SendEmailRequest.builder()
                .toAddresses(List.of("to@example.com"))
                .ccAddresses(null)
                .bccAddresses(List.of("bcc1@example.com", "bcc2@example.com"))
                .content(new EmailContent.Raw("raw"))
                .build();

        assertEquals(List.of("to@example.com", "bcc1@example.com", "bcc2@example.com"), request.recipients());
        assertTrue(request.hasRecipients());
    }

    @Test
    void hasRecipients_falseWhenEveryListIsNullOrEmpty() {
        SendEmailRequest request = SendEmailRequest.builder()
                .toAddresses(null)
                .ccAddresses(List.of())
                .bccAddresses(null)
                .content(new EmailContent.Raw("raw"))
                .build();

        assertEquals(List.of(), request.recipients());
        assertFalse(request.hasRecipients());
    }

    private static Object sampleValue(RecordComponent component) {
        if (component.getType() == String.class) {
            return component.getName() + "-value";
        }
        if (component.getType() == ListManagementOptions.class) {
            return new ListManagementOptions("list", "topic");
        }
        if (component.getType() == EmailContent.class) {
            return new EmailContent.Simple("subject", "text", "<p>html</p>", List.of());
        }
        if (component.getType() == List.class) {
            Object element = ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
            if (element == String.class) {
                return List.of(component.getName() + "@example.com");
            }
            if (element == MessageTag.class) {
                return List.of(new MessageTag("tag", "value"));
            }
        }
        return fail("no sample value for component " + component.getName() + " of type " + component.getGenericType());
    }
}
