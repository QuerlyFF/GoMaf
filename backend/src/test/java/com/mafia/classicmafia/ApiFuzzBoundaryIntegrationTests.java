package com.mafia.classicmafia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
class ApiFuzzBoundaryIntegrationTests {
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    private final AtomicInteger ip = new AtomicInteger(1);

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test
    void malformedLoginJsonCorpusNeverReturns5xx() throws Exception {
        List<String> bodies = List.of(
            "",
            "{",
            "[]",
            "null",
            "{\"username\":{}}",
            "{\"username\":[],\"password\":true,\"captcha\":1}",
            "{\"username\":1e309,\"password\":\"x\",\"captcha\":\"x\"}",
            "{\"username\":\"a\",\"username\":\"b\",\"password\":\"x\",\"captcha\":\"x\"}",
            "{\"username\":\"\\u0000\\u202E😀\",\"password\":\"x\",\"captcha\":\"x\"}",
            "{\"username\":\"" + "u".repeat(10000) + "\",\"password\":\"x\",\"captcha\":\"x\"}"
        );
        for (String body : bodies) {
            assertClientError(post("/api/v1/auth/login")
                .with(csrf()).with(request -> {
                    request.setRemoteAddr("2001:db8:44::" + ip.getAndIncrement());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }

    @Test
    void numericAndPathOverflowCorpusNeverReturns5xx() throws Exception {
        List<String> paths = List.of(
            "/api/v1/players?page=2147483648",
            "/api/v1/players?page=-2147483649",
            "/api/v1/players?size=2147483648",
            "/api/v1/players?size=-2147483649",
            "/api/v1/players/999999999999999999999999999999",
            "/api/v1/players/999999999999999999999999999999/games",
            "/api/v1/games/999999999999999999999999999999",
            "/api/v1/tournaments/999999999999999999999999999999",
            "/api/v1/players?q=" + "q".repeat(4096)
        );
        for (String path : paths) {
            assertClientError(get(path));
        }
    }

    @Test
    void malformedRegistrationCorpusNeverReturns5xx() throws Exception {
        List<String> bodies = List.of(
            "{}",
            "{\"username\":null,\"password\":null,\"passwordConfirm\":null,\"captcha\":null}",
            "{\"username\":false,\"password\":[],\"passwordConfirm\":{},\"captcha\":0}",
            "{\"username\":\"a\",\"username\":\"b\",\"password\":\"123\",\"passwordConfirm\":\"123\",\"captcha\":\"x\"}",
            "{\"username\":\"e\\u0301\\u202E😀\",\"password\":\"password-password\",\"passwordConfirm\":\"password-password\",\"captcha\":\"x\"}"
        );
        for (String body : bodies) {
            assertClientError(post("/api/v1/auth/register")
                .with(csrf()).with(request -> {
                    request.setRemoteAddr("2001:db8:45::" + ip.getAndIncrement());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }

    private void assertClientError(MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status).as("status for %s", result.getRequest().getRequestURI())
            .isBetween(400, 499);
        assertThat(body).doesNotContain("java.lang.", "org.springframework.", "stacktrace", "StackTrace");
    }
}
