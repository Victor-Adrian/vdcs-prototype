# F4: add a Library Loans source

The `f4-loans` branch adds a fifth, read-only source to the baseline. The demonstration joins book loans stored in H2 to student names held in PostgreSQL.

## Run

Start the [baseline environment](../README.md) on `main`, then run:

```powershell
.\scripts\f4-add-source.ps1
```

The script switches to `f4-loans`, builds and starts the extension, and checks that the new source is available. It requires a clean working tree.

## Check the result

Open [the access interface](http://localhost:8080), use `teacher-token`, and run:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name ?title WHERE {
  ?s :fullName ?name ; :hasLoan ?loan .
  ?loan :loanTitle ?title .
} ORDER BY ?name
```

Expect five rows with student names and book titles. The [source catalogue](http://localhost:8082/metadata/sources) should list five sources, including `library-loans`.

## What the branch contains

The extension introduces Library Loans as a separate read-only source and connects it to the existing student records through shared student identifiers. Its implementation is contained in the following additions and changes on `f4-loans`:

- `source-access/source-access-loans-service/` is a new Maven module. `LoansH2Server.java` creates and populates the in-memory loans database; `SacController.java` exposes its connection and health information; `LoansSacApplication.java` starts the service. The module's `pom.xml` and `application.yml` define its dependencies and configuration, and `source-access/pom.xml` includes it in the build.
- `ontology/src/main/resources/ontology/university.ttl` adds the Loan class, its properties, and the relationship between a student and their loans.
- `mappings/src/main/resources/mappings/university-loans.obda` maps the loan records to that shared vocabulary, so the mediator can join them with PostgreSQL student data.
- `metadata-service/src/main/resources/catalogue.yml` registers the source and the terms it provides. `artefacts.yml` registers the new mapping so the mediator can obtain it through the metadata service.
- `OntopMediatorService.java` in `semantic-mediator/` adds the connection and query setup for the loans source. The existing source services retain their implementations.
- `scripts/run-all.ps1` and `scripts/stop-all.ps1` include the additional service. The dedicated `f4-add-source.ps1` and `f4-rollback.ps1` helpers, available on all branches, manage the demonstration and its verification.

The result is a fifth source that participates in existing federated reads. Loan writes are outside this demonstration.

## Roll back

Use the dedicated script to return to the four-source baseline on `main`:

```powershell
.\scripts\f4-rollback.ps1
```
