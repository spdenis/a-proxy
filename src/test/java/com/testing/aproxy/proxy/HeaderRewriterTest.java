package com.testing.aproxy.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

class HeaderRewriterTest {

    private static final URI UPSTREAM = URI.create("https://autosys.example.internal:8443");
    private static final String PROXY = "http://localhost:8080";

    @Test
    void absoluteLocationOnUpstreamMovesToProxy() {
        assertThat(HeaderRewriter.rewriteLocation("https://autosys.example.internal:8443/ui/home?x=1#top", UPSTREAM,
                PROXY, "")).isEqualTo("http://localhost:8080/ui/home?x=1#top");
        assertThat(HeaderRewriter.rewriteLocation("https://AUTOSYS.example.internal:8443", UPSTREAM, PROXY,
                "/autosys")).isEqualTo("http://localhost:8080/autosys/");
    }

    @Test
    void defaultPortsAreEquivalent() {
        URI upstream = URI.create("https://autosys.example.internal");
        assertThat(HeaderRewriter.rewriteLocation("https://autosys.example.internal:443/a", upstream, PROXY, ""))
                .isEqualTo("http://localhost:8080/a");
    }

    @Test
    void relativeLocationGetsPrefix() {
        assertThat(HeaderRewriter.rewriteLocation("/ui/home", UPSTREAM, PROXY, "/autosys")).isEqualTo("/autosys/ui/home");
        assertThat(HeaderRewriter.rewriteLocation("/ui/home", UPSTREAM, PROXY, "")).isEqualTo("/ui/home");
    }

    @Test
    void foreignLocationIsUntouched() {
        assertThat(HeaderRewriter.rewriteLocation("https://other.example/x", UPSTREAM, PROXY, ""))
                .isEqualTo("https://other.example/x");
        assertThat(HeaderRewriter.rewriteLocation("https://autosys.example.internal:9443/x", UPSTREAM, PROXY, ""))
                .isEqualTo("https://autosys.example.internal:9443/x");
        assertThat(HeaderRewriter.rewriteLocation("home.jsp", UPSTREAM, PROXY, "")).isEqualTo("home.jsp");
    }

    @Test
    void setCookieDropsDomainAndSecureOnPlainHttp() {
        String rewritten = HeaderRewriter.rewriteSetCookie(
                "JSESSIONID=abc; Domain=.example.internal; Path=/ui; Secure; HttpOnly; SameSite=None", "", false);
        assertThat(rewritten).isEqualTo("JSESSIONID=abc; Path=/ui; HttpOnly; SameSite=Lax");
    }

    @Test
    void setCookieKeepsSecureOverHttpsAndPrefixesPath() {
        assertThat(HeaderRewriter.rewriteSetCookie("SID=1; Path=/; Secure; SameSite=None", "/autosys", true))
                .isEqualTo("SID=1; Path=/autosys/; Secure; SameSite=None");
        assertThat(HeaderRewriter.rewriteSetCookie("SID=1; HttpOnly", "/autosys", true))
                .isEqualTo("SID=1; HttpOnly; Path=/autosys/");
        assertThat(HeaderRewriter.rewriteSetCookie("SID=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT", "", true))
                .isEqualTo("SID=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT");
    }

    @Test
    void originAndRefererMapBackToUpstream() {
        assertThat(HeaderRewriter.toUpstream("http://localhost:8080", UPSTREAM, PROXY, ""))
                .isEqualTo("https://autosys.example.internal:8443");
        assertThat(HeaderRewriter.toUpstream("http://localhost:8080/autosys/ui/login?a=b", UPSTREAM, PROXY, "/autosys"))
                .isEqualTo("https://autosys.example.internal:8443/ui/login?a=b");
        assertThat(HeaderRewriter.toUpstream("https://elsewhere/", UPSTREAM, PROXY, "")).isEqualTo("https://elsewhere/");
    }

    @Test
    void ownCookiesAreNotForwarded() {
        assertThat(HeaderRewriter.stripOwnCookies("JSESSIONID=abc; ASP_TOKEN_TICKET=t; x=1", "ASP_"))
                .isEqualTo("JSESSIONID=abc; x=1");
        assertThat(HeaderRewriter.stripOwnCookies("ASP_TOKEN_TICKET=t", "ASP_")).isNull();
    }

    @Test
    void bodyRewriteHandlesPlainAndJsonEscapedUrls() {
        assertThat(BodyUrlRewriteFilter.rewrite("<a href=\"https://h:8443/x\"> {\"u\":\"https:\\/\\/h:8443\\/y\"}",
                "https://h:8443", "http://localhost:8080"))
                .isEqualTo("<a href=\"http://localhost:8080/x\"> {\"u\":\"http:\\/\\/localhost:8080\\/y\"}");
    }
}
