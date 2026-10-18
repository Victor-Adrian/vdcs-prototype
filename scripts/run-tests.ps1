<#
.SYNOPSIS
    Runs the VDCS smoke tests against the live services.

.DESCRIPTION
    Covers federated reads, access control, writes, failure handling,
    metadata, provenance, and cross-source rollback. Write tests restore
    their changes. The exit code is non-zero when any assertion fails.

.PARAMETER Start
    Starts the stack before testing.

.PARAMETER Stop
    Stops the stack after testing.

.PARAMETER SkipBuild
    Passes SkipBuild to run-all.ps1 when Start is used.

.EXAMPLE
    .\scripts\run-tests.ps1
    .\scripts\run-tests.ps1 -Start -Stop
#>
param(
    [switch] $Start,
    [switch] $Stop,
    [switch] $SkipBuild,
    [string] $JavaHome = $(
        # A launcher may still carry JAVA_HOME from before the user changed it.
        $savedJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
        if ([string]::IsNullOrWhiteSpace($savedJavaHome)) { $env:JAVA_HOME } else { $savedJavaHome }
    )
)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$ns       = "https://thesis.utwente.nl/vdcs/ontology/university#"
$res      = "https://thesis.utwente.nl/vdcs/resource/"
$mediator = "http://localhost:8081/sparql"
$apiQuery = "http://localhost:8080/api/query"
$apiUpd   = "http://localhost:8080/api/update"

# tally + assertion helpers
$script:pass = 0
$script:fail = 0
$script:fails = @()
function Ok([string]$name)            { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
function No([string]$name,[string]$d) { $script:fail++; $script:fails += $name; Write-Host ("  [FAIL] " + $name + $(if($d){"  -- " + $d})) -ForegroundColor Red }
function Assert([string]$name,[bool]$cond,[string]$detail="") { if ($cond) { Ok $name } else { No $name $detail } }

# HTTP helpers (all defensive: never abort the whole suite)
function MQuery([string]$q) {
    # mediator, no auth, returns array of bindings (or @() on error).
    # NOTE: leading comma keeps it an array; PowerShell unwraps a bare
    # single-element array back to a scalar, which would break .Count.
    try {
        $r = Invoke-RestMethod $mediator -Method Post -Body $q -ContentType "application/sparql-query" `
                -Headers @{ Accept = "application/sparql-results+json" } -TimeoutSec 30
        return ,@($r.results.bindings)
    } catch { return ,@() }
}
function AQuery([string]$token,[string]$q) {
    # access interface read, returns @{ ok; code; rows }
    try {
        $r = Invoke-RestMethod $apiQuery -Method Post -Body $q -ContentType "application/sparql-query" `
                -Headers @{ Authorization = "Bearer $token"; Accept = "application/sparql-results+json" } -TimeoutSec 30
        return @{ ok = $true; code = 200; rows = @($r.results.bindings) }
    } catch { return @{ ok = $false; code = $_.Exception.Response.StatusCode.value__; rows = @() } }
}
function AQueryCode([string]$token,[string]$q) {
    # returns just the HTTP status (200 on success, 401/403 on rejection)
    try {
        $h = @{ Accept = "application/sparql-results+json" }
        if ($token) { $h["Authorization"] = "Bearer $token" }
        Invoke-RestMethod $apiQuery -Method Post -Body $q -ContentType "application/sparql-query" -Headers $h -TimeoutSec 30 | Out-Null
        return 200
    } catch { return $_.Exception.Response.StatusCode.value__ }
}
function AUpdate([string]$token,[string]$body,[string]$ctype) {
    # returns HTTP status (204 success, 403 denied, etc.)
    try {
        Invoke-RestMethod $apiUpd -Method Post -Body $body -ContentType $ctype `
                -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30 | Out-Null
        return 204
    } catch { return $_.Exception.Response.StatusCode.value__ }
}
function ScoreOf([string]$gid) {
    $r = MQuery "PREFIX : <$ns> SELECT ?score WHERE { ?x a :Grade ; :gradeId $gid ; :score ?score }"
    if ($r.Count -lt 1) { return $null }
    return [double]$r[0].score.value
}
function Meta([string]$path) {
    # GET a metadata-service endpoint, return the parsed JSON (or $null on error)
    try { return Invoke-RestMethod ("http://localhost:8082" + $path) -TimeoutSec 10 } catch { return $null }
}

# preamble: bring up / readiness
if ($Start) {
    Write-Host "Starting the stack (run-all.ps1 -SkipDb)..." -ForegroundColor Cyan
    & "$PSScriptRoot\run-all.ps1" -JavaHome $JavaHome -SkipDb -SkipBuild:$SkipBuild
}

$ports = 8080,8081,8082,8083,8084,8085,8086,8087,8088,8089
$down = @()
foreach ($p in $ports) { if (-not (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)) { $down += $p } }
if ($down.Count -gt 0) {
    Write-Host ("Stack is not fully up. Ports not listening: " + ($down -join ', ')) -ForegroundColor Red
    Write-Host "Start it first:  .\scripts\run-all.ps1    (or run this with -Start)" -ForegroundColor Yellow
    exit 1
}
# mediator readiness (Ontop repos may still be initialising)
$ready = $false
for ($i = 0; $i -lt 20; $i++) {
    if ((MQuery "PREFIX : <$ns> SELECT ?s WHERE { ?s a :Student } LIMIT 1").Count -ge 1) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Write-Host "Mediator did not become ready in time." -ForegroundColor Red; exit 1 }

Write-Host "`n=================  VDCS automated test suite  =================" -ForegroundColor Cyan

Write-Host "`n[1] Read path (single-source)" -ForegroundColor Cyan
$students = MQuery "PREFIX : <$ns> SELECT ?n WHERE { ?s a :Student ; :fullName ?n }"
Assert "PostgreSQL read returns students" ($students.Count -ge 15) ("rows=" + $students.Count)

$courses = MQuery "PREFIX : <$ns> SELECT ?t WHERE { ?c a :Course ; :courseTitle ?t }"
Assert "MySQL read returns courses" ($courses.Count -ge 1) ("rows=" + $courses.Count)

$clubs = MQuery "PREFIX : <$ns> SELECT ?cn WHERE { ?cm a :ClubMembership ; :clubName ?cn }"
Assert "MongoDB read returns club memberships (via SAC)" ($clubs.Count -ge 15) ("rows=" + $clubs.Count)

Write-Host "`n[2] Read path (cross-source federation)" -ForegroundColor Cyan
$two = MQuery "PREFIX : <$ns> SELECT ?n ?score WHERE { ?s a :Student ; :fullName ?n ; :hasGrade ?g . ?g :score ?score }"
Assert "2-source join (PG name + MySQL score) returns rows" ($two.Count -gt 0) ("rows=" + $two.Count)

$three = MQuery "PREFIX : <$ns> SELECT ?n ?score ?cn WHERE { ?s a :Student ; :fullName ?n ; :hasGrade ?g ; :hasActivity ?a . ?g :score ?score . ?a :hasClubMembership ?cm . ?cm :clubName ?cn . FILTER(?score >= 90) } ORDER BY DESC(?score)"
$allGe90 = $true
foreach ($r in $three) { if ([double]$r.score.value -lt 90) { $allGe90 = $false } }
Assert "3-source join (PG+MySQL+Mongo) returns rows"      ($three.Count -gt 0) ("rows=" + $three.Count)
Assert "FILTER(score>=90) honoured across sources"        ($three.Count -gt 0 -and $allGe90)
$ordered = $true
for ($i = 1; $i -lt $three.Count; $i++) { if ([double]$three[$i].score.value -gt [double]$three[$i-1].score.value) { $ordered = $false } }
Assert "ORDER BY DESC(score) honoured"                    ($three.Count -lt 2 -or $ordered)

$byId = MQuery "PREFIX : <$ns> SELECT ?score ?cn WHERE { ?s a :Student ; :studentId 1 ; :hasGrade ?g ; :hasActivity ?a . ?g :score ?score . ?a :hasClubMembership ?cm . ?cm :clubName ?cn }"
Assert "Cross-source query scoped to studentId=1 returns rows" ($byId.Count -gt 0) ("rows=" + $byId.Count)

Write-Host "`n[3] Access control (IAM)" -ForegroundColor Cyan
$noTok = AQueryCode "" "PREFIX : <$ns> SELECT ?n WHERE { ?s a :Student ; :fullName ?n }"
Assert "No token -> 401" ($noTok -eq 401) ("got " + $noTok)

$tClubs = AQuery "teacher-token" "PREFIX : <$ns> SELECT ?cn WHERE { ?cm a :ClubMembership ; :clubName ?cn }"
Assert "teacher reads ClubMembership (ALLOW)" ($tClubs.ok -and $tClubs.rows.Count -gt 0)

$aClubs = AQuery "alice-token" "PREFIX : <$ns> SELECT ?cn WHERE { ?cm a :ClubMembership ; :clubName ?cn }"
Assert "alice reads ClubMembership (ALLOW)" ($aClubs.ok -and $aClubs.rows.Count -gt 0)

$aCourse = AQuery "alice-token" "PREFIX : <$ns> SELECT ?t WHERE { ?c a :Course ; :courseTitle ?t }"
Assert "alice reads Course -> DENY 403" ($aCourse.code -eq 403) ("got " + $aCourse.code)

$tGrades = AQuery "teacher-token" "PREFIX : <$ns> SELECT ?sc WHERE { ?x a :Grade ; :score ?sc }"
$aGrades = AQuery "alice-token"   "PREFIX : <$ns> SELECT ?sc WHERE { ?x a :Grade ; :score ?sc }"
Assert "alice Grade query is row-level scoped (SELF: fewer than teacher)" `
    ($tGrades.ok -and $aGrades.ok -and $aGrades.rows.Count -gt 0 -and $aGrades.rows.Count -lt $tGrades.rows.Count) `
    ("alice=" + $aGrades.rows.Count + " teacher=" + $tGrades.rows.Count)

$aWrite = AUpdate "alice-token" '{"operation":"UPDATE","entityType":"Grade","payload":{"score":1},"filter":{"gradeId":1}}' "application/json"
Assert "alice (read-only) write -> 403" ($aWrite -eq 403) ("got " + $aWrite)

Write-Host "`n[4] Write path (each test reverts itself)" -ForegroundColor Cyan
# pick a real grade to mutate + revert
$g = MQuery "PREFIX : <$ns> SELECT ?gid WHERE { ?x a :Grade ; :gradeId ?gid } ORDER BY ?gid LIMIT 1"
if ($g.Count -lt 1) {
    No "Write tests" "could not find a grade to test with"
} else {
    $gid  = $g[0].gid.value
    $orig = ScoreOf $gid

    # Canonical JSON update
    $code = AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"Grade","payload":{"score":33.33},"filter":{"gradeId":' + $gid + '}}') "application/json"
    Assert "JSON UPDATE accepted (grade $gid)" ($code -eq 204) ("HTTP " + $code)
    Assert "JSON UPDATE applied (score -> 33.33)" ((ScoreOf $gid) -eq 33.33) ("got " + (ScoreOf $gid))
    AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"Grade","payload":{"score":' + $orig + '},"filter":{"gradeId":' + $gid + '}}') "application/json" | Out-Null
    Assert "JSON UPDATE reverted (score -> $orig)" ((ScoreOf $gid) -eq $orig)

    # Cross-source update joining a PostgreSQL student to a MySQL grade
    $cs = MQuery "PREFIX : <$ns> SELECT ?sid ?gid WHERE { ?s a :Student ; :studentId ?sid ; :hasGrade ?g . ?g :gradeId ?gid } ORDER BY ?gid LIMIT 1"
    if ($cs.Count -lt 1) {
        No "Cross-source SPARQL update" "no studentId<->grade pair found"
    } else {
        $sid2 = $cs[0].sid.value
        $gid2 = $cs[0].gid.value
        $orig2 = ScoreOf $gid2
        $upd = "PREFIX : <$ns>`nDELETE { ?g :score ?old }`nINSERT { ?g :score 44.44 }`nWHERE { ?s a :Student ; :studentId $sid2 ; :hasGrade ?g . ?g :gradeId $gid2 ; :score ?old }"
        $uc = AUpdate "teacher-token" $upd "application/sparql-update"
        Assert "Cross-source DELETE/INSERT WHERE accepted (grade $gid2)" ($uc -eq 204) ("HTTP " + $uc)
        Assert "Cross-source update applied (score -> 44.44)" ((ScoreOf $gid2) -eq 44.44) ("got " + (ScoreOf $gid2))
        AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"Grade","payload":{"score":' + $orig2 + '},"filter":{"gradeId":' + $gid2 + '}}') "application/json" | Out-Null
        Assert "Cross-source update reverted (score -> $orig2)" ((ScoreOf $gid2) -eq $orig2)
    }

# Use a full IRI because '/' is invalid in a prefixed local name.
    $insert = @"
PREFIX : <$ns>
INSERT DATA { <${res}programme/9001> a :Programme ; :programmeId 9001 ; :programmeName "QA Test Programme" ; :programmeDepartment "QA" . }
"@
    $ic = AUpdate "teacher-token" $insert "application/sparql-update"
    Assert "INSERT DATA accepted (Programme 9001)" ($ic -eq 204) ("HTTP " + $ic)
    $present = MQuery "PREFIX : <$ns> SELECT ?n WHERE { <${res}programme/9001> :programmeName ?n }"
    Assert "INSERT DATA created Programme 9001" ($present.Count -eq 1) ("rows=" + $present.Count)
    $delete = @"
PREFIX : <$ns>
DELETE DATA { <${res}programme/9001> :programmeId 9001 . }
"@
    AUpdate "teacher-token" $delete "application/sparql-update" | Out-Null
    $gone = MQuery "PREFIX : <$ns> SELECT ?n WHERE { <${res}programme/9001> :programmeName ?n }"
    Assert "DELETE DATA removed Programme 9001 (cleanup)" ($gone.Count -eq 0) ("rows=" + $gone.Count)
}

Write-Host "`n[5] VS10 - transaction integrity / failure handling" -ForegroundColor Cyan
# Force a write that must fail (insert a Programme with an id that already
# exists, i.e. a duplicate primary key), and confirm: it is rejected, it leaves
# NO partial state, and the failure is recorded in provenance.
$pgRow = MQuery "PREFIX : <$ns> SELECT ?pid ?n WHERE { ?x a :Programme ; :programmeId ?pid ; :programmeName ?n } ORDER BY ?pid LIMIT 1"
if ($pgRow.Count -lt 1) {
    No "VS10 setup" "no Programme found to test with"
} else {
    $progId   = $pgRow[0].pid.value      # NB: do not use $pid -- it is a reserved PowerShell variable
    $origName = $pgRow[0].n.value
    $ev1 = Meta "/metadata/provenance?operation=WRITE&limit=200"
    $beforeFail = @($ev1 | Where-Object { $_.status -eq "FAILURE" }).Count

    $dupInsert = @"
PREFIX : <$ns>
INSERT DATA { <${res}programme/$progId> a :Programme ; :programmeId $progId ; :programmeName "SHOULD NOT PERSIST" ; :programmeDepartment "QA" . }
"@
    $code = AUpdate "teacher-token" $dupInsert "application/sparql-update"
    Assert "VS10a: duplicate-key write is rejected (not 204)" ($code -ne 204) ("HTTP " + $code)

    $after = MQuery "PREFIX : <$ns> SELECT ?n WHERE { <${res}programme/$progId> :programmeName ?n }"
    $unchanged = ($after.Count -eq 1) -and ($after[0].n.value -eq $origName)
    Assert "VS10b: no partial state (Programme $progId unchanged)" $unchanged ("name now = " + $(if($after.Count){$after[0].n.value}else{"<missing>"}))

    Start-Sleep -Milliseconds 600
    $ev2 = Meta "/metadata/provenance?operation=WRITE&limit=200"
    $afterFail = @($ev2 | Where-Object { $_.status -eq "FAILURE" }).Count
    Assert "VS10c: the failure is recorded in provenance (FAILURE event)" ($afterFail -gt $beforeFail) ("FAILURE events before=$beforeFail after=$afterFail")
}

Write-Host "`n[6] VS11 - metadata governance & provenance" -ForegroundColor Cyan
# FAIR source metadata is served by the metadata governance component.
# (Assign Meta's result first; @(Meta ...) inline collapses the array.)
$sources = Meta "/metadata/sources"
$src0 = @($sources)[0]
$srcOk = (@($sources).Count -ge 3) -and ($src0.id) -and ($src0.persistentId) -and ($src0.license)
Assert "VS11a: FAIR source metadata served (>=3 sources, with persistentId + license)" $srcOk ("sources=" + @($sources).Count)

# The ontology + mappings are governed artefacts with explicit versions.
$arts = Meta "/metadata/artefacts"
$hasOntology = (@($arts | Where-Object { $_.id -eq "ontology" }).Count -eq 1)
$artOk = (@($arts).Count -ge 4) -and $hasOntology -and (@($arts)[0].version)
Assert "VS11b: artefact registry served (ontology + mappings, versioned)" $artOk ("artefacts=" + @($arts).Count)

# Every operation leaves a provenance record: a write grows the log and is
# attributed to the IAM-resolved actor.
$before = (Meta "/metadata/provenance/summary").totalEvents
$gp = MQuery "PREFIX : <$ns> SELECT ?gid WHERE { ?x a :Grade ; :gradeId ?gid } ORDER BY ?gid LIMIT 1"
if ($gp.Count -ge 1) {
    $pgid = $gp[0].gid.value
    $pog  = ScoreOf $pgid
    AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"Grade","payload":{"score":42.42},"filter":{"gradeId":' + $pgid + '}}') "application/json" | Out-Null
    AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"Grade","payload":{"score":' + $pog + '},"filter":{"gradeId":' + $pgid + '}}') "application/json" | Out-Null
}
Start-Sleep -Milliseconds 600
$after = (Meta "/metadata/provenance/summary").totalEvents
Assert "VS11c: provenance log records operations (grows on writes)" ($after -gt $before) ("totalEvents before=$before after=$after")

$attr = Meta "/metadata/provenance?operation=WRITE&actor=prof.smith&limit=10"
Assert "VS11d: write provenance is attributed to the actor (prof.smith)" (@($attr).Count -ge 1) ("matching events=" + @($attr).Count)

Write-Host "`n[7] VS10-batch - cross-source atomic rollback (one write, two sources)" -ForegroundColor Cyan
# Change a PostgreSQL name, then trigger a MySQL foreign-key failure. The
# transaction must restore the name and record the compensation.
$sg = MQuery "PREFIX : <$ns> SELECT ?gid WHERE { ?s a :Student ; :studentId 1 ; :hasGrade ?g . ?g :gradeId ?gid } ORDER BY ?gid LIMIT 1"
if ($sg.Count -lt 1) {
    No "VS10-batch setup" "no grade found for student 1"
} else {
    $bgid  = $sg[0].gid.value
    $bname = @(MQuery "PREFIX : <$ns> SELECT ?n WHERE { <${res}student/1> :fullName ?n }")[0].n.value
    # NB: the assessment is a full IRI in angle brackets -- a prefixed name
    # r:assessment/99999 would be a SPARQL parse error (slash in local name).
    $w = @"
PREFIX : <$ns>
DELETE { ?s :fullName ?on .  ?g :assessedBy ?oa }
INSERT { ?s :fullName "ROLLBACK TEST" .  ?g :assessedBy <${res}assessment/99999> }
WHERE  { ?s a :Student ; :studentId 1 ; :fullName ?on ; :hasGrade ?g . ?g :gradeId $bgid ; :assessedBy ?oa }
"@
    $code = AUpdate "teacher-token" $w "application/sparql-update"
    Assert "VS10-batch a: cross-source write with a failing part is rejected" ($code -ne 204) ("HTTP " + $code)

    $nameAfter = @(MQuery "PREFIX : <$ns> SELECT ?n WHERE { <${res}student/1> :fullName ?n }")[0].n.value
    Assert "VS10-batch b: the applied part (PG name) is rolled back -- no partial state" ($nameAfter -eq $bname) ("name now = " + $nameAfter)

    Start-Sleep -Milliseconds 700
    $lp = Meta "/metadata/provenance?operation=WRITE&limit=3"
    $lastW = @($lp)[0]
    $compensated = ($lastW.status -eq "FAILURE") -and ($lastW.note -match "successful sub-operation")
    Assert "VS10-batch c: provenance shows a sub-op succeeded then was compensated" $compensated ("note = " + $lastW.note)

    # defensive: if rollback ever regressed, restore the name so the suite stays idempotent
    if ($nameAfter -ne $bname) {
        AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"Student","payload":{"fullName":"' + $bname + '"},"filter":{"studentId":1}}') "application/json" | Out-Null
    }
}

Write-Host "`n[8] FG - replicated final grade (one fact, two sources)" -ForegroundColor Cyan
# Final grades are replicated in PostgreSQL and MySQL. One canonical write
# must update both copies atomically.

function PgFinal([int]$sid,[int]$cid) {
    $v = docker exec student-registry psql -U registry -d registry -tAc "SELECT COALESCE(final_grade::text,'NULL') FROM enrollments WHERE student_id=$sid AND course_id=$cid;"
    if ($null -eq $v) { return "" } else { return ([string]$v).Trim() }
}
function MyFinal([int]$sid,[int]$cid) {
    $v = docker exec -e MYSQL_PWD=academics academic-records mysql -N -s -u academics academics -e "SELECT COALESCE(final_grade,'NULL') FROM final_grades WHERE student_id=$sid AND course_id=$cid;"
    if ($null -eq $v) { return "NULL" } else { return ([string]$v).Trim() }
}

# Read both stored copies.
$fg = MQuery "PREFIX : <$ns> SELECT ?f WHERE { ?fg a :FinalGrade ; :finalStudentId 1 ; :finalCourseId 1 ; :finalScore ?f }"
Assert "FG-a: final grade (1,1) readable through the ontology" (($fg.Count -eq 1) -and ($fg[0].f.value -eq "81.20")) ("rows=" + $fg.Count + " value=" + $(if ($fg.Count -ge 1) { $fg[0].f.value } else { "-" }))

$fj = MQuery "PREFIX : <$ns> SELECT ?title ?f WHERE { ?s :fullName ""Alice Chen"" ; :hasFinalGrade ?fg . ?fg :finalScore ?f ; :finalGradeFor ?c . ?c :courseTitle ?title }"
Assert "FG-b: cross-source join (PG final grade + MySQL course title)" ($fj.Count -eq 2) ("rows=" + $fj.Count)

# Write both copies, then revert them.
$origPg = PgFinal 1 1
$origMy = MyFinal 1 1
$code = AUpdate "teacher-token" '{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90.50},"filter":{"finalStudentId":1,"finalCourseId":1}}' "application/json"
Assert "FG-c: one canonical write accepted (broadcast to both sources)" ($code -eq 204) ("HTTP " + $code)
Assert "FG-d: PostgreSQL (Osiris) copy updated" ((PgFinal 1 1) -eq "90.50") ("PG = " + (PgFinal 1 1))
Assert "FG-e: MySQL (Canvas) copy updated to the SAME value" ((MyFinal 1 1) -eq "90.50") ("MySQL = " + (MyFinal 1 1))

AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":' + $origPg + '},"filter":{"finalStudentId":1,"finalCourseId":1}}') "application/json" | Out-Null
Assert "FG-f: reverted (both copies back to seed value)" (((PgFinal 1 1) -eq $origPg) -and ((MyFinal 1 1) -eq $origMy)) ("PG=" + (PgFinal 1 1) + " MySQL=" + (MyFinal 1 1))

# Derive a final grade from MySQL assessment scores and write both copies.
$avgW = @"
PREFIX : <$ns>
INSERT { <${res}final-grade/1-1> :finalScore ?avg . }
WHERE {
  { SELECT (AVG(?sc) AS ?avg) WHERE {
      <${res}student/1> :hasGrade ?g .
      ?g a :Grade ; :score ?sc ; :assessedBy ?a .
      <${res}course/1> :hasAssessment ?a .
  } }
}
"@
$code = AUpdate "teacher-token" $avgW "application/sparql-update"
$avgOk = ($code -eq 204) -and ((PgFinal 1 1) -eq "82.00") -and ((MyFinal 1 1) -eq "82.00")
Assert "FG-g: AVG-of-grades written as final grade to BOTH sources (82.00)" $avgOk ("HTTP=" + $code + " PG=" + (PgFinal 1 1) + " MySQL=" + (MyFinal 1 1))
AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":' + $origPg + '},"filter":{"finalStudentId":1,"finalCourseId":1}}') "application/json" | Out-Null

# MySQL lacks final grade (1,3), so its failure must restore PostgreSQL to NULL.
$code = AUpdate "teacher-token" '{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":77.77},"filter":{"finalStudentId":1,"finalCourseId":3}}' "application/json"
Assert "FG-h: write failing on one copy is rejected" ($code -ne 204) ("HTTP " + $code)
Assert "FG-i: the applied copy (PG) is compensated back to NULL" ((PgFinal 1 3) -eq "NULL") ("PG = " + (PgFinal 1 3))

Start-Sleep -Milliseconds 700
$lp = Meta "/metadata/provenance?operation=WRITE&limit=3"
$lastW = @($lp)[0]
$comp = ($lastW.status -eq "FAILURE") -and ($lastW.note -match "successful sub-operation")
Assert "FG-j: provenance shows apply-then-compensate" $comp ("note = " + $lastW.note)

# defensive: if compensation ever regressed, restore NULL so the suite stays idempotent
if ((PgFinal 1 3) -ne "NULL") {
    docker exec student-registry psql -U registry -d registry -c "UPDATE enrollments SET final_grade = NULL WHERE student_id=1 AND course_id=3;" | Out-Null
}

Write-Host "`n[9] F7 - RDF source (research publications, direct SPARQL)" -ForegroundColor Cyan
# Publications are read from Fuseki and translated to the global ontology
# with a CONSTRUCT mapping.

$cs = Meta "/metadata/sources"
$pubSrc = @($cs) | Where-Object { $_.id -eq "research-publications" }
Assert "F7a: catalogue registers the RDF source (research-publications)" (@($pubSrc).Count -eq 1 -and $pubSrc.engine -eq "fuseki") ("found=" + @($pubSrc).Count)

$pc = MQuery "PREFIX : <$ns> SELECT (COUNT(?p) AS ?n) WHERE { ?p a :Publication }"
$pubCount = if ($pc.Count -ge 1) { [int]$pc[0].n.value } else { -1 }
Assert "F7b: publications readable through the ontology (8 seeded)" ($pubCount -eq 8) ("count=" + $pubCount)

$pj = MQuery "PREFIX : <$ns> SELECT ?name ?title WHERE { ?s :fullName ?name ; :hasPublication ?p . ?p :publicationTitle ?title }"
Assert "F7c: cross-source join (PG student name x RDF publication title)" ($pj.Count -eq 12) ("rows=" + $pj.Count + " (12 authorship links seeded)")

$tri = MQuery "PREFIX : <$ns> SELECT DISTINCT ?name ?title ?club WHERE { ?s :fullName ?name ; :hasPublication ?p ; :hasActivity ?a . ?p :publicationTitle ?title . ?a :hasClubMembership ?cm . ?cm :clubName ?club }"
Assert "F7d: three native models in ONE query (relational + graph + document)" ($tri.Count -ge 1) ("rows=" + $tri.Count)

# Verify that reads pass through the source access service.
# The source service proxies SPARQL reads. Its counter must increase when
# the mediator queries publications.
function PubsSacReads() {
    try { return [long](Invoke-RestMethod "http://localhost:8091/sac/health" -TimeoutSec 10).proxiedReads }
    catch { return -1 }
}
$before = PubsSacReads
MQuery "PREFIX : <$ns> SELECT ?t WHERE { ?p a :Publication ; :publicationTitle ?t } LIMIT 1" | Out-Null
$after = PubsSacReads
Assert "F7e: reads flow THROUGH the SAC (data-plane proxy counter grows)" (($before -ge 0) -and ($after -gt $before)) ("proxiedReads " + $before + " -> " + $after)

# Verify federated writes to the graph source.
# Write a publication, read it through the ontology, join it to its
# PostgreSQL author, and remove it again.
$pubIns = '{"operation":"INSERT","entityType":"Publication","payload":{"publicationId":9001,"publicationTitle":"Federated Write-Back to a Graph Source","publicationYear":2026,"publicationVenue":"VDCS Demo","publicationAuthorStudentId":1}}'
$wc = AUpdate "teacher-token" $pubIns "application/json"
Assert "F7f-a: canonical Publication write accepted (RDF source)" ($wc -eq 204) ("HTTP " + $wc)
Start-Sleep -Milliseconds 300
$pback = MQuery "PREFIX : <$ns> SELECT ?t WHERE { ?p a :Publication ; :publicationId 9001 ; :publicationTitle ?t }"
Assert "F7f-b: the written publication is readable through the ontology" `
    (($pback.Count -eq 1) -and ($pback[0].t.value -eq "Federated Write-Back to a Graph Source")) ("rows=" + $pback.Count)
$pjoin = MQuery "PREFIX : <$ns> SELECT ?name WHERE { ?s :studentId 1 ; :fullName ?name ; :hasPublication ?p . ?p :publicationId 9001 }"
Assert "F7f-c: the new RDF publication joins cross-source to its PG author" ($pjoin.Count -eq 1) ("rows=" + $pjoin.Count)
AUpdate "teacher-token" '{"operation":"DELETE","entityType":"Publication","payload":{},"filter":{"publicationId":9001}}' "application/json" | Out-Null
$pgone = MQuery "PREFIX : <$ns> SELECT ?t WHERE { ?p a :Publication ; :publicationId 9001 ; :publicationTitle ?t }"
Assert "F7f-d: publication DELETE removes it (cleanup)" ($pgone.Count -eq 0) ("rows=" + $pgone.Count)

# Verify cross-source rollback for the graph source.
# Write a Fuseki publication, then trigger a PostgreSQL duplicate-key
# failure. Compensation must remove the publication.
$pubRollback = @"
PREFIX : <$ns>
INSERT DATA {
  <${res}publication/9002> a :Publication ; :publicationId 9002 ; :publicationTitle "ROLLBACK PUB" ; :publicationYear 2026 ; :publicationVenue "QA" ; :publicationAuthorStudentId 1 .
  <${res}programme/1> a :Programme ; :programmeId 1 ; :programmeName "SHOULD NOT PERSIST" ; :programmeDepartment "QA" .
}
"@
$rc = AUpdate "teacher-token" $pubRollback "application/sparql-update"
Assert "F7g-a: cross-source write with a failing part is rejected" ($rc -ne 204) ("HTTP " + $rc)
Start-Sleep -Milliseconds 300
$rleft = MQuery "PREFIX : <$ns> SELECT ?t WHERE { ?p a :Publication ; :publicationId 9002 ; :publicationTitle ?t }"
Assert "F7g-b: the applied RDF write is compensated -- no partial state" ($rleft.Count -eq 0) ("rows=" + $rleft.Count)
Start-Sleep -Milliseconds 700
$lpp    = Meta "/metadata/provenance?operation=WRITE&limit=3"
$lastPP = @($lpp)[0]
$ppComp = ($lastPP.status -eq "FAILURE") -and ($lastPP.note -match "successful sub-operation")
Assert "F7g-c: provenance shows apply-then-compensate across the sources" $ppComp ("note = " + $lastPP.note)
# defensive: if rollback ever regressed, remove the stray publication
if ($rleft.Count -gt 0) {
    AUpdate "teacher-token" '{"operation":"DELETE","entityType":"Publication","payload":{},"filter":{"publicationId":9002}}' "application/json" | Out-Null
}

Write-Host "`n[10] FinalGrade request validation and ambiguous enrollments" -ForegroundColor Cyan
function FinalGradeRequest([string]$body, [string]$ctype = "application/json") {
    try {
        $response = Invoke-WebRequest $apiUpd -Method Post -Body $body -ContentType $ctype -UseBasicParsing `
            -Headers @{ Authorization = "Bearer teacher-token" } -ErrorAction Stop -TimeoutSec 30
        return @{ code = [int]$response.StatusCode; body = [string]$response.Content }
    } catch {
        return @{ code = $_.Exception.Response.StatusCode.value__; body = [string]$_.ErrorDetails.Message }
    }
}
$fgBeforePg = PgFinal 1 1
$fgBeforeMy = MyFinal 1 1
try {
    $badFilter = FinalGradeRequest '{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90},"filter":{"finalStudentId":1,"finalCourseId":1,"finalScore":0}}'
    Assert "FG-k: unsupported filter returns a helpful 400" (($badFilter.code -eq 400) -and ($badFilter.body -match "Unsupported FinalGrade filter field")) ($badFilter.body)
    Assert "FG-l: rejected filter leaves both copies unchanged" (((PgFinal 1 1) -eq $fgBeforePg) -and ((MyFinal 1 1) -eq $fgBeforeMy))

    $badKey = FinalGradeRequest '{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90,"finalGradeKey":"1-2"},"filter":{"finalStudentId":1,"finalCourseId":1}}'
    Assert "FG-m: conflicting identifiers rejected before execution" (($badKey.code -eq 400) -and ($badKey.body -match "Conflicting FinalGrade")) ($badKey.body)

    $noDelete = FinalGradeRequest '{"operation":"DELETE","entityType":"FinalGrade","filter":{"finalStudentId":1,"finalCourseId":1}}'
    Assert "FG-n: unsupported deletion explains the restriction" (($noDelete.code -eq 400) -and ($noDelete.body -match "Deleting FinalGrade is not supported")) ($noDelete.body)

    # The first insertion would fail on an existing programme ID if executed.
    # Planning must instead reject the later FinalGrade deletion with HTTP 400.
    $lateDelete = @"
PREFIX : <$ns>
INSERT DATA { <${res}programme/1> :programmeName "SHOULD NOT EXECUTE" ; :programmeDepartment "QA" . };
DELETE DATA { <${res}final-grade/1-1> :finalScore 81.20 . }
"@
    $batch = FinalGradeRequest $lateDelete "application/sparql-update"
    Assert "FG-o: later deletion rejected during whole-request planning" (($batch.code -eq 400) -and ($batch.body -match "Deleting FinalGrade is not supported")) ($batch.body)
    Assert "FG-p: invalid requests leave both grade copies unchanged" (((PgFinal 1 1) -eq $fgBeforePg) -and ((MyFinal 1 1) -eq $fgBeforeMy))
} finally {
    # Keep the smoke suite recoverable even if validation regresses.
    AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":' + $fgBeforePg + '},"filter":{"finalStudentId":1,"finalCourseId":1}}') "application/json" | Out-Null
}

$duplicateId = $null
try {
    $inserted = docker exec student-registry psql -U registry -d registry -tAq -c "INSERT INTO enrollments(student_id,course_id,academic_year,final_grade) VALUES(1,1,'2099-2100',52.34) RETURNING id;"
    if ($LASTEXITCODE -ne 0) { throw "Could not create the duplicate-enrollment test fixture." }
    $duplicateId = [int](([string]$inserted).Trim())
    $ambiguous = FinalGradeRequest '{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":90},"filter":{"finalStudentId":1,"finalCourseId":1}}'
    Assert "FG-q: ambiguous enrollment rejected" (($null -ne $ambiguous.code) -and ($ambiguous.code -ge 400)) ("HTTP " + $ambiguous.code)
    $existing = docker exec student-registry psql -U registry -d registry -tAq -c "SELECT final_grade FROM enrollments WHERE student_id=1 AND course_id=1 AND id<>$duplicateId;"
    $duplicate = docker exec student-registry psql -U registry -d registry -tAq -c "SELECT final_grade FROM enrollments WHERE id=$duplicateId;"
    Assert "FG-r: neither enrollment changed after ambiguity rejection" (([string]$existing).Trim() -eq $fgBeforePg -and ([string]$duplicate).Trim() -eq "52.34")
    Assert "FG-s: MySQL unchanged after ambiguity rejection" ((MyFinal 1 1) -eq $fgBeforeMy)
} finally {
    if ($null -ne $duplicateId) {
        docker exec student-registry psql -U registry -d registry -c "DELETE FROM enrollments WHERE id=$duplicateId;" | Out-Null
        AUpdate "teacher-token" ('{"operation":"UPDATE","entityType":"FinalGrade","payload":{"finalScore":' + $fgBeforePg + '},"filter":{"finalStudentId":1,"finalCourseId":1}}') "application/json" | Out-Null
    }
}

Write-Host "`n==============================================================" -ForegroundColor Cyan
$total = $script:pass + $script:fail
if ($script:fail -eq 0) {
    Write-Host (" ALL TESTS PASSED  ($script:pass / $total)") -ForegroundColor Green
} else {
    Write-Host (" $script:fail of $total FAILED:") -ForegroundColor Red
    foreach ($f in $script:fails) { Write-Host ("   - " + $f) -ForegroundColor Red }
}
Write-Host "==============================================================" -ForegroundColor Cyan

if ($Stop) {
    Write-Host "`nStopping the stack (-Stop)..." -ForegroundColor Cyan
    & "$PSScriptRoot\stop-all.ps1" | Out-Null
}

if ($script:fail -gt 0) { exit 1 } else { exit 0 }
