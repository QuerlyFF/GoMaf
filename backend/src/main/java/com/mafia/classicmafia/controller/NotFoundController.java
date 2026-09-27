package com.mafia.classicmafia.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class NotFoundController {
    private static final String BODY = """
        <!doctype html>
        <html lang="ru">
          <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <meta name="robots" content="noindex,nofollow" />
            <title>Страница не найдена — GoMaf</title>
          </head>
          <body>
            <main>
              <h1>404</h1>
              <p>Страница не найдена.</p>
              <a href="/news">Перейти к новостям GoMaf</a>
            </main>
          </body>
        </html>
        """;

    @GetMapping("/not-found")
    ResponseEntity<String> notFound() {
        return response();
    }

    static ResponseEntity<String> response() {
        return ResponseEntity.status(404)
            .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noStore())
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(BODY);
    }

    public static void write(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.getWriter().write(BODY);
    }
}
