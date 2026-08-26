package itda.oauth.service;

import itda.oauth.domain.OAuthProvider;

public record OAuthExchangeCommand(OAuthProvider provider, String loginCode) {
    public OAuthExchangeCommand {
        if (provider == null || loginCode == null || loginCode.isBlank()) {
            throw new OAuthFlowException(OAuthFlowFailure.LOGIN_CODE_INVALID);
        }
    }
}
