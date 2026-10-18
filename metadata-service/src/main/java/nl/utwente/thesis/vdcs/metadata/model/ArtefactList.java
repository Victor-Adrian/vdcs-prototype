package nl.utwente.thesis.vdcs.metadata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/** Wrapper for the top-level artefacts: list in artefacts.yml. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class ArtefactList {

    private List<ArtefactDescriptor> artefacts = new ArrayList<>();

}
