package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contact lists and contacts (the {@code contactListStore} + {@code contactStore}), extracted from
 * {@link SesService} as the fifth step of the store-based domain split.
 *
 * <p>New facet: a multi-store domain — one service owns both stores and the locks that serialize
 * them (contact create/update against contact-list deletion), collapsing two SesService constructor
 * arguments into one. It also owns the list-management contact behaviour used during a send
 * ({@link #getOrAutoCreateContact}, {@link #isListManagementOptedOut}, {@link #unsubscribeContact});
 * the facade's send orchestration ({@code collectListManagementOptOuts}) stays in {@link SesService}
 * and calls into this service, keeping the shared {@code extractEmailAddress} send helper there.
 */
@ApplicationScoped
public class SesContactService {

    private static final Logger LOG = Logger.getLogger(SesContactService.class);

    private static final Set<String> SUBSCRIPTION_STATUSES = Set.of("OPT_IN", "OPT_OUT");
    private static final Pattern CONTACT_LIST_NAME_CHARS = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_TOPICS_PER_LIST = 20;
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;
    private static final int MAX_LIST_DESCRIPTION_LENGTH = 500;

    private final StorageBackend<String, ContactList> contactListStore;
    private final StorageBackend<String, Contact> contactStore;
    // Guards the one-list-per-account check-then-create so concurrent creates can't both pass.
    private final Object contactListCreateLock = new Object();
    // Serializes contact create/update against contact-list deletion so a concurrent delete
    // can't purge the list between validation and the write, leaving an orphaned contact.
    private final Object contactMutationLock = new Object();
    private final Clock clock;

    @Inject
    public SesContactService(StorageFactory storageFactory, Clock clock) {
        this.contactListStore = storageFactory.create("ses", "ses-contact-lists.json",
                new TypeReference<Map<String, ContactList>>() {});
        this.contactStore = storageFactory.create("ses", "ses-contacts.json",
                new TypeReference<Map<String, Contact>>() {});
        this.clock = clock;
    }

    SesContactService(StorageBackend<String, ContactList> contactListStore,
                      StorageBackend<String, Contact> contactStore, Clock clock) {
        this.contactListStore = contactListStore;
        this.contactStore = contactStore;
        this.clock = clock;
    }

    // ─────────────────────────── Contact lists ───────────────────────────

    public ContactList createContactList(String name, String description, List<Topic> topics,
                                         List<Tag> tags, String region) {
        validateContactListInput(name, description, topics);
        ContactList list = new ContactList(name);
        list.setDescription(description);
        list.setTopics(topics);
        list.setTags(tags);
        Instant now = Instant.now();
        list.setCreatedTimestamp(now);
        list.setLastUpdatedTimestamp(now);
        // AWS allows at most one contact list per account per region (verified against real AWS).
        // A duplicate name hits this same limit before any "already exists" check, so
        // AlreadyExistsException is never reachable for contact lists. Lock only the check-then-put
        // so concurrent calls can't both observe an empty region; building and logging stay outside.
        synchronized (contactListCreateLock) {
            if (!listContactLists(region).isEmpty()) {
                throw new AwsException("BadRequestException",
                        "A maximum of 1 Lists allowed per account.", 400);
            }
            contactListStore.put(contactListKey(region, name), list);
        }
        LOG.infov("Created SES contact list: {0} in region {1}", name, region);
        return list;
    }

    public ContactList getContactList(String name, String region) {
        return contactListStore.get(contactListKey(region, name))
                .orElseThrow(() -> contactListNotFound(name));
    }

    public List<ContactList> listContactLists(String region) {
        String prefix = "contactList::" + region + "::";
        return contactListStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(ContactList::getContactListName))
                .toList();
    }

    public ContactList updateContactList(String name, String description, boolean descriptionPresent,
                                         List<Topic> topics, String region) {
        validateContactListInput(name, description, topics);
        String key = contactListKey(region, name);
        ContactList existing = contactListStore.get(key).orElseThrow(() -> contactListNotFound(name));
        if (topics != null) {
            existing.setTopics(topics);
        }
        if (descriptionPresent) {
            existing.setDescription(description);
        }
        existing.setLastUpdatedTimestamp(Instant.now());
        contactListStore.put(key, existing);
        LOG.infov("Updated SES contact list: {0} in region {1}", name, region);
        return existing;
    }

    public void deleteContactList(String name, String region) {
        String key = contactListKey(region, name);
        // Existence check, list delete, and contact purge all under the lock: concurrent deletes
        // can't both pass the check (one must 404), and a concurrent create/update can't slip a
        // contact in after the purge. Contacts are stored independently, so purging them here keeps
        // them from leaking into a same-named list recreated later (AWS deletes them with the list).
        String prefix = "contact::" + region + "::" + name + "::";
        synchronized (contactMutationLock) {
            if (contactListStore.get(key).isEmpty()) {
                throw contactListNotFound(name);
            }
            contactListStore.delete(key);
            // Delete by the actual stored keys (not keys rebuilt from each value's EmailAddress),
            // so a persisted entry whose key and EmailAddress diverge is still purged.
            List<String> contactKeys = contactStore.keys().stream()
                    .filter(k -> k.startsWith(prefix))
                    .toList();
            for (String contactKey : contactKeys) {
                contactStore.delete(contactKey);
            }
        }
        LOG.infov("Deleted SES contact list: {0} in region {1}", name, region);
    }

    private static AwsException contactListNotFound(String name) {
        return new AwsException("NotFoundException",
                "List with name: " + name + " doesn't exist.", 404);
    }

    // SES V2 surfaces missing/invalid input as Smithy validation errors. Field paths and the
    // enum value order are taken verbatim from real AWS.
    private static AwsException validationError(String fieldPath, String constraint) {
        return new AwsException("BadRequestException",
                "1 validation error detected: Value at '" + fieldPath
                        + "' failed to satisfy constraint: " + constraint, 400);
    }

    private static void validateContactListName(String name) {
        if (name == null) {
            throw validationError("contactListName", "Member must not be null");
        }
        if (name.isBlank()) {
            throw new AwsException("BadRequestException", "ContactListName can't be blank.", 400);
        }
        if (!CONTACT_LIST_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("BadRequestException",
                    "ContactListName can contain up to 64 characters. Only alphanumeric characters, "
                            + "underscores(_) and hyphens(-) are allowed.", 400);
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > MAX_LIST_DESCRIPTION_LENGTH) {
            throw new AwsException("BadRequestException",
                    "List description can contain up to 500 characters.", 400);
        }
    }

    // Validates Create/Update input in the same two-phase order as real AWS (verified by probe):
    // protocol-layer (Smithy) checks across all fields first, then service-level constraints with
    // ContactListName ahead of topic/description constraints.
    private static void validateContactListInput(String name, String description, List<Topic> topics) {
        // Phase 1 — protocol-layer (Smithy) validation: null members and the subscription-status
        // enum. AWS reports these before any service-level constraint.
        if (name == null) {
            throw validationError("contactListName", "Member must not be null");
        }
        if (topics != null) {
            for (int i = 0; i < topics.size(); i++) {
                Topic t = topics.get(i);
                String member = "topics." + (i + 1) + ".member.";
                if (t.getTopicName() == null) {
                    throw validationError(member + "topicName", "Member must not be null");
                }
                if (t.getDisplayName() == null) {
                    throw validationError(member + "displayName", "Member must not be null");
                }
                if (t.getDefaultSubscriptionStatus() == null) {
                    throw validationError(member + "defaultSubscriptionStatus", "Member must not be null");
                }
                if (!SUBSCRIPTION_STATUSES.contains(t.getDefaultSubscriptionStatus())) {
                    throw validationError(member + "defaultSubscriptionStatus",
                            "Member must satisfy enum value set: [OPT_OUT, OPT_IN]");
                }
            }
        }
        // Phase 2 — service-level constraints: ContactListName first, then topics, then description.
        if (name.isBlank()) {
            throw new AwsException("BadRequestException", "ContactListName can't be blank.", 400);
        }
        if (!CONTACT_LIST_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("BadRequestException",
                    "ContactListName can contain up to 64 characters. Only alphanumeric characters, "
                            + "underscores(_) and hyphens(-) are allowed.", 400);
        }
        if (topics != null) {
            if (topics.size() > MAX_TOPICS_PER_LIST) {
                throw new AwsException("BadRequestException",
                        "Maximum of <" + MAX_TOPICS_PER_LIST + "> topics allowed per ContactList", 400);
            }
            Set<String> seenNames = new HashSet<>();
            for (Topic t : topics) {
                if (t.getTopicName().isBlank()) {
                    throw new AwsException("BadRequestException", "TopicName can't be blank.", 400);
                }
                if (!CONTACT_LIST_NAME_CHARS.matcher(t.getTopicName()).matches()) {
                    throw new AwsException("BadRequestException",
                            "TopicName can contain up to 64 characters. Only alphanumeric characters, "
                                    + "underscores(_) and hyphens(-) are allowed.", 400);
                }
                if (t.getDisplayName().length() > MAX_DISPLAY_NAME_LENGTH) {
                    throw new AwsException("BadRequestException",
                            "Topic DisplayName can contain up to <" + MAX_DISPLAY_NAME_LENGTH
                                    + "> characters.", 400);
                }
                if (!seenNames.add(t.getTopicName())) {
                    throw new AwsException("BadRequestException",
                            "Duplicate topic names are not allowed within a List.", 400);
                }
            }
        }
        validateDescription(description);
    }

    private static String contactListKey(String region, String name) {
        // Validate in the key builder so Get/Update/Delete reject an invalid ContactListName with
        // the AWS validation error (400) rather than a 404, matching configSetKey. Verified
        // against real AWS: read/delete with an invalid name returns the same constraint message.
        validateContactListName(name);
        return "contactList::" + region + "::" + name;
    }

    // ─────────────────────────── Contacts ───────────────────────────

    public Contact createContact(String listName, String emailAddress, List<TopicPreference> topicPreferences,
                                 Boolean unsubscribeAll, String attributesData, String region) {
        validateContactInput(listName, emailAddress, topicPreferences, region);
        Contact contact = new Contact(emailAddress);
        contact.setTopicPreferences(topicPreferences);
        contact.setUnsubscribeAll(unsubscribeAll != null && unsubscribeAll);
        contact.setAttributesData(attributesData);
        Instant now = Instant.now(clock);
        contact.setCreatedTimestamp(now);
        contact.setLastUpdatedTimestamp(now);
        String key = contactKey(region, listName, emailAddress);
        // Re-check the list, duplicate, and put under the lock so a concurrent deleteContactList
        // can't purge the list between validation and the write (which would orphan this contact).
        synchronized (contactMutationLock) {
            getContactList(listName, region);
            if (contactStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExistsException",
                        emailAddress + " already exists in List.", 400);
            }
            contactStore.put(key, contact);
        }
        LOG.infov("Created SES contact {0} in list {1} (region {2})", emailAddress, listName, region);
        return contact;
    }

    // Read operations return the resolved ContactList alongside the contact(s) so the controller
    // can render TopicDefaultPreferences without a second getContactList round-trip (which would
    // open a TOCTOU window where a concurrent delete turns a successful read into "List not found").
    public record ContactWithList(Contact contact, ContactList list) {
    }

    public record ContactsWithList(List<Contact> contacts, ContactList list) {
    }

    public ContactWithList getContact(String listName, String emailAddress, String region) {
        validateEmailAddress(emailAddress);
        ContactList list = getContactList(listName, region);
        Contact contact = contactStore.get(contactKey(region, listName, emailAddress))
                .orElseThrow(() -> contactNotFound(emailAddress));
        return new ContactWithList(contact, list);
    }

    public ContactsWithList listContacts(String listName, String region) {
        ContactList list = getContactList(listName, region);
        String prefix = "contact::" + region + "::" + listName + "::";
        List<Contact> contacts = contactStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(Contact::getEmailAddress,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        return new ContactsWithList(contacts, list);
    }

    public Contact updateContact(String listName, String emailAddress, List<TopicPreference> topicPreferences,
                                 boolean topicPreferencesPresent, Boolean unsubscribeAll, String attributesData,
                                 String region) {
        validateContactInput(listName, emailAddress, topicPreferences, region);
        String key = contactKey(region, listName, emailAddress);
        Contact existing;
        // Re-check the list and read-modify-write under the lock so a concurrent deleteContactList
        // can't purge the contact/list between validation and the write (which would resurrect it).
        synchronized (contactMutationLock) {
            getContactList(listName, region);
            existing = contactStore.get(key).orElseThrow(() -> contactNotFound(emailAddress));
            // Verified against real AWS: TopicPreferences merge by topic name (omitting keeps existing);
            // AttributesData and UnsubscribeAll are replaced (omitting clears / resets them).
            if (topicPreferencesPresent) {
                existing.setTopicPreferences(mergeTopicPreferences(existing.getTopicPreferences(), topicPreferences));
            }
            existing.setUnsubscribeAll(unsubscribeAll != null && unsubscribeAll);
            existing.setAttributesData(attributesData);
            existing.setLastUpdatedTimestamp(Instant.now(clock));
            contactStore.put(key, existing);
        }
        LOG.infov("Updated SES contact {0} in list {1}", emailAddress, listName);
        return existing;
    }

    public void deleteContact(String listName, String emailAddress, String region) {
        validateEmailAddress(emailAddress);
        String key = contactKey(region, listName, emailAddress);
        // List re-check + existence check + delete under the lock (matching create/update): a
        // concurrent deleteContactList then surfaces "list not found" rather than "contact not
        // found", and an updateContact can't put a just-deleted contact back (resurrecting it).
        synchronized (contactMutationLock) {
            getContactList(listName, region);
            if (contactStore.get(key).isEmpty()) {
                throw contactNotFound(emailAddress);
            }
            contactStore.delete(key);
        }
        LOG.infov("Deleted SES contact {0} in list {1}", emailAddress, listName);
    }

    /**
     * Derives {@code TopicDefaultPreferences}: each list topic the contact has not set an explicit
     * preference for, carrying that topic's default subscription status.
     */
    public List<TopicPreference> deriveTopicDefaultPreferences(Contact contact, ContactList list) {
        Set<String> explicit = new HashSet<>();
        for (TopicPreference p : contact.getTopicPreferences()) {
            explicit.add(p.getTopicName());
        }
        List<TopicPreference> defaults = new ArrayList<>();
        for (Topic t : list.getTopics()) {
            if (!explicit.contains(t.getTopicName())) {
                defaults.add(new TopicPreference(t.getTopicName(), t.getDefaultSubscriptionStatus()));
            }
        }
        return defaults;
    }

    private static AwsException contactNotFound(String emailAddress) {
        return new AwsException("NotFoundException", emailAddress + " doesn't exist in List.", 404);
    }

    // Validation order verified against real AWS: protocol-layer (Smithy) topic-preference checks
    // first, then EmailAddress format, then contact-list existence, then topic existence.
    private void validateContactInput(String listName, String emailAddress, List<TopicPreference> prefs,
                                      String region) {
        if (prefs != null) {
            for (int i = 0; i < prefs.size(); i++) {
                TopicPreference p = prefs.get(i);
                String member = "topicPreferences." + (i + 1) + ".member.";
                if (p.getTopicName() == null) {
                    throw validationError(member + "topicName", "Member must not be null");
                }
                if (p.getSubscriptionStatus() == null) {
                    throw validationError(member + "subscriptionStatus", "Member must not be null");
                }
                if (!SUBSCRIPTION_STATUSES.contains(p.getSubscriptionStatus())) {
                    throw validationError(member + "subscriptionStatus",
                            "Member must satisfy enum value set: [OPT_OUT, OPT_IN]");
                }
            }
        }
        validateEmailAddress(emailAddress);
        ContactList list = getContactList(listName, region);
        if (prefs != null && !prefs.isEmpty()) {
            Set<String> topicNames = new HashSet<>();
            for (Topic t : list.getTopics()) {
                topicNames.add(t.getTopicName());
            }
            for (TopicPreference p : prefs) {
                if (!topicNames.contains(p.getTopicName())) {
                    throw new AwsException("BadRequestException",
                            "List: " + listName + " doesn't contain Topic: " + p.getTopicName(), 400);
                }
            }
        }
    }

    private static List<TopicPreference> mergeTopicPreferences(List<TopicPreference> existing,
                                                               List<TopicPreference> provided) {
        Map<String, TopicPreference> byTopic = new LinkedHashMap<>();
        if (existing != null) {
            for (TopicPreference p : existing) {
                byTopic.put(p.getTopicName(), p);
            }
        }
        if (provided != null) {
            for (TopicPreference p : provided) {
                byTopic.put(p.getTopicName(), p);
            }
        }
        return new ArrayList<>(byTopic.values());
    }

    private static void validateEmailAddress(String emailAddress) {
        // Verified against real AWS: a missing/null required member is a Smithy validation error,
        // an empty/blank value is "can't be blank", and only a non-blank malformed value is "invalid".
        if (emailAddress == null) {
            throw validationError("emailAddress", "Member must not be null");
        }
        if (emailAddress.isBlank()) {
            throw new AwsException("BadRequestException", "EmailAddress can't be blank.", 400);
        }
        if (!EMAIL_PATTERN.matcher(emailAddress).matches()) {
            throw new AwsException("BadRequestException",
                    "EmailAddress <" + emailAddress + "> is invalid", 400);
        }
    }

    private static String contactKey(String region, String listName, String emailAddress) {
        return "contact::" + region + "::" + listName + "::" + emailAddress;
    }

    // ──────────────── List-management contact behaviour (used by the send path) ────────────────

    /**
     * Returns the contact for {@code email} in {@code list}, creating it automatically when absent —
     * AWS creates a contact on the list when {@code ListManagementOptions} names a recipient that is
     * not yet a contact. The auto-created contact has no explicit topic preferences (its effective
     * per-topic status derives from the list topic defaults) and is not unsubscribed. Creation runs
     * under {@code contactMutationLock} so a concurrent contact-list deletion can't orphan it.
     */
    public Contact getOrAutoCreateContact(ContactList list, String email, String region) {
        String key = contactKey(region, list.getContactListName(), email);
        Contact existing = contactStore.get(key).orElse(null);
        if (existing != null) {
            return existing;
        }
        synchronized (contactMutationLock) {
            Contact raced = contactStore.get(key).orElse(null);
            if (raced != null) {
                return raced;
            }
            // Re-check the list under the lock so a concurrent deleteContactList (which purges the
            // list and its contacts under the same lock) can't be followed by this creating an
            // orphaned contact for a list that no longer exists.
            getContactList(list.getContactListName(), region);
            Contact contact = new Contact(email);
            Instant now = Instant.now(clock);
            contact.setCreatedTimestamp(now);
            contact.setLastUpdatedTimestamp(now);
            contactStore.put(key, contact);
            LOG.infov("Auto-created SES contact {0} in list {1} on send (region {2})",
                    email, list.getContactListName(), region);
            return contact;
        }
    }

    /**
     * Whether a contact is opted out for a list-managed send. A contact with {@code UnsubscribeAll}
     * is opted out of everything. When no topic is given, only {@code UnsubscribeAll} suppresses
     * (AWS documents suppression of whole-list unsubscribers). When a topic is given, an explicit
     * {@code OPT_OUT} preference for that topic suppresses; absent an explicit preference, the topic's
     * {@code DefaultSubscriptionStatus} is used — the default-status fallback at send time is not
     * documented by AWS but mirrors the effective-status model AWS uses for {@code ListContacts}.
     */
    public boolean isListManagementOptedOut(Contact contact, ContactList list, String topicName) {
        if (contact.isUnsubscribeAll()) {
            return true;
        }
        if (topicName == null) {
            return false;
        }
        String explicit = explicitTopicStatus(contact, topicName);
        if (explicit != null) {
            return "OPT_OUT".equals(explicit);
        }
        return "OPT_OUT".equals(defaultTopicStatus(list, topicName));
    }

    private static String explicitTopicStatus(Contact contact, String topicName) {
        if (contact.getTopicPreferences() == null) {
            return null;
        }
        for (TopicPreference pref : contact.getTopicPreferences()) {
            if (topicName.equals(pref.getTopicName())) {
                return pref.getSubscriptionStatus();
            }
        }
        return null;
    }

    public String defaultTopicStatus(ContactList list, String topicName) {
        if (list.getTopics() == null) {
            return null;
        }
        for (Topic topic : list.getTopics()) {
            if (topicName.equals(topic.getTopicName())) {
                return topic.getDefaultSubscriptionStatus();
            }
        }
        return null;
    }

    /**
     * Applies a list-management unsubscribe (the action behind the {@code /_aws/ses/unsubscribe}
     * link): with a topic, sets that topic's preference to {@code OPT_OUT}; without a topic,
     * unsubscribes the contact from the whole list ({@code UnsubscribeAll}). The contact is created
     * if it does not exist yet, consistent with send-time auto-creation. Runs under
     * {@code contactMutationLock} so a concurrent contact-list deletion can't orphan it.
     */
    public void unsubscribeContact(String listName, String emailAddress, String topicName, String region) {
        validateEmailAddress(emailAddress);
        String effectiveTopic = (topicName == null || topicName.isBlank()) ? null : topicName;
        String key = contactKey(region, listName, emailAddress);
        synchronized (contactMutationLock) {
            ContactList list = getContactList(listName, region);
            if (effectiveTopic != null && defaultTopicStatus(list, effectiveTopic) == null) {
                throw new AwsException("BadRequestException",
                        "Topic " + effectiveTopic + " does not exist in contact list " + listName + ".", 400);
            }
            Contact contact = contactStore.get(key).orElseGet(() -> {
                Contact created = new Contact(emailAddress);
                created.setCreatedTimestamp(Instant.now(clock));
                return created;
            });
            if (effectiveTopic == null) {
                contact.setUnsubscribeAll(true);
            } else {
                setTopicPreference(contact, effectiveTopic, "OPT_OUT");
            }
            contact.setLastUpdatedTimestamp(Instant.now(clock));
            contactStore.put(key, contact);
        }
        LOG.infov("List-management unsubscribe: {0} from list {1} topic {2} (region {3})",
                emailAddress, listName, effectiveTopic == null ? "<all>" : effectiveTopic, region);
    }

    private static void setTopicPreference(Contact contact, String topicName, String status) {
        // Copy into a fresh mutable list and set it back, so this works even if the contact's
        // preferences were stored as an unmodifiable list (setTopicPreferences keeps the list as-is).
        List<TopicPreference> prefs = new ArrayList<>(contact.getTopicPreferences());
        boolean found = false;
        for (TopicPreference pref : prefs) {
            if (topicName.equals(pref.getTopicName())) {
                pref.setSubscriptionStatus(status);
                found = true;
                break;
            }
        }
        if (!found) {
            prefs.add(new TopicPreference(topicName, status));
        }
        contact.setTopicPreferences(prefs);
    }
}
