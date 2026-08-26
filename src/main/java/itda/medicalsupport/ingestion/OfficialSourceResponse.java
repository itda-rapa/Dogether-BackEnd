package itda.medicalsupport.ingestion;
import java.time.Instant;
public record OfficialSourceResponse(String body, String contentType, Instant fetchedAt) {}
