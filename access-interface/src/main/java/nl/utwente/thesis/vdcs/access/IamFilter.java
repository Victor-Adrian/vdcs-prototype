package nl.utwente.thesis.vdcs.access;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nl.utwente.thesis.vdcs.access.auth.IamClient;
import nl.utwente.thesis.vdcs.access.auth.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates API requests through the IAM service and stores the
 * resolved principal on the request. Static resources remain public.
 */
@Component
@Order(1)
public class IamFilter implements Filter {

    public static final String PRINCIPAL_ATTR = "vdcs.principal";

    private final IamClient iam;

    public IamFilter(IamClient iam) {
        this.iam = iam;
    }

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // Only enforce on API endpoints; the static UI is public.
        if (!req.getServletPath().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(req);
        Optional<Principal> principal = iam.authenticate(token);
        if (principal.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or invalid bearer token");
            return;
        }

        req.setAttribute(PRINCIPAL_ATTR, principal.get());
        chain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        return null;
    }
}
