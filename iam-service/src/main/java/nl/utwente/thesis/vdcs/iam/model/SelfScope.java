package nl.utwente.thesis.vdcs.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/** Defines the ownership edge used to restrict a principal to their own rows. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SelfScope {

    /** Ontology object property linking owner to owned entity (e.g. "hasGrade"). */
    private String ownerPredicate;

    /** Resource-IRI segment for the owner type (e.g. "student"). */
    private String ownerType;

    /** Principal attribute holding the owner id (e.g. "studentId"). */
    private String ownerIdAttribute;

    public SelfScope() {}

}
