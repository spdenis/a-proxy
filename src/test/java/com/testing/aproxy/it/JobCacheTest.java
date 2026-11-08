package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;

import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Job reads through the API are cached; an action on the job or on a job in its condition drops them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JobCacheTest {

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
        registry.add("autosys.job-cache.enabled", () -> "true");
    }

    static final RequestPatternBuilder LOADER_SEARCH = postRequestedFor(urlEqualTo(QuickViewApiTest.SEARCH))
            .withRequestBody(containing("jobNameInput=TEST_FX_LOADER&"))
            .withRequestBody(containing("source=goButtonID&"));

    @LocalServerPort
    int port;

    BrowserClient browser;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        QuickViewApiTest.stubAutosys(autosys, "asid-1");
        token = login();
    }

    @Test
    void readsOfTheSameJobShareOneSearchUntilFreshIsAskedFor() throws Exception {
        HttpResponse<String> first = get("/api/v1/jobs/TEST_FX_LOADER/status");
        HttpResponse<String> deps = get("/api/v1/jobs/TEST_FX_LOADER/dependencies");
        HttpResponse<String> runs = get("/api/v1/jobs/TEST_FX_LOADER/runs");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("X-Cache")).hasValue("MISS");
        assertThat(deps.statusCode()).isEqualTo(200);
        assertThat(deps.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(deps.headers().firstValue("Age")).isPresent();
        assertThat(runs.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(deps.body()).contains("TEST_FEED_SYNC");
        autosys.verify(1, LOADER_SEARCH);

        HttpResponse<String> fresh = get("/api/v1/jobs/TEST_FX_LOADER/status?fresh=true");
        assertThat(fresh.headers().firstValue("X-Cache")).hasValue("MISS");
        autosys.verify(2, LOADER_SEARCH);
    }

    @Test
    void anActionOnTheJobDropsItsCachedReads() throws Exception {
        QuickViewApiTest.stubEvents(autosys, "event-go.xml", 200);
        get("/api/v1/jobs/TEST_FX_LOADER/status");
        assertThat(action("TEST_FX_LOADER").statusCode()).isEqualTo(200);
        int before = autosys.findAll(LOADER_SEARCH).size();

        HttpResponse<String> after = get("/api/v1/jobs/TEST_FX_LOADER/status");

        assertThat(after.headers().firstValue("X-Cache")).hasValue("MISS");
        autosys.verify(before + 1, LOADER_SEARCH);
    }

    @Test
    void anActionOnAJobInTheConditionDropsTheCachedReads() throws Exception {
        get("/api/v1/jobs/TEST_FX_LOADER/status");
        // TEST_FX_LOADER waits for s(TEST_FEED_SYNC): even an action that was not sent drops it, to be safe
        action("TEST_FEED_SYNC");

        assertThat(get("/api/v1/jobs/TEST_FX_LOADER/status").headers().firstValue("X-Cache")).hasValue("MISS");
        autosys.verify(2, LOADER_SEARCH);
    }

    private HttpResponse<String> action(String job) throws Exception {
        return browser.post("/api/v1/jobs/" + job + "/actions", "application/json",
                "{\"action\":\"FORCE_STARTJOB\",\"comment\":\"test\"}", "Authorization", "Bearer " + token);
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
