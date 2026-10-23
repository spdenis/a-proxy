package com.testing.aproxy.logging;

import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback {@code %msg} replacement that masks password parameters, bearer tokens and
 * cookie values in case they ever end up in a log message.
 */
public class MaskingMessageConverter extends MessageConverter {

    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)(\"?(?:j_password|password|passwd|pwd)\"?\\s*[=:]\\s*\"?)[^&\\s,;\"]+");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization\\s*[=:]\\s*)[^,\\]\\r\\n]+");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)((?:set-)?cookie\\s*[=:]\\s*)[^\\]\\r\\n]+");
    private static final Pattern TOKEN = Pattern.compile("asp_[A-Za-z0-9_-]{20,}");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = SECRET_PARAM.matcher(message).replaceAll("$1***");
        masked = AUTH_HEADER.matcher(masked).replaceAll("$1***");
        masked = COOKIE_HEADER.matcher(masked).replaceAll("$1***");
        return TOKEN.matcher(masked).replaceAll("asp_***");
    }
}
