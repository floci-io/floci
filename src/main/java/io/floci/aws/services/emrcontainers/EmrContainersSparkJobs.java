package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.emrserverless.EmrServerlessJobRunService;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Spark job an EMR on EKS job run runs in the {@code docker} runner mode: its {@code sparkSubmitJobDriver}'s
 * {@code spark-submit}, or its {@code sparkSqlJobDriver}'s {@code spark-sql}, in a container of its release's EMR on
 * EKS base image, with the {@code spark-defaults} of its {@code configurationOverrides} and its execution role's
 * credentials. Its driver runs in that container, its executors with it in Spark's local mode, where EMR on EKS runs
 * each in a pod of its own. Its driver's output goes where its {@code monitoringConfiguration} sends a driver's logs,
 * in S3 and CloudWatch Logs.
 */
final class EmrContainersSparkJobs {

    /**
     * The release's base image: "For Amazon EMR 6.9.0 and higher, you can get the base images from the Amazon ECR
     * Public Gallery", {@code public.ecr.aws/emr-on-eks/spark/emr-7.13.0:latest} for example, with {@code %s} its
     * container image tag (EMR on EKS Development Guide, "Customizing Docker images").
     */
    static final String DEFAULT_IMAGE_TEMPLATE =
            "public.ecr.aws/emr-on-eks/spark/%s"; // partition-literal: ECR Public, where AWS publishes the base images

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SPARK_DEFAULTS = "spark-defaults";
    // "You can use spark.kubernetes.driver.container.image and spark.kubernetes.executor.container.image to specify a
    // different image for driver and executor pods."
    private static final String CONTAINER_IMAGE = "spark.kubernetes.container.image";
    private static final String DRIVER_CONTAINER_IMAGE = "spark.kubernetes.driver.container.image";
    private static final String DRIVER_ENV_PREFIX = "spark.kubernetes.driverEnv.";
    // The base image's spark-defaults.conf runs a driver in Kubernetes' cluster mode, which local mode refuses.
    static final String DEPLOY_MODE = "spark.submit.deployMode";
    static final String CLIENT = "client";
    private static final String SPARK_SQL = "spark-sql";
    // A release label is its image's repository and tag joined by a hyphen: emr-7.2.0-20240610 runs the image tagged
    // emr-7.2.0:20240610, and emr-7.2.0-java11-latest the one tagged emr-7.2.0-java11:latest.
    private static final Pattern RELEASE_LABEL = Pattern.compile("(.+)-(latest|\\d{8})");
    private static final String STDOUT = "stdout";
    private static final String STDERR = "stderr";

    private EmrContainersSparkJobs() {
    }

    /**
     * A release's container image tag, "Container image tag: emr-7.2.0:20240610" for {@code emr-7.2.0-20240610} in
     * the development guide's release notes, its repository's {@code latest} for a label of no other form.
     */
    static String containerImageTag(String releaseLabel) {
        Matcher label = RELEASE_LABEL.matcher(releaseLabel);
        return label.matches() ? label.group(1) + ":" + label.group(2) : releaseLabel + ":latest";
    }

    /** The image a release's job runs use: {@code imageTemplate}, or else the release's base image. */
    static String image(String imageTemplate, String releaseLabel) {
        String template = imageTemplate != null ? imageTemplate : DEFAULT_IMAGE_TEMPLATE;
        return template.replace("%s", containerImageTag(releaseLabel));
    }

    /**
     * The {@code spark-defaults} properties of a run's {@code configurationOverrides}: those of each
     * {@code spark-defaults} classification of its {@code applicationConfiguration}, a later one's winning.
     */
    static Map<String, String> sparkDefaults(JsonNode configurationOverrides) {
        JsonNode configurations = configurationOverrides != null
                ? configurationOverrides.path("applicationConfiguration") : null;
        if (configurations == null || !configurations.isArray()) {
            return new LinkedHashMap<>();
        }
        List<?> list = JSON.convertValue(configurations, List.class);
        return EmrServerlessJobRunService.classificationProperties(list, SPARK_DEFAULTS);
    }

    /**
     * The entry point a job run's job driver runs, its {@code sparkSqlJobDriver}'s or else its
     * {@code sparkSubmitJobDriver}'s, or null where it names none, which the model allows of a Spark SQL driver.
     */
    static String entryPoint(ObjectNode run) {
        JsonNode jobDriver = run.path("jobDriver");
        JsonNode driver = jobDriver.path("sparkSqlJobDriver").isObject() ? jobDriver.path("sparkSqlJobDriver")
                : jobDriver.path("sparkSubmitJobDriver");
        return driver.path("entryPoint").textValue();
    }

    /**
     * What runs a job run's Spark job, one whose job driver names an entry point ({@link #entryPoint}): its
     * {@code sparkSubmitJobDriver}'s {@code sparkSubmitParameters}, entry point and its arguments after Floci's
     * settings on {@code spark-submit}'s command line, or its {@code sparkSqlJobDriver}'s {@code spark-sql} with its
     * {@code sparkSqlParameters} and its entry point as the file it runs ({@code -f}). Its custom image is the one
     * {@code spark.kubernetes.driver.container.image}, or else {@code spark.kubernetes.container.image}, names; its
     * driver's environment its {@code spark.kubernetes.driverEnv.<KEY>} properties; its {@code spark.archives} are
     * unpacked in its working directory, as they are in a pod's. Its driver's logs go to {@link #driverLogFolder} and
     * {@link #driverCloudWatchLogs}, under {@code sparkApplicationId} and its driver pod's name, {@code driverPod}.
     */
    static SparkJobRunner.Job job(ObjectNode run, String imageTemplate, String dockerNetwork,
                                  String sparkApplicationId, String driverPod) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(run.get("arn").textValue());
        String releaseLabel = run.get("releaseLabel").textValue();
        JsonNode sparkSql = run.path("jobDriver").path("sparkSqlJobDriver");
        JsonNode sparkSubmit = run.path("jobDriver").path("sparkSubmitJobDriver");
        String parameters = sparkSql.isObject() ? sparkSql.path("sparkSqlParameters").textValue()
                : sparkSubmit.path("sparkSubmitParameters").textValue();
        Map<String, String> sparkDefaults = sparkDefaults(run.get("configurationOverrides"));
        sparkDefaults.put(DEPLOY_MODE, CLIENT);
        Map<String, String> properties = EmrServerlessJobRunService.sparkProperties(sparkDefaults, parameters);
        String customImage = properties.getOrDefault(DRIVER_CONTAINER_IMAGE, properties.get(CONTAINER_IMAGE));
        List<String> application = new ArrayList<>();
        List<String> command = null;
        if (sparkSql.isObject()) {
            command = new ArrayList<>(List.of(SPARK_SQL));
            command.addAll(EmrServerlessSparkRunner.splitParameters(parameters));
            command.addAll(List.of("-f", entryPoint(run)));
        } else {
            application.addAll(EmrServerlessSparkRunner.splitParameters(parameters));
            application.add(entryPoint(run));
            sparkSubmit.path("entryPointArguments").forEach(argument -> application.add(argument.asText()));
        }
        return new SparkJobRunner.Job(EmrContainersService.SERVICE, run.get("id").textValue(), arn.accountId(),
                arn.region(), releaseLabel, SparkJobRunner.Engine.SPARK, image(imageTemplate, releaseLabel),
                customImage, dockerNetwork, run.get("executionRoleArn").textValue(), sparkDefaults, application,
                command, Duration.ZERO, driverLogFolder(run, sparkApplicationId, driverPod),
                driverCloudWatchLogs(run, sparkApplicationId, driverPod),
                EmrServerlessJobRunService.environment(properties, DRIVER_ENV_PREFIX),
                EmrServerlessJobRunService.archives(properties), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null);
    }

    /**
     * The S3 folder a run's driver logs go to, or null without an {@code s3MonitoringConfiguration}: "Driver Logs -
     * /logUri/virtual-cluster-id/jobs/job-id/containers/spark-application-id/spark-job-id-driver/(stderr.gz/stdout.gz)"
     * (EMR on EKS Development Guide, "Configure a job run to use Amazon S3 logs").
     */
    static String driverLogFolder(ObjectNode run, String sparkApplicationId, String driverPod) {
        String logUri = run.path("configurationOverrides").path("monitoringConfiguration")
                .path("s3MonitoringConfiguration").path("logUri").textValue();
        return EmrServerlessSparkRunner.logFolder(logUri, driverLogPath(run, sparkApplicationId, driverPod) + "/");
    }

    /**
     * Where in CloudWatch Logs a run's driver output goes, or null without a {@code cloudWatchMonitoringConfiguration}:
     * its {@code logGroupName}, and "Driver logs - logGroup/logStreamPrefix/virtual-cluster-id/jobs/job-id/containers/
     * spark-application-id/spark-job-id-driver/(stderr/stdout)", a stream for each (EMR on EKS Development Guide,
     * "Configure a job run to use Amazon CloudWatch Logs"). Without a {@code logStreamNamePrefix} a stream's name
     * starts at the virtual cluster's id.
     */
    static SparkJobRunner.CloudWatchLogs driverCloudWatchLogs(ObjectNode run, String sparkApplicationId,
                                                              String driverPod) {
        JsonNode cloudWatch = run.path("configurationOverrides").path("monitoringConfiguration")
                .path("cloudWatchMonitoringConfiguration");
        String logGroup = cloudWatch.path("logGroupName").textValue();
        if (logGroup == null) {
            return null;
        }
        String prefix = cloudWatch.path("logStreamNamePrefix").textValue();
        String driver = (prefix != null && !prefix.isEmpty() ? prefix + "/" : "")
                + driverLogPath(run, sparkApplicationId, driverPod) + "/";
        return new SparkJobRunner.CloudWatchLogs(logGroup, driver + STDOUT, driver + STDERR);
    }

    // virtual-cluster-id/jobs/job-id/containers/spark-application-id/driver-pod, the driver pod's name being
    // spark-job-id-driver, with an attempt's suffix where the run retries.
    private static String driverLogPath(ObjectNode run, String sparkApplicationId, String driverPod) {
        return run.get("virtualClusterId").textValue() + "/jobs/" + run.get("id").textValue() + "/containers/"
                + sparkApplicationId + "/" + driverPod;
    }
}
