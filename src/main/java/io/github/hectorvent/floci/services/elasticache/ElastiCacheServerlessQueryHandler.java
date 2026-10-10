package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.CacheUsageLimits;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.DataStorage;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.EcpuPerSecond;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ElastiCacheServerlessQueryHandler {
    private final ElastiCacheServerlessService service;

    @Inject
    public ElastiCacheServerlessQueryHandler(ElastiCacheServerlessService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        try {
            String name = params.getFirst("ServerlessCacheName");
            String body = switch (action) {
                case "CreateServerlessCache" -> cacheXml(service.createServerlessCache(
                        new ElastiCacheServerlessService.CreateServerlessCacheRequest(name, params.getFirst("Engine"),
                                params.getFirst("MajorEngineVersion"), params.getFirst("Description"),
                                members(params, "SubnetIds"), members(params, "SecurityGroupIds"), params.getFirst("UserGroupId"),
                                params.getFirst("KmsKeyId"), usageLimits(params), integer(params, "SnapshotRetentionLimit"),
                                params.getFirst("DailySnapshotTime"), members(params, "SnapshotArnsToRestore"), tags(params))));
                case "ModifyServerlessCache" -> cacheXml(service.modifyServerlessCache(
                        new ElastiCacheServerlessService.ModifyServerlessCacheRequest(name, params.getFirst("Description"),
                                params.getFirst("Engine"), params.getFirst("MajorEngineVersion"),
                                present(params, "SecurityGroupIds") ? members(params, "SecurityGroupIds") : null,
                                params.getFirst("UserGroupId"), bool(params, "RemoveUserGroup"), usageLimits(params),
                                integer(params, "SnapshotRetentionLimit"), params.getFirst("DailySnapshotTime"))));
                case "DeleteServerlessCache" -> cacheXml(service.deleteServerlessCache(name, params.getFirst("FinalSnapshotName")));
                case "DescribeServerlessCaches" -> {
                    XmlBuilder xml = new XmlBuilder().start("ServerlessCaches");
                    for (ServerlessCache cache : service.describeServerlessCaches(name)) {
                        xml.start("member").raw(cacheFields(cache)).end("member");
                    }
                    yield xml.end("ServerlessCaches").build();
                }
                default -> throw new AwsException("UnsupportedOperation", "Unsupported serverless cache action.", 400);
            };
            return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.EC, body)).build();
        } catch (AwsException exception) {
            return AwsQueryResponse.error(exception.getErrorCode(), exception.getMessage(), AwsNamespaces.EC, exception.getHttpStatus());
        }
    }

    public Map<String, String> getTags(String name) {
        return service.getServerlessCache(name).getTags();
    }

    private static String cacheXml(ServerlessCache cache) {
        return new XmlBuilder().start("ServerlessCache").raw(cacheFields(cache)).end("ServerlessCache").build();
    }

    private static String cacheFields(ServerlessCache cache) {
        XmlBuilder xml = new XmlBuilder().elem("ServerlessCacheName", cache.getServerlessCacheName())
                .elem("Description", cache.getDescription()).elem("Status", cache.getStatus()).elem("Engine", cache.getEngine())
                .elem("MajorEngineVersion", cache.getMajorEngineVersion()).elem("FullEngineVersion", cache.getFullEngineVersion())
                .elem("ARN", cache.getArn()).elem("CreateTime", cache.getCreateTime().toString());
        optional(xml, "UserGroupId", cache.getUserGroupId());
        optional(xml, "KmsKeyId", cache.getKmsKeyId());
        optional(xml, "SnapshotRetentionLimit", cache.getSnapshotRetentionLimit());
        optional(xml, "DailySnapshotTime", cache.getDailySnapshotTime());
        endpoint(xml, "Endpoint", cache.getEndpoint());
        endpoint(xml, "ReaderEndpoint", cache.getReaderEndpoint());
        list(xml, "SubnetIds", cache.getSubnetIds());
        list(xml, "SecurityGroupIds", cache.getSecurityGroupIds());
        CacheUsageLimits limits = cache.getCacheUsageLimits();
        if (limits != null) {
            xml.start("CacheUsageLimits");
            if (limits.dataStorage() != null) {
                xml.start("DataStorage");
                optional(xml, "Minimum", limits.dataStorage().minimum());
                optional(xml, "Maximum", limits.dataStorage().maximum());
                optional(xml, "Unit", limits.dataStorage().unit());
                xml.end("DataStorage");
            }
            if (limits.ecpuPerSecond() != null) {
                xml.start("ECPUPerSecond");
                optional(xml, "Minimum", limits.ecpuPerSecond().minimum());
                optional(xml, "Maximum", limits.ecpuPerSecond().maximum());
                xml.end("ECPUPerSecond");
            }
            xml.end("CacheUsageLimits");
        }
        return xml.build();
    }

    private static void optional(XmlBuilder xml, String name, Object value) {
        if (value != null) {
            xml.elem(name, value.toString());
        }
    }

    private static void endpoint(XmlBuilder xml, String name, Endpoint endpoint) {
        if (endpoint != null) {
            xml.start(name).elem("Address", endpoint.address()).elem("Port", String.valueOf(endpoint.port())).end(name);
        }
    }

    private static void list(XmlBuilder xml, String name, List<String> values) {
        xml.start(name);
        for (String value : values) {
            xml.elem(memberName(name), value);
        }
        xml.end(name);
    }

    private static boolean present(MultivaluedMap<String, String> params, String prefix) {
        return params.keySet().stream().anyMatch(key -> key.equals(prefix) || key.startsWith(prefix + "."));
    }

    private static List<String> members(MultivaluedMap<String, String> params, String name) {
        List<String> result = new ArrayList<>();
        for (int index = 1; ; index++) {
            String value = params.getFirst(name + "." + memberName(name) + "." + index);
            if (value == null) {
                value = params.getFirst(name + ".member." + index);
            }
            if (value == null) {
                break;
            }
            result.add(value);
        }
        return result;
    }

    private static String memberName(String name) {
        return switch (name) {
            case "SubnetIds" -> "SubnetId";
            case "SecurityGroupIds" -> "SecurityGroupId";
            case "SnapshotArnsToRestore" -> "SnapshotArn";
            default -> "member";
        };
    }

    private static Map<String, String> tags(MultivaluedMap<String, String> params) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 1; ; index++) {
            String key = params.getFirst("Tags.Tag." + index + ".Key");
            if (key == null) {
                break;
            }
            String value = params.getFirst("Tags.Tag." + index + ".Value");
            result.put(key, value == null ? "" : value);
        }
        return result;
    }

    private static Integer integer(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new AwsException("InvalidParameterValue", name + " must be an integer.", 400);
        }
    }

    private static Boolean bool(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null) {
            return null;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("InvalidParameterValue", name + " must be true or false.", 400);
        }
        return Boolean.valueOf(value);
    }

    private static CacheUsageLimits usageLimits(MultivaluedMap<String, String> params) {
        if (!present(params, "CacheUsageLimits")) {
            return null;
        }
        DataStorage storage = present(params, "CacheUsageLimits.DataStorage")
                ? new DataStorage(integer(params, "CacheUsageLimits.DataStorage.Minimum"),
                        integer(params, "CacheUsageLimits.DataStorage.Maximum"), params.getFirst("CacheUsageLimits.DataStorage.Unit")) : null;
        EcpuPerSecond ecpu = present(params, "CacheUsageLimits.ECPUPerSecond")
                ? new EcpuPerSecond(integer(params, "CacheUsageLimits.ECPUPerSecond.Minimum"),
                        integer(params, "CacheUsageLimits.ECPUPerSecond.Maximum")) : null;
        return new CacheUsageLimits(storage, ecpu);
    }
}
