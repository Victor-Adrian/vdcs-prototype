package nl.utwente.thesis.vdcs.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Principal definition loaded from iam.yml. Entity permissions can allow,
 * deny, or restrict reads to data identified by a {@link SelfScope}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class Principal {

    /** Loaded from policy but omitted from responses. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String token;

    private String identity;
    private List<String> roles = new ArrayList<>();
    private boolean mayWrite = false;

    /** "ALLOW" or "DENY" - applied to entities not explicitly listed. */
    private String defaultPermission = "DENY";

    /** entity to "ALLOW" | "DENY" | "SELF". */
    private Map<String, String> permissions = new LinkedHashMap<>();

    /** entity to its row-level binding, for SELF permissions. */
    private Map<String, SelfScope> selfScopes = new LinkedHashMap<>();

    /** arbitrary identity attributes, e.g. studentId. */
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public Principal() {}

}
