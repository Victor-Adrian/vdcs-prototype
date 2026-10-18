package nl.utwente.thesis.vdcs.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/** Wrapper for the top-level principals: list in iam.yml. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class PrincipalList {
    private List<Principal> principals = new ArrayList<>();
}
