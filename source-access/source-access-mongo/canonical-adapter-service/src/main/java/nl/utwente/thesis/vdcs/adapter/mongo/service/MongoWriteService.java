package nl.utwente.thesis.vdcs.adapter.mongo.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.pull;
import static com.mongodb.client.model.Updates.push;

/** Applies canonical updates to MongoDB and refreshes the H2 projection. */
@Service
public class MongoWriteService {

    private static final Logger log = LoggerFactory.getLogger(MongoWriteService.class);

    private static final String SOURCE_ID = "student-activities";

    private final ProjectionService projection;

    public MongoWriteService(ProjectionService projection) {
        this.projection = projection;
    }

    public CompletedOperation apply(CanonicalUpdate update) throws Exception {
        CompletedOperation handle = switch (update.getOperation()) {
            case INSERT -> applyInsert(update);
            case DELETE -> applyDelete(update);
            case UPDATE -> throw new UnsupportedOperationException(
                    "UPDATE on MongoDB nested entities not supported — "
                  + "use DELETE + INSERT");
        };
        projection.refresh();
        return handle;
    }

    public void compensate(CompletedOperation handle) throws Exception {
        CanonicalUpdate orig = handle.getOriginalUpdate();
        MongoCollection<Document> coll = projection.mongo().getActivitiesCollection();

        switch (orig.getOperation()) {
            case INSERT -> {
                int sid = (int) handle.getSnapshot().get("student_id");
                switch (orig.getEntityType()) {
                    case "StudentActivity" -> coll.deleteOne(eq("student_id", sid));
                    case "ClubMembership"  -> coll.updateOne(eq("student_id", sid),
                            pull("clubs", new Document("name", handle.getSnapshot().get("name"))));
                    case "AcademicProject" -> coll.updateOne(eq("student_id", sid),
                            pull("projects", new Document("title", handle.getSnapshot().get("title"))));
                    default -> { /* nothing */ }
                }
            }
            case DELETE -> {
                int sid = (int) handle.getSnapshot().get("student_id");
                switch (orig.getEntityType()) {
                    case "ClubMembership" -> coll.updateOne(eq("student_id", sid),
                            push("clubs", documentFromSnapshot(handle.getSnapshot(),
                                    "name", "role", "joined")));
                    case "AcademicProject" -> coll.updateOne(eq("student_id", sid),
                            push("projects", documentFromSnapshot(handle.getSnapshot(),
                                    "title", "year", "status")));
                    default -> { /* nothing */ }
                }
            }
            default -> { /* UPDATE not supported, nothing to compensate */ }
        }

        projection.refresh();
        log.info("Compensated [{}] {} on {}", SOURCE_ID, orig.getOperation(), orig.getEntityType());
    }

    // INSERT

    private CompletedOperation applyInsert(CanonicalUpdate update) {
        MongoCollection<Document> coll = projection.mongo().getActivitiesCollection();
        Map<String, Object> payload = update.getPayload();
        Integer sid = readInt(payload.get("activityStudentId"));
        if (sid == null) {
            throw new IllegalArgumentException(
                    "INSERT on " + update.getEntityType()
                  + " requires 'activityStudentId' in payload");
        }

        switch (update.getEntityType()) {
            case "StudentActivity" -> {
                coll.insertOne(new Document("student_id", sid)
                        .append("clubs",    java.util.List.of())
                        .append("projects", java.util.List.of()));
                log.info("Inserted student_activities document student_id={}", sid);
                return new CompletedOperation(SOURCE_ID, update,
                        Map.of("student_id", sid));
            }
            case "ClubMembership" -> {
                String name   = readString(payload.get("clubName"));
                String role   = readString(payload.get("clubRole"));
                String joined = readString(payload.get("joinedDate"));
                if (name == null) {
                    throw new IllegalArgumentException("ClubMembership requires 'clubName'");
                }
                Document club = new Document("name", name);
                if (role   != null) club.append("role",   role);
                if (joined != null) club.append("joined", joined);

                UpdateResult res = coll.updateOne(eq("student_id", sid), push("clubs", club));
                if (res.getMatchedCount() == 0) {
                    throw new IllegalStateException(
                            "No student_activities document for student_id=" + sid);
                }
                log.info("Pushed club '{}' onto student_id={}", name, sid);
                return new CompletedOperation(SOURCE_ID, update,
                        Map.of("student_id", sid, "name", name));
            }
            case "AcademicProject" -> {
                String title  = readString(payload.get("projectTitle"));
                Integer year  = readInt(payload.get("projectYear"));
                String status = readString(payload.get("projectStatus"));
                if (title == null) {
                    throw new IllegalArgumentException("AcademicProject requires 'projectTitle'");
                }
                Document proj = new Document("title", title);
                if (year   != null) proj.append("year",   year);
                if (status != null) proj.append("status", status);

                UpdateResult res = coll.updateOne(eq("student_id", sid), push("projects", proj));
                if (res.getMatchedCount() == 0) {
                    throw new IllegalStateException(
                            "No student_activities document for student_id=" + sid);
                }
                log.info("Pushed project '{}' onto student_id={}", title, sid);
                return new CompletedOperation(SOURCE_ID, update,
                        Map.of("student_id", sid, "title", title));
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported entity: " + update.getEntityType());
        }
    }

    // DELETE

    private CompletedOperation applyDelete(CanonicalUpdate update) {
        MongoCollection<Document> coll = projection.mongo().getActivitiesCollection();
        Map<String, Object> filter = update.getFilter();
        Integer sid = readInt(filter.get("activityStudentId"));
        if (sid == null) {
            throw new IllegalArgumentException(
                    "DELETE on " + update.getEntityType() + " requires 'activityStudentId'");
        }

        switch (update.getEntityType()) {
            case "ClubMembership" -> {
                String name = readString(filter.get("clubName"));
                if (name == null) {
                    throw new IllegalArgumentException("ClubMembership DELETE requires 'clubName'");
                }
                Document parent = coll.find(eq("student_id", sid)).first();
                if (parent == null) {
                    throw new IllegalStateException("No document for student_id=" + sid);
                }
                Document target = findEmbedded(parent.getList("clubs", Document.class), "name", name);
                if (target == null) {
                    throw new IllegalStateException(
                            "No club '" + name + "' for student_id=" + sid);
                }

                coll.updateOne(eq("student_id", sid),
                        pull("clubs", new Document("name", name)));
                log.info("Pulled club '{}' from student_id={}", name, sid);
                return new CompletedOperation(SOURCE_ID, update, Map.of(
                        "student_id", sid,
                        "name",   target.getString("name"),
                        "role",   target.getString("role")   == null ? "" : target.getString("role"),
                        "joined", target.getString("joined") == null ? "" : target.getString("joined")
                ));
            }
            case "AcademicProject" -> {
                String title = readString(filter.get("projectTitle"));
                if (title == null) {
                    throw new IllegalArgumentException("AcademicProject DELETE requires 'projectTitle'");
                }
                Document parent = coll.find(eq("student_id", sid)).first();
                if (parent == null) {
                    throw new IllegalStateException("No document for student_id=" + sid);
                }
                Document target = findEmbedded(parent.getList("projects", Document.class), "title", title);
                if (target == null) {
                    throw new IllegalStateException(
                            "No project '" + title + "' for student_id=" + sid);
                }

                coll.updateOne(eq("student_id", sid),
                        pull("projects", new Document("title", title)));
                log.info("Pulled project '{}' from student_id={}", title, sid);
                return new CompletedOperation(SOURCE_ID, update, Map.of(
                        "student_id", sid,
                        "title",  target.getString("title"),
                        "year",   target.getInteger("year") == null ? 0 : target.getInteger("year"),
                        "status", target.getString("status") == null ? "" : target.getString("status")
                ));
            }
            default -> throw new IllegalArgumentException(
                    "DELETE on " + update.getEntityType() + " not supported in MongoDB CSA");
        }
    }

    // helpers

    private static Document findEmbedded(java.util.List<Document> list, String key, String value) {
        if (list == null) return null;
        for (Document d : list) {
            if (value.equals(d.getString(key))) return d;
        }
        return null;
    }

    private static Document documentFromSnapshot(Map<String, Object> snap, String... fields) {
        Document d = new Document();
        for (String f : fields) {
            Object v = snap.get(f);
            if (v != null && !"".equals(v)) d.append(f, v);
        }
        return d;
    }

    private static Integer readInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static String readString(Object v) {
        return v == null ? null : v.toString();
    }
}
