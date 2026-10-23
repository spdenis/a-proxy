package com.testing.aproxy.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * All AutoSys-specific settings. Endpoint paths, field names and login markers depend on
 * the AutoSys version and are configured, never hard-coded in Java.
 */
@Validated
@ConfigurationProperties("autosys")
public record AutosysProperties(
        @NotNull URI baseUrl,
        @NotEmpty Set<String> allowedEnvironments,
        @NotBlank String environment,
        @Valid @DefaultValue Proxy proxy,
        @Valid @DefaultValue Session session,
        @Valid @DefaultValue Http http,
        @Valid @DefaultValue Token token,
        @Valid @DefaultValue Webui webui) {

    /** Browser-facing reverse proxy settings. */
    public record Proxy(
            /* Path prefix under which AutoSys is exposed; empty means the proxy root. */
            @DefaultValue("") String prefix,
            /* Optional replacement of absolute AutoSys URLs inside text response bodies. */
            @DefaultValue("false") boolean rewriteBodyUrls,
            /*
             * Forward the browser's Host header (and leave Origin/Referer untouched), so AutoSys
             * believes it is served under the proxy's name. Needed for OWASP CSRFGuard, which
             * embeds the request host in its JavaScript and checks it against the page domain.
             */
            @DefaultValue("true") boolean preserveHost,
            /* One INFO line per request on the ACCESS logger. */
            @DefaultValue("true") boolean accessLog) {

        public String normalizedPrefix() {
            String p = prefix.strip();
            if (p.isEmpty() || p.equals("/")) {
                return "";
            }
            p = p.startsWith("/") ? p : "/" + p;
            return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        }
    }

    public record Session(
            @DefaultValue("8h") Duration idleTtl,
            @DefaultValue("24h") Duration absoluteTtl,
            /* How long the freshly issued token can be viewed on /proxy/token. */
            @DefaultValue("5m") Duration tokenPageTtl) {
    }

    public record Http(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            @DefaultValue("50") int maxConnections,
            /* PKCS12/JKS truststore with the internal CA; JVM default trust when unset. */
            @Nullable Resource truststore,
            @Nullable String truststorePassword,
            @DefaultValue("PKCS12") String truststoreType) {
    }

    public record Token(@DefaultValue("false") boolean bindClientIp) {
    }

    public record Webui(@Valid @DefaultValue Login login) {
    }

    /** How the AutoSys login exchange looks on the wire (filled from discovery D1). */
    public record Login(
            /* Request path of the login submit, relative to the AutoSys base URL. Ant patterns allowed. */
            @DefaultValue("/CHANGE-ME/login") String path,
            @DefaultValue("POST") String method,
            @DefaultValue("username") String usernameField,
            @DefaultValue("password") String passwordField,
            @Valid @DefaultValue Success success,
            /* Text in the response body that indicates a failed login. */
            @Nullable String failureMarker,
            /* Logs the shape (never values) of every POST and its response to help fill in this section. */
            @DefaultValue("false") boolean discoveryLog,
            /* Redirect the browser to /proxy/token after a successful login that ends with a redirect. */
            @DefaultValue("true") boolean redirectToTokenPage) {

        public Login {
            failureMarker = blankToNull(failureMarker);
        }
    }

    /** All configured rules must hold for the login to count as successful. */
    public record Success(
            @DefaultValue({}) List<Integer> statuses,
            /* Regex the Location header must match. */
            @Nullable String locationPattern,
            /* Regex the Location header must NOT match (e.g. back to the login page with an error flag). */
            @Nullable String locationRejectPattern,
            /* Name of a cookie AutoSys sets on success, e.g. a session cookie. */
            @Nullable String cookie) {

        public Success {
            // Keys left empty in YAML bind as "", which must mean "no rule".
            locationPattern = blankToNull(locationPattern);
            locationRejectPattern = blankToNull(locationRejectPattern);
            cookie = blankToNull(cookie);
        }

        public boolean hasAnyRule() {
            return !statuses.isEmpty() || locationPattern != null || locationRejectPattern != null || cookie != null;
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
