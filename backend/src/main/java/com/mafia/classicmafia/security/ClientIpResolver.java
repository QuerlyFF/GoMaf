package com.mafia.classicmafia.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

@Component
public class ClientIpResolver {
    private final boolean trustProxyHeaders;

    public ClientIpResolver(@Value("${app.security.trust-proxy-headers:false}") boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public String resolve(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",", 2)[0].trim();
                String parsed = literalIp(first);
                if (parsed != null) {
                    return parsed;
                }
            }
            String realIp = literalIp(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return realIp;
            }
        }

        String direct = literalIp(request.getRemoteAddr());
        return direct == null ? "unknown" : direct;
    }

    private String literalIp(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isBlank() || candidate.length() > 64 || !candidate.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            byte[] raw = address.getAddress();
            if (raw.length == 16) {
                // IPv6 privacy addresses rotate frequently. Rate-limit the /64 network
                // rather than a single temporary address so mass-registration limits
                // cannot be trivially bypassed by changing the interface identifier.
                for (int i = 8; i < raw.length; i++) {
                    raw[i] = 0;
                }
                return InetAddress.getByAddress(raw).getHostAddress() + "/64";
            }
            return address.getHostAddress();
        } catch (Exception exception) {
            return null;
        }
    }
}
