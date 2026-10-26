package com.testing.aproxy.config;

import com.testing.aproxy.api.BearerTokenFilter;
import com.testing.aproxy.api.Problems;
import com.testing.aproxy.session.SessionRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * Three filter chains:
 * <ol>
 * <li>{@code /api/**}: stateless bearer-token authentication against the session registry.</li>
 * <li>{@code /proxy/**}, {@code /actuator/**}: the proxy's own pages; only health is public among actuators.</li>
 * <li>everything else: proxied to AutoSys untouched — AutoSys does its own authentication, so Spring
 * Security adds no CSRF, headers, session, logout or redirects there.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, SessionRegistry sessions) throws Exception {
        return http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new BearerTokenFilter(sessions), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            Object why = req.getAttribute(BearerTokenFilter.REJECTED);
                            if (why != null) {
                                log.info("[api] 401 on {}: {}", req.getRequestURI(), why);
                            }
                            Problems.write(res, HttpStatus.UNAUTHORIZED, Problems.TOKEN_INVALID,
                                    why != null ? why.toString() : "Missing API token (Authorization: Bearer asp_...)");
                        })
                        .accessDeniedHandler((req, res, ex) -> Problems.write(res, HttpStatus.FORBIDDEN,
                                "forbidden", "Access denied")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain ownPagesChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/proxy/**", "/actuator/**", "/error")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/proxy/**", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain proxiedChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(AbstractHttpConfigurer::disable)
                .sessionManagement(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .build();
    }

    /** Legacy Java web UIs use ;jsessionid= and encoded characters in URLs; let them through to AutoSys. */
    @Bean
    WebSecurityCustomizer proxyFriendlyFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        firewall.setAllowUrlEncodedSlash(true);
        firewall.setAllowUrlEncodedDoubleSlash(true);
        firewall.setAllowUrlEncodedPercent(true);
        firewall.setAllowUrlEncodedPeriod(true);
        return web -> web.httpFirewall(firewall);
    }

    /** No local users exist; this only stops Boot from generating a default password. */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
