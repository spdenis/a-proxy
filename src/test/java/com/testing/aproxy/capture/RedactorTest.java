package com.testing.aproxy.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RedactorTest {

    private final Redactor redactor = new Redactor(List.of("pw"));

    @Test
    void headersKeepNamesButMaskSecretValues() {
        Map<String, Object> headers = redactor.headers(Map.of(
                "Cookie", List.of("JSESSIONID=abc; theme=dark"),
                "Set-Cookie", List.of("JSESSIONID=abc; Path=/ui; HttpOnly"),
                "Authorization", List.of("Bearer x"),
                "OWASP-CSRFTOKEN", List.of("ABCD-1234"),
                "Accept", List.of("application/json")));

        assertThat(headers).containsEntry("Cookie", "JSESSIONID=***; theme=***")
                .containsEntry("Set-Cookie", "JSESSIONID=***; Path=/ui; HttpOnly")
                .containsEntry("Authorization", "***")
                .containsEntry("OWASP-CSRFTOKEN", "***")
                .containsEntry("Accept", "application/json");
    }

    @Test
    void paramsMaskSecretNamesAndConfiguredPasswordField() {
        assertThat(redactor.params(Map.of("user", new String[] {"alice"}, "pw", new String[] {"s3cr3t"},
                "j_password", new String[] {"x"}, "job", new String[] {"A", "B"})))
                .containsEntry("user", "alice").containsEntry("pw", "***").containsEntry("j_password", "***")
                .containsEntry("job", List.of("A", "B"));
    }

    @Test
    void jsonMasksSecretFieldsAtAnyDepthAndKeepsStructure() {
        JsonNode tree = JsonMapper.shared().readTree("""
                {"user":"bob","credentials":{"password":"p","remember":true},
                 "csrfToken":"tok-123","jobs":[{"name":"J1","status":"SU"}],"sessionTimeout":30}""");

        String out = redactor.json(tree).toString();

        assertThat(out).contains("\"user\":\"bob\"", "\"password\":\"***\"", "\"remember\":true",
                "\"csrfToken\":\"***\"", "\"name\":\"J1\"", "\"sessionTimeout\":30")
                .doesNotContain("tok-123");
    }

    @Test
    void textMasksCsrfTokensInHtmlAndJs() {
        assertThat(redactor.text("<input type=hidden name=\"OWASP-CSRFTOKEN\" value=\"AAAA-BBBB\">"))
                .doesNotContain("AAAA-BBBB").contains("name=\"OWASP-CSRFTOKEN\"");
        assertThat(redactor.text("<input value='ZZZZ-1' name='csrf_token'>")).doesNotContain("ZZZZ-1");
        assertThat(redactor.text("OWASP-CSRFTOKEN:1A2B-3C4D-5E6F")).isEqualTo("OWASP-CSRFTOKEN:***");
        assertThat(redactor.text("var tokenValue = \"abcdef12\";")).doesNotContain("abcdef12");
        assertThat(redactor.text("/ui/home;jsessionid=ABCDEF0123")).doesNotContain("ABCDEF0123");
        assertThat(redactor.text("<td>MY_BOX_JOB</td><td>SU</td>")).isEqualTo("<td>MY_BOX_JOB</td><td>SU</td>");
    }
}
