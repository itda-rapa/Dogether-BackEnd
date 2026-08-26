package itda.oauth.domain;

/**
 * Providers that may be represented in the common OAuth persistence model.
 * Only GOOGLE has a runtime adapter in this milestone.
 */
public enum OAuthProvider {
    GOOGLE,
    NAVER
}
