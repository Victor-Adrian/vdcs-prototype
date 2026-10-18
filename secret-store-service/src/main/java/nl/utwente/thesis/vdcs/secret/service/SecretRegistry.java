package nl.utwente.thesis.vdcs.secret.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import nl.utwente.thesis.vdcs.secret.model.SecretEntry;
import nl.utwente.thesis.vdcs.secret.model.SecretList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of source credentials, loaded once at startup from
 * secrets.yml. The authorisation check (is this service token
 * permitted to fetch this source's credentials?) lives here.
 */
@Service
public class SecretRegistry {

    private static final Logger log = LoggerFactory.getLogger(SecretRegistry.class);

    private final Map<String, SecretEntry> byId = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("secrets.yml").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            SecretList list = mapper.readValue(is, SecretList.class);
            for (SecretEntry e : list.getSecrets()) {
                byId.put(e.getSourceId(), e);
            }
            log.info("SecretRegistry loaded {} source credential entries", byId.size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load secrets.yml", e);
        }
    }

    public Optional<SecretEntry> byId(String sourceId) {
        return Optional.ofNullable(byId.get(sourceId));
    }

    /**
     * Returns true when the given service token is authorised to fetch
     * the given source's credentials, per the source's allowedTokens.
     */
    public boolean isAuthorised(String sourceId, String serviceToken) {
        SecretEntry e = byId.get(sourceId);
        return e != null && serviceToken != null
                && e.getAllowedTokens().contains(serviceToken);
    }

    public int size() { return byId.size(); }
}
