package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.coerceBoolean;
import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.putTimestamp;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.unexpectedStartError;

/**
 * SES V2 contact-list and contact endpoints ({@code /v2/email/contact-lists}). Every operation
 * is a single-domain call on {@link SesContactService}, so this controller does not touch the
 * {@link SesService} facade at all; the send-path opt-out collection that also reads contacts
 * stays behind the facade.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesContactController {

    private static final Logger LOG = Logger.getLogger(SesContactController.class);

    private final SesContactService contactService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesContactController(SesContactService contactService, RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        this.contactService = contactService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/contact-lists")
    public Response createContactList(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            // Read leniently; the service surfaces a missing ContactListName as the AWS Smithy
            // validation error rather than a custom "required" message.
            String name = request.path("ContactListName").asText(null);
            List<Topic> topics = parseTopicsArray(request.path("Topics"));
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            String description = request.path("Description").asText(null);
            contactService.createContactList(name, description, topics, tags, region);
            LOG.infov("SES V2 CreateContactList: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/contact-lists")
    public Response listContactLists(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode lists = result.putArray("ContactLists");
        for (ContactList cl : contactService.listContactLists(region)) {
            ObjectNode item = lists.addObject();
            item.put("ContactListName", cl.getContactListName());
            putTimestamp(item, "LastUpdatedTimestamp", cl.getLastUpdatedTimestamp());
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/contact-lists/{contactListName}")
    public Response getContactList(@Context HttpHeaders headers,
                                   @PathParam("contactListName") String contactListName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(contactListJson(contactService.getContactList(contactListName, region)))
                .build();
    }

    @PUT
    @Path("/contact-lists/{contactListName}")
    public Response updateContactList(@Context HttpHeaders headers,
                                      @PathParam("contactListName") String contactListName,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode topicsNode = request.path("Topics");
            // Treat absent or explicit-null Topics as "not provided" (keep existing), consistent
            // with Description; clearing is done via an explicit empty array [].
            List<Topic> topics = (topicsNode.isMissingNode() || topicsNode.isNull())
                    ? null : parseTopicsArray(topicsNode);
            JsonNode descNode = request.path("Description");
            boolean descriptionPresent = !descNode.isMissingNode() && !descNode.isNull();
            String description = descNode.asText(null);
            contactService.updateContactList(contactListName, description, descriptionPresent,
                    topics, region);
            LOG.infov("SES V2 UpdateContactList: {0}", contactListName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/contact-lists/{contactListName}")
    public Response deleteContactList(@Context HttpHeaders headers,
                                      @PathParam("contactListName") String contactListName) {
        String region = regionResolver.resolveRegion(headers);
        contactService.deleteContactList(contactListName, region);
        LOG.infov("SES V2 DeleteContactList: {0}", contactListName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private List<Topic> parseTopicsArray(JsonNode topicsNode) {
        List<Topic> out = new ArrayList<>();
        if (topicsNode == null || topicsNode.isMissingNode() || topicsNode.isNull()) {
            return out;
        }
        if (!topicsNode.isArray()) {
            throw new AwsException("BadRequestException", "Topics must be an array.", 400);
        }
        for (JsonNode t : topicsNode) {
            out.add(new Topic(
                    t.path("TopicName").asText(null),
                    t.path("DisplayName").asText(null),
                    t.path("DefaultSubscriptionStatus").asText(null),
                    t.path("Description").asText(null)));
        }
        return out;
    }

    private ObjectNode contactListJson(ContactList cl) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ContactListName", cl.getContactListName());
        if (cl.getDescription() != null) {
            result.put("Description", cl.getDescription());
        }
        ArrayNode topics = result.putArray("Topics");
        for (Topic t : cl.getTopics()) {
            ObjectNode to = topics.addObject();
            to.put("TopicName", t.getTopicName());
            to.put("DisplayName", t.getDisplayName());
            to.put("DefaultSubscriptionStatus", t.getDefaultSubscriptionStatus());
            if (t.getDescription() != null) {
                to.put("Description", t.getDescription());
            }
        }
        putTimestamp(result, "CreatedTimestamp", cl.getCreatedTimestamp());
        putTimestamp(result, "LastUpdatedTimestamp", cl.getLastUpdatedTimestamp());
        ArrayNode tags = result.putArray("Tags");
        for (Tag tag : cl.getTags()) {
            ObjectNode tn = tags.addObject();
            tn.put("Key", tag.key());
            tn.put("Value", tag.value());
        }
        return result;
    }

    @POST
    @Path("/contact-lists/{contactListName}/contacts")
    public Response createContact(@Context HttpHeaders headers,
                                  @PathParam("contactListName") String contactListName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String emailAddress = request.path("EmailAddress").asText(null);
            List<TopicPreference> prefs = parseTopicPreferences(request.path("TopicPreferences"));
            Boolean unsubscribeAll = parseUnsubscribeAll(request);
            String attributesData = parseAttributesData(request);
            contactService.createContact(contactListName, emailAddress, prefs, unsubscribeAll,
                    attributesData, region);
            LOG.infov("SES V2 CreateContact: {0} in {1}", emailAddress, contactListName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/contact-lists/{contactListName}/contacts/list")
    public Response listContacts(@Context HttpHeaders headers,
                                 @PathParam("contactListName") String contactListName, String body) {
        // AWS uses POST .../contacts/list with Filter/PageSize/NextToken in the body; Floci returns
        // all contacts (filtering/pagination not yet implemented) but still rejects a malformed or
        // non-object body like the other v2 endpoints.
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body != null && !body.isBlank()) {
                requireJsonObject(objectMapper.readTree(body));
            }
            SesContactService.ContactsWithList listed =
                    contactService.listContacts(contactListName, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("Contacts");
            for (Contact c : listed.contacts()) {
                arr.add(contactJson(c, listed.list(), false));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/contact-lists/{contactListName}/contacts/{emailAddress}")
    public Response getContact(@Context HttpHeaders headers,
                               @PathParam("contactListName") String contactListName,
                               @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        SesContactService.ContactWithList result =
                contactService.getContact(contactListName, emailAddress, region);
        return Response.ok(contactJson(result.contact(), result.list(), true)).build();
    }

    @PUT
    @Path("/contact-lists/{contactListName}/contacts/{emailAddress}")
    public Response updateContact(@Context HttpHeaders headers,
                                  @PathParam("contactListName") String contactListName,
                                  @PathParam("emailAddress") String emailAddress, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode prefsNode = request.path("TopicPreferences");
            boolean prefsPresent = !prefsNode.isMissingNode() && !prefsNode.isNull();
            List<TopicPreference> prefs = prefsPresent ? parseTopicPreferences(prefsNode) : null;
            Boolean unsubscribeAll = parseUnsubscribeAll(request);
            String attributesData = parseAttributesData(request);
            contactService.updateContact(contactListName, emailAddress, prefs, prefsPresent,
                    unsubscribeAll, attributesData, region);
            LOG.infov("SES V2 UpdateContact: {0} in {1}", emailAddress, contactListName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/contact-lists/{contactListName}/contacts/{emailAddress}")
    public Response deleteContact(@Context HttpHeaders headers,
                                  @PathParam("contactListName") String contactListName,
                                  @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        contactService.deleteContact(contactListName, emailAddress, region);
        LOG.infov("SES V2 DeleteContact: {0} in {1}", emailAddress, contactListName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private List<TopicPreference> parseTopicPreferences(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new AwsException("BadRequestException", "TopicPreferences must be an array.", 400);
        }
        List<TopicPreference> out = new ArrayList<>();
        for (JsonNode p : node) {
            out.add(new TopicPreference(
                    p.path("TopicName").asText(null),
                    p.path("SubscriptionStatus").asText(null)));
        }
        return out;
    }

    // UnsubscribeAll is a Boolean; AWS coerces it the same way as any other SES v2 boolean
    // (see parseSendingEnabled): a JSON string coerces to true, a number/null/array/object is a
    // SerializationException. Absent leaves it unset.
    private static Boolean parseUnsubscribeAll(JsonNode request) {
        if (!request.has("UnsubscribeAll")) {
            return null;
        }
        return coerceBoolean(request.path("UnsubscribeAll"));
    }

    // AttributesData is a String; a non-string (number/boolean/array/object) is a
    // SerializationException. Absent or explicit null leaves it unset.
    private static String parseAttributesData(JsonNode request) {
        if (!request.has("AttributesData")) {
            return null;
        }
        JsonNode node = request.path("AttributesData");
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            throw new AwsException("SerializationException",
                    "NUMBER_VALUE can not be converted to a String", 400);
        }
        if (node.isBoolean()) {
            throw new AwsException("SerializationException",
                    (node.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE")
                            + " can not be converted to a String", 400);
        }
        throw unexpectedStartError(node);
    }

    private ObjectNode contactJson(Contact c, ContactList list, boolean full) {
        ObjectNode result = objectMapper.createObjectNode();
        if (full) {
            result.put("ContactListName", list.getContactListName());
        }
        result.put("EmailAddress", c.getEmailAddress());
        ArrayNode prefs = result.putArray("TopicPreferences");
        for (TopicPreference p : c.getTopicPreferences()) {
            ObjectNode po = prefs.addObject();
            po.put("TopicName", p.getTopicName());
            po.put("SubscriptionStatus", p.getSubscriptionStatus());
        }
        ArrayNode defaults = result.putArray("TopicDefaultPreferences");
        for (TopicPreference p : contactService.deriveTopicDefaultPreferences(c, list)) {
            ObjectNode po = defaults.addObject();
            po.put("TopicName", p.getTopicName());
            po.put("SubscriptionStatus", p.getSubscriptionStatus());
        }
        result.put("UnsubscribeAll", c.isUnsubscribeAll());
        if (full && c.getAttributesData() != null) {
            result.put("AttributesData", c.getAttributesData());
        }
        if (full) {
            putTimestamp(result, "CreatedTimestamp", c.getCreatedTimestamp());
        }
        putTimestamp(result, "LastUpdatedTimestamp", c.getLastUpdatedTimestamp());
        return result;
    }
}
