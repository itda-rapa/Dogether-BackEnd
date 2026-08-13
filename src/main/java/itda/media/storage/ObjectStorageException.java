package itda.media.storage;

public abstract class ObjectStorageException extends RuntimeException {

    private final String operation;

    protected ObjectStorageException(String message, String operation) {
        // Provider exceptions can contain signed URLs, credentials, or raw keys.
        // Do not retain them in the application-facing exception chain.
        super(message);
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
