package itda.oauth.service;

/**
 * Core failure classifications. The HTTP/auth layer owns mapping these to the public ErrorCode.
 */
public enum OAuthFlowFailure {
    LOGIN_CODE_INVALID,
    LOGIN_CODE_EXPIRED,
    LOGIN_CODE_CONSUMED,
    SIGNUP_TOKEN_INVALID,
    SIGNUP_TOKEN_EXPIRED,
    ACCOUNT_LINK_DECISION_REQUIRED,
    ACCOUNT_NOT_ACTIVE,
    VALIDATION_FAILED,
    NEIGHBORHOOD_NOT_FOUND,
    CONCURRENT_UPDATE_CONFLICT,
    PUBLIC_TAG_GENERATION_FAILED
}
