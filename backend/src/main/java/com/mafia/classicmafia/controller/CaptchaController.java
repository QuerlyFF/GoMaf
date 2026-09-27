package com.mafia.classicmafia.controller;

import com.mafia.classicmafia.security.CaptchaService;
import com.mafia.classicmafia.security.ClientIpResolver;
import com.mafia.classicmafia.security.IpHashService;
import com.mafia.classicmafia.security.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class CaptchaController {
    private final CaptchaService captchaService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final IpHashService ipHashService;

    public CaptchaController(
        CaptchaService captchaService,
        RateLimitService rateLimitService,
        ClientIpResolver clientIpResolver,
        IpHashService ipHashService
    ) {
        this.captchaService = captchaService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.ipHashService = ipHashService;
    }

    @GetMapping(value = "/captcha/{purpose}", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> captcha(@PathVariable String purpose, HttpServletRequest request) throws IOException {
        if (!(purpose.equals("login") || purpose.equals("register"))) {
            return ResponseEntity.notFound().build();
        }

        String ipHash = ipHashService.hash(clientIpResolver.resolve(request));
        if (!rateLimitService.allowCaptcha(ipHash)) {
            return ResponseEntity.status(429)
                .cacheControl(CacheControl.noStore())
                .body(new byte[0]);
        }

        byte[] png = captchaService.create(request.getSession(true), purpose);
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .body(png);
    }
}
