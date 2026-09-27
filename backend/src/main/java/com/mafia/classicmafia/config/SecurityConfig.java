package com.mafia.classicmafia.config;

import com.mafia.classicmafia.api.ApiAuthenticationEntryPoint;
import com.mafia.classicmafia.api.RequestCorrelationFilter;
import com.mafia.classicmafia.controller.NotFoundController;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.AuditingAccessDeniedHandler;
import com.mafia.classicmafia.security.CredentialVersionFilter;
import com.mafia.classicmafia.security.PersistentLoginFilter;
import com.mafia.classicmafia.security.PersistentLoginService;
import com.mafia.classicmafia.security.SecuritySessionFilter;
import com.mafia.classicmafia.security.SecuritySessionService;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.function.Supplier;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
            new RequestAttributeSecurityContextRepository(),
            new HttpSessionSecurityContextRepository()
        );
    }


    @Bean
    CsrfTokenRepository csrfTokenRepository(
        @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie
    ) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieCustomizer(builder -> builder
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
        );
        return repository;
    }

    @Bean
    CsrfTokenRequestHandler csrfTokenRequestHandler() {
        XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
        return new CsrfTokenRequestHandler() {
            @Override
            public void handle(
                HttpServletRequest request,
                HttpServletResponse response,
                Supplier<CsrfToken> deferredCsrfToken
            ) {
                delegate.handle(request, response, deferredCsrfToken);
                String uri = request.getRequestURI();
                String context = request.getContextPath();
                if (context != null && !context.isEmpty() && uri.startsWith(context)) {
                    uri = uri.substring(context.length());
                }
                if ("/api/v1/csrf".equals(uri)) {
                    deferredCsrfToken.get();
                }
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
                return delegate.resolveCsrfTokenValue(request, csrfToken);
            }
        };
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(CurrentUserService currentUserService, AuditLogService auditLogService) {
        return new AuditingAccessDeniedHandler(currentUserService, auditLogService);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        UserRepository users,
        AccessDeniedHandler accessDeniedHandler,
        SecurityContextRepository securityContextRepository,
        CsrfTokenRepository csrfTokenRepository,
        CsrfTokenRequestHandler csrfTokenRequestHandler,
        SecuritySessionService securitySessionService,
        PersistentLoginService persistentLoginService
    ) throws Exception {
        RequestMatcher apiRequest = request -> {
            String uri = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty() && uri.startsWith(context)) {
                uri = uri.substring(context.length());
            }
            return uri.equals("/api") || uri.startsWith("/api/");
        };
        AuthenticationEntryPoint apiEntryPoint = new ApiAuthenticationEntryPoint();
        AuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        AuthenticationEntryPoint routingEntryPoint = (request, response, exception) -> {
            if (apiRequest.matches(request)) {
                apiEntryPoint.commence(request, response, exception);
                return;
            }
            String uri = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty() && uri.startsWith(context)) {
                uri = uri.substring(context.length());
            }
            if (uri.equals("/profile") || uri.startsWith("/profile/") || uri.equals("/admin") || uri.startsWith("/admin/")) {
                loginEntryPoint.commence(request, response, exception);
            } else {
                NotFoundController.write(response);
            }
        };

        return http
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository).csrfTokenRequestHandler(csrfTokenRequestHandler))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/session", "/api/v1/csrf").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/players",
                    "/api/v1/players/*",
                    "/api/v1/players/*/games",
                    "/api/v1/players/*/avatar",
                    "/api/v1/players/*/banner",
                    "/api/v1/games",
                    "/api/v1/games/*",
                    "/api/v1/schedule",
                    "/api/v1/tournaments",
                    "/api/v1/tournaments/*",
                    "/api/v1/rating",
                    "/api/v1/news",
                    "/api/v1/rules"
                ).permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/login",
                    "/api/v1/auth/register"
                ).permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(
                    "/",
                    "/news",
                    "/rules",
                    "/rating",
                    "/players",
                    "/players/*",
                    "/games",
                    "/games/*",
                    "/schedule",
                    "/tournaments",
                    "/tournaments/*",
                    "/register",
                    "/login",
                    "/captcha/**",
                    "/healthz",
                    "/index.html",
                    "/robots.txt",
                    "/sitemap.xml",
                    "/not-found",
                    "/favicon.png",
                    "/favicon.ico",
                    "/assets/**",
                    "/css/**",
                    "/fonts/**",
                    "/avatar/**",
                    "/forbidden",
                    "/error"
                ).permitAll()
                .requestMatchers("/profile/**").authenticated()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint(routingEntryPoint)
            )
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.changeSessionId())
            )
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                    "font-src 'self'; connect-src 'self'; object-src 'none'; media-src 'none'; " +
                    "frame-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
                ))
                .frameOptions(frame -> frame.deny())
                .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "strict-origin-when-cross-origin"))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin"))
                .addHeaderWriter(new StaticHeadersWriter("X-Permitted-Cross-Domain-Policies", "none"))
            )
            .addFilterBefore(new RequestCorrelationFilter(), CsrfFilter.class)
            .addFilterAfter(new PersistentLoginFilter(persistentLoginService, securityContextRepository, securitySessionService), SecurityContextHolderFilter.class)
            .addFilterAfter(new CredentialVersionFilter(users), PersistentLoginFilter.class)
            .addFilterAfter(new SecuritySessionFilter(users, securitySessionService), CredentialVersionFilter.class)
            .build();
    }
}
