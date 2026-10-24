package com.testing.aproxy.session;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The browser's latest Quick View form submit: where it went (path and query with
 * {@code AntiXSRFToken} and {@code conversationContext}), its form fields (with
 * {@code javax.faces.ViewState}) and its Cookie header (with the Quick View JSESSIONID).
 * Lets API calls use the Quick View page the browser already has open. Holds session
 * secrets: kept in memory only and never logged.
 */
public record BrowserPage(String action, Map<String, String> fields, String cookieHeader, Instant seenAt) {

    public BrowserPage {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @Override
    public String toString() {
        return "BrowserPage[" + action.split("\\?", 2)[0] + ", " + fields.size() + " fields, seen " + seenAt + "]";
    }
}
