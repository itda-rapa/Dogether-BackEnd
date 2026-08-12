package itda.media.storage;

/** A non-retryable request rejection from the storage provider. */
public final class StorageProviderRejectedException extends ObjectStorageException {

    private final int statusCode;

    public StorageProviderRejectedException(String operation, int statusCode, Throwable cause) {
        super("Object storage provider rejected the operation: " + operation,
                operation);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
