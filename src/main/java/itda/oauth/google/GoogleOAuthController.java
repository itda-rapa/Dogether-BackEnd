package itda.oauth.google;

import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.IssuedOAuthLoginCode;
import itda.oauth.service.OAuthLoginCodeIssuer;
import itda.oauth.service.OAuthVerifiedIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Browser-only endpoints: all recognized OAuth outcomes redirect through configured callback URLs. */
@RestController
public class GoogleOAuthController {

    private final GoogleOAuthProperties properties;
    private final OAuthAuthorizationTransactionStore transactionStore;
    private final GoogleOidcClient googleOidcClient;
    private final OAuthLoginCodeIssuer loginCodeIssuer;
    private final OAuthBrowserBindingCookie browserBindingCookie;

    public GoogleOAuthController(
            GoogleOAuthProperties properties,
            OAuthAuthorizationTransactionStore transactionStore,
            GoogleOidcClient googleOidcClient,
            OAuthLoginCodeIssuer loginCodeIssuer,
            OAuthBrowserBindingCookie browserBindingCookie
    ) {
        this.properties = properties;
        this.transactionStore = transactionStore;
        this.googleOidcClient = googleOidcClient;
        this.loginCodeIssuer = loginCodeIssuer;
        this.browserBindingCookie = browserBindingCookie;
    }

    @GetMapping("/oauth2/authorization/google")
    @Operation(
            summary = "Google OAuth authorization start",
            description = "Creates a one-time server-side authorization transaction, sets a short-lived browser "
                    + "correlation cookie (HttpOnly, Secure, SameSite=Lax, Path=/), and redirects to Google."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "302",
                    description = "Google authorization redirect, or the configured safe error callback.",
                    headers = {
                            @Header(name = "Location", description = "Server-selected redirect URI.",
                                    schema = @Schema(type = "string", format = "uri")),
                            @Header(name = "Set-Cookie", description = "On authorization start, a short-lived "
                                    + "HttpOnly, Secure, SameSite=Lax browser correlation cookie.",
                                    schema = @Schema(type = "string"))
                    }
            ),
            @ApiResponse(responseCode = "404", description = "Google OAuth is disabled.")
    })
    public ResponseEntity<Void> authorizationStart(HttpServletRequest request) {
        if (!properties.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            String browserBinding = browserBindingCookie.bindingOrNew(request);
            OAuthAuthorizationTransactionStore.CreatedTransaction transaction = transactionStore.create(browserBinding);
            return redirect(googleOidcClient.authorizationUri(transaction), browserBindingCookie.cookie(browserBinding,
                    properties.transactionTtl().plus(properties.transactionGraceTtl())));
        } catch (OAuthCallbackException exception) {
            return errorRedirect(exception.failure());
        } catch (DataAccessException exception) {
            return errorRedirect(OAuthCallbackFailure.INTERNAL_ERROR);
        }
    }

    @GetMapping("/login/oauth2/code/google")
    @Operation(
            summary = "Google OAuth browser callback",
            description = "Completes the server-side OIDC flow only when the short-lived browser correlation "
                    + "cookie matches the authorization transaction, then redirects only to a configured callback. "
                    + "Callback query values are intentionally omitted from this contract."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "302",
                    description = "Configured success callback (loginCode and provider only), or safe error callback "
                            + "with one allowlisted errorCode: INTERNAL_ERROR, OAUTH_STATE_INVALID, "
                            + "OAUTH_STATE_EXPIRED, OAUTH_AUTHORIZATION_DENIED, "
                            + "OAUTH_IDENTITY_VERIFICATION_FAILED, or OAUTH_PROVIDER_UNAVAILABLE.",
                    headers = @Header(name = "Location", description = "Server-selected redirect URI.",
                            schema = @Schema(type = "string", format = "uri"))
            ),
            @ApiResponse(responseCode = "404", description = "Google OAuth is disabled.")
    })
    public ResponseEntity<Void> callback(
            @Parameter(hidden = true, in = ParameterIn.QUERY) @RequestParam(required = false) String state,
            @Parameter(hidden = true, in = ParameterIn.QUERY) @RequestParam(required = false) String code,
            @Parameter(hidden = true, in = ParameterIn.QUERY) @RequestParam(required = false) String error,
            HttpServletRequest request
    ) {
        if (!properties.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            String browserBinding = browserBindingCookie.binding(request);
            if (!OAuthAuthorizationTransactionStore.isValidBrowserBinding(browserBinding)) {
                return errorRedirect(OAuthCallbackFailure.OAUTH_STATE_INVALID);
            }
            OAuthAuthorizationTransactionStore.ConsumedTransaction transaction = transactionStore.consume(state, browserBinding);
            if (error != null && !error.isBlank()) {
                return errorRedirect(providerErrorFailure(error));
            }
            OAuthVerifiedIdentity identity = googleOidcClient.exchangeAndVerify(code, transaction);
            IssuedOAuthLoginCode issued = loginCodeIssuer.issue(identity);
            return redirect(UriComponentsBuilder.fromUri(properties.successCallback())
                    .queryParam("loginCode", issued.loginCode())
                    .queryParam("provider", OAuthProvider.GOOGLE.name())
                    .build()
                    .encode()
                    .toUri());
        } catch (OAuthCallbackException exception) {
            return errorRedirect(exception.failure());
        } catch (DataAccessException exception) {
            return errorRedirect(OAuthCallbackFailure.INTERNAL_ERROR);
        }
    }

    private OAuthCallbackFailure providerErrorFailure(String providerError) {
        return switch (providerError.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "access_denied", "consent_denied", "consent_required" ->
                    OAuthCallbackFailure.OAUTH_AUTHORIZATION_DENIED;
            case "server_error", "temporarily_unavailable", "service_unavailable" ->
                    OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE;
            default -> OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED;
        };
    }

    private ResponseEntity<Void> errorRedirect(OAuthCallbackFailure failure) {
        URI location = UriComponentsBuilder.fromUri(properties.errorCallback())
                .queryParam("errorCode", failure.name())
                .build()
                .encode()
                .toUri();
        return redirect(location);
    }

    private ResponseEntity<Void> redirect(URI location, org.springframework.http.ResponseCookie cookie) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(location)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    private ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
