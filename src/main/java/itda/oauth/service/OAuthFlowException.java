package itda.oauth.service;

public class OAuthFlowException extends RuntimeException {

    private final OAuthFlowFailure failure;

    public OAuthFlowException(OAuthFlowFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public OAuthFlowFailure getFailure() {
        return failure;
    }
}
