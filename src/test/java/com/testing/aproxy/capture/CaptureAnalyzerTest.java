package com.testing.aproxy.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureAnalyzerTest {

    @TempDir
    Path dir;

    @Test
    void summarisesEndpointsShapesAndLoginLikeResponses() throws IOException {
        capture(1, "POST", "/authentication/login", null, "application/x-www-form-urlencoded",
                "{\"user\":\"alice\",\"pw\":\"***\"}", "{\"Cookie\":\"JSESSIONID=***\",\"X-Requested-With\":\"XMLHttpRequest\"}",
                200, "application/json", "{\"Set-Cookie\":\"JSESSIONID=***; Path=/\"}", "{\"success\":true}");
        capture(2, "GET", "/ui/rest/jobs", "{\"name\":\"TEST*\",\"status\":[\"SU\",\"FA\"]}", null, "null",
                "{\"Cookie\":\"JSESSIONID=***\",\"Referer\":\"https://host:8443/ui/Jobs.html\"}",
                200, "application/json", "{}",
                "{\"total\":2,\"jobs\":[{\"name\":\"JOB_A\",\"status\":\"SU\",\"exit\":0},"
                        + "{\"name\":\"JOB_B\",\"status\":\"FA\",\"exit\":1}]}");
        capture(3, "GET", "/ui/rest/jobs/12345/deps", null, null, "null", "{}", 200, "application/json", "{}",
                "{\"upstream\":[]}");
        capture(4, "GET", "/ui/rest/jobs/67890/deps", null, null, "null", "{}", 200, "application/json", "{}",
                "{\"upstream\":[{\"job\":\"X\",\"type\":\"s\"}]}");
        capture(5, "POST", "/ui/rest/jobs/event", null, "application/json",
                "{\"event\":\"FORCE_STARTJOB\",\"job\":\"JOB_A\"}", "{\"OWASP-CSRFTOKEN\":\"***\"}",
                200, "application/json", "{}", "{\"result\":\"OK\"}");
        capture(6, "GET", "/ui/Job.html", null, null, "null", "{}", 200, "text/html", "{}",
                "\"<html><title>Job Status</title><table><tr><th>Name</th><th>Status</th></tr><tr><td>A</td></tr>"
                        + "</table><form action='/ui/do/search' method='post'><input name='q'>"
                        + "<input type='hidden' name='OWASP-CSRFTOKEN' value='***'></form>"
                        + "<script>$.get('/ui/rest/jobs?name=x')</script></html>\"");
        capture(7, "GET", "/ui/rest/jobs", "{\"name\":\"X\"}", null, "null", "{}", 302, null,
                "{\"Location\":\"https://host:8443/ui/Login.html?expired=1\"}", "null");

        // e.g. written while the proxy stopped: reported, not silently left out
        Files.writeString(dir.resolve("0008-GET-ui-broken.json"), "{\"seq\":8,\"request\":{");

        String summary = new CaptureAnalyzer(3, true).analyze(dir, null, false);

        assertThat(summary)
                .contains("files=8  requests=7  endpoints=5")
                .contains("== UNREADABLE FILES (1, not analyzed)")
                .contains("0008-GET-ui-broken.json: ")
                .contains("1:E1 2:E2 3:E3 4:E3 5:E4 6:E5 7:E2!")
                .contains("[E1] POST /authentication/login  x1  status=200  req=form  res=json")
                .contains("[E2] GET /ui/rest/jobs  x2  status=200 | 302  res=json  !LOGIN-LIKE")
                .contains("[E3] GET /ui/rest/jobs/{n}/deps  x2")
                .contains("query status: SU | FA")
                .contains("called from: /ui/Jobs.html")
                .contains("header X-Requested-With: XMLHttpRequest")
                .contains("header OWASP-CSRFTOKEN: ***")
                .contains("sets cookies: JSESSIONID")
                .contains("redirect to: /ui/Login.html?expired=1")
                .contains("jobs[].status : string = \"SU\", \"FA\"")
                .contains("jobs : array[max 2]")
                .contains("upstream[].type : string = \"s\"")
                .contains("event : string = \"FORCE_STARTJOB\"")
                .contains("res title: Job Status")
                .contains("res form: POST /ui/do/search [q, OWASP-CSRFTOKEN:hidden]")
                .contains("res tables: 1 table(s), 2 rows, headers [Name, Status]")
                .contains("res urls: /ui/rest/jobs?name=x");

        String brief = new CaptureAnalyzer(3, false).analyze(dir, java.util.regex.Pattern.compile("jobs"), true);
        assertThat(brief).contains("endpoints=3").doesNotContain("DETAILS").doesNotContain("Login.html");
        String noValues = new CaptureAnalyzer(3, false).analyze(dir, null, false);
        assertThat(noValues).doesNotContain("JOB_A").contains("jobs[].name : string = \"<5>\"");
    }

    private void capture(int seq, String method, String path, String query, String reqType, String reqBody,
            String reqHeaders, int status, String resType, String resHeaders, String resBody) throws IOException {
        String json = """
                {"seq":%d,"time":"2026-10-23T10:00:00Z","durationMs":5,
                 "request":{"method":"%s","path":"%s","autosysPath":"%s","query":%s,"contentType":%s,
                            "headers":%s,"body":%s},
                 "response":{"status":%d,"contentType":%s,"headers":%s,"bodyLength":100,"body":%s}}
                """.formatted(seq, method, path, path, query == null ? "null" : query,
                reqType == null ? "null" : "\"" + reqType + "\"", reqHeaders, reqBody, status,
                resType == null ? "null" : "\"" + resType + "\"", resHeaders, resBody);
        Files.writeString(dir.resolve("%04d-%s-x.json".formatted(seq, method)), json);
    }
}
