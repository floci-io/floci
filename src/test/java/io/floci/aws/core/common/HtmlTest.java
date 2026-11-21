package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTest {

    @Test
    void markupInTextOrInAnAttributeQuotedEitherWayIsShownAsItIs() {
        assertEquals("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;R&amp;D&lt;/a&gt;",
                Html.escape("<a href=\"x\" title='y'>R&D</a>"));
    }

    @Test
    void aPageEscapesItsTitleAndARowEscapesItsCellsAndIsLeftOutWithoutAValue() {
        List<String> lines = new ArrayList<>();
        Html.row(lines, "Name", "<b>");
        Html.row(lines, "Empty", "");
        Html.row(lines, "Absent", null);

        assertEquals(List.of("<tr><th>Name</th><td>&lt;b&gt;</td></tr>"), lines);
        String page = Html.page("A & B", lines);
        assertTrue(page.contains("<title>A &amp; B</title>"), page);
        assertTrue(page.contains("<h1>A &amp; B</h1>\n<tr><th>Name</th>"), page);
    }

    @Test
    void anEntityAlreadyWrittenIsEscapedAgainRatherThanReadAsOne() {
        assertEquals("&amp;lt;", Html.escape("&lt;"));
        assertEquals("plain text", Html.escape("plain text"));
    }
}
