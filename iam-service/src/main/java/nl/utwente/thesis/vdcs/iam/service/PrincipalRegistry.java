package nl.utwente.thesis.vdcs.iam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import nl.utwente.thesis.vdcs.iam.model.Principal;
import nl.utwente.thesis.vdcs.iam.model.PrincipalList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of principals, loaded once at startup from
 * iam.yml, keyed by bearer token.
 */
@Service
public class PrincipalRegistry {

    private static final Logger log = LoggerFactory.getLogger(PrincipalRegistry.class);

    private final Map<String, Principal> byToken = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("iam.yml").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            PrincipalList list = mapper.readValue(is, PrincipalList.class);
            for (Principal p : list.getPrincipals()) {
                byToken.put(p.getToken(), p);
            }
            log.info("PrincipalRegistry loaded {} principals", byToken.size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load iam.yml", e);
        }
    }

    /** Resolve a bearer token to its principal, if known. */
    public Optional<Principal> resolve(String token) {
        return Optional.ofNullable(token == null ? null : byToken.get(token));
    }

    public int size() { return byToken.size(); }
}
