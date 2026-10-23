package com.testing.aproxy.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.testing.aproxy.capture.CaptureFilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Capture mode records AutoSys traffic to files with secrets masked. Placeholder fake AutoSys. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaptureModeTest {

    static final String PASSWORD = "Capture-Pa55";
    static Path captureDir;

    @RegisterExtension
    static WireMockExtension autosys = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        captureDir = Files.createTempDirectory("captures");
        registry.add("autosys.base-url", autosys::baseUrl);
        registry.add("autosys.capture.enabled", () -> "true");
        registry.add("autosys.capture.dir", captureDir::toString);
        registry.add("autosys.webui.login.path", () -> "/authentication/login");
        registry.add("autosys.webui.login.username-field", () -> "user");
        registry.add("autosys.webui.login.password-field", () -> "pw");
        registry.add("autosys.webui.login.success.statuses", () -> "200");
    }

    @LocalServerPort
    int port;

    @Autowired
    CaptureFilter captureFilter;

    BrowserClient browser;

    @BeforeEach
    void setUp() {
        browser = new BrowserClient(port);
        autosys.resetAll();
        autosys.stubFor(post("/authentication/login").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("Set-Cookie", "JSESSIONID=sess-999; Path=/; HttpOnly")
                .withBody("{\"success\":true,\"csrfToken\":\"TOKEN-SECRET-1\"}")));
        autosys.stubFor(get(urlPathEqualTo("/ui/rest/jobs")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":1,\"jobs\":[{\"name\":\"TEST_JOB\",\"status\":\"SUCCESS\"}]}")));
        autosys.stubFor(post("/ui/rest/jobs/TEST_JOB/event").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"result\":\"OK\"}")));
        autosys.stubFor(get("/ui/Job.html").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("<form><input type=hidden name=\"OWASP-CSRFTOKEN\" value=\"HIDDEN-SECRET\"></form>")));
        autosys.stubFor(get("/ui/app.js").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/javascript").withBody("var x;")));
    }

    @Test
    void recordsExchangesWithSecretsMasked() throws Exception {
        browser.post("/authentication/login", "application/x-www-form-urlencoded", "user=alice&pw=" + PASSWORD);
        HttpResponse<String> search = browser.get("/ui/rest/jobs?name=TEST*&status=SU&status=FA",
                "Accept", "application/json");
        HttpResponse<String> action = browser.post("/ui/rest/jobs/TEST_JOB/event", "application/json",
                "{\"event\":\"FORCE_STARTJOB\",\"comment\":\"test\"}", "OWASP-CSRFTOKEN", "HEADER-SECRET");
        browser.get("/ui/Job.html");
        browser.get("/ui/app.js");

        // the browser is served normally
        assertThat(search.body()).contains("TEST_JOB");
        assertThat(action.body()).contains("OK");
        autosys.verify(postRequestedFor(urlEqualTo("/ui/rest/jobs/TEST_JOB/event"))
                .withRequestBody(equalTo("{\"event\":\"FORCE_STARTJOB\",\"comment\":\"test\"}"))
                .withHeader("OWASP-CSRFTOKEN", equalTo("HEADER-SECRET")));
        autosys.verify(postRequestedFor(urlEqualTo("/authentication/login"))
                .withRequestBody(containing("pw=" + PASSWORD)));

        List<Path> files = captures();
        assertThat(files.stream().map(f -> f.getFileName().toString()))
                .containsExactly("0001-POST-authentication_login.json", "0002-GET-ui_rest_jobs.json",
                        "0003-POST-ui_rest_jobs_TEST_JOB_event.json", "0004-GET-ui_Job.html.json");

        String all = String.join("\n", files.stream().map(CaptureModeTest::read).toList());
        assertThat(all).doesNotContain(PASSWORD, "sess-999", "TOKEN-SECRET-1", "HEADER-SECRET", "HIDDEN-SECRET");

        JsonNode login = JsonMapper.shared().readTree(files.get(0).toFile());
        assertThat(login.at("/request/body/user").asString()).isEqualTo("alice");
        assertThat(login.at("/request/body/pw").asString()).isEqualTo("***");
        assertThat(login.at("/response/headers/Set-Cookie").asString()).isEqualTo("JSESSIONID=***; Path=/; HttpOnly");
        assertThat(login.at("/response/body/success").asBoolean()).isTrue();

        JsonNode jobs = JsonMapper.shared().readTree(files.get(1).toFile());
        assertThat(jobs.at("/request/autosysPath").asString()).isEqualTo("/ui/rest/jobs");
        assertThat(jobs.at("/request/query/name").asString()).isEqualTo("TEST*");
        assertThat(jobs.at("/request/query/status").toString()).isEqualTo("[\"SU\",\"FA\"]");
        assertThat(jobs.at("/request/headers/Cookie").asString()).isEqualTo("JSESSIONID=***");
        assertThat(jobs.at("/response/status").asInt()).isEqualTo(200);
        assertThat(jobs.at("/response/body/jobs/0/name").asString()).isEqualTo("TEST_JOB");

        JsonNode event = JsonMapper.shared().readTree(files.get(2).toFile());
        assertThat(event.at("/request/body/event").asString()).isEqualTo("FORCE_STARTJOB");
        assertThat(event.at("/request/headers/OWASP-CSRFTOKEN").asString()).isEqualTo("***");

        JsonNode page = JsonMapper.shared().readTree(files.get(3).toFile());
        assertThat(page.at("/response/body").asString()).contains("name=\"OWASP-CSRFTOKEN\" value=\"***\"");
    }

    private List<Path> captures() throws IOException {
        try (Stream<Path> files = Files.list(captureFilter.runDir())) {
            return files.sorted().toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
