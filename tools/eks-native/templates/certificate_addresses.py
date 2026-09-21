"""Validate kubelet serving SANs against the registered node addresses."""

import ipaddress


def expected_names(addresses):
    dns, ips = set(), set()
    for address in addresses:
        value, kind = address["address"], address["type"]
        if not value:
            continue
        if kind in {"InternalDNS", "ExternalDNS"}:
            dns.add(value)
        elif kind in {"InternalIP", "ExternalIP"}:
            ips.add(str(ipaddress.ip_address(value)))
        elif kind == "Hostname":
            try:
                ips.add(str(ipaddress.ip_address(value)))
            except ValueError:
                dns.add(value)
    return dns, ips


def matches_node(name, dns, ips, expected_dns, expected_ips):
    # A CSR can precede the cloud controller adding DNS aliases. Every requested
    # DNS name must still belong to this node; the node name and all IPs must match.
    return name in dns and dns <= expected_dns and ips == expected_ips and bool(ips)
