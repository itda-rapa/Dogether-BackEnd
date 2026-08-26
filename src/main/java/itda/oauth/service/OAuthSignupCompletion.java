package itda.oauth.service;

import itda.user.domain.User;

/** Runs inside the successful OAuth signup attempt transaction. */
@FunctionalInterface
public interface OAuthSignupCompletion<T> {
    T complete(User user);
}
