package itda.media.storage;

public final class ObjectNotFoundException extends ObjectStorageException {

    public ObjectNotFoundException(String operation, Throwable cause) {
        super("Object storage object was not found", operation);
    }
}
