package dev.kbmd.web;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * There is no login: the API is protected by listening on localhost only. A web page from anywhere can still make
 * the browser send requests here, so this filter turns away what did not come from the app's own pages.
 * <ul>
 * <li>Writes with a foreign {@code Origin} or {@code Sec-Fetch-Site} (cross-site form posts and uploads, which
 * browsers send without a preflight) are refused. Requests without those headers (curl, scripts) pass.</li>
 * <li>While bound to a loopback address, the {@code Host} header has to name it too: a page that resolves its own
 * domain to 127.0.0.1 (DNS rebinding) is served nothing.</li>
 * </ul>
 */
@Component
public class LocalOriginFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    private final boolean loopbackOnly;

    public LocalOriginFilter(@Value("${server.address:127.0.0.1}") String address) {
        this.loopbackOnly = LOOPBACK.contains(address.toLowerCase(Locale.ROOT));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        if (loopbackOnly && host != null && !LOOPBACK.contains(hostName(host))) { // browsers always send Host
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Unexpected Host header");
            return;
        }
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod());
        if (!read && crossSite(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site requests are not accepted");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean crossSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.equals("null")) {
            return origin != null; // "null" is what sandboxed and file: pages send
        }
        try {
            // any loopback origin is fine: the Vite dev server proxies from another port
            URI uri = URI.create(origin);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return !LOOPBACK.contains(host);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static String hostName(String host) {
        if (host == null) {
            return "";
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("[")) {
            return lower.substring(0, lower.indexOf(']') + 1);
        }
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(0, colon);
    }
}
