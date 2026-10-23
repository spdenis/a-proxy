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
        registry.add("autosys.webui.login.success.cookie", () -> "WCC-ASID");
        registry.add("autosys.proxy.start-page", () -> "/ui/Login.html");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        autosys.resetAll();
        autosys.stubFor(post("/authentication/login")
                .withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
                .withRequestBody(matchingJsonPath("$.password", equalTo(PASSWORD)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("Set-Cookie", "WCC-ASID=asid-1; Path=/; HttpOnly").withBody("{\"ok\":true}")));
        autosys.stubFor(get("/wcc/quickview/index.jsp").atPriority(9)
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/ui/Login.html")));
        autosys.stubFor(get("/wcc/quickview/index.jsp").atPriority(1)
                .withHeader("Cookie", containing("WCC-ASID=asid-1"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "/wcc/quickview/pages/main.jsf?AntiXSRFToken=XSRF-1&OWASP-CSRFTOKEN=C-1")
                        .withHeader("Set-Cookie", "JSESSIONID=js-1; Path=/wcc/quickview; HttpOnly")));
        autosys.stubFor(get("/ui/Login.html").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html><form id=\"login\"></form></html>")));
        autosys.stubFor(get(urlPathEqualTo("/wcc/quickview/pages/main.jsf"))
                .withHeader("Cookie", containing("JSESSIONID=js-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(resource("main.html"))));
        stubSearch("TEST_FX_LOADER", "job.xml");
        stubSearch("NOPE", "not-found.xml");
        stubSearch("TEST_FX*", "job.xml");
    }

    static void stubSearch(String job, String file) throws IOException {
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
        // browser login + one proxy login
        autosys.verify(2, postRequestedFor(urlEqualTo("/authentication/login")));
        assertThat(output.getAll()).contains("BACKEND_LOGIN", "[backend] Quick View opened for 'carol'")
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
        autosys.verify(3, postRequestedFor(urlEqualTo("/authentication/login")));
        assertThat(output.getAll()).contains("is no longer valid (not a partial-page response (text/html))");
    }

    @Test
    void rejectedBackendLoginIs502() throws Exception {
        String token = login();
        autosys.stubFor(post("/authentication/login").atPriority(1)
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"locked\"}")));

        JsonNode problem = api("/api/v1/jobs/TEST_FX_LOADER", token, 502);
        assertThat(problem.at("/type").asString()).isEqualTo("/problems/autosys-login");
        assertThat(problem.at("/detail").asString()).contains("status 401 not in [200]");
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
