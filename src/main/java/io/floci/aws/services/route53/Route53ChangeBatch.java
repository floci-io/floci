package io.floci.aws.services.route53;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsNamespaces;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.services.route53.model.AliasTarget;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared parsing keeps IAM authorization and record mutations on the same change batch. */
public final class Route53ChangeBatch {

    private static final String NS = AwsNamespaces.ROUTE53;
    private static final Set<String> CHANGE_ACTIONS = Set.of("CREATE", "DELETE", "UPSERT");

    private Route53ChangeBatch() {}

    private static void requireExactlyOneRecordShape(String action, ResourceRecordSet rrs, boolean hasRecords) {
        boolean hasAlias = rrs.getAliasTarget() != null;
        boolean hasTtl = rrs.getTtl() != null;
        String found;
        if (hasAlias && (hasTtl || hasRecords)) {
            found = "more than one";
        } else if (!hasAlias && !(hasTtl && hasRecords)) {
            found = "none";
        } else {
            return;
        }
        throw new AwsException("InvalidInput",
                "Invalid request: Expected exactly one of [AliasTarget, all of [TTL, and ResourceRecords], "
                        + "or TrafficPolicyInstanceId], but found " + found + " in Change with [Action=" + action
                        + ", Name=" + rrs.getName() + ", Type=" + rrs.getType()
                        + ", SetIdentifier=" + rrs.getSetIdentifier() + "]", 400);
    }

    /**
     * Parses the ChangeBatch XML using StAX to correctly handle multiple Change elements,
     * each containing a ResourceRecordSet with its own set of ResourceRecord/Value children.
     */
    public static List<Map<String, Object>> parse(String body) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            parseChangeBatchInto(body, result);
        }
        if (result.isEmpty()) {
            throw new AwsException("InvalidInput",
                    "Invalid XML ; cvc-complex-type.2.4.b: The content of element 'Changes' is not complete. "
                            + "One of '{\"" + NS + "\":Change}' is expected.", 400);
        }
        return result;
    }

    private static void parseChangeBatchInto(String body, List<Map<String, Object>> result) {
        try {
            XMLStreamReader r = XmlParser.newStreamReader(body);
            String currentAction = null;
            ResourceRecordSet currentRrs = null;
            List<ResourceRecord> currentRecords = null;
            AliasTarget currentAlias = null;
            boolean inChangeBatch = false;
            boolean inChange = false;
            boolean inRrs = false;
            boolean inResourceRecords = false;
            boolean inAlias = false;
            boolean inHealthCheckConfig = false;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String currentElement = r.getLocalName();
                    switch (currentElement) {
                        case "ChangeBatch" -> inChangeBatch = true;
                        case "Change" -> {
                            if (inChangeBatch) {
                                inChange = true;
                                currentAction = null;
                                currentRrs = null;
                            }
                        }
                        case "Action" -> {
                            if (inChange && !inRrs) {
                                currentAction = r.getElementText();
                                if (!CHANGE_ACTIONS.contains(currentAction)) {
                                    throw new AwsException("InvalidInput",
                                            "Invalid value '" + currentAction + "' at 'Action' failed to satisfy "
                                                    + "constraint: Member must satisfy enum value set.", 400);
                                }
                            }
                        }
                        case "ResourceRecordSet" -> {
                            if (inChange) {
                                inRrs = true;
                                currentRrs = new ResourceRecordSet();
                                currentRecords = new ArrayList<>();
                            }
                        }
                        case "ResourceRecords" -> {
                            if (inRrs) {
                                inResourceRecords = true;
                            }
                        }
                        case "AliasTarget" -> {
                            if (inRrs) {
                                inAlias = true;
                                currentAlias = new AliasTarget();
                            }
                        }
                        case "Name" -> {
                            if (inRrs && !inAlias) {
                                String n = r.getElementText();
                                if (n != null && !n.endsWith(".")) {
                                    n = n + ".";
                                }
                                if (currentRrs != null) {
                                    currentRrs.setName(n);
                                }
                            }
                        }
                        case "Type" -> {
                            if (inRrs && !inAlias && !inHealthCheckConfig && currentRrs != null) {
                                currentRrs.setType(r.getElementText());
                            }
                        }
                        case "TTL" -> {
                            if (inRrs && currentRrs != null) {
                                String ttl = r.getElementText();
                                try {
                                    currentRrs.setTtl(Long.parseLong(ttl));
                                } catch (NumberFormatException e) {
                                    throw new AwsException("InvalidInput",
                                            "Invalid value '" + ttl + "' at 'TTL' failed to satisfy constraint: "
                                                    + "Member must be a valid long.", 400);
                                }
                            }
                        }
                        case "Value" -> {
                            if (inResourceRecords && currentRecords != null) {
                                currentRecords.add(new ResourceRecord(r.getElementText()));
                            }
                        }
                        case "SetIdentifier" -> {
                            if (inRrs && currentRrs != null) {
                                currentRrs.setSetIdentifier(r.getElementText());
                            }
                        }
                        case "MultiValueAnswer" -> {
                            if (inRrs && currentRrs != null) {
                                String multiValueAnswer = r.getElementText();
                                if (!"true".equals(multiValueAnswer) && !"false".equals(multiValueAnswer)) {
                                    throw new AwsException("InvalidInput",
                                            "MultiValueAnswer must be true or false.", 400);
                                }
                                currentRrs.setMultiValueAnswer(Boolean.valueOf(multiValueAnswer));
                            }
                        }
                        case "Weight" -> {
                            if (inRrs && currentRrs != null) {
                                String weight = r.getElementText();
                                try {
                                    currentRrs.setWeight(Long.parseLong(weight));
                                } catch (NumberFormatException e) {
                                    throw new AwsException("InvalidInput",
                                            "Invalid value '" + weight + "' at 'Weight' failed to satisfy "
                                                    + "constraint: Member must be a valid long.", 400);
                                }
                            }
                        }
                        case "Region" -> {
                            if (inRrs && !inAlias && currentRrs != null) {
                                currentRrs.setRegion(r.getElementText());
                            }
                        }
                        case "Failover" -> {
                            if (inRrs && currentRrs != null) {
                                currentRrs.setFailover(r.getElementText());
                            }
                        }
                        case "HealthCheckId" -> {
                            if (inRrs && !inHealthCheckConfig && currentRrs != null) {
                                currentRrs.setHealthCheckId(r.getElementText());
                            }
                        }
                        case "HostedZoneId" -> {
                            if (inAlias && currentAlias != null) {
                                currentAlias.setHostedZoneId(r.getElementText());
                            }
                        }
                        case "DNSName" -> {
                            if (inAlias && currentAlias != null) {
                                currentAlias.setDnsName(r.getElementText());
                            }
                        }
                        case "EvaluateTargetHealth" -> {
                            if (inAlias && currentAlias != null) {
                                currentAlias.setEvaluateTargetHealth(
                                        "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "ResourceRecords" -> inResourceRecords = false;
                        case "AliasTarget" -> {
                            if (inAlias && currentRrs != null && currentAlias != null) {
                                currentRrs.setAliasTarget(currentAlias);
                            }
                            inAlias = false;
                            currentAlias = null;
                        }
                        case "ResourceRecordSet" -> {
                            if (inRrs && currentRrs != null) {
                                if (currentRrs.getName() == null || currentRrs.getType() == null) {
                                    throw new AwsException("InvalidInput",
                                            "ResourceRecordSet is missing a required Name or Type element.", 400);
                                }
                                boolean hasRecords = currentRecords != null && !currentRecords.isEmpty();
                                if (hasRecords) {
                                    currentRrs.setRecords(currentRecords);
                                }
                                requireExactlyOneRecordShape(currentAction, currentRrs, hasRecords);
                            }
                            inRrs = false;
                        }
                        case "Change" -> {
                            if (inChange) {
                                if (currentAction == null || currentRrs == null) {
                                    throw new AwsException("InvalidInput",
                                            "Change is missing a required Action or ResourceRecordSet element.",
                                            400);
                                }
                                Map<String, Object> change = new HashMap<>();
                                change.put("action", currentAction);
                                change.put("rrs", currentRrs);
                                result.add(change);
                            }
                            inChange = false;
                            currentAction = null;
                            currentRrs = null;
                            currentRecords = null;
                        }
                    }
                }
            }
            r.close();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidInput", "The XML you provided was not well-formed.", 400);
        }
    }

}
