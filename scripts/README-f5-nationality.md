# F5: expose a new student column

The `f5-nationality` branch adds a student nationality column in PostgreSQL and makes it available through the university ontology. Existing queries and the other sources remain available.

## Run

Start the [baseline environment](../README.md) on `main`, then run:

```powershell
.\scripts\f5-add-column.ps1
```

The script switches to `f5-nationality`, adds and populates the column for ten students, rebuilds the affected modules, and restarts the environment. It requires a clean working tree.

## Check the result

Open [the access interface](http://localhost:8080), use `teacher-token`, and run:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name ?nationality WHERE {
  ?student a :Student ; :fullName ?name ; :nationality ?nationality .
} ORDER BY ?name
```

Expect ten rows with student names and their nationality. The baseline student query in [test.md](../test.md) should still work.

## What the branch contains

This demonstration extends an existing source instead of introducing another service. Three resource files differ from `main`:

- `ontology/src/main/resources/ontology/university.ttl` adds nationality as a property of Student, making it part of the vocabulary available to queries.
- `mappings/src/main/resources/mappings/university-pg.obda` reads the new column from the PostgreSQL students table and exposes its values through the nationality property.
- `metadata-service/src/main/resources/catalogue.yml` adds nationality to the terms provided by Student Registry, allowing the mediator to route queries for it to the correct source.

The database change is applied by `scripts/f5-add-column.ps1`, which is available on all branches. It adds the column, populates ten seeded students with Dutch, German, or Italian values, and rebuilds and restarts the affected services. `scripts/f5-rollback.ps1` removes the column and restores the baseline configuration.

The other source services need no implementation changes. Nationality is exposed for reading; the demonstration does not extend the student write operations to accept it.

## Roll back

Use the dedicated script to return to `main` and remove the nationality column and its values:

```powershell
.\scripts\f5-rollback.ps1
```
