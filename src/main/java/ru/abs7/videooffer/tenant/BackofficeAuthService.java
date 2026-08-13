package ru.abs7.videooffer.tenant;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BackofficeAuthService {
    public static final String SESSION_KEY = "VIDEO_OFFER_MASTER_AUTH";
    public static final String SESSION_CSRF_KEY = "VIDEO_OFFER_MASTER_CSRF";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final String username;
    private final byte[] passwordDigest;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public BackofficeAuthService(
            @Value("${app.backoffice.username:admin}") String username,
            @Value("${app.backoffice.password:CHANGE_ME_BEFORE_PRODUCTION}") String password) {
        this.username = username == null ? "admin" : username.trim();
        this.passwordDigest = digest(password == null ? "" : password);
    }

    public boolean login(String suppliedUser, String suppliedPassword, String remoteAddress, HttpSession session) {
        String key = remoteAddress == null ? "unknown" : remoteAddress;
        Attempt state = attempts.get(key);
        long now = Instant.now().getEpochSecond();
        if (state != null && state.blockedUntil > now) return false;

        boolean ok = username.equals(suppliedUser == null ? "" : suppliedUser.trim())
                && MessageDigest.isEqual(passwordDigest, digest(suppliedPassword == null ? "" : suppliedPassword));
        if (ok) {
            attempts.remove(key);
            session.setAttribute(SESSION_KEY, Boolean.TRUE);
            session.setAttribute(SESSION_CSRF_KEY, newCsrfToken());
            session.setMaxInactiveInterval(8 * 60 * 60);
            return true;
        }
        attempts.compute(key, (ignored, previous) -> {
            boolean previousBlockExpired = previous != null && previous.blockedUntil > 0 && previous.blockedUntil <= now;
            int failures = previous == null || previousBlockExpired ? 1 : previous.failures + 1;
            long blocked = failures >= 5 ? now + 300 : 0;
            return new Attempt(failures >= 5 ? 0 : failures, blocked);
        });
        return false;
    }

    public boolean isAuthenticated(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
    }

    public void require(HttpSession session) {
        if (!isAuthenticated(session)) throw new BackofficeUnauthorizedException();
    }

    public String csrfToken(HttpSession session) {
        require(session);
        Object existing = session.getAttribute(SESSION_CSRF_KEY);
        if (existing instanceof String token && !token.isBlank()) return token;
        String token = newCsrfToken();
        session.setAttribute(SESSION_CSRF_KEY, token);
        return token;
    }

    public void requireMutation(HttpSession session, String suppliedToken) {
        require(session);
        String expected = csrfToken(session);
        byte[] supplied = (suppliedToken == null ? "" : suppliedToken).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied)) {
            throw new BackofficeCsrfException();
        }
    }

    public void logout(HttpSession session) {
        if (session != null) session.invalidate();
    }

    private String newCsrfToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private record Attempt(int failures, long blockedUntil) {}

    public static class BackofficeUnauthorizedException extends RuntimeException {}
    public static class BackofficeCsrfException extends RuntimeException {}
}
