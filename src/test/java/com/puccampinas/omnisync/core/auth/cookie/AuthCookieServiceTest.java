package com.puccampinas.omnisync.core.auth.cookie;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AuthCookieServiceTest {

    private final AuthCookieService authCookieService =
            new AuthCookieService("ACCESS_TOKEN", "REFRESH_TOKEN", false);

    @Test
    void setAccessCookie_shouldAddConfiguredAccessCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookieService.setAccessCookie(response, "access-token-value", 900);

        Cookie cookie = response.getCookie("ACCESS_TOKEN");

        assertNotNull(cookie);
        assertEquals("ACCESS_TOKEN", cookie.getName());
        assertEquals("access-token-value", cookie.getValue());
        assertEquals(900, cookie.getMaxAge());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertFalse(cookie.getSecure());
    }

    @Test
    void setRefreshCookie_shouldAddConfiguredRefreshCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookieService.setRefreshCookie(response, "refresh-token-value", 604800);

        Cookie cookie = response.getCookie("REFRESH_TOKEN");

        assertNotNull(cookie);
        assertEquals("REFRESH_TOKEN", cookie.getName());
        assertEquals("refresh-token-value", cookie.getValue());
        assertEquals(604800, cookie.getMaxAge());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertFalse(cookie.getSecure());
    }

    @Test
    void clearAccessCookie_shouldAddExpiredAccessCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookieService.clearAccessCookie(response);

        Cookie cookie = response.getCookie("ACCESS_TOKEN");

        assertNotNull(cookie);
        assertEquals("ACCESS_TOKEN", cookie.getName());
        assertEquals("", cookie.getValue());
        assertEquals(0, cookie.getMaxAge());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertFalse(cookie.getSecure());
    }

    @Test
    void clearRefreshCookie_shouldAddExpiredRefreshCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookieService.clearRefreshCookie(response);

        Cookie cookie = response.getCookie("REFRESH_TOKEN");

        assertNotNull(cookie);
        assertEquals("REFRESH_TOKEN", cookie.getName());
        assertEquals("", cookie.getValue());
        assertEquals(0, cookie.getMaxAge());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertFalse(cookie.getSecure());
    }

    @Test
    void clearAuthCookies_shouldClearBothCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookieService.clearAuthCookies(response);

        Cookie accessCookie = response.getCookie("ACCESS_TOKEN");
        Cookie refreshCookie = response.getCookie("REFRESH_TOKEN");

        assertNotNull(accessCookie);
        assertNotNull(refreshCookie);

        assertEquals("", accessCookie.getValue());
        assertEquals(0, accessCookie.getMaxAge());

        assertEquals("", refreshCookie.getValue());
        assertEquals(0, refreshCookie.getMaxAge());
    }

    @Test
    void getAccessCookieName_shouldReturnConfiguredName() {
        assertEquals("ACCESS_TOKEN", authCookieService.getAccessCookieName());
    }

    @Test
    void getRefreshCookieName_shouldReturnConfiguredName() {
        assertEquals("REFRESH_TOKEN", authCookieService.getRefreshCookieName());
    }

    @Test
    void cookiesShouldRespectSecureFlagWhenEnabled() {
        AuthCookieService secureCookieService =
                new AuthCookieService("ACCESS_TOKEN", "REFRESH_TOKEN", true);

        MockHttpServletResponse response = new MockHttpServletResponse();

        secureCookieService.setAccessCookie(response, "secure-token", 900);

        Cookie cookie = response.getCookie("ACCESS_TOKEN");

        assertNotNull(cookie);
        assertTrue(cookie.getSecure());
    }
}