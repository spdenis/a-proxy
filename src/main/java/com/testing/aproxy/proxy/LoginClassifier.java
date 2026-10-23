package com.testing.aproxy.proxy;

import java.util.Collection;
import java.util.regex.Pattern;

import com.testing.aproxy.config.AutosysProperties;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Decides from the AutoSys login response whether the login succeeded (rules from config, D1). */
@Component
public class LoginClassifier {

    private final AutosysProperties.Success rules;
    private final @Nullable Pattern locationPattern;
    private final @Nullable Pattern locationRejectPattern;
    private final @Nullable String failureMarker;

    public LoginClassifier(AutosysProperties properties) {
        AutosysProperties.Login login = properties.webui().login();
        this.rules = login.success();
        this.locationPattern = compile(rules.locationPattern());
        this.locationRejectPattern = compile(rules.locationRejectPattern());
        this.failureMarker = login.failureMarker();
    }

    public boolean isConfigured() {
        return rules.hasAnyRule() || failureMarker != null;
    }

    public Result classify(int status, @Nullable String location, Collection<String> setCookies, String body) {
        if (!isConfigured()) {
            return Result.failure("no login success rules configured (autosys.webui.login.success.*)");
        }
        if (!rules.statuses().isEmpty() && !rules.statuses().contains(status)) {
            return Result.failure("status " + status + " not in " + rules.statuses());
        }
        if (locationPattern != null && (location == null || !locationPattern.matcher(location).find())) {
            return Result.failure("redirect target does not match location-pattern");
        }
        if (locationRejectPattern != null && location != null && locationRejectPattern.matcher(location).find()) {
            return Result.failure("redirect target matches location-reject-pattern");
        }
        if (rules.cookie() != null
                && setCookies.stream().map(HeaderRewriter::cookieName).noneMatch(rules.cookie()::equals)) {
            return Result.failure("cookie " + rules.cookie() + " not set");
        }
        if (failureMarker != null && body.contains(failureMarker)) {
            return Result.failure("failure marker found in response body");
        }
        if (status >= 400) {
            return Result.failure("status " + status);
        }
        return Result.SUCCESS;
    }

    private static @Nullable Pattern compile(@Nullable String regex) {
        return regex == null ? null : Pattern.compile(regex);
    }

    public record Result(boolean success, String reason) {

        static final Result SUCCESS = new Result(true, "ok");

        static Result failure(String reason) {
            return new Result(false, reason);
        }
    }
}
