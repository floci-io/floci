package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.model.Delivery;
import io.floci.aws.services.cloudwatch.logs.model.DeliveryDestination;
import io.floci.aws.services.cloudwatch.logs.model.DeliverySource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * CloudWatch Logs vended-log delivery: delivery sources, delivery destinations and the
 * deliveries that pair them. A configuration registry: Floci stores and returns them, and does
 * not send the source's logs anywhere.
 */
@ApplicationScoped
public class CloudWatchLogsDeliveryService {

    /** What a delivery, source or destination ARN's resource starts with, in AWS's form. */
    static final String SOURCE_RESOURCE = "delivery-source:";
    static final String DESTINATION_RESOURCE = "delivery-destination:";
    static final String DELIVERY_RESOURCE = "delivery:";

    private static final String ID_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 16;
    private static final int MAX_TAGS = 50;
    private static final int MAX_PAGE = 50;

    /**
     * What AWS fills in for a delivery from an EventBridge event bus when the request names no
     * record fields: the defaults of the bus's configuration templates (one per log type), and the
     * S3 suffix path GetDelivery reports even for a log group destination.
     */
    private static final List<String> EVENT_BUS_RECORD_FIELDS = List.of("resource_arn", "message_timestamp_ms",
            "event_bus_name", "request_id", "event_id", "invocation_id", "message_type", "log_level", "details",
            "error");
    private static final Set<String> EVENT_BUS_LOG_TYPES = Set.of("ERROR_LOGS", "INFO_LOGS", "TRACE_LOGS");
    private static final String DEFAULT_SUFFIX_PATH = "{region}/{yyyy}/{MM}/{dd}/{HH}/";

    private final StorageBackend<String, DeliverySource> sources;
    private final StorageBackend<String, DeliveryDestination> destinations;
    private final StorageBackend<String, Delivery> deliveries;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchLogsDeliveryService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("cloudwatchlogs", "cwlogs-delivery-sources.json",
                        new TypeReference<Map<String, DeliverySource>>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-delivery-destinations.json",
                        new TypeReference<Map<String, DeliveryDestination>>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-deliveries.json",
                        new TypeReference<Map<String, Delivery>>() {}),
                regionResolver);
    }

    CloudWatchLogsDeliveryService(StorageBackend<String, DeliverySource> sources,
                                  StorageBackend<String, DeliveryDestination> destinations,
                                  StorageBackend<String, Delivery> deliveries, RegionResolver regionResolver) {
        this.sources = sources;
        this.destinations = destinations;
        this.deliveries = deliveries;
        this.regionResolver = regionResolver;
    }

    // ── Delivery sources ──

    /** Creates a delivery source or replaces every parameter of an existing one. */
    public synchronized DeliverySource putDeliverySource(String name, String resourceArn, String logType,
                                                         Map<String, String> configuration,
                                                         Map<String, String> tags, String region) {
        if (!AwsArnUtils.isArn(resourceArn)) {
            throw validation("resourceArn must be the ARN of the resource that sends the logs.");
        }
        Optional<DeliverySource> existing = sources.get(key(region, name));
        DeliverySource source = new DeliverySource();
        source.setName(name);
        source.setArn(arn(region, SOURCE_RESOURCE + name));
        source.setResourceArns(new ArrayList<>(List.of(resourceArn)));
        source.setService(AwsArnUtils.parse(resourceArn).service());
        source.setLogType(logType);
        source.setDeliverySourceConfiguration(configuration == null ? null : new LinkedHashMap<>(configuration));
        source.setTags(new LinkedHashMap<>(tags == null ? existing.map(DeliverySource::getTags).orElse(Map.of()) : tags));
        sources.put(key(region, name), source);
        return source;
    }

    public DeliverySource getDeliverySource(String name, String region) {
        return sources.get(key(region, name)).orElseThrow(() -> notFound("Delivery Source"));
    }

    /** Refused while a delivery uses the source, as AWS does. */
    public synchronized void deleteDeliverySource(String name, String region) {
        DeliverySource source = getDeliverySource(name, region);
        if (inUse(source, region)) {
            throw conflict("Delivery source " + name + " is used by a delivery; delete the delivery first.");
        }
        sources.delete(key(region, name));
    }

    /** Every source, or those sending from one of {@code resourceArns}. */
    public PaginatedResult<DeliverySource> describeDeliverySources(Integer limit, String nextToken,
                                                                   List<String> resourceArns, String region) {
        List<DeliverySource> all = sources.scan(k -> k.startsWith(prefix(region))).stream()
                .filter(source -> resourceArns == null
                        || source.getResourceArns().stream().anyMatch(resourceArns::contains))
                .toList();
        return page(all, DeliverySource::getName, limit, nextToken, "delivery-sources");
    }

    // ── Delivery destinations ──

    /**
     * Creates a delivery destination or replaces every parameter of an existing one. The type
     * follows from the destination resource's ARN; only X-Ray, which has no resource, names it.
     */
    public synchronized DeliveryDestination putDeliveryDestination(String name, String outputFormat,
                                                                   String destinationResourceArn, String type,
                                                                   Map<String, String> tags, String region) {
        String resolvedType = destinationType(destinationResourceArn, type);
        Optional<DeliveryDestination> existing = destinations.get(key(region, name));
        DeliveryDestination destination = new DeliveryDestination();
        destination.setName(name);
        destination.setArn(arn(region, DESTINATION_RESOURCE + name));
        destination.setDeliveryDestinationType(resolvedType);
        destination.setOutputFormat(outputFormat);
        destination.setDestinationResourceArn(destinationResourceArn);
        destination.setTags(new LinkedHashMap<>(tags == null
                ? existing.map(DeliveryDestination::getTags).orElse(Map.of()) : tags));
        destinations.put(key(region, name), destination);
        return destination;
    }

    public DeliveryDestination getDeliveryDestination(String name, String region) {
        return destinations.get(key(region, name)).orElseThrow(() -> notFound("Delivery Destination"));
    }

    /** Refused while a delivery uses the destination, as AWS does. */
    public synchronized void deleteDeliveryDestination(String name, String region) {
        DeliveryDestination destination = getDeliveryDestination(name, region);
        if (inUse(destination, region)) {
            throw conflict("Delivery destination " + name + " is used by a delivery; delete the delivery first.");
        }
        destinations.delete(key(region, name));
    }

    /** Every destination, or those {@code names} names. */
    public PaginatedResult<DeliveryDestination> describeDeliveryDestinations(Integer limit, String nextToken,
                                                                             List<String> names, String region) {
        List<DeliveryDestination> all = destinations.scan(k -> k.startsWith(prefix(region))).stream()
                .filter(destination -> names == null || names.contains(destination.getName()))
                .toList();
        return page(all, DeliveryDestination::getName, limit, nextToken, "delivery-destinations");
    }

    // ── Deliveries ──

    /** Pairs a delivery source with a delivery destination; one delivery per pair. */
    public synchronized Delivery createDelivery(String sourceName, String destinationArn, List<String> recordFields,
                                                String fieldDelimiter, Boolean enableHiveCompatiblePath,
                                                String suffixPath, Map<String, String> tags, String region) {
        DeliverySource source = getDeliverySource(sourceName, region);
        DeliveryDestination destination = destinationByArn(destinationArn, region);
        boolean taken = deliveries.scan(k -> k.startsWith(prefix(region))).stream()
                .anyMatch(d -> sourceName.equals(d.getDeliverySourceName())
                        && destination.getArn().equals(d.getDeliveryDestinationArn()));
        if (taken) {
            throw conflict("A delivery from " + sourceName + " to " + destination.getName() + " already exists.");
        }
        Delivery delivery = new Delivery();
        delivery.setId(newId(region));
        delivery.setArn(arn(region, DELIVERY_RESOURCE + delivery.getId()));
        delivery.setDeliverySourceName(sourceName);
        delivery.setDeliveryDestinationArn(destination.getArn());
        delivery.setDeliveryDestinationType(destination.getDeliveryDestinationType());
        boolean eventBus = isEventBus(source);
        delivery.setRecordFields(new ArrayList<>(recordFields != null ? recordFields
                : eventBus ? EVENT_BUS_RECORD_FIELDS : List.of()));
        delivery.setFieldDelimiter(fieldDelimiter);
        delivery.setEnableHiveCompatiblePath(enableHiveCompatiblePath != null ? enableHiveCompatiblePath
                : eventBus ? Boolean.FALSE : null);
        delivery.setSuffixPath(suffixPath != null ? suffixPath : eventBus ? DEFAULT_SUFFIX_PATH : null);
        delivery.setTags(new LinkedHashMap<>(tags == null ? Map.of() : tags));
        deliveries.put(key(region, delivery.getId()), delivery);
        return delivery;
    }

    public Delivery getDelivery(String id, String region) {
        return deliveries.get(key(region, id)).orElseThrow(() -> notFound("Delivery"));
    }

    /** Replaces the record fields, delimiter and S3 options a request gives; the rest stay. */
    public synchronized void updateDeliveryConfiguration(String id, List<String> recordFields, String fieldDelimiter,
                                                         Boolean enableHiveCompatiblePath, String suffixPath,
                                                         String region) {
        Delivery delivery = getDelivery(id, region);
        if (recordFields != null) {
            delivery.setRecordFields(new ArrayList<>(recordFields));
        }
        if (fieldDelimiter != null) {
            delivery.setFieldDelimiter(fieldDelimiter);
        }
        if (enableHiveCompatiblePath != null) {
            delivery.setEnableHiveCompatiblePath(enableHiveCompatiblePath);
        }
        if (suffixPath != null) {
            delivery.setSuffixPath(suffixPath);
        }
        deliveries.put(key(region, id), delivery);
    }

    public synchronized void deleteDelivery(String id, String region) {
        getDelivery(id, region);
        deliveries.delete(key(region, id));
    }

    /** The ARNs of the delivery destinations the deliveries from the delivery source of that name send to. */
    public List<String> destinationArnsFrom(String sourceName, String region) {
        return deliveries.scan(k -> k.startsWith(prefix(region))).stream()
                .filter(delivery -> sourceName.equals(delivery.getDeliverySourceName()))
                .map(Delivery::getDeliveryDestinationArn).distinct().toList();
    }

    /** Every delivery, or those from one of {@code deliverySourceNames}. */
    public PaginatedResult<Delivery> describeDeliveries(Integer limit, String nextToken,
                                                        List<String> deliverySourceNames, String region) {
        List<Delivery> all = deliveries.scan(k -> k.startsWith(prefix(region))).stream()
                .filter(delivery -> deliverySourceNames == null
                        || deliverySourceNames.contains(delivery.getDeliverySourceName()))
                .toList();
        return page(all, Delivery::getId, limit, nextToken, "deliveries");
    }

    // ── Tags ──

    /**
     * True when the ARN names a delivery source, destination or delivery, in any account or Region:
     * how IAM tells these ARNs on the shared tag operations apart from a log group's.
     */
    public static boolean isDeliveryResourceArn(String arn) {
        if (arn == null || !AwsArnUtils.isArn(arn)) {
            return false;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        return "logs".equals(parsed.service()) && (resource.startsWith(SOURCE_RESOURCE)
                || resource.startsWith(DESTINATION_RESOURCE) || resource.startsWith(DELIVERY_RESOURCE));
    }

    /** True when the ARN names a delivery source, destination or delivery of this account and Region. */
    public boolean isDeliveryArn(String arn, String region) {
        return resourceOf(arn, region) != null;
    }

    public Map<String, String> listTags(String arn, String region) {
        return switch (kindOf(arn, region)) {
            case SOURCE -> new LinkedHashMap<>(getDeliverySource(nameOf(arn, region), region).getTags());
            case DESTINATION -> new LinkedHashMap<>(getDeliveryDestination(nameOf(arn, region), region).getTags());
            case DELIVERY -> new LinkedHashMap<>(getDelivery(nameOf(arn, region), region).getTags());
        };
    }

    public synchronized void tag(String arn, Map<String, String> tags, String region) {
        retag(arn, region, current -> {
            Map<String, String> merged = new LinkedHashMap<>(current);
            merged.putAll(tags);
            if (merged.size() > MAX_TAGS) {
                throw validation("A resource can have at most " + MAX_TAGS + " tags.");
            }
            return merged;
        });
    }

    public synchronized void untag(String arn, List<String> keys, String region) {
        retag(arn, region, current -> {
            Map<String, String> remaining = new LinkedHashMap<>(current);
            keys.forEach(remaining::remove);
            return remaining;
        });
    }

    private void retag(String arn, String region, UnaryOperator<Map<String, String>> change) {
        String name = nameOf(arn, region);
        switch (kindOf(arn, region)) {
            case SOURCE -> {
                DeliverySource source = getDeliverySource(name, region);
                source.setTags(change.apply(source.getTags()));
                sources.put(key(region, name), source);
            }
            case DESTINATION -> {
                DeliveryDestination destination = getDeliveryDestination(name, region);
                destination.setTags(change.apply(destination.getTags()));
                destinations.put(key(region, name), destination);
            }
            case DELIVERY -> {
                Delivery delivery = getDelivery(name, region);
                delivery.setTags(change.apply(delivery.getTags()));
                deliveries.put(key(region, name), delivery);
            }
        }
    }

    // ── Helpers ──

    private enum Kind { SOURCE, DESTINATION, DELIVERY }

    private Kind kindOf(String arn, String region) {
        String resource = resourceOf(arn, region);
        if (resource == null) {
            throw validation("Invalid resourceArn");
        }
        if (resource.startsWith(SOURCE_RESOURCE)) {
            return Kind.SOURCE;
        }
        return resource.startsWith(DESTINATION_RESOURCE) ? Kind.DESTINATION : Kind.DELIVERY;
    }

    private String nameOf(String arn, String region) {
        String resource = resourceOf(arn, region);
        return resource.substring(resource.indexOf(':') + 1);
    }

    /** The resource part of a delivery-family ARN of this account and Region, or null. */
    private String resourceOf(String arn, String region) {
        if (arn == null || !AwsArnUtils.isArn(arn)) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        // The partition too: an ARN of another partition is not the caller's resource, even with the same
        // Region, account and name.
        if (!"logs".equals(parsed.service()) || !region.equals(parsed.region())
                || !regionResolver.getAccountId().equals(parsed.accountId())
                || !regionResolver.partitionForRegion(region).equals(parsed.partition())) {
            return null;
        }
        String resource = parsed.resource();
        boolean delivery = resource.startsWith(SOURCE_RESOURCE) || resource.startsWith(DESTINATION_RESOURCE)
                || resource.startsWith(DELIVERY_RESOURCE);
        return delivery ? resource : null;
    }

    private DeliveryDestination destinationByArn(String destinationArn, String region) {
        String resource = resourceOf(destinationArn, region);
        if (resource == null || !resource.startsWith(DESTINATION_RESOURCE)) {
            throw notFound("Delivery Destination");
        }
        return getDeliveryDestination(resource.substring(DESTINATION_RESOURCE.length()), region);
    }

    /** An EventBridge event bus sending one of the log types its templates define. */
    private static boolean isEventBus(DeliverySource source) {
        return "events".equals(source.getService()) && EVENT_BUS_LOG_TYPES.contains(source.getLogType())
                && source.getResourceArns().stream()
                        .anyMatch(arn -> AwsArnUtils.parse(arn).resource().startsWith("event-bus/"));
    }

    private boolean inUse(DeliverySource source, String region) {
        return deliveries.scan(k -> k.startsWith(prefix(region))).stream()
                .anyMatch(d -> source.getName().equals(d.getDeliverySourceName()));
    }

    private boolean inUse(DeliveryDestination destination, String region) {
        return deliveries.scan(k -> k.startsWith(prefix(region))).stream()
                .anyMatch(d -> destination.getArn().equals(d.getDeliveryDestinationArn()));
    }

    /** The destination type an ARN's service names: Logs, S3 or Firehose; X-Ray has no resource. */
    private static String destinationType(String destinationResourceArn, String requestedType) {
        if (destinationResourceArn == null || destinationResourceArn.isBlank()) {
            if ("XRAY".equals(requestedType)) {
                return requestedType;
            }
            throw validation("deliveryDestinationConfiguration is required unless deliveryDestinationType is XRAY.");
        }
        if (!AwsArnUtils.isArn(destinationResourceArn)) {
            throw validation("destinationResourceArn must be an ARN.");
        }
        String type = switch (AwsArnUtils.parse(destinationResourceArn).service()) {
            case "logs" -> "CWL";
            case "s3" -> "S3";
            case "firehose" -> "FH";
            default -> throw validation("destinationResourceArn must be a log group, an S3 bucket or a "
                    + "Firehose delivery stream.");
        };
        if (requestedType != null && !requestedType.equals(type)) {
            throw validation("deliveryDestinationType " + requestedType + " does not match the destination resource.");
        }
        return type;
    }

    /** A page of up to {@code limit} (default 50) items, with AWS's wording for a bad token. */
    private static <T> PaginatedResult<T> page(List<T> all, Function<T, String> cursorOf, Integer limit,
                                               String nextToken, String namespace) {
        return Pagination.paginate(all, cursorOf, limit != null ? limit : MAX_PAGE, nextToken, namespace,
                token -> validation("Invalid Next Token."));
    }

    private String newId(String region) {
        String id;
        do {
            StringBuilder sb = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                sb.append(ID_CHARACTERS.charAt(ThreadLocalRandom.current().nextInt(ID_CHARACTERS.length())));
            }
            id = sb.toString();
        } while (deliveries.get(key(region, id)).isPresent());
        return id;
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn("logs", region, resource);
    }




    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    /** AWS's message: "Requested Delivery Source does not exist in this account." and its siblings. */
    private static AwsException notFound(String what) {
        return new AwsException("ResourceNotFoundException", "Requested " + what + " does not exist in this account.",
                400);
    }

    private static String key(String region, String name) {
        return prefix(region) + name;
    }

    private static String prefix(String region) {
        return region + "::";
    }
}
