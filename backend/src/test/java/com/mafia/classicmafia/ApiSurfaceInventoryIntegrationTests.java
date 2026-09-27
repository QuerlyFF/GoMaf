package com.mafia.classicmafia;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApiSurfaceInventoryIntegrationTests {
    @Autowired RequestMappingHandlerMapping mappings;

    @Test
    void everyApiV1RouteIsExplicitlyInventoried() {
        Set<String> actual = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().equals("com.mafia.classicmafia.api")) return;
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1/")) continue;
                info.getMethodsCondition().getMethods().forEach(http -> actual.add(http.name() + " " + path));
            }
        });
        assertThat(actual).containsExactlyElementsOf(expected());
    }
    private Set<String> expected() {
        return new TreeSet<>(Set.of(
            "GET /api/v1/session",
            "GET /api/v1/csrf",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/logout",
            "GET /api/v1/news",
            "POST /api/v1/news",
            "PUT /api/v1/news/{id}",
            "DELETE /api/v1/news/{id}",
            "GET /api/v1/rules",
            "PUT /api/v1/rules",
            "GET /api/v1/games",
            "GET /api/v1/games/{id}",
            "POST /api/v1/games",
            "PUT /api/v1/games/{id}",
            "DELETE /api/v1/games/{id}",
            "GET /api/v1/admin/moderators/defaults",
            "GET /api/v1/admin/moderators",
            "GET /api/v1/admin/moderators/eligible",
            "PUT /api/v1/admin/moderators/defaults",
            "POST /api/v1/admin/moderators/{id}",
            "DELETE /api/v1/admin/moderators/{id}",
            "PUT /api/v1/admin/moderators/{id}/scopes",
            "GET /api/v1/players/{id}/administration",
            "PATCH /api/v1/players/{id}/rating",
            "PATCH /api/v1/players/{id}/username",
            "PUT /api/v1/players/{id}/password",
            "DELETE /api/v1/players/{id}/avatar",
            "PATCH /api/v1/players/{id}/profile",
            "DELETE /api/v1/players/{id}",
            "GET /api/v1/players",
            "GET /api/v1/players/{id}",
            "GET /api/v1/players/{id}/games",
            "GET /api/v1/players/{id}/avatar",
            "GET /api/v1/players/{id}/banner",
            "GET /api/v1/profile",
            "GET /api/v1/profile/games",
            "GET /api/v1/profile/avatar",
            "PATCH /api/v1/profile/about",
            "POST /api/v1/profile/avatar",
            "POST /api/v1/profile/banner",
            "DELETE /api/v1/profile/avatar",
            "DELETE /api/v1/profile/banner",
            "GET /api/v1/rating",
            "GET /api/v1/schedule",
            "POST /api/v1/schedule",
            "PUT /api/v1/schedule/{id}",
            "DELETE /api/v1/schedule/{id}",
            "POST /api/v1/schedule/{id}/participation",
            "GET /api/v1/tournaments",
            "GET /api/v1/tournaments/{id}",
            "POST /api/v1/tournaments",
            "PUT /api/v1/tournaments/{id}",
            "DELETE /api/v1/tournaments/{id}",
            "POST /api/v1/tournaments/{id}/participation",
            "POST /api/v1/tournaments/{id}/invitations",
            "DELETE /api/v1/tournaments/{id}/invitations/{userId}"
        ));
    }
}
