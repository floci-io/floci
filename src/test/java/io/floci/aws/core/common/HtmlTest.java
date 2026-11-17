package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlTest {

    @Test
    void markupInTextOrInAnAttributeQuotedEitherWayIsShownAsItIs() {
        assertEquals("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;R&amp;D&lt;/a&gt;",
                Html.escape("<a href=\"x\" title='y'>R&D</a>"));
    }

    @Test
    void anEntityAlreadyWrittenIsEscapedAgainRatherThanReadAsOne() {
        assertEquals("&amp;lt;", Html.escape("&lt;"));
        assertEquals("plain text", Html.escape("plain text"));
    }
}
