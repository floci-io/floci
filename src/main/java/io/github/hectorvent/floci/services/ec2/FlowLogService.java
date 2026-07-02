package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Manages VPC Flow Logs (a floci addition — absent upstream) and a background
 * generator that synthesizes realistic VPC flow-log records and delivers them
 * to the configured S3 bucket.
 *
 * <p>Records use the AWS VPC Flow Logs <b>default format</b> (version 5, the
 * full 29-field space-delimited layout with a header line) and are written
 * under the exact AWS S3 key prefix
 * {@code AWSLogs/{account}/vpcflowlogs/{region}/{yyyy}/{MM}/{dd}/{HH}/...log.gz}.
 * This is the exact layout AWS uses, so any tool that lists and parses VPC flow
 * logs can ingest the emulated traffic with no real cloud spend.</p>
 *
 * <p>Records are correlated to the real EC2 inventory: source/destination
 * addresses, ENI ids, instance ids, subnet ids and vpc ids are taken from the
 * instances/ENIs that actually exist in {@link Ec2Service} for the flow log's
 * resource, so the synthesized flows reference resources that genuinely exist.</p>
 */
@ApplicationScoped
public class FlowLogService {

    private static final Logger LOG = Logger.getLogger(FlowLogService.class);

    /** AWS VPC Flow Logs default (version 5) field order. */
    static final String DEFAULT_HEADER =
            "account-id action az-id bytes dstaddr dstport end flow-direction instance-id "
            + "interface-id log-status packets pkt-dst-aws-service pkt-dstaddr pkt-src-aws-service "
            + "pkt-srcaddr protocol region srcaddr srcport start sublocation-id sublocation-type "
            + "subnet-id tcp-flags traffic-path type version vpc-id";

    private static final DateTimeFormatter PATH_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    // flowLogId -> FlowLog (persisted via StorageFactory so flow logs survive a
    // restart in persistent/hybrid/wal modes and generation resumes)
    private final StorageBackend<String, FlowLog> flowLogs;

    private final EmulatorConfig config;
    private final Ec2Service ec2Service;
    private final S3Service s3Service;
    private ScheduledExecutorService scheduler;

    @Inject
    public FlowLogService(EmulatorConfig config, Ec2Service ec2Service, S3Service s3Service,
                          StorageFactory storageFactory) {
        this(config, ec2Service, s3Service,
                storageFactory.create("ec2", "ec2-flow-logs.json", new TypeReference<Map<String, FlowLog>>() {}));
    }

    // Package-private for hermetic tests (pass an in-memory StorageBackend directly).
    FlowLogService(EmulatorConfig config, Ec2Service ec2Service, S3Service s3Service,
                   StorageBackend<String, FlowLog> flowLogs) {
        this.config = config;
        this.ec2Service = ec2Service;
        this.s3Service = s3Service;
        this.flowLogs = flowLogs;
    }

    void onStart(@Observes StartupEvent ev) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "floci-flowlog-generator");
            t.setDaemon(true);
            return t;
        });
        // Periodically deliver a fresh batch for every active flow log so the
        // emulated environment shows continuous traffic. Interval kept short
        // (60s) so consumers see flows quickly; AWS real interval is 60/600s.
        scheduler.scheduleAtFixedRate(this::generateForAll, 60, 60, TimeUnit.SECONDS);
        LOG.info("FlowLog generator scheduled (every 60s)");
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ─── CRUD ───────────────────────────────────────────────────────────────

    public FlowLog createFlowLog(String region, String resourceId, String resourceType,
                                 String trafficType, String logDestinationType,
                                 String logDestination, String logFormat, int maxAggregationInterval) {
        FlowLog fl = new FlowLog();
        fl.setFlowLogId("fl-" + randomHex(17));
        fl.setResourceId(resourceId);
        fl.setResourceType(resourceType != null ? resourceType : "VPC");
        fl.setTrafficType(trafficType != null ? trafficType : "ALL");
        fl.setLogDestinationType(logDestinationType != null ? logDestinationType : "s3");
        fl.setLogDestination(logDestination);
        fl.setBucketName(bucketFromArn(logDestination));
        fl.setLogFormat(logFormat);
        fl.setMaxAggregationInterval(maxAggregationInterval);
        fl.setRegion(region);
        fl.setAccountId(config.defaultAccountId());
        flowLogs.put(fl.getFlowLogId(), fl);
        LOG.infov("Created flow log {0} for {1} {2} -> {3}",
                fl.getFlowLogId(), fl.getResourceType(), resourceId, fl.getBucketName());

        // Deliver an initial batch right away so flows are visible without
        // waiting for the first scheduled tick.
        if ("s3".equals(fl.getLogDestinationType()) && fl.getBucketName() != null) {
            try {
                generateAndDeliver(fl);
            } catch (Exception e) {
                LOG.warnv("Initial flow-log delivery failed for {0}: {1}", fl.getFlowLogId(), e.getMessage());
            }
        }
        return fl;
    }

    public List<FlowLog> describeFlowLogs(String region, List<String> flowLogIds) {
        List<FlowLog> result = new ArrayList<>();
        for (FlowLog fl : flowLogs.scan(k -> true)) {
            if (region != null && fl.getRegion() != null && !region.equals(fl.getRegion())) {
                continue;
            }
            if (flowLogIds != null && !flowLogIds.isEmpty() && !flowLogIds.contains(fl.getFlowLogId())) {
                continue;
            }
            result.add(fl);
        }
        return result;
    }

    public List<String> deleteFlowLogs(String region, List<String> flowLogIds) {
        List<String> deleted = new ArrayList<>();
        if (flowLogIds == null) {
            return deleted;
        }
        for (String id : flowLogIds) {
            FlowLog fl = flowLogs.get(id).orElse(null);
            if (fl == null) {
                continue;
            }
            if (region != null && fl.getRegion() != null && !region.equals(fl.getRegion())) {
                continue;
            }
            flowLogs.delete(id);
            deleted.add(id);
        }
        return deleted;
    }

    // ─── Generation ───────────────────────────────────────────────────────────

    private void generateForAll() {
        for (FlowLog fl : flowLogs.scan(k -> true)) {
            if (!"s3".equals(fl.getLogDestinationType()) || fl.getBucketName() == null) {
                continue;
            }
            try {
                generateAndDeliver(fl);
            } catch (Exception e) {
                LOG.warnv("Scheduled flow-log delivery failed for {0}: {1}", fl.getFlowLogId(), e.getMessage());
            }
        }
    }

    /**
     * Synthesize one flow-log file for the given flow log and write it to S3
     * at the AWS-native key prefix. Records are derived from the live EC2
     * inventory of the flow log's resource (its instances/ENIs).
     */
    void generateAndDeliver(FlowLog fl) {
        List<Eni> enis = resolveEnis(fl);
        if (enis.isEmpty()) {
            LOG.debugv("No ENIs found for flow log {0} resource {1}; skipping",
                    fl.getFlowLogId(), fl.getResourceId());
            return;
        }

        Instant now = Instant.now();
        long endEpoch = now.getEpochSecond();
        long startEpoch = endEpoch - fl.getMaxAggregationInterval();

        StringBuilder sb = new StringBuilder();
        sb.append(fl.getLogFormat() != null && !fl.getLogFormat().isBlank()
                ? customHeader(fl.getLogFormat()) : DEFAULT_HEADER).append('\n');

        // Collect the private IPs of all sibling ENIs in this flow log's resource
        // so instances in the same VPC actually talk to EACH OTHER (not just to
        // random external peers). This yields realistic intra-VPC conversations
        // between the resources that exist in the account.
        List<String> siblingIps = new ArrayList<>();
        for (Eni e : enis) {
            if (e.privateIp != null && !e.privateIp.isBlank()) {
                siblingIps.add(e.privateIp);
            }
        }

        // Interface VPC endpoints (e.g. S3 PrivateLink) have an ENI with a private
        // IP in this VPC. Treat those as peers so instances send flows to the
        // endpoint — which is how traffic to an AWS service over PrivateLink shows
        // up in flow logs. The endpoint's service is recorded in pkt-dst-aws-service.
        List<String> endpointIps = new ArrayList<>();
        Map<String, String> endpointService = new java.util.HashMap<>(); // ip -> service short name (e.g. S3)
        String resourceVpc = vpcOfFlowLogResource(fl);
        for (NetworkInterface ni : ec2Service.endpointNetworkInterfaces(fl.getRegion())) {
            if (ni.getPrivateIpAddress() == null || ni.getPrivateIpAddress().isBlank()) {
                continue;
            }
            // Only endpoints in the same VPC this flow log covers.
            if (resourceVpc != null && ni.getVpcId() != null && !resourceVpc.equals(ni.getVpcId())) {
                continue;
            }
            endpointIps.add(ni.getPrivateIpAddress());
            endpointService.put(ni.getPrivateIpAddress(), awsServiceFromEndpoint(ni, fl.getRegion()));
        }

        int recordCount = 0;
        for (Eni eni : enis) {
            // Peers this ENI should talk to: every OTHER sibling VM in the VPC
            // (guaranteed intra-VPC edges), every interface endpoint (VM -> S3),
            // plus a couple of random external peers.
            List<String> peers = new ArrayList<>();
            for (String ip : siblingIps) {
                if (!ip.equals(eni.privateIp)) {
                    peers.add(ip); // intra-VPC: app-01 <-> web-01
                }
            }
            peers.addAll(endpointIps); // VM -> S3 endpoint
            int externals = 2 + ThreadLocalRandom.current().nextInt(2); // 2-3 external conversations
            for (int i = 0; i < externals; i++) {
                peers.add(randomPeer(eni.privateIp));
            }

            for (String peer : peers) {
                int dstPort = pickServicePort();
                int srcPortEph = 32768 + ThreadLocalRandom.current().nextInt(28000);
                String svc = endpointService.get(peer); // non-null only for endpoint peers
                // egress: eni -> peer
                sb.append(record(fl, eni, eni.privateIp, peer, srcPortEph, dstPort,
                        "egress", startEpoch, endEpoch, svc)).append('\n');
                // ingress: peer -> eni (response)
                sb.append(record(fl, eni, peer, eni.privateIp, dstPort, srcPortEph,
                        "ingress", startEpoch, endEpoch, svc)).append('\n');
                recordCount += 2;
            }
        }

        byte[] gz = gzip(sb.toString());
        String key = s3Key(fl, now);
        try {
            s3Service.putObject(fl.getBucketName(), key, gz, "application/octet-stream",
                    Map.of("content-encoding", "gzip"));
            LOG.infov("Delivered {0} flow records to s3://{1}/{2}", recordCount, fl.getBucketName(), key);
        } catch (RuntimeException e) {
            // Bucket may not exist yet (e.g. flow log created before the bucket).
            // Skip this delivery; the next scheduled tick will retry once it exists.
            LOG.debugv("Flow-log delivery skipped for {0} (bucket {1}): {2}",
                    fl.getFlowLogId(), fl.getBucketName(), e.getMessage());
        }
    }

    /**
     * Build one space-delimited default-format flow record line.
     *
     * @param svc AWS service short-name (e.g. "S3") when the peer is an interface
     *            VPC endpoint, else null. Sets pkt-dst-aws-service (egress) or
     *            pkt-src-aws-service (ingress) so the record looks like real
     *            AWS-service traffic.
     */
    private String record(FlowLog fl, Eni eni, String src, String dst, int srcPort, int dstPort,
                          String direction, long start, long end, String svc) {
        int bytes = 200 + ThreadLocalRandom.current().nextInt(40000);
        int packets = 1 + ThreadLocalRandom.current().nextInt(60);
        int protocol = 6; // TCP
        String action = "ACCEPT";
        String azId = azId(fl.getRegion(), eni.az);
        int tcpFlags = "egress".equals(direction) ? 2 : 18; // SYN / SYN-ACK-ish
        boolean egress = "egress".equals(direction);
        // For endpoint traffic, the AWS service tags the side that is the endpoint:
        // egress (eni->endpoint) => dst is the service; ingress (endpoint->eni) => src.
        String pktDstSvc = (svc != null && egress) ? svc : "-";
        String pktSrcSvc = (svc != null && !egress) ? svc : "-";
        // Field order MUST match DEFAULT_HEADER exactly.
        return String.join(" ",
                fl.getAccountId(),                 // account-id
                action,                            // action
                azId,                              // az-id
                String.valueOf(bytes),             // bytes
                dst,                               // dstaddr
                String.valueOf(dstPort),           // dstport
                String.valueOf(end),               // end
                direction,                         // flow-direction
                nz(eni.instanceId),                // instance-id
                eni.eniId,                         // interface-id
                "OK",                              // log-status
                String.valueOf(packets),           // packets
                pktDstSvc,                         // pkt-dst-aws-service
                dst,                               // pkt-dstaddr
                pktSrcSvc,                         // pkt-src-aws-service
                src,                               // pkt-srcaddr
                String.valueOf(protocol),          // protocol
                fl.getRegion(),                    // region
                src,                               // srcaddr
                String.valueOf(srcPort),           // srcport
                String.valueOf(start),             // start
                "-",                               // sublocation-id
                "-",                               // sublocation-type
                nz(eni.subnetId),                  // subnet-id
                String.valueOf(tcpFlags),          // tcp-flags
                "egress".equals(direction) ? "1" : "-", // traffic-path
                "IPv4",                            // type
                "5",                               // version
                nz(eni.vpcId));                    // vpc-id
    }

    // ─── Inventory correlation ─────────────────────────────────────────────────

    /** The VPC id a flow log covers (its resource is a VPC, or a subnet/eni in one). */
    private String vpcOfFlowLogResource(FlowLog fl) {
        String rid = fl.getResourceId();
        if (rid == null) {
            return null;
        }
        if (rid.startsWith("vpc-")) {
            return rid;
        }
        // subnet/eni: find the owning VPC from a matching instance ENI.
        List<Reservation> reservations = ec2Service.describeInstances(fl.getRegion(), List.of(), Map.of());
        for (Reservation res : reservations) {
            for (Instance inst : res.getInstances()) {
                if (rid.equals(inst.getSubnetId())) {
                    return inst.getVpcId();
                }
                List<InstanceNetworkInterface> nis = inst.getNetworkInterfaces();
                if (nis != null) {
                    for (InstanceNetworkInterface ni : nis) {
                        if (rid.equals(ni.getNetworkInterfaceId()) || rid.equals(ni.getSubnetId())) {
                            return ni.getVpcId() != null ? ni.getVpcId() : inst.getVpcId();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Map an endpoint ENI to its service short tag (com.amazonaws.us-east-1.s3 -> S3). */
    private String awsServiceFromEndpoint(NetworkInterface eni, String region) {
        String desc = eni.getDescription(); // "VPC Endpoint Interface vpce-..."
        int marker = desc != null ? desc.indexOf("vpce-") : -1;
        if (marker >= 0) {
            String endpointId = desc.substring(marker).trim();
            try {
                for (VpcEndpoint endpoint : ec2Service.describeVpcEndpoints(region, List.of(endpointId), Map.of())) {
                    String serviceName = endpoint.getServiceName();
                    if (serviceName != null && serviceName.contains(".")) {
                        return serviceName.substring(serviceName.lastIndexOf('.') + 1).toUpperCase();
                    }
                }
            } catch (AwsException e) {
                LOG.debugv("Endpoint {0} not resolvable for service tagging: {1}", endpointId, e.getMessage());
            }
        }
        return "S3";
    }

    /** Resolve the ENIs to attribute flows to, based on the flow log's resource. */
    private List<Eni> resolveEnis(FlowLog fl) {
        List<Eni> enis = new ArrayList<>();
        String region = fl.getRegion();
        String resourceId = fl.getResourceId();
        String resourceType = fl.getResourceType();

        List<Reservation> reservations = ec2Service.describeInstances(region, List.of(), Map.of());
        for (Reservation res : reservations) {
            for (Instance inst : res.getInstances()) {
                boolean match = switch (resourceType) {
                    case "VPC" -> resourceId == null || resourceId.equals(inst.getVpcId());
                    case "Subnet" -> resourceId == null || resourceId.equals(inst.getSubnetId());
                    case "NetworkInterface" -> hasEni(inst, resourceId);
                    default -> true;
                };
                if (!match) {
                    continue;
                }
                String az = inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : region + "a";
                List<InstanceNetworkInterface> nis = inst.getNetworkInterfaces();
                if (nis != null && !nis.isEmpty()) {
                    for (InstanceNetworkInterface ni : nis) {
                        if ("NetworkInterface".equals(resourceType) && resourceId != null
                                && !resourceId.equals(ni.getNetworkInterfaceId())) {
                            continue;
                        }
                        Eni e = new Eni();
                        e.eniId = ni.getNetworkInterfaceId();
                        e.privateIp = ni.getPrivateIpAddress() != null ? ni.getPrivateIpAddress() : inst.getPrivateIpAddress();
                        e.instanceId = inst.getInstanceId();
                        e.subnetId = ni.getSubnetId() != null ? ni.getSubnetId() : inst.getSubnetId();
                        e.vpcId = ni.getVpcId() != null ? ni.getVpcId() : inst.getVpcId();
                        e.az = az;
                        enis.add(e);
                    }
                } else {
                    // Fall back to a synthetic ENI from instance fields.
                    Eni e = new Eni();
                    e.eniId = "eni-" + randomHex(17);
                    e.privateIp = inst.getPrivateIpAddress();
                    e.instanceId = inst.getInstanceId();
                    e.subnetId = inst.getSubnetId();
                    e.vpcId = inst.getVpcId();
                    e.az = az;
                    enis.add(e);
                }
            }
        }
        return enis;
    }

    private boolean hasEni(Instance inst, String eniId) {
        if (eniId == null || inst.getNetworkInterfaces() == null) {
            return false;
        }
        return inst.getNetworkInterfaces().stream()
                .anyMatch(ni -> eniId.equals(ni.getNetworkInterfaceId()));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private String s3Key(FlowLog fl, Instant when) {
        String datePath = PATH_FMT.format(when);
        String ts = FILE_TS_FMT.format(when);
        String file = fl.getAccountId() + "_vpcflowlogs_" + fl.getRegion() + "_" + fl.getFlowLogId()
                + "_" + ts + "_" + randomHex(8) + ".log.gz";
        return String.format("AWSLogs/%s/vpcflowlogs/%s/%s/%s",
                fl.getAccountId(), fl.getRegion(), datePath, file);
    }

    private static byte[] gzip(String content) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("gzip failed", e);
        }
    }

    private static String bucketFromArn(String arn) {
        if (arn == null) {
            return null;
        }
        // arn:aws:s3:::bucket-name[/optional/prefix]  OR a bare bucket name.
        String name = arn.startsWith("arn:") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        int slash = name.indexOf('/');
        return slash >= 0 ? name.substring(0, slash) : name;
    }

    /** Random peer IP: ~half inside the same /16 (intra-VPC), half "external". */
    private static String randomPeer(String selfIp) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (selfIp != null && selfIp.contains(".") && r.nextBoolean()) {
            String[] o = selfIp.split("\\.");
            if (o.length == 4) {
                return o[0] + "." + o[1] + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
            }
        }
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private static int pickServicePort() {
        int[] ports = {443, 80, 53, 5432, 3306, 6379, 8080, 22};
        return ports[ThreadLocalRandom.current().nextInt(ports.length)];
    }

    /** Map a region + az letter to a plausible AWS az-id (e.g. us-east-1a -> use1-az1). */
    private static String azId(String region, String az) {
        String code = switch (region == null ? "" : region) {
            case "us-east-1" -> "use1";
            case "us-east-2" -> "use2";
            case "us-west-1" -> "usw1";
            case "us-west-2" -> "usw2";
            case "eu-west-1" -> "euw1";
            case "eu-central-1" -> "euc1";
            case "ap-southeast-1" -> "apse1";
            case "ap-southeast-2" -> "apse2";
            default -> region == null ? "use1" : region.replaceAll("[^a-z0-9]", "");
        };
        int n = 1;
        if (az != null && !az.isEmpty()) {
            char last = az.charAt(az.length() - 1);
            if (last >= 'a' && last <= 'f') {
                n = (last - 'a') + 1;
            }
        }
        return code + "-az" + n;
    }

    private static String customHeader(String logFormat) {
        // AWS custom format uses ${field-name} tokens; convert to a space-delimited header.
        return logFormat.replace("${", "").replace("}", "").trim();
    }

    private static String nz(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }

    private static String randomHex(int len) {
        String chars = "0123456789abcdef";
        StringBuilder sb = new StringBuilder(len);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** Lightweight holder for the ENI fields a flow record needs. */
    private static final class Eni {
        String eniId;
        String privateIp;
        String instanceId;
        String subnetId;
        String vpcId;
        String az;
    }
}
