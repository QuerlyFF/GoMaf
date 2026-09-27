package com.mafia.classicmafia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.servlet.session.cookie.secure=false"
)
class CsrfHttpRoundTripIntegrationTests {
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Test
    void csrfEndpointUsesSingleRawCookieAndMaskedHeaderToken() throws Exception {
        URI csrfUri = URI.create("http://127.0.0.1:" + port + "/api/v1/csrf");
        HttpResponse<String> csrf = http.send(
            HttpRequest.newBuilder(csrfUri).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, csrf.statusCode());

        List<String> xsrfCookies = csrf.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN="))
            .toList();
        assertEquals(1, xsrfCookies.size());
        String setCookie = xsrfCookies.get(0);
        assertTrue(setCookie.contains("HttpOnly"));
        assertFalse(setCookie.contains("MAFIASESSION"));

        String rawToken = setCookie.substring(
            "XSRF-TOKEN=".length(), setCookie.indexOf(';')
        );
        String body = csrf.body();
        String headerName = body.replaceFirst(
            ".*\\\"headerName\\\":\\\"([^\\\"]+)\\\".*", "$1"
        );
        String maskedToken = body.replaceFirst(
            ".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1"
        );
        assertFalse(rawToken.equals(maskedToken));

        URI loginUri = URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login");
        String payload = "{\"username\":\"csrf_probe\",\"password\":\"invalid-shape-password\",\"captcha\":\"ABCDE\"}";
        HttpRequest login = HttpRequest.newBuilder(loginUri)
            .header("Cookie", "XSRF-TOKEN=" + rawToken)
            .header(headerName, maskedToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        HttpResponse<String> response = http.send(
            login, HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("CAPTCHA_INVALID"));
    }
}
