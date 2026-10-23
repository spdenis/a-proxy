package com.testing.aproxy.webui;

import org.springframework.http.HttpStatus;

/** The proxy could not get an answer from the AutoSys web UI; mapped to an API problem. */
public class BackendException extends RuntimeException {

    private final HttpStatus status;
    private final String type;

    public BackendException(HttpStatus status, String type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public BackendException(HttpStatus status, String type, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }
}
