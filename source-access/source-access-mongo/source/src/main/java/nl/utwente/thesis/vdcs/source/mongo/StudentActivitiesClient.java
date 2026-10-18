package nl.utwente.thesis.vdcs.source.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

/** Provides MongoDB access for activity writes and projection refreshes. */
public class StudentActivitiesClient implements AutoCloseable {

    private final MongoClient    mongoClient;
    private final MongoDatabase  database;

    public StudentActivitiesClient(String connectionUri, String databaseName) {
        this.mongoClient = MongoClients.create(connectionUri);
        this.database    = mongoClient.getDatabase(databaseName);
    }

    /** Returns the student_activities collection. */
    public MongoCollection<Document> getActivitiesCollection() {
        return database.getCollection("student_activities");
    }

    public MongoDatabase getDatabase() {
        return database;
    }

    /**
     * Default connection URI for the Docker-compose Student Activities container.
     * authSource=admin because the root user is created in the admin database.
     */
    public static String defaultUri() {
        return "mongodb://mongo:mongo@localhost:5434/activities?authSource=admin";
    }

    @Override
    public void close() {
        mongoClient.close();
    }
}
