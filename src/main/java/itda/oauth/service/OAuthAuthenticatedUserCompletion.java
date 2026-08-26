package itda.oauth.service;

import itda.user.domain.User;

/**
 * Runs inside the core artifact transaction. Auth integration supplies token issuance here so
 * login-code consumption and RefreshToken persistence commit or roll back together.
 */
@FunctionalInterface
public interface OAuthAuthenticatedUserCompletion<T> {
    T complete(User user);
}
