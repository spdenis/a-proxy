package com.testing.aproxy.proxy;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * Wraps a request we need to look into: optionally replays an already-read body, and
 * hides {@code Accept-Encoding} so AutoSys answers uncompressed and the proxy can inspect
 * (or rewrite) the response body.
 */
class LoginRequestWrapper extends HttpServletRequestWrapper {

    private final byte @Nullable [] body;

    LoginRequestWrapper(HttpServletRequest request, byte @Nullable [] body) {
        super(request);
        this.body = body;
    }

    @Override
    public @Nullable String getHeader(String name) {
        return hidden(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = Collections.list(super.getHeaderNames()).stream().filter(n -> !hidden(n)).toList();
        return Collections.enumeration(names);
    }

    @Override
    public ServletInputStream getInputStream() throws java.io.IOException {
        if (body == null) {
            return super.getInputStream();
        }
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public BufferedReader getReader() throws java.io.IOException {
        if (body == null) {
            return super.getReader();
        }
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return body == null ? super.getContentLength() : body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body == null ? super.getContentLengthLong() : body.length;
    }

    private static boolean hidden(String name) {
        return HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name);
    }
}
