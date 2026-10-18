package nl.utwente.thesis.vdcs.secret.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/** Wrapper for the top-level secrets: list in secrets.yml. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SecretList {
    private List<SecretEntry> secrets = new ArrayList<>();
}
