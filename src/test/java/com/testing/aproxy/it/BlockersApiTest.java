package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/v1/blockers and /api/v1/jobs/{name}/blockers against the fake Quick View. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BlockersApiTest {

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
        registry.add("autosys.time-zone", () -> "UTC");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        QuickViewApiTest.stubAutosys(autosys, "asid-1");
        // TEST_FX_LOADER: s(TEST_FEED_SYNC,10.00), in box TEST_PMC_BOX; neither is in this Quick View
        QuickViewApiTest.stubSearch(autosys, "TEST_FEED_SYNC", "not-found.xml");
        QuickViewApiTest.stubSearch(autosys, "TEST_PMC_BOX", "not-found.xml");
        token = login();
    }

    @Test
    void listsTheUnreadyDependenciesOfAJob() throws Exception {
        HttpResponse<String> response = get("/api/v1/blockers?jobs=TEST_FX_LOADER&mode=all");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode report = JsonMapper.shared().readTree(response.body());
        assertThat(report.at("/mode").asString()).isEqualTo("all");
        assertThat(report.at("/targets/0/name").asString()).isEqualTo("TEST_FX_LOADER");
        assertThat(report.at("/targets/0/condition").asString()).isEqualTo("s(TEST_FEED_SYNC,10.00)");
        assertThat(report.at("/targets/0/blocked").asBoolean()).isTrue();
        // satisfied in Quick View, but with a look-back to check: its job is looked up, and is not there
        assertThat(report.at("/blockers/0/name").asString()).isEqualTo("TEST_FEED_SYNC");
        assertThat(report.at("/blockers/0/kind").asString()).isEqualTo("UNKNOWN");
        assertThat(report.at("/blockers/0/root").asBoolean()).isTrue();
        assertThat(report.at("/blockers/0/blocks/0/job").asString()).isEqualTo("TEST_FX_LOADER");
        assertThat(report.at("/blockers/0/blocks/0/condition").asString()).isEqualTo("s(TEST_FEED_SYNC,10.00)");
        assertThat(report.at("/blockers/0/blocks/0/reason").asString()).isEqualTo("no such job in Quick View");
        assertThat(report.at("/blockers/1/name").asString()).isEqualTo("TEST_PMC_BOX");
        assertThat(report.at("/blockers/1/blocks/0/condition").asString()).isEqualTo("box");
        assertThat(report.at("/truncated").asBoolean()).isFalse();

        HttpResponse<String> single = get("/api/v1/jobs/TEST_FX_LOADER/blockers?mode=root");
        assertThat(single.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.shared().readTree(single.body()).at("/mode").asString()).isEqualTo("root");
    }

    @Test
    void badRequestsAreRefused() throws Exception {
        assertThat(get("/api/v1/blockers?jobs=TEST_FX_LOADER&mode=deep").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/blockers?jobs=").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/blockers?jobs=bad%20name").statusCode()).isEqualTo(400);
        HttpResponse<String> unknown = get("/api/v1/blockers?jobs=NOPE");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("job-not-found");
        assertThat(browser.get("/api/v1/blockers?jobs=TEST_FX_LOADER").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.get(path, "Authorization", "Bearer " + token);
    }

    private String login() throws Exception {
        HttpResponse<String> login = browser.post("/authentication/login", "application/json",
                "{\"username\":\"carol\",\"password\":\"" + QuickViewApiTest.PASSWORD + "\",\"remember\":false}",
                "X-Requested-With", "XMLHttpRequest", "Accept", "application/json");
        assertThat(login.statusCode()).isEqualTo(200);
        Matcher m = ProxyLoginFlowTest.TOKEN.matcher(browser.get("/proxy/token").body());
        assertThat(m.find()).isTrue();
        return m.group();
    }
}
