package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * API calls when the AutoSys server in use goes down: the proxy logs in on the next server with the
 * stored login and goes on there, with the same token. The browser is moved to that server too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class ApiFailoverTest {

    @RegisterExtension
    static WireMockExtension first = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension second = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("autosys.base-url", first::baseUrl);
        registry.add("autosys.backend-urls", () -> first.baseUrl() + "," + second.baseUrl());
        registry.add("autosys.webui.login.path", () -> "/authentication/login");
        registry.add("autosys.webui.login.username-field", () -> "username");
        registry.add("autosys.webui.login.password-field", () -> "password");
        registry.add("autosys.webui.login.success.statuses", () -> "200");
        registry.add("autosys.proxy.start-page", () -> "/ui/Login.html");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        // the second server's sessions differ from the first's: the browser's cookies from the first do not
        // work there, only the proxy's own login on it does
        QuickViewApiTest.stubAutosys(second, "asid-9");
        second.stubFor(post("/authentication/login").atPriority(1)
                .withRequestBody(matchingJsonPath("$.password", equalTo(QuickViewApiTest.PASSWORD)))
                .willReturn(QuickViewApiTest.loginOk("asid-9")));
    }

    @Test
    void theBrowsersSessionGoesDownAndTheApiGoesOnOnTheNextServerWithTheSameToken(CapturedOutput output)
            throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-1"); // the API uses the browser's session on the first
        String token = login();
        assertThat(status(token).statusCode()).isEqualTo(200);
        second.verify(0, postRequestedFor(urlEqualTo("/authentication/login")));

        down(first);
        HttpResponse<String> status = status(token);

        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("TEST_FX_LOADER");
        assertThat(output.getAll()).contains("AutoSys at " + first.baseUrl() + " is not reachable for 'carol'",
                "[failover] AutoSys at " + first.baseUrl() + " is not usable",
                "logged in to AutoSys at " + second.baseUrl() + " as 'carol'");
        // and stays there
        assertThat(status(token).statusCode()).isEqualTo(200);
        second.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));

        // the browser was moved to the second server as well
        second.stubFor(get("/ui/page").willReturn(aResponse().withStatus(200).withBody("second")));
        assertThat(browser.get("/ui/page").body()).isEqualTo("second");
    }

    @Test
    void theProxysOwnSessionGoesDownAndTheApiGoesOnOnTheNextServerWithTheSameToken(CapturedOutput output)
            throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-2"); // only the proxy's own login opens Quick View there
        String token = login();
        assertThat(status(token).statusCode()).isEqualTo(200);
        assertThat(output.getAll()).contains("logged in to AutoSys at " + first.baseUrl() + " as 'carol'");

        down(first);

        assertThat(status(token).statusCode()).isEqualTo(200);
        assertThat(output.getAll()).contains("logged in to AutoSys at " + second.baseUrl() + " as 'carol'");
    }

    @Test
    void whileEveryServerIsDownCallsFailButTheTokenStaysValid() throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-1");
        String token = login();
        assertThat(status(token).statusCode()).isEqualTo(200);

        down(first);
        down(second);
        HttpResponse<String> failed = status(token);
        assertThat(failed.statusCode()).isEqualTo(502);

        // the second server comes back: the same token works, no new login through the browser
        QuickViewApiTest.stubAutosys(second, "asid-9");
        second.stubFor(post("/authentication/login").atPriority(1)
                .withRequestBody(matchingJsonPath("$.password", equalTo(QuickViewApiTest.PASSWORD)))
                .willReturn(QuickViewApiTest.loginOk("asid-9")));
        assertThat(status(token).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/v1/session", "Authorization", "Bearer " + token).statusCode()).isEqualTo(200);
    }

    @Test
    void aServerGoingDownAfterTheSearchStartsTheCallAgainOnTheNextServer(CapturedOutput output) throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-1");
        String token = login();
        // the box is found on the first server, but the connection is reset when it is expanded
        first.stubFor(post(urlEqualTo(QuickViewApiTest.SEARCH)).atPriority(0)
                .withRequestBody(containing("event=show&path=0"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> box = browser.get("/api/v1/jobs/TEST_BOX/jobs", "Authorization", "Bearer " + token);

        assertThat(box.statusCode()).isEqualTo(200);
        assertThat(box.body()).contains("TEST_STOP_A");
        assertThat(output.getAll()).contains("AutoSys at " + first.baseUrl() + " went down during expanding box "
                + "TEST_BOX for 'carol'", "starting again on " + second.baseUrl(),
                "logged in to AutoSys at " + second.baseUrl() + " as 'carol'");
    }

    @Test
    void aServerThatDropsTheSessionAndRefusesTheStoredLoginIsLeftForTheNextOne(CapturedOutput output)
            throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-1");
        String token = login();
        assertThat(status(token).statusCode()).isEqualTo(200);

        // the first server drops the session and does not take the stored password any more (e.g. shutting down)
        kick(first);

        HttpResponse<String> status = status(token);

        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(output.getAll()).contains("AutoSys rejected the proxy's login for 'carol'",
                "logging in as 'carol' on " + second.baseUrl() + " instead of " + first.baseUrl(),
                "logged in to AutoSys at " + second.baseUrl() + " as 'carol'");
        assertThat(status(token).statusCode()).isEqualTo(200);
        first.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
        second.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
    }

    @Test
    void whenEveryServerRefusesTheStoredLoginItIsTriedOnceOnEach() throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-1");
        String token = login();
        kick(first);
        refuseLogins(second);

        assertThat(status(token).statusCode()).isGreaterThanOrEqualTo(400);
        HttpResponse<String> again = status(token);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("on every server", "Log in to AutoSys through the proxy again");
        // no more tries, so the account is not locked
        first.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
        second.verify(1, postRequestedFor(urlEqualTo("/authentication/login")));
    }

    @Test
    void loggingInAgainThroughTheBrowserKeepsTheTokenWorking() throws Exception {
        QuickViewApiTest.stubAutosys(first, "asid-2"); // only the second login's session opens Quick View
        String token = login();
        String newer = login();

        assertThat(newer).isNotEqualTo(token);
        assertThat(status(token).statusCode()).isEqualTo(200);
        assertThat(status(newer).statusCode()).isEqualTo(200);
    }

    /** The server drops every session (searches go back to the login page) and refuses the stored login. */
    private static void kick(WireMockExtension autosys) throws Exception {
        QuickViewApiTest.stubAutosys(autosys, "none");
        autosys.stubFor(post(urlEqualTo(QuickViewApiTest.SEARCH)).atPriority(0).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/ui/Login.html")));
        refuseLogins(autosys);
    }

    private static void refuseLogins(WireMockExtension autosys) {
        autosys.stubFor(post("/authentication/login").atPriority(0).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid credentials\"}")));
    }

    /** Every request to this server fails as a server that went away does (the connection drops). */
    private static void down(WireMockExtension autosys) {
        autosys.stubFor(any(anyUrl()).atPriority(0)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    private HttpResponse<String> status(String token) throws Exception {
        return browser.get("/api/v1/jobs/TEST_FX_LOADER/status", "Authorization", "Bearer " + token);
    }

    private String login() throws Exception {
        HttpResponse<String> login = browser.post("/authentication/login", "application/json",
                "{\"username\":\"carol\",\"password\":\"" + QuickViewApiTest.PASSWORD + "\",\"remember\":false}",
                "X-Requested-With", "XMLHttpRequest", "Accept", "application/json");
        assertThat(login.statusCode()).isEqualTo(200);
        first.verify(postRequestedFor(urlEqualTo("/authentication/login"))
                .withHeader("X-Requested-With", containing("XMLHttpRequest")));
        Matcher m = ProxyLoginFlowTest.TOKEN.matcher(browser.get("/proxy/token").body());
        assertThat(m.find()).isTrue();
        return m.group();
    }
}
