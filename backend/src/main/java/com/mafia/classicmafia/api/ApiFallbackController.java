package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApiFallbackController {
    @RequestMapping("/{*path}")
    ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        Object requestId = request.getAttribute(ApiRequestContext.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
            404,
            "API_NOT_FOUND",
            "API endpoint не найден.",
            List.of(),
            requestId == null ? "" : requestId.toString()
        ));
    }
}
