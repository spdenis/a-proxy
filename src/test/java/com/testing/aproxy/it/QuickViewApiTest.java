package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job API answered through a fake WCC Quick View shaped like the real one (index.jsp redirect with
 * CSRF tokens, JSF form, Trinidad partial-page XML); placeholder data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class QuickViewApiTest {

    static final String PASSWORD = "Qv-Pa55word!";
    static final String SEARCH = "/wcc/quickview/pages/main.jsf?AntiXSRFToken=XSRF-1&conversationContext=1";

    @RegisterExtension
    static WireMockExtension autosys = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("autosys.base-url", autosys::baseUrl);
        registry.add("autosys.webui.login.path", () -> "/authentication/login");
        registry.add("autosys.webui.login.username-field", () -> "username");
        registry.add("autosys.webui.login.password-field", () -> "password");
        registry.add("autosys.webui.login.success.statuses", () -> "200");
        registry.add("autosys.proxy.start-page", () -> "/ui/Login.html");
        // not scheduled (disabled); the test runs it by hand
        registry.add("autosys.keep-alive.job", () -> "TEST_FX_LOADER");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @org.springframework.beans.factory.annotation.Autowired
    com.testing.aproxy.webui.KeepAliveTask keepAlive;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        stubAutosys("asid-1");
    }

    /** Fake AutoSys whose login sets WCC-ASID={@code loginAsid}; Quick View opens only with {@code validAsid}. */
    static void stubAutosys(String validAsid) throws IOException {
        stubAutosys(autosys, validAsid);
    }

    static void stubAutosys(WireMockExtension autosys, String validAsid) throws IOException {
        autosys.resetAll();
        autosys.stubFor(post("/authentication/login")
                .withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
                .withRequestBody(matchingJsonPath("$.password", equalTo(PASSWORD)))
                .inScenario("logins").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("second")
                .willReturn(loginOk("asid-1")));
        autosys.stubFor(post("/authentication/login")
                .withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
                .withRequestBody(matchingJsonPath("$.password", equalTo(PASSWORD)))
                .inScenario("logins").whenScenarioStateIs("second")
                .willReturn(loginOk("asid-2")));
        autosys.stubFor(get("/wcc/quickview/index.jsp").atPriority(9)
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/ui/Login.html")));
        autosys.stubFor(get("/wcc/quickview/index.jsp").atPriority(1)
                .withHeader("Cookie", containing("WCC-ASID=" + validAsid))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "/wcc/quickview/pages/main.jsf?AntiXSRFToken=XSRF-1&OWASP-CSRFTOKEN=C-1")
                        .withHeader("Set-Cookie", "JSESSIONID=js-1; Path=/wcc/quickview; HttpOnly")));
        autosys.stubFor(get("/ui/Login.html").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html><form id=\"login\"></form></html>")));
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/pages/main.jsf"))
                .withHeader("Cookie", containing("JSESSIONID=js-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(resource("main.html"))));
        stubSearch(autosys, "TEST_FX_LOADER", "job.xml");
        stubSearch(autosys, "NOPE", "not-found.xml");
        stubSearch(autosys, "TEST_FX*", "job.xml");
        stubSearch(autosys, "TEST_BOX", "box.xml");
        // expanding the box: the fields the search response added, then the tree event on its first row
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(1)
                .withHeader("Cookie", containing("JSESSIONID=js-1"))
                .withRequestBody(containing("jobNameInput=TEST_BOX&"))
                .withRequestBody(containing("boxJobResultTable%3A_us=0"))
                .withRequestBody(containing("treeTableId%3ArangeStart=0"))
                .withRequestBody(containing("org.apache.myfaces.trinidad.faces.FORM=jobDetailsForm"))
                .withRequestBody(containing("source=boxJobResultTable&partialTargets=boxJobResultTable&partial=true"))
                .withRequestBody(containing("event=show&path=0"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("box-jobs.xml"))));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder loginOk(String asid) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("Set-Cookie", "WCC-ASID=" + asid + "; Path=/; HttpOnly").withBody("{\"ok\":true}");
    }

    static void stubSearch(String job, String file) throws IOException {
        stubSearch(autosys, job, file);
    }

    static void stubSearch(WireMockExtension autosys, String job, String file) throws IOException {
        autosys.stubFor(post(urlEqualTo(SEARCH))
                .withHeader("Cookie", containing("JSESSIONID=js-1"))
                .withHeader("Tr-XHR-Message", equalTo("true"))
                .withRequestBody(containing("jobNameInput=" + job + "&"))
                .withRequestBody(containing("AntiXSRFToken=XSRF-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource(file))));
    }

    @Test
    void jobDetailsStatusAndDependencies(CapturedOutput output) throws Exception {
        String token = login();

        JsonNode job = api("/api/v1/jobs/TEST_FX_LOADER", token, 200);
        assertThat(job.at("/name").asString()).isEqualTo("TEST_FX_LOADER");
        assertThat(job.at("/type").asString()).isEqualTo("REGULAR");
        assertThat(job.at("/jobType").asString()).isEqualTo("CMD");
        assertThat(job.at("/box").asString()).isEqualTo("TEST_PMC_BOX");
        assertThat(job.at("/jobs").isNull()).isTrue();
        assertThat(job.at("/status/name").asString()).isEqualTo("TEST_FX_LOADER");
        assertThat(job.at("/status/box").asString()).isEqualTo("TEST_PMC_BOX");
        assertThat(job.at("/status/status").asString()).isEqualTo("SUCCESS");
        assertThat(job.at("/status/statusText").asString()).isEqualTo("Success");
        assertThat(job.at("/status/run").asString()).isEqualTo("711037496");
        assertThat(job.at("/status/exitCode").asString()).isEqualTo("0");
        assertThat(job.at("/status/priority").isNull()).isTrue();
        assertThat(job.at("/attributes/Send to machine").asString()).isEqualTo("host-1.example");
        assertThat(job.at("/attributes/Command").asString()).isEqualTo("$RUNTIME/scripts/load.sh && echo \"done\"");
        assertThat(job.at("/attributes").has("Primary")).isFalse();
        assertThat(job.at("/resources").size()).isZero();
        assertThat(job.at("/runs").size()).isEqualTo(2);
        assertThat(job.at("/runs/1/status").asString()).isEqualTo("FAILURE");
        assertThat(job.at("/runs/1/run").asString()).isEqualTo("711037496/1");

        // the next request carries the ViewState of the previous response and reuses the AutoSys session
        JsonNode deps = api("/api/v1/jobs/TEST_FX_LOADER/dependencies", token, 200);
        assertThat(deps.at("/condition").asString()).isEqualTo("s(TEST_FEED_SYNC,10.00)");
        assertThat(deps.at("/conditions/0/condition").asString()).isEqualTo("TEST_FEED_SYNC");
        assertThat(deps.at("/conditions/0/satisfied").asBoolean()).isTrue();
        assertThat(deps.at("/dependents/0/jobName").asString()).isEqualTo("TEST_CLEANUP");
        assertThat(deps.at("/dependents/1/satisfied").isNull()).isTrue();
        assertThat(api("/api/v1/jobs/TEST_FX_LOADER/status", token, 200).at("/status").asString())
                .isEqualTo("SUCCESS");

        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("javax.faces.ViewState=%21VS-1")));
        autosys.verify(2, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("javax.faces.ViewState=%21VS-2")));
        autosys.verify(postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("serverCombo_input=0&"))
                .withRequestBody(containing("source=goButtonID&state=&value=&event=autosub&partial=true")));
        // the browser's AutoSys session is used: no second login
        autosys.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
        assertThat(output.getAll()).contains("[backend] Quick View opened for 'carol' with the browser's AutoSys session")
                .doesNotContain("BACKEND_LOGIN")
                .doesNotContain(PASSWORD).doesNotContain("XSRF-1").doesNotContain(token);
    }

    @Test
    void unknownJobIs404AndInvalidNameIs400() throws Exception {
        String token = login();
        assertThat(api("/api/v1/jobs/NOPE", token, 404).at("/type").asString()).isEqualTo("/problems/job-not-found");
        assertThat(api("/api/v1/jobs/NOPE", token, 404).at("/detail").asString()).isEqualTo("No job named NOPE");
        assertThat(api("/api/v1/jobs/bad%20name", token, 400).at("/type").asString())
                .isEqualTo("/problems/invalid-job-name");
        assertThat(api("/api/v1/jobs?name=NOPE", token, 200).at("/jobs").size()).isZero();
        assertThat(api("/api/v1/jobs?name=bad'name", token, 400).at("/type").asString())
                .isEqualTo("/problems/invalid-job-name");
        JsonNode found = api("/api/v1/jobs?name=TEST_FX*", token, 200);
        assertThat(found.at("/jobs/0/name").asString()).isEqualTo("TEST_FX_LOADER");
        assertThat(found.at("/jobs/0/status").asString()).isEqualTo("SUCCESS");
    }

    @Test
    void expiredAutosysSessionLogsInAgainAndRetries(CapturedOutput output) throws Exception {
        String token = login();
        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");

        // AutoSys forgets the session: the next search gets the login page instead of a partial response
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(1).inScenario("expiry")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("expired")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html><form id=\"login\"></form></html>")));

        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");
        // a fresh Quick View session from the same browser login; still no extra login
        autosys.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
        assertThat(output.getAll()).contains("is no longer valid (not a partial-page response (text/html))");
    }

    @Test
    void usesTheQuickViewPageTheBrowserHasOpen(CapturedOutput output) throws Exception {
        String browserPage = "/wcc/quickview/pages/main.jsf?AntiXSRFToken=XSRF-B&conversationContext=1";
        autosys.stubFor(post(urlEqualTo(browserPage)).withHeader("Cookie", containing("JSESSIONID=js-browser"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("job.xml"))));
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/index.jsp")).atPriority(1)
                .willReturn(aResponse().withStatus(403).withBody("error 403")));
        String token = login();
        browser.cookies.put("WCC-ASID", "asid-js");
        browser.cookies.put("JSESSIONID", "js-browser");

        // the user searches once in the Quick View tab
        HttpResponse<String> search = browser.post(browserPage, "application/x-www-form-urlencoded",
                "AntiXSRFToken=XSRF-B&conversationContext=1&serverCombo_input=0&jobNameInput=OTHER_JOB"
                        + "&jobNameInput%3A%3Ahidden=false&goButtonID=&org.apache.myfaces.trinidad.faces.FORM=jobDetailsForm"
                        + "&_noJavaScript=false&javax.faces.ViewState=%21VS-B&source=goButtonID&event=autosub&partial=true",
                "Tr-XHR-Message", "true");
        assertThat(search.statusCode()).isEqualTo(200);

        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");
        autosys.verify(postRequestedFor(urlEqualTo(browserPage))
                .withHeader("Cookie", equalTo("WCC-ASID=asid-js; JSESSIONID=js-browser"))
                .withRequestBody(containing("jobNameInput=TEST_FX_LOADER&"))
                .withRequestBody(containing("javax.faces.ViewState=%21VS-B")));
        autosys.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/wcc/quickview/index.jsp")));
        assertThat(output.getAll()).contains("API calls of 'carol' will use the browser's Quick View page")
                .contains("using the browser's Quick View page for 'carol'").doesNotContain("XSRF-B")
                .doesNotContain("js-browser");
    }

    @Test
    void usesTheQuickViewPageTheBrowserHasLoaded(CapturedOutput output) throws Exception {
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/pages/main.jsf")).atPriority(1)
                .withHeader("Cookie", containing("JSESSIONID=js-browser"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withHeader("Set-Cookie", "wccQv=qv-1; Path=/wcc/quickview")
                        .withBody(resource("main.html"))));
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(1).withHeader("Cookie", containing("JSESSIONID=js-browser"))
                .withRequestBody(containing("jobNameInput=TEST_FX_LOADER&"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("job.xml"))));
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/index.jsp")).atPriority(1)
                .willReturn(aResponse().withStatus(403).withBody("error 403")));
        String token = login();
        browser.cookies.put("WCC-ASID", "asid-js");
        browser.cookies.put("JSESSIONID", "js-browser");

        // the user only opens Quick View, without searching
        browser.get("/ui/Login.html");
        assertThat(browser.get("/wcc/quickview/index.jsp?appToken=T-1", "Referer",
                "http://localhost:" + port + "/wcc/ui/Launcher.html?x=secret-ref").statusCode()).isEqualTo(403);
        HttpResponse<String> page = browser.get("/wcc/quickview/pages/main.jsf?AntiXSRFToken=XSRF-1&OWASP-CSRFTOKEN=C-1");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("jobDetailsForm");

        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");
        autosys.verify(postRequestedFor(urlEqualTo(SEARCH))
                .withHeader("Cookie", equalTo("WCC-ASID=asid-js; JSESSIONID=js-browser; wccQv=qv-1"))
                .withRequestBody(containing("javax.faces.ViewState=%21VS-1"))
                .withRequestBody(containing("org.apache.myfaces.trinidad.faces.FORM=jobDetailsForm")));
        autosys.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/wcc/quickview/index.jsp")));
        assertThat(output.getAll()).contains("API calls of 'carol' will use the Quick View page the browser opened")
                .containsPattern("browser of 'carol' opens Quick View: GET /wcc/quickview/index.jsp query \\[appToken\\] "
                        + "headers \\[.*\\] cookies \\[[^\\]]*JSESSIONID[^\\]]*\\] referer /wcc/ui/Launcher.html\\?x -> status 403 "
                        + "location null sets cookies \\[\\]; its requests before: \\[.*GET /ui/Login.html\\]")
                .doesNotContain("T-1").doesNotContain("secret-ref").doesNotContain("XSRF-1").doesNotContain("js-browser");
    }

    @Test
    void opensQuickViewWithTheLaunchersToken(CapturedOutput output) throws Exception {
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/index.jsp")).atPriority(1)
                .willReturn(aResponse().withStatus(403).withBody("error 403")));
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/index.jsp")).atPriority(1)
                .withQueryParam("AntiXSRFToken", equalTo("L-1+/="))
                .withQueryParam("randomId", com.github.tomakehurst.wiremock.client.WireMock.matching("[0-9]+"))
                .withHeader("Referer", containing("/wcc/ui/Launcher.html"))
                .withHeader("Cookie", containing("WCC-ASID=asid-1"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "/wcc/quickview/pages/main.jsf?AntiXSRFToken=XSRF-1&OWASP-CSRFTOKEN=C-1")
                        .withHeader("Set-Cookie", "JSESSIONID=js-1; Path=/wcc/quickview; HttpOnly")));
        String token = login();
        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 502).at("/type").asString()).endsWith("autosys-quickview");

        // the launcher passes its token on some request the browser makes (here a query parameter)
        browser.get("/ui/Login.html?AntiXSRFToken=L-1%2B%2F%3D");
        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");
        assertThat(output.getAll()).contains("no AntiXSRFToken seen from the browser of 'carol' yet")
                .contains("browser of 'carol' sends AntiXSRFToken (query of GET /ui/Login.html)")
                .doesNotContain("L-1");
    }

    @Test
    void usesCookiesTheUiSetInJavaScript(CapturedOutput output) throws Exception {
        autosys.stubFor(post("/authentication/login").atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"asid\":\"asid-1\"}")));
        autosys.stubFor(get("/ui/Home.html").willReturn(aResponse().withStatus(200).withBody("<html></html>")));
        String token = login();
        assertThat(browser.cookies).containsKey("ASP_SID").doesNotContainKey("WCC-ASID");
        // the UI's JavaScript sets the session cookie, then the browser loads a page through the proxy
        browser.cookies.put("WCC-ASID", "asid-1");
        browser.cookies.put("JSESSIONID", "browser-page-state");
        browser.cookies.put("wccDashboard81900", "{\"tabs\":[1,2],\"x\":\"a b\"}");
        browser.get("/ui/Home.html");
        assertThat(api("/api/v1/session", token, 200).at("/browserCookies").toString())
                .isEqualTo("[\"JSESSIONID\",\"WCC-ASID\",\"wccDashboard81900\"]");

        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");
        autosys.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
        // own cookies never reach AutoSys; the browser's JSESSIONID is not reused for API calls
        autosys.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/ui/Home.html"))
                .withHeader("Cookie", com.github.tomakehurst.wiremock.client.WireMock.notContaining("ASP_")));
        autosys.verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                urlPathEqualTo("/wcc/quickview/index.jsp"))
                .withHeader("Cookie", containing("browser-page-state")));
        assertThat(output.getAll()).contains("with the browser's AutoSys session").doesNotContain("asid-1")
                .contains("browser of 'carol' sends AutoSys cookies [JSESSIONID, WCC-ASID, wccDashboard81900]");
    }

    @Test
    void ownLoginWhenTheBrowserSessionDoesNotOpenQuickView(CapturedOutput output) throws Exception {
        stubAutosys("asid-2");
        String token = login();

        assertThat(api("/api/v1/jobs/TEST_FX_LOADER", token, 200).at("/status/status").asString())
                .isEqualTo("SUCCESS");
        autosys.verify(2, postRequestedFor(urlEqualTo("/authentication/login")));
        assertThat(output.getAll())
                .contains("the browser's AutoSys session of 'carol' did not open Quick View (ended at /ui/Login.html")
                .contains("BACKEND_LOGIN", "Quick View opened for 'carol' with the proxy's own login");
    }

    @Test
    void rejectedOwnLoginIs502AndLogged(CapturedOutput output) throws Exception {
        stubAutosys("asid-2");
        String token = login();
        autosys.stubFor(post("/authentication/login").atPriority(1)
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "text/html")
                        .withBody("<html>CSRF token missing</html>")));

        JsonNode problem = api("/api/v1/jobs/TEST_FX_LOADER", token, 502);
        assertThat(problem.at("/type").asString()).isEqualTo("/problems/autosys-login");
        assertThat(problem.at("/detail").asString()).contains("status 403 not in [200]");
        assertThat(output.getAll()).contains("response 403 text/html", "body: <html>CSRF token missing</html>",
                "sent headers [Accept, X-Requested-With, Origin, Referer")
                .doesNotContain(PASSWORD);

        // the stored password is not tried again, so the account cannot be locked
        JsonNode again = api("/api/v1/jobs/TEST_FX_LOADER", token, 409);
        assertThat(again.at("/type").asString()).isEqualTo("/problems/autosys-login-rejected");
        autosys.verify(2, postRequestedFor(urlEqualTo("/authentication/login")));
    }

    @Test
    void boxListsItsJobs() throws Exception {
        String token = login();
        JsonNode box = api("/api/v1/jobs/TEST_BOX", token, 200);
        assertThat(box.at("/type").asString()).isEqualTo("BOX");
        assertThat(box.at("/jobType").asString()).isEqualTo("BOX");
        assertThat(box.at("/box").isNull()).isTrue();
        assertThat(box.at("/status/name").asString()).isEqualTo("TEST_BOX");
        assertThat(box.at("/status/status").asString()).isEqualTo("SUCCESS");
        assertThat(box.at("/dependents/0/satisfied").asBoolean()).isFalse();
        assertThat(box.at("/jobs").size()).isEqualTo(2);
        assertThat(box.at("/jobs/0/name").asString()).isEqualTo("TEST_STOP_A");
        assertThat(box.at("/jobs/0/type").asString()).isEqualTo("REGULAR");
        assertThat(box.at("/jobs/0/box").asString()).isEqualTo("TEST_BOX");
        // shortened in the tree; the full name comes from the link
        assertThat(box.at("/jobs/1/name").asString()).isEqualTo("TEST_SUB_BOX_WITH_A_LONG_NAME");
        assertThat(box.at("/jobs/1/type").asString()).isEqualTo("BOX");
        assertThat(box.at("/jobs/1/status").asString()).isEqualTo("FAILURE");

        JsonNode jobs = api("/api/v1/jobs/TEST_BOX/jobs", token, 200);
        assertThat(jobs.at("/name").asString()).isEqualTo("TEST_BOX");
        assertThat(jobs.at("/jobs/1/exitCode").asString()).isEqualTo("1");
        assertThat(api("/api/v1/jobs/TEST_FX_LOADER/jobs", token, 409).at("/detail").asString())
                .contains("not a box job").contains("CMD");
    }

    static final String DIALOG = "/wcc/quickview/pages/dialog/confirmationDialog.jsf";

    /** Quick View's force start: choosing the event, Go (opens the dialog), the dialog, its Yes. */
    static void stubEvents(String goResponse, int answerStatus) throws IOException {
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(1)
                .withRequestBody(containing("source=jobResultTable%3AeventId&"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("event-chosen.xml"))));
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(1)
                .withRequestBody(containing("source=jobResultTable%3AsendEventButton&"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource(goResponse))));
        autosys.stubFor(get(urlPathEqualTo(DIALOG))
                .withQueryParam("OWASP-CSRFTOKEN", equalTo("C-1"))
                .withHeader("Cookie", containing("JSESSIONID=js-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(resource("dialog.html"))));
        autosys.stubFor(post(urlPathEqualTo(DIALOG))
                .willReturn(aResponse().withStatus(answerStatus).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(answerStatus == 200 ? resource("dialog-closed.html") : "<html>Error</html>")));
    }

    @Test
    void forceStartChoosesTheEventPressesGoAndConfirms(CapturedOutput output) throws Exception {
        stubEvents("event-go.xml", 200);
        String token = login();

        HttpResponse<String> response = action("TEST_FX_LOADER", token,
                "{\"action\":\"FORCE_STARTJOB\",\"comment\":\"rerun after fix\"}", "key-1");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode result = JsonMapper.shared().readTree(response.body());
        assertThat(result.at("/job").asString()).isEqualTo("TEST_FX_LOADER");
        assertThat(result.at("/action").asString()).isEqualTo("FORCE_STARTJOB");
        assertThat(result.at("/outcome").asString()).isEqualTo("ACCEPTED");
        assertThat(result.at("/event").asString()).isEqualTo("Force Start Job");
        assertThat(result.at("/confirmation").asString())
                .isEqualTo("Are you sure you want to send the event FORCE_STARTJOB for the job TEST_FX_LOADER? Yes No");
        assertThat(result.at("/statusAfter").asString()).isEqualTo("SUCCESS");
        assertThat(result.at("/requestId").asString()).isNotBlank();

        // the dropdown value of the option labelled "Force Start Job", then Go with it, on the latest ViewState
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("jobNameInput=TEST_FX_LOADER&"))
                .withRequestBody(containing("jobResultTable%3AeventId=1&"))
                .withRequestBody(containing("javax.faces.ViewState=%21VS-2"))
                .withRequestBody(containing("source=jobResultTable%3AeventId&partialTargets=&partial=true&state=&value="
                        + "&event=autosub")));
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("jobResultTable%3AeventId=1&"))
                .withRequestBody(containing("javax.faces.ViewState=%21VS-3"))
                .withRequestBody(containing("source=jobResultTable%3AsendEventButton&partialTargets=&partial=true"
                        + "&event=autosub"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("state=")));
        // the dialog with the page's tokens, then its Yes
        autosys.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(DIALOG
                + "?_afPfm=pf-1&AntiXSRFToken=XSRF-1&OWASP-CSRFTOKEN=C-1&conversationContext=1")));
        autosys.verify(1, postRequestedFor(urlEqualTo(DIALOG
                + "?_afPfm=pf-1&AntiXSRFToken=XSRF-1&OWASP-CSRFTOKEN=C-1&conversationContext=1"))
                .withHeader("Referer", containing(DIALOG + "?_afPfm=pf-1"))
                .withRequestBody(equalTo("org.apache.myfaces.trinidad.faces.FORM=confirmationDialog"
                        + "&_noJavaScript=false&javax.faces.ViewState=%21DVS-1&source=sendEvtYes")));

        // the same Idempotency-Key gets the same answer without sending the event again
        HttpResponse<String> again = action("TEST_FX_LOADER", token, "{\"action\":\"FORCE_STARTJOB\"}", "key-1");
        assertThat(again.body()).isEqualTo(response.body());
        autosys.verify(1, postRequestedFor(urlPathEqualTo(DIALOG)));
        assertThat(action("TEST_FX_LOADER", token, "{\"action\":\"STARTJOB\"}", "key-1").statusCode())
                .isEqualTo(422);

        assertThat(output.getAll())
                .containsPattern("\"event\":\"JOB_ACTION\",\"user\":\"carol\",.*\"job\":\"TEST_FX_LOADER\","
                        + "\"action\":\"FORCE_STARTJOB\",\"outcome\":\"ACCEPTED\",.*\"comment\":\"rerun after fix\"")
                .contains("replayed for its Idempotency-Key")
                .doesNotContain("XSRF-1").doesNotContain("C-1").doesNotContain("DVS-1").doesNotContain(token);
    }

    @Test
    void unclearAnswerIsUnknownAndNotRetried() throws Exception {
        stubEvents("event-go.xml", 500);
        String token = login();

        HttpResponse<String> response = action("TEST_FX_LOADER", token, "{\"action\":\"force_startjob\"}", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(502);
        JsonNode problem = JsonMapper.shared().readTree(response.body());
        assertThat(problem.at("/type").asString()).isEqualTo("/problems/autosys-action-unknown");
        assertThat(problem.at("/outcome").asString()).isEqualTo("UNKNOWN");
        assertThat(problem.at("/detail").asString()).contains("check the job's status before trying again");
        autosys.verify(1, postRequestedFor(urlPathEqualTo(DIALOG)));
    }

    @Test
    void refusedOrNotOfferedEventsAreRejectedAndNothingIsConfirmed() throws Exception {
        stubEvents("event-refused.xml", 200);
        String token = login();

        // the dropdown has no Kill: nothing is sent
        HttpResponse<String> kill = action("TEST_FX_LOADER", token, "{\"action\":\"KILLJOB\"}", null);
        assertThat(kill.statusCode()).as(kill.body()).isEqualTo(409);
        JsonNode problem = JsonMapper.shared().readTree(kill.body());
        assertThat(problem.at("/type").asString()).isEqualTo("/problems/autosys-action-rejected");
        assertThat(problem.at("/outcome").asString()).isEqualTo("REJECTED");
        assertThat(problem.at("/autosysMessage").asString())
                .isEqualTo("Quick View offers no Kill for job TEST_FX_LOADER (it offers Start Job, Force Start Job, "
                        + "On Hold, Off Hold, Send Event)");
        autosys.verify(0, postRequestedFor(urlEqualTo(SEARCH)).withRequestBody(containing("eventId")));

        // without the dropdown's options the configured value is sent; AutoSys answers Go with an error
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(2).withRequestBody(containing("source=goButtonID&"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("job.xml").replaceAll("<select id=\"jobResultTable:eventId\".*?</select>", ""))));
        HttpResponse<String> force = action("TEST_FX_LOADER", token, "{\"action\":\"FORCE_STARTJOB\"}", null);
        assertThat(force.statusCode()).as(force.body()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(force.body()).at("/autosysMessage").asString())
                .isEqualTo("You are not authorized to send this event.");
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("jobResultTable%3AeventId=1&"))
                .withRequestBody(containing("source=jobResultTable%3AsendEventButton&")));
        autosys.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(DIALOG)));
        autosys.verify(0, postRequestedFor(urlPathEqualTo(DIALOG)));

        assertThat(action("TEST_FX_LOADER", token, "{\"action\":\"DELETE_JOB\"}", null).statusCode()).isEqualTo(400);
        assertThat(action("NOPE", token, "{\"action\":\"FORCE_STARTJOB\"}", null).statusCode()).isEqualTo(404);
    }

    static final String SEND_DIALOG = "/wcc/quickview/pages/dialog/autosysSendEvent.jsf";

    /** Send Event: Go opens the Send Event dialog; choosing an event adds its status dropdown; OK; the return. */
    static void stubSendEvent() throws IOException {
        stubEvents("send-event-go.xml", 200);
        autosys.stubFor(get(urlPathEqualTo(SEND_DIALOG))
                .withQueryParam("_afPfm", equalTo("pf-2"))
                .withHeader("Cookie", containing("JSESSIONID=js-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(resource("send-event-dialog.html"))));
        autosys.stubFor(post(urlPathEqualTo(SEND_DIALOG))
                .withHeader("Tr-XHR-Message", equalTo("true"))
                .withRequestBody(containing("source=eventId&partial=true"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("send-event-chosen.xml"))));
        autosys.stubFor(post(urlPathEqualTo(SEND_DIALOG))
                .withRequestBody(containing("source=j_id76"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(resource("dialog-closed.html"))));
        autosys.stubFor(post(urlEqualTo(SEARCH)).atPriority(1)
                .withRequestBody(containing("rtrn=jobResultTable%3AsendEventButton"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=utf-8")
                        .withBody(resource("send-event-return.xml"))));
    }

    @Test
    void changeStatusSendsTheEventThroughTheSendEventDialog(CapturedOutput output) throws Exception {
        stubSendEvent();
        String token = login();

        HttpResponse<String> response = action("TEST_FX_LOADER", token,
                "{\"action\":\"CHANGE_STATUS\",\"status\":\"inactive\",\"comment\":\"reset for rerun\"}", "key-cs");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode result = JsonMapper.shared().readTree(response.body());
        assertThat(result.at("/action").asString()).isEqualTo("CHANGE_STATUS");
        assertThat(result.at("/status").asString()).isEqualTo("INACTIVE");
        assertThat(result.at("/outcome").asString()).isEqualTo("ACCEPTED");
        assertThat(result.at("/event").asString()).isEqualTo("Send Event: Change Status INACTIVE");
        assertThat(result.at("/autosysMessage").asString()).contains("Event CHANGE_STATUS was sent for TEST_FX_LOADER.");

        // "Send Event" in the results' dropdown, then Go
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("jobResultTable%3AeventId=10&"))
                .withRequestBody(containing("source=jobResultTable%3AeventId&")));
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("jobResultTable%3AeventId=10&"))
                .withRequestBody(containing("source=jobResultTable%3AsendEventButton&")));
        // Change Status in the dialog's dropdown (a partial-page event on the dialog's ViewState)
        autosys.verify(1, postRequestedFor(urlPathEqualTo(SEND_DIALOG))
                .withQueryParam("OWASP-CSRFTOKEN", equalTo("C-1"))
                .withRequestBody(containing("eventId=11&"))
                .withRequestBody(containing("javax.faces.ViewState=%21SVS-1"))
                .withRequestBody(containing("event=autosub&source=eventId&partial=true")));
        // OK with the status, the comment and the ViewState of the partial response; no disabled fields
        autosys.verify(1, postRequestedFor(urlPathEqualTo(SEND_DIALOG))
                .withHeader("Referer", containing(SEND_DIALOG + "?_afPfm=pf-2"))
                .withRequestBody(containing("eventId=11&"))
                .withRequestBody(containing("statusId=4"))
                .withRequestBody(containing("j_id23=reset+for+rerun&"))
                .withRequestBody(containing("sendPriority=10&"))
                .withRequestBody(containing("javax.faces.ViewState=%21SVS-2"))
                .withRequestBody(containing("event=&source=j_id76"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("j_id59"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("partial=")));
        // back on the page, as the browser returns from the dialog
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH))
                .withRequestBody(containing("source=&partialTargets=&partial=true&rtrn=jobResultTable%3AsendEventButton")));

        // replayed for the same key, refused for the same key with another status
        assertThat(action("TEST_FX_LOADER", token, "{\"action\":\"CHANGE_STATUS\",\"status\":\"INACTIVE\"}", "key-cs")
                .body()).isEqualTo(response.body());
        assertThat(action("TEST_FX_LOADER", token, "{\"action\":\"CHANGE_STATUS\",\"status\":\"SUCCESS\"}", "key-cs")
                .statusCode()).isEqualTo(422);
        autosys.verify(1, postRequestedFor(urlPathEqualTo(SEND_DIALOG)).withRequestBody(containing("source=j_id76")));
        assertThat(output.getAll()).containsPattern("\"event\":\"JOB_ACTION\",.*\"action\":\"CHANGE_STATUS INACTIVE\","
                + "\"outcome\":\"ACCEPTED\"");
    }

    @Test
    void changeStatusNeedsAStatusTheDialogOffers() throws Exception {
        stubSendEvent();
        String token = login();

        assertThat(action("TEST_FX_LOADER", token, "{\"action\":\"CHANGE_STATUS\"}", null).statusCode()).isEqualTo(400);
        assertThat(action("TEST_FX_LOADER", token, "{\"action\":\"FORCE_STARTJOB\",\"status\":\"SUCCESS\"}", null)
                .statusCode()).isEqualTo(400);

        HttpResponse<String> unknown = action("TEST_FX_LOADER", token,
                "{\"action\":\"CHANGE_STATUS\",\"status\":\"ON_ICE\"}", null);
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(unknown.body()).at("/autosysMessage").asString())
                .isEqualTo("The Send Event dialog offers no status ON_ICE (it offers Running, Starting, Success, "
                        + "Failure, Inactive, Terminated)");
        autosys.verify(0, postRequestedFor(urlPathEqualTo(SEND_DIALOG)).withRequestBody(containing("source=j_id76")));
    }

    private HttpResponse<String> action(String job, String token, String body, @org.jspecify.annotations.Nullable String key)
            throws Exception {
        return key == null
                ? browser.post("/api/v1/jobs/" + job + "/actions", "application/json", body,
                        "Authorization", "Bearer " + token)
                : browser.post("/api/v1/jobs/" + job + "/actions", "application/json", body,
                        "Authorization", "Bearer " + token, "Idempotency-Key", key);
    }

    @Test
    void keepAliveRequestsAJobStatusUnlessTheSessionWasJustUsed() throws Exception {
        login();
        assertThat(keepAlive.runOnce()).isEqualTo(1);
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH)).withRequestBody(containing("jobNameInput=TEST_FX_LOADER&")));
        // the session just talked to AutoSys: nothing to keep alive yet
        assertThat(keepAlive.runOnce()).isZero();
        autosys.verify(1, postRequestedFor(urlEqualTo(SEARCH)));
    }

    @Test
    void apiNeedsToken() throws Exception {
        assertThat(browser.get("/api/v1/jobs/TEST_FX_LOADER").statusCode()).isEqualTo(401);
    }

    /** Browser-style XHR login through the proxy; returns the API token from the token page. */
    private String login() throws Exception {
        HttpResponse<String> login = browser.post("/authentication/login", "application/json",
                "{\"username\":\"carol\",\"password\":\"" + PASSWORD + "\",\"remember\":false}",
                "X-Requested-With", "XMLHttpRequest", "Accept", "application/json");
        assertThat(login.statusCode()).isEqualTo(200);
        Matcher m = ProxyLoginFlowTest.TOKEN.matcher(browser.get("/proxy/token").body());
        assertThat(m.find()).isTrue();
        return m.group();
    }

    private JsonNode api(String path, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = browser.get(path, "Authorization", "Bearer " + token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return JsonMapper.shared().readTree(response.body());
    }

    static String resource(String name) throws IOException {
        try (InputStream in = QuickViewApiTest.class.getResourceAsStream("/quickview/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
