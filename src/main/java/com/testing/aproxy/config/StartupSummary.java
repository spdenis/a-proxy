package com.testing.aproxy.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.testing.aproxy.proxy.LoginClassifier;
import com.testing.aproxy.proxy.ProxyPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Logs where the proxy listens, where it forwards to and where the configuration came from. */
@Component
public class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final AutosysProperties properties;
    private final ProxyPaths paths;
    private final LoginClassifier classifier;
    private final Environment environment;

    public StartupSummary(AutosysProperties properties, ProxyPaths paths, LoginClassifier classifier,
            Environment environment) {
        this.properties = properties;
        this.paths = paths;
        this.classifier = classifier;
        this.environment = environment;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        int port = event.getApplicationContext() instanceof WebServerApplicationContext web
                ? web.getWebServer().getPort() : -1;
        String base = "http://localhost:" + port;
        Path userProperties = Path.of(environment.getProperty("aproxy.user-properties", "./user.properties"))
                .toAbsolutePath().normalize();
        log.info("Configuration: {}", Files.isRegularFile(userProperties)
                ? "loaded " + userProperties : "no " + userProperties + " found, using defaults");
        log.info("AutoSys UI:   {}{}/  ->  {} (environment {})", base, paths.prefix(), properties.baseUrl(),
                properties.environment());
        log.info("Token page:   {}{}   (after logging in on the AutoSys page)", base, ProxyPaths.TOKEN_PAGE);
        log.info("API:          {}/api/v1/session  (send the token as a Bearer token in the Authorization header)",
                base);
        log.info("Login capture: {}; logs: {}", classifier.isConfigured()
                ? "configured for " + properties.webui().login().method() + " " + properties.webui().login().path()
                : "NOT configured (no token will be issued)",
                environment.getProperty("logging.file.name", "console only"));
    }
}
