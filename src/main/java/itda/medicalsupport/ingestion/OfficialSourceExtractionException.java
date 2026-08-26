package itda.medicalsupport.ingestion;

public class OfficialSourceExtractionException extends IllegalArgumentException {

    private final String contentType;
    private final String sourceHash;

    public OfficialSourceExtractionException(
            String message, String contentType, String sourceHash, RuntimeException cause) {
        super(message, cause);
        this.contentType = contentType;
        this.sourceHash = sourceHash;
    }

    public String contentType() {
        return contentType;
    }

    public String sourceHash() {
        return sourceHash;
    }
}
