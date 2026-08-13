package itda.media.storage;

public final class StorageProviderUnavailableException extends ObjectStorageException {

    public StorageProviderUnavailableException(String operation, Throwable cause) {
        super("Object storage provider is temporarily unavailable: " + operation,
                operation);
    }
}
