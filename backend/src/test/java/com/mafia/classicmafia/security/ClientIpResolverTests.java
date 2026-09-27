package com.mafia.classicmafia.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTests {
    @Test
    void ipv4IsKeptAsAddress() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.25");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.25");
    }

    @Test
    void ipv6PrivacyAddressesShareTheSame64BitRateLimitIdentity() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        MockHttpServletRequest first = new MockHttpServletRequest();
        MockHttpServletRequest second = new MockHttpServletRequest();
        first.setRemoteAddr("2001:db8:1234:5678:1111:2222:3333:4444");
        second.setRemoteAddr("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd");

        assertThat(resolver.resolve(first)).isEqualTo(resolver.resolve(second));
        assertThat(resolver.resolve(first)).endsWith("/64");
    }
}
