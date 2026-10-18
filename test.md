# Manual tests

Use the [README](README.md) to start the baseline on `main`. Open `http://localhost:8080`. Paste reads into the Query tab; in the Write tab, select JSON canonical or SPARQL Update as indicated. Run PowerShell commands from the repository root.

These scenarios use the supplied synthetic seed data. Each test has its own preconditions and cleanup, so it can be run individually. Complete cleanup before moving to another scenario. For automated coverage of the running system, use `scripts/run-tests.ps1`.

## 1. Read each source

### Preconditions

- The four-source baseline is running with its seeded databases.
- The interface uses `teacher-token`.

### Steps and expected results

Run each query separately. Expect nonempty results from all four sources.

**Students (PostgreSQL):**

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?id ?name WHERE {
  ?s a :Student ; :studentId ?id ; :fullName ?name .
} ORDER BY ?id
```

**Courses (MySQL):**

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?title WHERE { ?c a :Course ; :courseTitle ?title . }
ORDER BY ?title
```

**Clubs (MongoDB via H2):**

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?club WHERE { ?m a :ClubMembership ; :clubName ?club . }
```

**Publications (Fuseki):**

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?title WHERE { ?p :publicationTitle ?title . }
ORDER BY ?title
```

### Cleanup

None. These queries do not change source data.

## 2. Join three sources

### Preconditions

- The baseline is running with seeded student, publication, and activity records.
- The interface uses `teacher-token`.

### Steps and expected results

This query joins PostgreSQL student names, Fuseki publications, and MongoDB clubs:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT DISTINCT ?name ?title ?club WHERE {
  ?s :fullName ?name ; :hasPublication ?p ; :hasActivity ?a .
  ?p :publicationTitle ?title .
  ?a :hasClubMembership ?m . ?m :clubName ?club .
} ORDER BY ?name
```

Expect rows such as Alice Chen, *Query Rewriting over Virtual Knowledge Graphs*, and Chess Club. Multiple publications and clubs produce multiple combinations for a student.

### Cleanup

None. The query does not change source data.

## 3. Check access control

### Preconditions

- The baseline is running with its seeded data and demo access policies.
- Student 1 is Alice Chen. Confirm her current name with `teacher-token` and the student query below before testing the rejected write.
- The `alice-token` account is read-only and restricted to its own grades.

### Steps and expected results

Change to `alice-token`. This student query should succeed:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name WHERE { ?s a :Student ; :studentId 1 ; :fullName ?name . }
```

This course query should return HTTP 403:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?title WHERE { ?c a :Course ; :courseTitle ?title . }
```

This grade query should return only Alice Chen's six grades, with her name alongside each score:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name ?score WHERE {
  ?student :fullName ?name ;
           :hasGrade ?g .
  ?g a :Grade ;
     :score ?score .
}
```

Submit this JSON write with Alice's token; expect HTTP 403 and no change:

```json
{"operation":"UPDATE","entityType":"Student","payload":{"fullName":"SHOULD NOT CHANGE"},"filter":{"studentId":1}}
```

Set the token to `invalid-token` and run the student query again: expect HTTP 401.

### Cleanup

Restore `teacher-token` and confirm that student 1 is still named Alice Chen. No data cleanup is needed when the write is rejected. If it unexpectedly succeeded, the test failed; restore the seeded name using JSON canonical mode:

```json
{"operation":"UPDATE","entityType":"Student","payload":{"fullName":"Alice Chen"},"filter":{"studentId":1}}
```

## 4. Create, update, and delete a grade

### Preconditions

- The baseline is running, and the interface uses `teacher-token`.
- Seeded student 1 and assessment 1 exist. These source checks should each return one row:

```powershell
docker exec student-registry psql -U registry -d registry -c "SELECT id FROM students WHERE id=1;"
docker exec -e MYSQL_PWD=academics academic-records mysql -u academics academics -e "SELECT id FROM assessments WHERE id=1;"
```

Check that grade 700 does not exist using the query below. If it does, choose an unused ID and use it throughout this scenario, including cleanup.

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?score WHERE { ?g a :Grade ; :gradeId 700 ; :score ?score . }
```

### Steps and expected results

Create the grade in JSON canonical mode:

```json
{"operation":"INSERT","entityType":"Grade","payload":{"gradeId":700,"studentId":1,"assessmentId":1,"score":60.0},"filter":{}}
```

Expect HTTP 204 and a score of 60 in the read-back query above. Change it in SPARQL Update mode:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
DELETE { ?g :score ?old }
INSERT { ?g :score 65.0 }
WHERE { ?g a :Grade ; :gradeId 700 ; :score ?old . }
```

Expect HTTP 204 and a score of 65 in the read-back query.

### Cleanup

Delete the grade created by this test using JSON canonical mode:

```json
{"operation":"DELETE","entityType":"Grade","payload":{},"filter":{"gradeId":700}}
```

Expect HTTP 204. Repeat the read-back query and confirm that it returns no rows. Perform this cleanup even if the update step failed, provided the grade was created.

## 5. Write one FinalGrade to two sources

### Preconditions

- The baseline is running, and the interface uses `teacher-token`.
- For student 1 and course 1, each database must have exactly one matching row with a final grade of 81.20. Check both sources:

```powershell
docker exec student-registry psql -U registry -d registry -c "SELECT id, final_grade FROM enrollments WHERE student_id=1 AND course_id=1;"
docker exec -e MYSQL_PWD=academics academic-records mysql -u academics academics -e "SELECT final_grade FROM final_grades WHERE student_id=1 AND course_id=1;"
```

Proceed only when both checks match this starting state.

### Steps and expected results

Submit in JSON canonical mode:

```json
{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90.50},"filter":{"finalStudentId":1,"finalCourseId":1}}
```

Expect HTTP 204. Repeat both database checks: each should show 90.50. This ontology query should also show 90.50, but it reads only PostgreSQL's copy and cannot verify synchronization by itself:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?score WHERE {
  ?fg a :FinalGrade ; :finalStudentId 1 ; :finalCourseId 1 ; :finalScore ?score .
}
```

### Cleanup

Restore the starting value using JSON canonical mode:

```json
{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":81.20},"filter":{"finalStudentId":1,"finalCourseId":1}}
```

Repeat both database checks from this test's preconditions and confirm that both copies are 81.20. Cleanup is complete only when both values are restored.

## 6. Reject invalid FinalGrade requests before writing

### Preconditions

- The baseline is running, and the interface uses `teacher-token`.
- Student/course pair 1/1 has exactly one matching row with grade 81.20 in each database.
- Programme 1 exists with both its name and department set to `Computer Science`, as in the seed data.

Check these conditions directly:

```powershell
docker exec student-registry psql -U registry -d registry -c "SELECT id, final_grade FROM enrollments WHERE student_id=1 AND course_id=1; SELECT id, name, department FROM programmes WHERE id=1;"
docker exec -e MYSQL_PWD=academics academic-records mysql -u academics academics -e "SELECT final_grade FROM final_grades WHERE student_id=1 AND course_id=1;"
```

### Steps and expected results

Submit each JSON request separately. Expect HTTP 400 with an explanation for each.

Unsupported extra filter:

```json
{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90},"filter":{"finalStudentId":1,"finalCourseId":1,"finalScore":0}}
```

Conflicting identifiers:

```json
{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90,"finalGradeKey":"1-2"},"filter":{"finalStudentId":1,"finalCourseId":1}}
```

Unsupported deletion:

```json
{"operation":"DELETE","entityType":"FinalGrade","filter":{"finalStudentId":1,"finalCourseId":1}}
```

To check that a later invalid operation blocks the whole request, submit this in SPARQL Update mode:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
INSERT DATA {
  <https://thesis.utwente.nl/vdcs/resource/programme/1>
    :programmeName "SHOULD NOT EXECUTE" ; :programmeDepartment "QA" .
};
DELETE DATA {
  <https://thesis.utwente.nl/vdcs/resource/final-grade/1-1> :finalScore 81.20 .
}
```

Expect HTTP 400 stating that FinalGrade deletion is unsupported, rather than a database error from the earlier insertion.

### Cleanup

Repeat this test's source checks. Both grade copies must still be 81.20, and programme 1's name and department must still be `Computer Science`. No data cleanup is needed when every request is rejected correctly. If an update changed a value, mark the test as failed and restore the affected entity using the appropriate JSON request below:

```json
{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":81.20},"filter":{"finalStudentId":1,"finalCourseId":1}}
```

```json
{"operation":"UPDATE","entityType":"Programme","payload":{"programmeName":"Computer Science","programmeDepartment":"Computer Science"},"filter":{"programmeId":1}}
```

Repeat the checks to confirm restoration. If a row was unexpectedly deleted, stop here and restore the demo fixtures before continuing; these UPDATE requests do not recreate missing rows.

## 7. Observe compensation after a source failure

### Preconditions

- The baseline is running, and the interface uses `teacher-token`.
- PostgreSQL has exactly one enrollment for student 1 and course 3, with a NULL final grade.
- MySQL has no final-grade row for the same student/course pair.

Check the two sources directly:

```powershell
docker exec student-registry psql -U registry -d registry -c "SELECT id, COALESCE(final_grade::text, 'NULL') AS final_grade FROM enrollments WHERE student_id=1 AND course_id=3;"
docker exec -e MYSQL_PWD=academics academic-records mysql -u academics academics -e "SELECT final_grade FROM final_grades WHERE student_id=1 AND course_id=3;"
```

The first command must return one row showing NULL; the second must return no rows. Proceed only with this starting state.

### Steps and expected results

Submit in JSON canonical mode:

```json
{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":77.77},"filter":{"finalStudentId":1,"finalCourseId":3}}
```

Expect a failed request (HTTP 5xx). PostgreSQL applies the change, then MySQL fails because its row is absent. Repeat the source checks: PostgreSQL should be back to NULL, and MySQL should still have no row.

Open `http://localhost:8082/metadata/provenance?operation=WRITE&limit=10`. Look for the failed write and its compensation note.

### Cleanup

Successful compensation restores the starting state, so no additional write is needed. If either source check differs, mark the test as failed. Restore the verified starting state directly in the demo databases:

```powershell
docker exec student-registry psql -U registry -d registry -c "UPDATE enrollments SET final_grade=NULL WHERE student_id=1 AND course_id=3;"
docker exec -e MYSQL_PWD=academics academic-records mysql -u academics academics -e "DELETE FROM final_grades WHERE student_id=1 AND course_id=3;"
```

Use these recovery commands only if cleanup is needed after this test. Repeat the precondition checks to confirm restoration. The canonical FinalGrade API cannot restore a NULL score or delete a final grade.

## 8. Inspect metadata and provenance

### Preconditions

- The baseline is running, and the metadata service is available.
- To produce an event for inspection, use `teacher-token` to run this query in the interface:

```sparql
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name WHERE { ?s a :Student ; :studentId 1 ; :fullName ?name . }
```

### Steps and expected results

Open these URLs in a browser:

- `http://localhost:8082/metadata/sources`: four baseline source descriptions and capabilities.
- `http://localhost:8082/metadata/artefacts`: ontology and mapping registrations.
- `http://localhost:8082/metadata/provenance?limit=20`: recent read/write events.

Expect a read event attributed to `prof.smith` for the query above. No preceding write test is required for this scenario.

### Cleanup

None. Source data is unchanged; the generated provenance entry can remain for inspection.

## 9. Run the extension demonstrations

The two additional branches demonstrate different ways of extending the prototype:

- **F4: Library Loans** adds a fifth, read-only source and joins its loan records with student data from PostgreSQL. See the [F4 guide](scripts/README-f4-loans.md) for setup, verification, and rollback.
- **F5: nationality** adds a column to the existing PostgreSQL student records and exposes it through the shared ontology. See the [F5 guide](scripts/README-f5-nationality.md) for setup, verification, and rollback.
