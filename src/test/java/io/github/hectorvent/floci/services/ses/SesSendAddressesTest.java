package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SesSendAddressesTest {

    @Test
    void splitAddressListSeparatesAddressesAtTopLevelCommas() {
        assertEquals(List.of("a@b.com", " \"Doe, John\" <c@d.com>", " \"x \\\"y, z\\\"\" <e@f.com>"),
                SesSendAddresses.splitAddressList("a@b.com, \"Doe, John\" <c@d.com>, \"x \\\"y, z\\\"\" <e@f.com>"));
    }

    @Test
    void splitAddressListKeepsCommasInsideAngleBrackets() {
        assertEquals(List.of("Name <a,b@c.com>"), SesSendAddresses.splitAddressList("Name <a,b@c.com>"));
    }

    @Test
    void splitAddressListIgnoresQuotesAndCommasInsideComments() {
        assertEquals(List.of("a@b.com (it\"s, (nested)) ", " c@d.com"),
                SesSendAddresses.splitAddressList("a@b.com (it\"s, (nested)) , c@d.com"));
    }

    @Test
    void splitAddressListKeepsParenthesesInsideQuotedNames() {
        assertEquals(List.of("\"a (b\" <x@y.com>", " z@y.com"),
                SesSendAddresses.splitAddressList("\"a (b\" <x@y.com>, z@y.com"));
    }

    @Test
    void splitAddressListDropsGroupSyntax() {
        assertEquals(List.of(" a@b.com", " c@d.com"),
                SesSendAddresses.splitAddressList("team: a@b.com, c@d.com;"));
        assertEquals(List.of(), SesSendAddresses.splitAddressList("undisclosed-recipients:;"));
    }

    @Test
    void splitAddressListKeepsColonsAndCommasInsideDomainLiterals() {
        assertEquals(List.of("user@[IPv6:2001:db8::1]", " a@[x,y]"),
                SesSendAddresses.splitAddressList("user@[IPv6:2001:db8::1], a@[x,y]"));
        assertEquals(List.of(" user@[IPv6:2001:db8::1]"),
                SesSendAddresses.splitAddressList("team: user@[IPv6:2001:db8::1];"));
    }
}
