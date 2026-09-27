package com.mafia.classicmafia.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTests {
    @Test
    void unexpectedExceptionNeverLeaksInternalMessage() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestCorrelationFilter())
            .build();

        mvc.perform(get("/test/internal-failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(content().string(not(containsString("jdbc:postgresql://private-db"))))
            .andExpect(content().string(not(containsString("sensitive-internal-detail"))));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test/internal-failure")
        String fail() {
            throw new IllegalStateException("sensitive-internal-detail jdbc:postgresql://private-db");
        }
    }
}
