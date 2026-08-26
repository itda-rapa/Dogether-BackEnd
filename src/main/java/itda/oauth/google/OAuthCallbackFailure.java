package itda.oauth.google;

/** Safe, allowlisted browser-callback outcomes. Raw provider errors never cross this boundary. */
public enum OAuthCallbackFailure {
    INTERNAL_ERROR,
    OAUTH_STATE_INVALID,
    OAUTH_STATE_EXPIRED,
    OAUTH_AUTHORIZATION_DENIED,
    OAUTH_IDENTITY_VERIFICATION_FAILED,
    OAUTH_PROVIDER_UNAVAILABLE
}
