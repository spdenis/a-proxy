package com.testing.aproxy.config;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Refuses to start the application for an environment that is not explicitly allowed
 * (production is not allowed by default).
 */
@Component
public class EnvironmentGuard {

    public EnvironmentGuard(AutosysProperties properties) {
        Set<String> allowed = properties.allowedEnvironments().stream()
                .map(e -> e.strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        String env = properties.environment().strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(env)) {
            throw new IllegalStateException("autosys.environment=" + env
                    + " is not in autosys.allowed-environments " + allowed + "; refusing to start");
        }
    }
}
