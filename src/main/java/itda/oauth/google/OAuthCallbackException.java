package itda.oauth.google;

final class OAuthCallbackException extends RuntimeException {

    private final OAuthCallbackFailure failure;

    OAuthCallbackException(OAuthCallbackFailure failure) {
        this.failure = failure;
    }

    OAuthCallbackFailure failure() {
        return failure;
    }
}
