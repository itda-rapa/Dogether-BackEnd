package itda.oauth.naver;

import itda.oauth.domain.OAuthProvider;
import itda.oauth.google.OAuthAuthorizationTransactionStore;
import itda.oauth.google.OAuthBrowserBindingCookie;
import itda.oauth.google.OAuthCallbackException;
import itda.oauth.google.OAuthCallbackFailure;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Browser-only Naver endpoints; provider credentials are never placed in redirects. */
@RestController
public class NaverOAuthController {
    private final NaverOAuthProperties properties;
    private final OAuthAuthorizationTransactionStore transactionStore;
    private final NaverOidcClient naverOidcClient;
    private final OAuthLoginCodeIssuer loginCodeIssuer;
    private final OAuthBrowserBindingCookie browserBindingCookie;

    public NaverOAuthController(NaverOAuthProperties properties, OAuthAuthorizationTransactionStore transactionStore,
                                NaverOidcClient naverOidcClient, OAuthLoginCodeIssuer loginCodeIssuer,
                                OAuthBrowserBindingCookie browserBindingCookie) {
        this.properties = properties;
        this.transactionStore = transactionStore;
        this.naverOidcClient = naverOidcClient;
        this.loginCodeIssuer = loginCodeIssuer;
        this.browserBindingCookie = browserBindingCookie;
    }

    @GetMapping("/oauth2/authorization/naver")
    @Operation(summary = "Naver OAuth authorization start", description = "Creates a one-time browser-bound PKCE transaction with the exact openid scope and redirects to Naver.")
    @ApiResponses({@ApiResponse(responseCode = "302", description = "Naver authorization redirect or configured safe error callback.",
            headers = {@Header(name = "Location", schema = @Schema(type = "string", format = "uri")), @Header(name = "Set-Cookie", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "404", description = "Naver OAuth is disabled.")})
    public ResponseEntity<Void> authorizationStart(HttpServletRequest request) {
        if (!properties.enabled()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        try {
            String binding = browserBindingCookie.bindingOrNew(request);
            OAuthAuthorizationTransactionStore.CreatedTransaction transaction = transactionStore.create(binding, OAuthProvider.NAVER,
                    properties.backendRedirectUri(), properties.transactionTtl(), properties.transactionGraceTtl());
            return redirect(naverOidcClient.authorizationUri(transaction), browserBindingCookie.cookie(binding,
                    properties.transactionTtl().plus(properties.transactionGraceTtl())));
        } catch (OAuthCallbackException exception) { return errorRedirect(exception.failure());
        } catch (DataAccessException exception) { return errorRedirect(OAuthCallbackFailure.INTERNAL_ERROR); }
    }

    @GetMapping("/login/oauth2/code/naver")
    @Operation(summary = "Naver OAuth browser callback", description = "Consumes a browser-bound one-time transaction, exchanges the code with the same configured backend redirect URI, trusts only the configured Naver client audience (with an optional matching azp), and redirects only to configured callbacks.")
    @ApiResponses({@ApiResponse(responseCode = "302", description = "Configured success callback or safe error callback.",
            headers = @Header(name = "Location", schema = @Schema(type = "string", format = "uri"))),
            @ApiResponse(responseCode = "404", description = "Naver OAuth is disabled.")})
    public ResponseEntity<Void> callback(@Parameter(hidden = true, in = ParameterIn.QUERY) @RequestParam(required = false) String state,
                                         @Parameter(hidden = true, in = ParameterIn.QUERY) @RequestParam(required = false) String code,
                                         @Parameter(hidden = true, in = ParameterIn.QUERY) @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        if (!properties.enabled()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        try {
            String binding = browserBindingCookie.binding(request);
            if (!OAuthAuthorizationTransactionStore.isValidBrowserBinding(binding)) return errorRedirect(OAuthCallbackFailure.OAUTH_STATE_INVALID);
            OAuthAuthorizationTransactionStore.ConsumedTransaction transaction = transactionStore.consume(state, binding, OAuthProvider.NAVER,
                    properties.backendRedirectUri());
            if (error != null && !error.isBlank()) return errorRedirect(providerErrorFailure(error));
            OAuthVerifiedIdentity identity = naverOidcClient.exchangeAndVerify(code, transaction);
            IssuedOAuthLoginCode issued = loginCodeIssuer.issue(identity);
            return redirect(UriComponentsBuilder.fromUri(properties.successCallback()).queryParam("loginCode", issued.loginCode())
                    .queryParam("provider", OAuthProvider.NAVER.name()).build().encode().toUri());
        } catch (OAuthCallbackException exception) { return errorRedirect(exception.failure());
        } catch (DataAccessException exception) { return errorRedirect(OAuthCallbackFailure.INTERNAL_ERROR); }
    }

    private OAuthCallbackFailure providerErrorFailure(String error) {
        return switch (error.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "access_denied", "consent_denied", "consent_required" -> OAuthCallbackFailure.OAUTH_AUTHORIZATION_DENIED;
            case "server_error", "temporarily_unavailable", "service_unavailable" -> OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE;
            default -> OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED;
        };
    }
    private ResponseEntity<Void> errorRedirect(OAuthCallbackFailure failure) {
        return redirect(UriComponentsBuilder.fromUri(properties.errorCallback()).queryParam("errorCode", failure.name()).build().encode().toUri());
    }
    private ResponseEntity<Void> redirect(URI location, org.springframework.http.ResponseCookie cookie) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
    private ResponseEntity<Void> redirect(URI location) { return ResponseEntity.status(HttpStatus.FOUND).location(location).build(); }
}
