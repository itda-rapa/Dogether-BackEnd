package itda.oauth.service;

import java.time.Instant;

public sealed interface OAuthExchangeResult<T>
        permits OAuthExchangeResult.ExistingUser, OAuthExchangeResult.SignupRequired {

    record ExistingUser<T>(T value) implements OAuthExchangeResult<T> {
    }

    record SignupRequired<T>(String signupToken, Instant signupTokenExpiresAt)
            implements OAuthExchangeResult<T> {
        public boolean profileCompletionRequired() {
            return true;
        }
    }
}
