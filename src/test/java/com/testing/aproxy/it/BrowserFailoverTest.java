package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.http.HttpResponse;

import com.github.tomakehurst.wiremock.http.Fault;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Browser traffic with several AutoSys servers ({@code autosys.backend-urls}): failover only. The first
 * URL has nothing listening, then two fake AutoSys servers. Each test starts with a fresh proxy, so on
 * the first URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class BrowserFailoverTest {

    static final String PASSWORD = "S3cr3t-Pa55";

    @RegisterExtension
    static WireMockExtension first = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension second = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String DOWN = "http://127.0.0.1:" + freePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("autosys.base-url", () -> DOWN);
        registry.add("autosys.backend-urls", () -> DOWN + "," + first.baseUrl() + "," + second.baseUrl());
        registry.add("autosys.webui.login.path", () -> "/ui/j_login");
        registry.add("autosys.webui.login.username-field", () -> "user");
        registry.add("autosys.webui.login.password-field", () -> "pass");
        registry.add("autosys.webui.login.success.statuses", () -> "302");
        registry.add("autosys.webui.login.success.cookie", () -> "JSESSIONID");
        registry.add("autosys.http.connect-timeout", () -> "1s");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @BeforeEach
    void setUp() {
        browser = new BrowserClient(port);
        for (WireMockExtension autosys : new WireMockExtension[] {first, second}) {
            autosys.resetAll();
            autosys.stubFor(get("/ui/page").willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "text/html").withBody("page from " + autosys.getPort())));
            autosys.stubFor(post("/ui/j_login").willReturn(aResponse().withStatus(302)
                    .withHeader("Location", autosys.baseUrl() + "/ui/home")
                    .withHeader("Set-Cookie", "JSESSIONID=s" + autosys.getPort() + "; Path=/ui; HttpOnly")));
        }
    }

    @Test
    void aLoginToAnUnreachableServerIsSentToTheNextOneWithItsBody(CapturedOutput output) throws Exception {
        HttpResponse<String> login = browser.post("/ui/j_login", "application/x-www-form-urlencoded",
                "user=alice&pass=" + PASSWORD, "Origin", browser.origin());

        assertThat(login.statusCode()).isEqualTo(302);
        // the server's own URL in Location is rewritten to the proxy, whichever server answered
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l)
                .doesNotContain(first.baseUrl()).contains("ui%2Fhome"));
        first.verify(postRequestedFor(urlEqualTo("/ui/j_login")).withRequestBody(equalTo("user=alice&pass="
                + PASSWORD)));
        assertThat(output.getAll()).contains("[failover] AutoSys at " + DOWN + " is not usable",
                "switching to " + first.baseUrl())
                .contains("POST /ui/j_login -> " + first.baseUrl() + " 302")
                .doesNotContain(PASSWORD);

        // later requests go straight to the server in use
        assertThat(browser.get("/ui/page").body()).isEqualTo("page from " + first.getPort());
        second.verify(0, postRequestedFor(urlEqualTo("/ui/j_login")));
    }

    @Test
    void aPageAnsweredWithServiceUnavailableIsFetchedFromTheNextServerWhichStaysInUse(CapturedOutput output)
            throws Exception {
        browser.get("/ui/page"); // moves past the unreachable URL to the first server
        first.stubFor(get("/ui/page").atPriority(1).willReturn(aResponse().withStatus(503)));

        HttpResponse<String> page = browser.get("/ui/page");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).isEqualTo("page from " + second.getPort());
        assertThat(output.getAll()).contains("AutoSys at " + first.baseUrl() + " is not usable (answered 503");

        // no switching back when the first server recovers: its sessions would be lost again
        first.resetAll();
        first.stubFor(get("/ui/page").willReturn(aResponse().withStatus(200).withBody("first is back")));
        assertThat(browser.get("/ui/page").body()).isEqualTo("page from " + second.getPort());
        first.verify(0, getRequestedFor(urlEqualTo("/ui/page")));
    }

    @Test
    void aPostAnsweredWithServiceUnavailableIsNotSentAgainButTheNextRequestGoesToTheNextServer()
            throws Exception {
        browser.get("/ui/page");
        first.stubFor(post("/ui/action").willReturn(aResponse().withStatus(503)));
        second.stubFor(post("/ui/action").willReturn(aResponse().withStatus(200)));

        // the POST may have done something on AutoSys: the browser sees the error, not a second POST
        assertThat(browser.post("/ui/action", "application/x-www-form-urlencoded", "job=X").statusCode())
                .isEqualTo(503);
        second.verify(0, postRequestedFor(urlEqualTo("/ui/action")));

        assertThat(browser.get("/ui/page").body()).isEqualTo("page from " + second.getPort());
    }

    @Test
    void aServerErrorThatIsNotAnOutageDoesNotSwitchServers() throws Exception {
        browser.get("/ui/page");
        first.stubFor(get("/ui/broken").willReturn(aResponse().withStatus(500)));

        assertThat(browser.get("/ui/broken").statusCode()).isEqualTo(500);
        assertThat(browser.get("/ui/page").body()).isEqualTo("page from " + first.getPort());
        second.verify(0, getRequestedFor(urlEqualTo("/ui/page")));
        first.verify(getRequestedFor(urlEqualTo("/ui/page")).withHeader("Host", containing("localhost")));
    }

    @Test
    void aPageWhoseConnectionIsResetTwiceIsFetchedFromTheNextServer(CapturedOutput output) throws Exception {
        browser.get("/ui/page");
        first.stubFor(get("/ui/page").atPriority(1).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> page = browser.get("/ui/page");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).isEqualTo("page from " + second.getPort());
        assertThat(output.getAll()).contains("connection to AutoSys at " + first.baseUrl() + " dropped",
                "trying it once more", "[failover] AutoSys at " + first.baseUrl() + " is not usable");
        first.verify(3, getRequestedFor(urlEqualTo("/ui/page"))); // the first visit, then twice reset
        assertThat(browser.get("/ui/page").body()).isEqualTo("page from " + second.getPort());
    }

    @Test
    void aSingleResetIsTriedAgainOnTheSameServerWithoutSwitching() throws Exception {
        browser.get("/ui/page");
        first.stubFor(get("/ui/page").atPriority(1).inScenario("stale").whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("reset once").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> page = browser.get("/ui/page");

        assertThat(page.body()).isEqualTo("page from " + first.getPort());
        assertThat(browser.get("/ui/page").body()).isEqualTo("page from " + first.getPort());
        second.verify(0, getRequestedFor(urlEqualTo("/ui/page")));
    }

    @Test
    void aLoginWhoseConnectionIsResetIsSentToTheNextServerWithItsWholeBody(CapturedOutput output)
            throws Exception {
        browser.get("/ui/page");
        first.stubFor(post("/ui/j_login").atPriority(1)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> login = browser.post("/ui/j_login", "application/x-www-form-urlencoded",
                "user=alice&pass=" + PASSWORD, "Origin", browser.origin());

        assertThat(login.statusCode()).isEqualTo(302);
        first.verify(2, postRequestedFor(urlEqualTo("/ui/j_login"))
                .withRequestBody(equalTo("user=alice&pass=" + PASSWORD)));
        second.verify(1, postRequestedFor(urlEqualTo("/ui/j_login"))
                .withRequestBody(equalTo("user=alice&pass=" + PASSWORD)));
        assertThat(output.getAll()).contains("POST /ui/j_login -> " + second.baseUrl() + " 302")
                .contains("\"event\":\"LOGIN_CAPTURED\"")
                .doesNotContain(PASSWORD);
    }

    @Test
    void anotherPostWhoseConnectionIsResetIsNotSentAgain(CapturedOutput output) throws Exception {
        browser.get("/ui/page");
        first.stubFor(post("/ui/action").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        second.stubFor(post("/ui/action").willReturn(aResponse().withStatus(200)));

        // it may have been executed (e.g. a job started): the browser sees the error, AutoSys no second POST
        assertThat(browser.post("/ui/action", "application/x-www-form-urlencoded", "job=X").statusCode())
                .isGreaterThanOrEqualTo(500);
        first.verify(1, postRequestedFor(urlEqualTo("/ui/action")));
        second.verify(0, postRequestedFor(urlEqualTo("/ui/action")));
        assertThat(output.getAll()).contains("not sent again, it may have reached AutoSys");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
