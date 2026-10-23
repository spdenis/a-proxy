package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.zip.GZIPOutputStream;

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
 * AutoSys mounted under {@code /autosys}, JSON (XHR-style) login answered with 200, discovery
 * logging and body URL rewriting enabled. Placeholder fake AutoSys, not real endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class PrefixedJsonLoginTest {

    static final String PASSWORD = "Js0n-Pa55word";

    @RegisterExtension
    static WireMockExtension autosys = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("autosys.base-url", autosys::baseUrl);
        registry.add("autosys.proxy.prefix", () -> "/autosys");
        registry.add("autosys.proxy.rewrite-body-urls", () -> "true");
        registry.add("autosys.proxy.preserve-host", () -> "false");
        registry.add("autosys.webui.login.path", () -> "/rest/auth/**");
        registry.add("autosys.webui.login.username-field", () -> "credentials.user");
        registry.add("autosys.webui.login.password-field", () -> "credentials.secret");
        registry.add("autosys.webui.login.success.statuses", () -> "200");
        registry.add("autosys.webui.login.success.cookie", () -> "SID");
        registry.add("autosys.webui.login.failure-marker", () -> "\"error\"");
        registry.add("autosys.webui.login.discovery-log", () -> "true");
    }

    @LocalServerPort
    int port;

    BrowserClient browser;

    @BeforeEach
    void setUp() throws Exception {
        browser = new BrowserClient(port);
        autosys.resetAll();
        autosys.stubFor(post("/rest/auth/login")
                .withRequestBody(matchingJsonPath("$.credentials.secret", equalTo(PASSWORD)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("Set-Cookie", "SID=s-42; Path=/; HttpOnly")
                        .withBody("{\"ok\":true,\"home\":\"" + autosys.baseUrl() + "/app\"}")));
        autosys.stubFor(get("/app/index.html").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody("<script src=\"" + autosys.baseUrl() + "/app/main.js\"></script>")));
        autosys.stubFor(get("/app/main.js").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/javascript")
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip("console.log('hello')"))));
        autosys.stubFor(post("/app/search").willReturn(aResponse().withStatus(200)));
    }

    @Test
    void jsonLoginUnderPrefixIssuesToken(CapturedOutput output) throws Exception {
        String body = "{\"credentials\":{\"user\":\"bob\",\"secret\":\"" + PASSWORD + "\"}}";
        HttpResponse<String> login = browser.post("/autosys/rest/auth/login", "application/json", body,
                "Origin", browser.origin(), "Referer", browser.origin() + "/autosys/app/index.html");

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Location")).isEmpty();
        assertThat(BrowserClient.setCookies(login)).contains("SID=s-42; Path=/autosys/; HttpOnly");
        // body URL rewriting also applies to the login response
        assertThat(login.body()).contains("\"home\":\"" + browser.origin() + "/autosys/app\"");
        autosys.verify(postRequestedFor(urlEqualTo("/rest/auth/login")).withRequestBody(equalToJson(body))
                .withHeader("Host", equalTo(URI.create(autosys.baseUrl()).getAuthority()))
                .withHeader("Origin", equalTo(autosys.baseUrl()))
                .withHeader("Referer", equalTo(autosys.baseUrl() + "/app/index.html")));

        HttpResponse<String> tokenPage = browser.get("/proxy/token");
        Matcher m = ProxyLoginFlowTest.TOKEN.matcher(tokenPage.body());
        assertThat(m.find()).isTrue();
        assertThat(tokenPage.body()).contains("bob", "href=\"/autosys/\"");
        assertThat(browser.get("/api/v1/session", "Authorization", "Bearer " + m.group()).body())
                .contains("\"username\":\"bob\"");

        assertThat(output.getAll())
                .contains("[discovery] POST /autosys/rest/auth/login", "fields=[credentials]", "cookieNames=[SID]")
                .doesNotContain(PASSWORD).doesNotContain(m.group());
    }

    @Test
    void htmlIsRewrittenAndCompressedAssetsPassThroughUntouched() throws Exception {
        HttpResponse<String> page = browser.get("/autosys/app/index.html");
        assertThat(page.body()).isEqualTo("<script src=\"" + browser.origin() + "/autosys/app/main.js\"></script>");

        HttpClient raw = HttpClient.newHttpClient();
        HttpResponse<byte[]> js = raw.send(HttpRequest.newBuilder(URI.create(browser.origin() + "/autosys/app/main.js"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(js.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(js.body()).isEqualTo(gzip("console.log('hello')"));
    }

    @Test
    void ownPathsAreNotProxiedAndUnprefixedPathsAreNotForwarded() throws Exception {
        browser.get("/app/index.html");
        autosys.verify(0, getRequestedFor(urlEqualTo("/app/index.html")));
        autosys.verify(0, getRequestedFor(urlEqualTo("/index.html")));
    }

    @Test
    void discoveryLogsNonLoginPostsWithoutValues(CapturedOutput output) throws Exception {
        browser.post("/autosys/app/search", "application/x-www-form-urlencoded", "job=MY_JOB&password=hunter2");
        assertThat(output.getAll()).contains("[discovery] POST /autosys/app/search", "fields=[job, password]")
                .doesNotContain("hunter2").doesNotContain("MY_JOB");
        autosys.verify(postRequestedFor(urlEqualTo("/app/search"))
                .withRequestBody(equalTo("job=MY_JOB&password=hunter2")));
    }

    static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
