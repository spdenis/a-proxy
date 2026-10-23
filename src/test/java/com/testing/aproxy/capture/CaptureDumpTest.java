package com.testing.aproxy.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fake Trinidad partial-page response shaped like a WCC Quick View job search (placeholder data). */
class CaptureDumpTest {

    static final String PPR = """
            <?xml version="1.0" ?><content action="/wcc/quickview/pages/main.jsf?AntiXSRFToken=***">
            <fragment><![CDATA[<div id="jobResultPanel"><table class="layout"><tr><td>
              <table id="jobResultTable" summary="Search Results">
                <tr><th>Name</th><th>Box Name</th><th>Status</th><th>Exit Code</th></tr>
                <tr><td><a href="#">JOB_A</a></td><td>BOX_1</td><td><span>SUCCESS</span></td><td>0</td></tr>
                <tr><td>JOB_B</td><td>&nbsp;</td><td>FAILURE</td><td>1</td></tr>
              </table></td></tr></table>
              <table id="attrs"><tr><th>Attribute Name</th><th>Attribute Value</th></tr>
                <tr><td>machine</td><td>host-1</td></tr></table>
              <input type="hidden" name="javax.faces.ViewState" value="!abc123">
            </div>]]></fragment>
            <fragment><![CDATA[<span id="lastUpdated">Last Updated</span>]]></fragment></content>
            """;

    @Test
    void dumpsFieldsTablesAndFragments() {
        ObjectNode c = (ObjectNode) JsonMapper.shared().readTree("""
                {"seq":84,"request":{"method":"POST","autosysPath":"/wcc/quickview/pages/main.jsf",
                  "query":{"AntiXSRFToken":"***","conversationContext":"1"},
                  "headers":{"X-Requested-With":"XMLHttpRequest","Tr-XHR-Message":"true","Accept":"*/*"},
                  "body":{"jobNameInput":"JOB_A","event":"autoSub","source":"goButtonID","partial":"true"}},
                 "response":{"status":200,"contentType":"text/xml;charset=utf-8","bodyLength":900,"headers":{}}}""");
        c.withObject("/response").put("body", PPR);

        String dump = new CaptureDump(10, false, 60).dump(c);

        assertThat(dump)
                .contains("DUMP seq=84 POST /wcc/quickview/pages/main.jsf -> 200 text/xml;charset=utf-8 900 bytes")
                .contains("conversationContext=1", "X-Requested-With=XMLHttpRequest", "Tr-XHR-Message=true")
                .doesNotContain("Accept=")
                .contains("-- request fields (4)", "jobNameInput=JOB_A", "event=autoSub", "source=goButtonID")
                .contains("-- response root element: <content>")
                .contains("-- partial-page fragments (2): jobResultPanel, lastUpdated")
                .contains("javax.faces.ViewState=!abc123")
                .contains("-- table T2 id=jobResultTable summary=Search Results rows=2")
                .contains("  headers: Name | Box Name | Status | Exit Code")
                .contains("  r1: JOB_A | BOX_1 | SUCCESS | 0")
                .contains("  r2: JOB_B |  | FAILURE | 1")
                .contains("id=attrs rows=1", "r1: machine | host-1")
                .contains("3 tables in total, 2 shown");
    }

    @Test
    void parsesNestedTables() {
        List<HtmlTables.Table> tables = HtmlTables.parse(PPR);
        assertThat(tables).hasSize(3);
        assertThat(tables.get(1).headers()).containsExactly("Name", "Box Name", "Status", "Exit Code");
        assertThat(tables.get(1).depth()).isEqualTo(1);
    }

    @Test
    void cellTextIncludesNestedTablesAndIconTitles() {
        String html = """
                <table><tr><th>Name</th><th>Status</th><th>Satisfied</th></tr>
                <tr><td><table><tr><td><img src="job.png"></td><td><a href="#">JOB_A</a></td></tr></table></td>
                <td><img title="Success" alt="Success (SUCCESS)" src="s.png"> </td><td><img title="Yes" src="ok.gif"/></td></tr></table>""";

        HtmlTables.Table table = HtmlTables.parse(html).getFirst();

        assertThat(table.dataRows()).containsExactly(List.of("JOB_A", "Success (SUCCESS)", "Yes"));
    }

    @Test
    void grepShowsRawMarkupAroundMatches() {
        ObjectNode c = (ObjectNode) JsonMapper.shared().readTree("""
                {"seq":7,"request":{"method":"GET","autosysPath":"/wcc/quickview/pages/main.jsf"},
                 "response":{"status":200,"headers":{}}}""");
        c.withObject("/response").put("body", PPR);
        StringBuilder out = new StringBuilder();

        int hits = new CaptureGrep(java.util.regex.Pattern.compile("ViewState"), 20, 5, true).grep(c, out);

        assertThat(hits).isEqualTo(1);
        assertThat(out.toString())
                .contains("== seq=7 GET /wcc/quickview/pages/main.jsf -> 200")
                .contains("[response body @")
                .contains("name=\"javax.faces.ViewState\" value=\"!abc123\"");
    }
}
