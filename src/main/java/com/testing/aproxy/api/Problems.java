package com.testing.aproxy.api;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** RFC 9457 problem types of the public API. */
public final class Problems {

    public static final String TOKEN_INVALID = "token-invalid";

    private static final JsonMapper JSON = JsonMapper.shared();

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    /** For use outside Spring MVC (security entry points). */
    public static void write(HttpServletResponse response, HttpStatus status, String type, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        JSON.writeValue(response.getOutputStream(), of(status, type, detail));
    }
}
