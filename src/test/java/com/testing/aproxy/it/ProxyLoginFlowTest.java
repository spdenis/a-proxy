package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

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
 * Browser-style login through the proxy (AutoSys at the proxy root, form login) against a
 * fake AutoSys. The fake's paths and fields are test placeholders, not real AutoSys endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ProxyLoginFlowTest {

    static final String PASSWORD = "S3cr3t-Pa55";
    static final Pattern TOKEN = Pattern.compile("asp_[A-Za-z0-9_-]{40,}");

    @RegisterExtension
    static WireMockExtension autosys = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("autosys.base-url", autosys::baseUrl);
        registry.add("autosys.webui.login.path", () -> "/ui/j_login");
        registry.add("autosys.webui.login.username-field", () -> "user");
        registry.add("autosys.webui.login.password-field", () -> "pass");
        registry.add("autosys.webui.login.success.statuses", () -> "302");
        registry.add("autosys.webui.login.success.location-reject-pattern", () -> "login");
        registry.add("autosys.webui.login.success.cookie", () -> "JSESSIONID");
        registry.add("autosys.webui.login.failure-marker", () -> "Invalid credentials");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @BeforeEach
    void setUp() {
        browser = new BrowserClient(port);
        autosys.resetAll();
        autosys.stubFor(get("/").willReturn(aResponse().withStatus(302)
                .withHeader("Location", autosys.baseUrl() + "/ui/login.html")));
        autosys.stubFor(get("/ui/login.html").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("<form method=post action=/ui/j_login><input name=user><input name=pass></form>")));
        autosys.stubFor(post("/ui/j_login").withRequestBody(containing("pass=" + PASSWORD))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", autosys.baseUrl() + "/ui/home")
                        .withHeader("Set-Cookie",
                                "JSESSIONID=abc123; Domain=localhost; Path=/ui; Secure; HttpOnly; SameSite=None")));
        autosys.stubFor(post("/ui/j_login").withRequestBody(containing("pass=wrong"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<p>Invalid credentials</p><form>...</form>")));
        autosys.stubFor(get("/ui/home").withCookie("JSESSIONID", equalTo("abc123"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<h1>Welcome to AutoSys</h1>")));
    }

    @Test
    void browserLoginThroughProxyIssuesTokenUsableOnApi(CapturedOutput output) throws Exception {
        HttpResponse<String> root = browser.get("/");
        assertThat(root.statusCode()).isEqualTo(302);
        assertThat(root.headers().firstValue("Location")).hasValue(browser.origin() + "/ui/login.html");

        HttpResponse<String> loginPage = browser.get("/ui/login.html");
        assertThat(loginPage.statusCode()).isEqualTo(200);
        assertThat(loginPage.body()).contains("<form");

        HttpResponse<String> login = browser.post("/ui/j_login", "application/x-www-form-urlencoded",
                "user=alice&pass=" + PASSWORD, "Origin", browser.origin(), "Referer", browser.origin() + "/ui/login.html");
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location")).hasValue("/proxy/token?next=%2Fui%2Fhome");
        assertThat(BrowserClient.setCookies(login))
                .contains("JSESSIONID=abc123; Path=/ui; HttpOnly; SameSite=Lax")
                .anySatisfy(c -> assertThat(c).startsWith("ASP_TOKEN_TICKET=").contains("Path=/proxy", "HttpOnly"));

        // the login request reached AutoSys unchanged; with preserve-host (default) AutoSys sees the
        // proxy's Host and the browser's Origin/Referer, as OWASP CSRFGuard requires
        autosys.verify(postRequestedFor(urlEqualTo("/ui/j_login"))
                .withRequestBody(equalTo("user=alice&pass=" + PASSWORD))
                .withHeader("Host", equalTo("localhost:" + port))
                .withHeader("Origin", equalTo(browser.origin()))
                .withHeader("Referer", equalTo(browser.origin() + "/ui/login.html")));

        HttpResponse<String> tokenPage = browser.get("/proxy/token?next=%2Fui%2Fhome");
        assertThat(tokenPage.statusCode()).isEqualTo(200);
        assertThat(tokenPage.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(tokenPage.body()).contains("alice", "href=\"/ui/home\"");
        Matcher m = TOKEN.matcher(tokenPage.body());
        assertThat(m.find()).isTrue();
        String token = m.group();

        // the AutoSys UI keeps working through the proxy with the rewritten cookie
        HttpResponse<String> home = browser.get("/ui/home");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Welcome to AutoSys");
        autosys.verify(getRequestedFor(urlPathEqualTo("/ui/home"))
                .withHeader("Cookie", equalTo("JSESSIONID=abc123")));

        HttpResponse<String> session = browser.get("/api/v1/session", "Authorization", "Bearer " + token);
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(session.body()).contains("\"username\":\"alice\"", "\"state\":\"ACTIVE\"");

        assertThat(browser.delete("/api/v1/sessions/current", "Authorization", "Bearer " + token).statusCode())
                .isEqualTo(204);
        HttpResponse<String> revoked = browser.get("/api/v1/session", "Authorization", "Bearer " + token);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(browser.get("/proxy/token").body()).contains("No API token");

        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(token).doesNotContain("abc123")
                .contains("\"event\":\"LOGIN_CAPTURED\"")
                .contains("open " + browser.origin() + "/proxy/token within")
                // access log: one line per request, query strings omitted
                .contains("GET /ui/home -> " + autosys.baseUrl() + " 200")
                .contains("POST /ui/j_login -> " + autosys.baseUrl() + " 302")
                .contains("GET /proxy/token (proxy) 200")
                .doesNotContain("next=%2Fui");
    }

    @Test
    void redirectBuiltFromPreservedHostKeepsBrowserOnProxyScheme() throws Exception {
        autosys.stubFor(get("/ui/logout").willReturn(aResponse().withStatus(302)
                .withHeader("Location", "https://localhost:" + port + "/ui/login.html")));
        assertThat(browser.get("/ui/logout").headers().firstValue("Location"))
                .hasValue(browser.origin() + "/ui/login.html");
    }

    @Test
    void failedLoginStoresNothing(CapturedOutput output) throws Exception {
        HttpResponse<String> login = browser.post("/ui/j_login", "application/x-www-form-urlencoded",
                "user=alice&pass=wrong");

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("Invalid credentials");
        assertThat(BrowserClient.setCookies(login)).noneMatch(c -> c.startsWith("ASP_"));
        assertThat(browser.get("/proxy/token").body()).contains("No API token");
        assertThat(output.getAll()).contains("\"event\":\"LOGIN_FAILED\"").doesNotContain("pass=wrong");
    }

    @Test
    void apiRequiresValidToken() throws Exception {
        HttpResponse<String> none = browser.get("/api/v1/session");
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(none.body()).contains("/problems/token-invalid");

        assertThat(browser.get("/api/v1/session", "Authorization", "Bearer asp_notarealtokennotarealtoken")
                .statusCode()).isEqualTo(401);
    }

    @Test
    void logMaskingIsActive(CapturedOutput output) {
        org.slf4j.LoggerFactory.getLogger("test").info("form j_password={} Authorization: Bearer xyz", PASSWORD);
        assertThat(output.getAll()).contains("j_password=***", "Authorization: ***").doesNotContain(PASSWORD);
    }

    @Test
    void healthIsPublicAndOtherActuatorsAreNot() throws Exception {
        assertThat(browser.get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(browser.get("/actuator/env").statusCode()).isIn(401, 403, 404);
        // proxy-owned paths never reach AutoSys
        autosys.verify(0, getRequestedFor(urlPathEqualTo("/actuator/health")));
    }
}
