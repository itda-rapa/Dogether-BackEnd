package itda.oauth.google;

public final class OAuthCallbackException extends RuntimeException {

    private final OAuthCallbackFailure failure;

    public OAuthCallbackException(OAuthCallbackFailure failure) {
        this.failure = failure;
    }

    public OAuthCallbackFailure failure() {
        return failure;
    }
}
