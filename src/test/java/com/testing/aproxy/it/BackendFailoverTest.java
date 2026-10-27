package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.http.HttpResponse;
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

/**
 * Several AutoSys URLs ({@code autosys.backend-urls}): the proxy's own login skips one that is
 * unreachable, and a session AutoSys drops logs in again on the next URL. The fake AutoSys is one
 * server reached under two names (localhost and 127.0.0.1); the third URL has nothing listening.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class BackendFailoverTest {

    @RegisterExtension
    static WireMockExtension autosys = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String DOWN = "http://127.0.0.1:" + freePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("autosys.base-url", autosys::baseUrl);
        registry.add("autosys.backend-urls", () -> DOWN + "," + autosys.baseUrl() + ",http://127.0.0.1:"
                + autosys.getPort());
        registry.add("autosys.webui.login.path", () -> "/authentication/login");
        registry.add("autosys.webui.login.username-field", () -> "username");
        registry.add("autosys.webui.login.password-field", () -> "password");
        registry.add("autosys.webui.login.success.statuses", () -> "200");
        registry.add("autosys.proxy.start-page", () -> "/ui/Login.html");
        registry.add("autosys.http.connect-timeout", () -> "1s");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        // Quick View opens only with the cookie of the proxy's own login, not the browser's
        QuickViewApiTest.stubAutosys(autosys, "asid-2");
    }

    @Test
    void skipsAnUnreachableUrlAndMovesOnWhenAutosysDropsTheSession(CapturedOutput output) throws Exception {
        String token = login();

        assertThat(status(token).statusCode()).isEqualTo(200);
        assertThat(output.getAll()).contains("AutoSys at " + DOWN + " is not usable for 'carol'",
                "logged in to AutoSys at " + autosys.baseUrl() + " as 'carol'");

        // AutoSys drops the session once: the next login goes to the next URL, with the same token
        autosys.stubFor(post(urlEqualTo(QuickViewApiTest.SEARCH)).atPriority(1).inScenario("drop")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("dropped")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html><form id=\"login\"></form></html>")));
        assertThat(status(token).statusCode()).isEqualTo(200);

        String next = "http://127.0.0.1:" + autosys.getPort();
        assertThat(output.getAll()).contains("logging in again on " + next,
                "logged in to AutoSys at " + next + " as 'carol'");
        autosys.verify(1, postRequestedFor(urlEqualTo("/authentication/login"))
                .withHeader("Host", containing("127.0.0.1")));
    }

    @Test
    void theBrowsersSessionIsUsedFirstWhateverTheUrlList(CapturedOutput output) throws Exception {
        QuickViewApiTest.stubAutosys(autosys, "asid-1");
        String token = login();

        assertThat(status(token).statusCode()).isEqualTo(200);
        // only the browser's login: the proxy did not log in itself on any of the URLs
        autosys.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
        assertThat(output.getAll()).contains("Quick View opened for 'carol' with the browser's AutoSys session")
                .doesNotContain("is not usable for 'carol'");
    }

    @Test
    void aServerThatAnswersWithErrorsIsSkippedAndTheTurnGoesRoundToTheFirstUrls(CapturedOutput output)
            throws Exception {
        String token = login();
        assertThat(status(token).statusCode()).isEqualTo(200);
        String second = autosys.baseUrl();
        String third = "http://127.0.0.1:" + autosys.getPort();

        // the session on the second URL is dropped: on to the third
        autosys.stubFor(post(urlEqualTo(QuickViewApiTest.SEARCH)).atPriority(1).inScenario("drop")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("dropped")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html><form id=\"login\"></form></html>")));
        assertThat(status(token).statusCode()).isEqualTo(200);
        assertThat(output.getAll()).contains("logged in to AutoSys at " + third + " as 'carol'");

        // the third drops it too, while the second is up but answers logins with 503: round to the first
        // (unreachable), the second (503), the third again
        autosys.stubFor(post(urlEqualTo(QuickViewApiTest.SEARCH)).atPriority(1).inScenario("drop")
                .whenScenarioStateIs("dropped").willSetStateTo("dropped twice")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html><form id=\"login\"></form></html>")));
        autosys.stubFor(post(urlEqualTo("/authentication/login")).atPriority(1)
                .withHeader("Host", containing("localhost"))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));
        assertThat(status(token).statusCode()).isEqualTo(200);

        assertThat(output.getAll())
                .contains("AutoSys at " + DOWN + " is not usable for 'carol'")
                .contains("AutoSys at " + second + " is not usable for 'carol' (AutoSys at " + second
                        + " answered the login with status 503)")
                .doesNotContain("autosys-login-rejected", "AutoSys rejected the proxy's login");
        autosys.verify(2, postRequestedFor(urlEqualTo("/authentication/login"))
                .withHeader("Host", containing("127.0.0.1")));
        // a 503 is not a wrong password: the proxy keeps logging in
        assertThat(status(token).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> status(String token) throws Exception {
        return browser.get("/api/v1/jobs/TEST_FX_LOADER/status", "Authorization", "Bearer " + token);
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

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
