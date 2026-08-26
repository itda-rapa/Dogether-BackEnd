package itda.auth.dto;

public sealed interface OAuthExchangeResponse
        permits AuthTokensResponse, OAuthSignupRequiredResponse {
}
