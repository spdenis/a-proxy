package com.testing.aproxy.webui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WebUiPartsTest {

    @Test
    void cookiesAreSentByPathMostSpecificFirst() {
        CookieJar jar = new CookieJar();
        jar.store("/authentication/login", List.of("WCC-ASID=a; Path=/; HttpOnly", "JSESSIONID=auth; Path=/authentication"));
        jar.store("/wcc/quickview/index.jsp", List.of("JSESSIONID=qv; Path=/wcc/quickview; HttpOnly"));

        assertThat(jar.header("/wcc/quickview/pages/main.jsf")).isEqualTo("JSESSIONID=qv; WCC-ASID=a");
        assertThat(jar.header("/authentication/logout")).isEqualTo("JSESSIONID=auth; WCC-ASID=a");
        assertThat(jar.header("/wcc/quickviewer")).isEqualTo("WCC-ASID=a");

        jar.store("/x", List.of("WCC-ASID=; Path=/; Max-Age=0"));
        assertThat(jar.header("/wcc/quickviewer")).isNull();
    }

    @Test
    void browserCookiesReplaceLoginCookiesOfTheSameNameAndKeepDuplicates() {
        CookieJar jar = new CookieJar();
        jar.store("/authentication/login", List.of("WCC-ASID=stale; Path=/wcc", "OTHER=o; Path=/"));
        jar.putBrowser("WCC-ASID", List.of("a1", "a2"));

        assertThat(jar.header("/wcc/quickview/index.jsp")).isEqualTo("OTHER=o; WCC-ASID=a1; WCC-ASID=a2");
    }

    @Test
    void statusCodesFromIconTitles() {
        assertThat(JobPage.statusCode("Success (SUCCESS)")).isEqualTo("SUCCESS");
        assertThat(JobPage.statusText("Success (SUCCESS)")).isEqualTo("Success");
        assertThat(JobPage.statusCode("On Ice")).isEqualTo("ON_ICE");
        assertThat(JobPage.statusCode("FAILURE")).isEqualTo("FAILURE");
        assertThat(JobPage.satisfied("Not satisfied")).isFalse();
        assertThat(JobPage.satisfied("Yes")).isTrue();
        assertThat(JobPage.satisfied("maybe")).isNull();
        assertThat(JobPage.satisfied("Condition not satisfied")).isFalse();
        assertThat(JobPage.satisfied("Condition satisfied")).isTrue();
        assertThat(JobPage.nameCell("Select to expand Box (box) LONG_BO...", "<span title=\"Go to LONG_BOX\">"))
                .containsExactly("LONG_BOX", "BOX", "BOX");
        assertThat(JobPage.nameCell("File Watcher (fw) FEED_WATCH", ""))
                .containsExactly("FEED_WATCH", "REGULAR", "FW");
        assertThat(JobPage.nameCell("PLAIN_JOB", "<a href=\"main.jsf?a=1&amp;jobName=PLAIN%23JOB\">"))
                .containsExactly("PLAIN#JOB", null, null);
    }

    @Test
    void formFieldsAsABrowserSubmitsThem() {
        HtmlForms.Form form = HtmlForms.find("""
                <form id="other" action="/x"><input name="a" value="1"></form>
                <form name="f" action="/p.jsf?T=1&amp;c=2"><input type="hidden" name="h" value="&quot;v&quot;">
                <input type="checkbox" name="cb" value="on"><input type="checkbox" name="cc" value="on" checked>
                <input type="submit" name="go" value="Go"><select name="s"><option value="0">A<option value="1" selected>B</select>
                <textarea name="t">x &amp; y</textarea></form>""", "f");

        assertThat(form.action()).isEqualTo("/p.jsf?T=1&c=2");
        assertThat(form.fields()).containsExactly(Map.entry("h", "\"v\""), Map.entry("cc", "on"),
                Map.entry("s", "1"), Map.entry("t", "x & y"));
        assertThat(QuickViewClient.queryParams(form.action())).containsEntry("T", "1").containsEntry("c", "2");
    }
}
