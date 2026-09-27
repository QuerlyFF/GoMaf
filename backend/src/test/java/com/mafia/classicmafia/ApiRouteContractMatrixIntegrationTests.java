package com.mafia.classicmafia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ApiRouteContractMatrixIntegrationTests {
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private static final Set<String> QUERY_BOUNDARY = Set.of(
        "GET /api/v1/players",
        "GET /api/v1/games",
        "GET /api/v1/rating",
        "GET /api/v1/players/{id}/games",
        "GET /api/v1/profile/games",
        "GET /api/v1/admin/moderators",
        "GET /api/v1/admin/moderators/eligible",
        "GET /api/v1/tournaments"
    );

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void matrixMatchesRuntimeSurfaceAndRequiredClassifications() throws Exception {
        Map<String, Row> matrix = loadMatrix();
        Set<String> actual = runtimeRoutes();
        assertThat(matrix).hasSize(57);
        assertThat(new TreeSet<>(matrix.keySet())).containsExactlyElementsOf(actual);

        for (Map.Entry<String, Row> entry : matrix.entrySet()) {
            String route = entry.getKey();
            Row row = entry.getValue();
            assertThat(row.primaryEvidence()).isNotBlank();
            assertThat(row.dimensions()).contains("runtime-inventory", "domain-flow");
            Class.forName("com.mafia.classicmafia." + row.primaryEvidence());
            if (route.contains("{id}") || route.contains("{userId}")) {
                assertThat(row.dimensions()).as(route).contains("path-type");
            }
            if (!route.startsWith("GET ")) {
                assertThat(row.dimensions()).as(route).contains("mutation-envelope");
            }
        }

        Set<String> jsonBody = new TreeSet<>();
        Set<String> queryBoundary = new TreeSet<>();
        matrix.forEach((route, row) -> {
            if (row.dimensions().contains("json-body")) jsonBody.add(route);
            if (row.dimensions().contains("query-boundary")) queryBoundary.add(route);
        });
        assertThat(jsonBody).containsExactlyInAnyOrderElementsOf(requiredJsonBodyRoutes());
        assertThat(queryBoundary).containsExactlyInAnyOrderElementsOf(QUERY_BOUNDARY);
    }

    @Test
    void everyAuthnBoundaryRowRejectsAnonymousRequests() throws Exception {
        for (Map.Entry<String, Row> entry : loadMatrix().entrySet()) {
            if (!entry.getValue().dimensions().contains("authn-boundary")) continue;
            String[] parts = entry.getKey().split(" ", 2);
            HttpMethod method = HttpMethod.valueOf(parts[0]);
            String path = parts[1].replace("{id}", "999999").replace("{userId}", "999999");
            MockHttpServletRequestBuilder builder = request(method, path).with(csrf());
            if (method != HttpMethod.GET) {
                builder.contentType(MediaType.APPLICATION_JSON).content("{}");
            }
            mvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    private Set<String> runtimeRoutes() {
        Set<String> actual = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().equals("com.mafia.classicmafia.api")) return;
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1/")) continue;
                info.getMethodsCondition().getMethods().forEach(http -> actual.add(http.name() + " " + path));
            }
        });
        return actual;
    }

    private Map<String, Row> loadMatrix() throws Exception {
        var stream = getClass().getResourceAsStream("/api-route-contract-matrix.tsv");
        assertThat(stream).isNotNull();
        Map<String, Row> rows = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertThat(header).isEqualTo("route\tprimary_evidence\tcontract_dimensions");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\\t", -1);
                assertThat(fields).hasSize(3);
                Set<String> dimensions = new TreeSet<>(Arrays.asList(fields[2].split(";")));
                assertThat(rows.put(fields[0], new Row(fields[1], dimensions))).as(fields[0]).isNull();
            }
        }
        return rows;
    }

    private Set<String> requiredJsonBodyRoutes() {
        return Set.of(
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/register",
            "POST /api/v1/news",
            "PUT /api/v1/news/{id}",
            "PUT /api/v1/rules",
            "POST /api/v1/games",
            "PUT /api/v1/games/{id}",
            "PUT /api/v1/admin/moderators/defaults",
            "POST /api/v1/admin/moderators/{id}",
            "PUT /api/v1/admin/moderators/{id}/scopes",
            "PATCH /api/v1/players/{id}/rating",
            "PATCH /api/v1/players/{id}/username",
            "PUT /api/v1/players/{id}/password",
            "PATCH /api/v1/players/{id}/profile",
            "DELETE /api/v1/players/{id}",
            "PATCH /api/v1/profile/about",
            "POST /api/v1/schedule",
            "PUT /api/v1/schedule/{id}",
            "POST /api/v1/tournaments",
            "PUT /api/v1/tournaments/{id}",
            "POST /api/v1/tournaments/{id}/invitations"
        );
    }

    private record Row(String primaryEvidence, Set<String> dimensions) {}
}
