package nl.utwente.thesis.vdcs.access.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IAM principal and permissions returned by the authentication endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class Principal {

    private String identity;
    private List<String> roles = new ArrayList<>();
    private boolean mayWrite = false;
    private String defaultPermission = "DENY";
    private Map<String, String> permissions = new LinkedHashMap<>();
    private Map<String, SelfScope> selfScopes = new LinkedHashMap<>();
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public Principal() {}

    /** Permission for one entity: ALLOW / DENY / SELF, falling back to the default. */
    public String permissionFor(String entity) {
        return permissions.getOrDefault(entity, defaultPermission);
    }

    /** SELF row-level binding for an entity, if any. */
    public SelfScope selfScopeFor(String entity) {
        return selfScopes.get(entity);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Setter
    public static class SelfScope {
        private String ownerPredicate;
        private String ownerType;
        private String ownerIdAttribute;
    }
}
