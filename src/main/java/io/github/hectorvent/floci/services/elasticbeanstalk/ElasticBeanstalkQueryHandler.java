package io.github.hectorvent.floci.services.elasticbeanstalk;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkApplication;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkApplicationVersion;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkEnvironment;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.ConfigurationOptionSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ElasticBeanstalkQueryHandler {

    private static final Logger LOG = Logger.getLogger(ElasticBeanstalkQueryHandler.class);
    private static final String NS = AwsNamespaces.ELASTIC_BEANSTALK;
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ElasticBeanstalkService service;

    @Inject
    ElasticBeanstalkQueryHandler(ElasticBeanstalkService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> p, String region) {
        LOG.debugv("Elastic Beanstalk action: {0}", action);
        try {
            return switch (action) {
                case "CreateApplication" -> createApplication(p, region);
                case "DescribeApplications" -> describeApplications(p, region);
                case "UpdateApplication" -> updateApplication(p, region);
                case "DeleteApplication" -> deleteApplication(p, region);
                case "CreateApplicationVersion" -> createApplicationVersion(p, region);
                case "DescribeApplicationVersions" -> describeApplicationVersions(p, region);
                case "DeleteApplicationVersion" -> deleteApplicationVersion(p, region);
                case "CreateEnvironment" -> createEnvironment(p, region);
                case "DescribeEnvironments" -> describeEnvironments(p, region);
                case "UpdateEnvironment" -> updateEnvironment(p, region);
                case "TerminateEnvironment" -> terminateEnvironment(p, region);
                case "DescribeConfigurationSettings" -> describeConfigurationSettings(p, region);
                case "CheckDNSAvailability" -> checkDnsAvailability(p, region);
                case "ListAvailableSolutionStacks" -> listAvailableSolutionStacks();
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported.", NS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), NS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in Elastic Beanstalk {0}", action);
            return AwsQueryResponse.error("InternalFailure", e.getMessage(), NS, 500);
        }
    }

    private Response createApplication(MultivaluedMap<String, String> p, String region) {
        BeanstalkApplication app = service.createApplication(region, p.getFirst("ApplicationName"),
                p.getFirst("Description"), parseTags(p));
        return ok(AwsQueryResponse.envelope("CreateApplication", NS,
                new XmlBuilder().start("Application").raw(applicationInnerXml(app)).end("Application").build()));
    }

    private Response describeApplications(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder().start("Applications");
        for (BeanstalkApplication app : service.describeApplications(region, memberList(p, "ApplicationNames"))) {
            xml.start("member").raw(applicationInnerXml(app)).end("member");
        }
        xml.end("Applications");
        return ok(AwsQueryResponse.envelope("DescribeApplications", NS, xml.build()));
    }

    private Response updateApplication(MultivaluedMap<String, String> p, String region) {
        BeanstalkApplication app = service.updateApplication(region, p.getFirst("ApplicationName"),
                p.getFirst("Description"));
        return ok(AwsQueryResponse.envelope("UpdateApplication", NS,
                new XmlBuilder().start("Application").raw(applicationInnerXml(app)).end("Application").build()));
    }

    private Response deleteApplication(MultivaluedMap<String, String> p, String region) {
        service.deleteApplication(region, p.getFirst("ApplicationName"),
                "true".equalsIgnoreCase(p.getFirst("TerminateEnvByForce")));
        return ok(AwsQueryResponse.envelopeEmptyResult("DeleteApplication", NS));
    }

    private Response createApplicationVersion(MultivaluedMap<String, String> p, String region) {
        BeanstalkApplicationVersion version = service.createApplicationVersion(region,
                p.getFirst("ApplicationName"),
                p.getFirst("VersionLabel"),
                p.getFirst("Description"),
                "true".equalsIgnoreCase(p.getFirst("AutoCreateApplication")),
                p.getFirst("SourceBundle.S3Bucket"),
                p.getFirst("SourceBundle.S3Key"),
                parseTags(p));
        return ok(AwsQueryResponse.envelope("CreateApplicationVersion", NS,
                new XmlBuilder().start("ApplicationVersion")
                        .raw(applicationVersionInnerXml(version))
                        .end("ApplicationVersion").build()));
    }

    private Response describeApplicationVersions(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder().start("ApplicationVersions");
        for (BeanstalkApplicationVersion version : service.describeApplicationVersions(region,
                p.getFirst("ApplicationName"), memberList(p, "VersionLabels"))) {
            xml.start("member").raw(applicationVersionInnerXml(version)).end("member");
        }
        xml.end("ApplicationVersions");
        return ok(AwsQueryResponse.envelope("DescribeApplicationVersions", NS, xml.build()));
    }

    private Response deleteApplicationVersion(MultivaluedMap<String, String> p, String region) {
        service.deleteApplicationVersion(region, p.getFirst("ApplicationName"), p.getFirst("VersionLabel"));
        return ok(AwsQueryResponse.envelopeEmptyResult("DeleteApplicationVersion", NS));
    }

    private Response createEnvironment(MultivaluedMap<String, String> p, String region) {
        BeanstalkEnvironment env = service.createEnvironment(region,
                p.getFirst("ApplicationName"),
                p.getFirst("EnvironmentName"),
                p.getFirst("Description"),
                p.getFirst("CNAMEPrefix"),
                p.getFirst("SolutionStackName"),
                p.getFirst("PlatformArn"),
                p.getFirst("TemplateName"),
                p.getFirst("VersionLabel"),
                parseOptionSettings(p),
                parseTags(p));
        return ok(AwsQueryResponse.envelope("CreateEnvironment", NS, environmentInnerXml(env)));
    }

    private Response describeEnvironments(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder().start("Environments");
        for (BeanstalkEnvironment env : service.describeEnvironments(region,
                p.getFirst("ApplicationName"),
                memberList(p, "EnvironmentNames"),
                memberList(p, "EnvironmentIds"),
                p.getFirst("VersionLabel"),
                "true".equalsIgnoreCase(p.getFirst("IncludeDeleted")))) {
            xml.start("member").raw(environmentInnerXml(env)).end("member");
        }
        xml.end("Environments");
        return ok(AwsQueryResponse.envelope("DescribeEnvironments", NS, xml.build()));
    }

    private Response updateEnvironment(MultivaluedMap<String, String> p, String region) {
        BeanstalkEnvironment env = service.updateEnvironment(region,
                p.getFirst("EnvironmentName"),
                p.getFirst("EnvironmentId"),
                p.getFirst("Description"),
                p.getFirst("VersionLabel"),
                p.getFirst("SolutionStackName"),
                p.getFirst("PlatformArn"),
                parseOptionSettings(p));
        return ok(AwsQueryResponse.envelope("UpdateEnvironment", NS, environmentInnerXml(env)));
    }

    private Response terminateEnvironment(MultivaluedMap<String, String> p, String region) {
        BeanstalkEnvironment env = service.terminateEnvironment(region,
                p.getFirst("EnvironmentName"), p.getFirst("EnvironmentId"));
        return ok(AwsQueryResponse.envelope("TerminateEnvironment", NS, environmentInnerXml(env)));
    }

    private Response describeConfigurationSettings(MultivaluedMap<String, String> p, String region) {
        List<ConfigurationOptionSetting> settings = service.describeConfigurationSettings(region,
                p.getFirst("ApplicationName"), p.getFirst("EnvironmentName"), p.getFirst("TemplateName"));
        XmlBuilder xml = new XmlBuilder()
                .start("ConfigurationSettings")
                  .start("member")
                    .elem("ApplicationName", p.getFirst("ApplicationName"))
                    .elem("EnvironmentName", p.getFirst("EnvironmentName"))
                    .elem("TemplateName", p.getFirst("TemplateName"))
                    .elem("DeploymentStatus", "deployed")
                    .start("OptionSettings");
        appendOptionSettings(xml, settings);
        xml.end("OptionSettings").end("member").end("ConfigurationSettings");
        return ok(AwsQueryResponse.envelope("DescribeConfigurationSettings", NS, xml.build()));
    }

    private Response checkDnsAvailability(MultivaluedMap<String, String> p, String region) {
        String cnamePrefix = p.getFirst("CNAMEPrefix");
        boolean available = service.isCnameAvailable(cnamePrefix, region);
        String result = new XmlBuilder()
                .elem("Available", available)
                .elem("FullyQualifiedCNAME", cnamePrefix + ".elasticbeanstalk.local")
                .build();
        return ok(AwsQueryResponse.envelope("CheckDNSAvailability", NS, result));
    }

    private Response listAvailableSolutionStacks() {
        XmlBuilder xml = new XmlBuilder()
                .start("SolutionStacks");
        for (String stack : service.listAvailableSolutionStacks()) {
            xml.elem("member", stack);
        }
        xml.end("SolutionStacks").start("SolutionStackDetails").end("SolutionStackDetails");
        return ok(AwsQueryResponse.envelope("ListAvailableSolutionStacks", NS, xml.build()));
    }

    private static String applicationInnerXml(BeanstalkApplication app) {
        XmlBuilder xml = new XmlBuilder()
                .elem("ApplicationName", app.getApplicationName())
                .elem("ApplicationArn", app.getApplicationArn())
                .elem("Description", app.getDescription())
                .elem("DateCreated", ISO_FMT.format(app.getDateCreated()))
                .elem("DateUpdated", ISO_FMT.format(app.getDateUpdated()))
                .start("Versions");
        for (String version : app.getVersions()) {
            xml.elem("member", version);
        }
        xml.end("Versions").start("ConfigurationTemplates");
        for (String template : app.getConfigurationTemplates()) {
            xml.elem("member", template);
        }
        return xml.end("ConfigurationTemplates").build();
    }

    private static String applicationVersionInnerXml(BeanstalkApplicationVersion version) {
        XmlBuilder xml = new XmlBuilder()
                .elem("ApplicationName", version.getApplicationName())
                .elem("VersionLabel", version.getVersionLabel())
                .elem("Description", version.getDescription())
                .elem("DateCreated", ISO_FMT.format(version.getDateCreated()))
                .elem("DateUpdated", ISO_FMT.format(version.getDateUpdated()))
                .elem("Status", version.getStatus());
        if (version.getSourceBundleBucket() != null || version.getSourceBundleKey() != null) {
            xml.start("SourceBundle")
                    .elem("S3Bucket", version.getSourceBundleBucket())
                    .elem("S3Key", version.getSourceBundleKey())
               .end("SourceBundle");
        }
        return xml.build();
    }

    private static String environmentInnerXml(BeanstalkEnvironment env) {
        return new XmlBuilder()
                .elem("EnvironmentName", env.getEnvironmentName())
                .elem("EnvironmentId", env.getEnvironmentId())
                .elem("EnvironmentArn", env.getEnvironmentArn())
                .elem("ApplicationName", env.getApplicationName())
                .elem("VersionLabel", env.getVersionLabel())
                .elem("SolutionStackName", env.getSolutionStackName())
                .elem("PlatformArn", env.getPlatformArn())
                .elem("TemplateName", env.getTemplateName())
                .elem("Description", env.getDescription())
                .elem("EndpointURL", env.getEndpointUrl())
                .elem("CNAME", env.getCname())
                .elem("Status", env.getStatus())
                .elem("Health", env.getHealth())
                .elem("HealthStatus", env.getHealthStatus())
                .elem("AbortableOperationInProgress", false)
                .elem("DateCreated", ISO_FMT.format(env.getDateCreated()))
                .elem("DateUpdated", ISO_FMT.format(env.getDateUpdated()))
                .start("Tier")
                  .elem("Name", "WebServer")
                  .elem("Type", "Standard")
                  .elem("Version", "1.0")
                .end("Tier")
                .build();
    }

    private static void appendOptionSettings(XmlBuilder xml, List<ConfigurationOptionSetting> settings) {
        for (ConfigurationOptionSetting setting : settings) {
            xml.start("member")
                    .elem("Namespace", setting.getNamespace())
                    .elem("OptionName", setting.getOptionName())
                    .elem("ResourceName", setting.getResourceName())
                    .elem("Value", setting.getValue())
               .end("member");
        }
    }

    private static List<String> memberList(MultivaluedMap<String, String> p, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = p.getFirst(prefix + ".member." + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> p) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = p.getFirst("Tags.member." + i + ".Key");
            String value = p.getFirst("Tags.member." + i + ".Value");
            if (key == null && value == null) {
                break;
            }
            if (key != null) {
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private static List<ConfigurationOptionSetting> parseOptionSettings(MultivaluedMap<String, String> p) {
        List<ConfigurationOptionSetting> settings = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "OptionSettings.member." + i + ".";
            String namespace = p.getFirst(prefix + "Namespace");
            String optionName = p.getFirst(prefix + "OptionName");
            String resourceName = p.getFirst(prefix + "ResourceName");
            String value = p.getFirst(prefix + "Value");
            if (namespace == null && optionName == null && resourceName == null && value == null) {
                break;
            }
            settings.add(new ConfigurationOptionSetting(namespace, optionName, resourceName, value));
        }
        return settings;
    }

    private static Response ok(String xml) {
        return Response.ok(xml).build();
    }
}
