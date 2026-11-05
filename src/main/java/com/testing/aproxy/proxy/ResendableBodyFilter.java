package com.testing.aproxy.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the body of the AutoSys login request readable again, so that {@link ProxyRouteConfig} can send
 * the login to the next AutoSys server when the connection drops. Runs last, after the gateway's form
 * filter, so it records the body exactly as it is forwarded. Only login requests (marked by
 * {@link LoginCaptureFilter}) are wrapped: other requests are never sent twice.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ResendableBodyFilter extends OncePerRequestFilter {

    /** Request attribute set by {@link LoginCaptureFilter} on the AutoSys login request. */
    public static final String LOGIN_REQUEST = ResendableBodyFilter.class.getName() + ".login";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Boolean.TRUE.equals(request.getAttribute(LOGIN_REQUEST));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new Resendable(request), response);
    }

    /** The resendable login request behind this request, or null when it is not one. */
    static @Nullable Resendable find(ServletRequest request) {
        for (ServletRequest r = request; r != null; ) {
            if (r instanceof Resendable resendable) {
                return resendable;
            }
            r = r instanceof ServletRequestWrapper w ? w.getRequest() : null;
        }
        return null;
    }

    /** Records the body as it is read; {@link #rewind()} makes the next read start from the beginning. */
    static final class Resendable extends HttpServletRequestWrapper {

        private final ByteArrayOutputStream recorded = new ByteArrayOutputStream();
        private @Nullable ServletInputStream stream;

        Resendable(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Recording(super.getInputStream(), new byte[0]);
            }
            return stream;
        }

        /** The body read so far is replayed first, then the rest of the original stream. */
        void rewind() throws IOException {
            ServletInputStream original = super.getInputStream();
            if (stream instanceof Recording r) {
                original = r.original;
                // a send cut off while replaying: the part not replayed yet comes after what it read
                recorded.write(r.replay, r.position, r.replay.length - r.position);
            }
            stream = new Recording(original, recorded.toByteArray());
            recorded.reset();
        }

        private final class Recording extends ServletInputStream {

            final ServletInputStream original;
            final byte[] replay;
            int position;

            Recording(ServletInputStream original, byte[] replay) {
                this.original = original;
                this.replay = replay;
            }

            @Override
            public int read() throws IOException {
                int b = position < replay.length ? replay[position++] & 0xff : original.read();
                if (b >= 0) {
                    recorded.write(b);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                int n;
                if (position < replay.length) {
                    n = Math.min(length, replay.length - position);
                    System.arraycopy(replay, position, buffer, offset, n);
                    position += n;
                } else {
                    n = original.read(buffer, offset, length);
                }
                if (n > 0) {
                    recorded.write(buffer, offset, n);
                }
                return n;
            }

            @Override
            public boolean isFinished() {
                return position >= replay.length && original.isFinished();
            }

            @Override
            public boolean isReady() {
                return position < replay.length || original.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
                original.setReadListener(listener);
            }
        }
    }
}
