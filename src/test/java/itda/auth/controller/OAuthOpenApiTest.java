package itda.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthOpenApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void runtimeOpenApiExposesOAuthExchangeAndSignupContracts() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode exchange = openApi.path("paths").path("/auth/oauth/exchange").path("post");
        JsonNode signup = openApi.path("paths").path("/auth/oauth/signup").path("post");
        assertConcreteOperation(exchange, "exchange");
        for (String statusCode : java.util.List.of("200", "202", "400", "401", "403", "409", "410")) {
            assertThat(exchange.path("responses").has(statusCode)).as("exchange %s", statusCode).isTrue();
        }
        assertSchemaProperties(
                resolveSchema(openApi, responseSchema(exchange, "200", "exchange")),
                "success", "message", "data", "error"
        );
        JsonNode exchangeEnvelope = resolveSchema(openApi, responseSchema(exchange, "200", "exchange"));
        assertThat(resolveSchema(openApi, responseSchema(exchange, "202", "exchange")))
                .isEqualTo(exchangeEnvelope);
        assertOneOfDataSchemas(
                openApi,
                exchangeEnvelope,
                "AuthTokensResponse",
                "OAuthSignupRequiredResponse"
        );
        assertSchemaProperties(
                resolveSchema(openApi, requestSchema(exchange, "exchange")),
                "provider", "loginCode"
        );

        assertConcreteOperation(signup, "signup");
        for (String statusCode : java.util.List.of("201", "400", "401", "409", "410", "422")) {
            assertThat(signup.path("responses").has(statusCode)).as("signup %s", statusCode).isTrue();
        }
        JsonNode signupEnvelope = resolveSchema(openApi, responseSchema(signup, "201", "signup"));
        assertSchemaProperties(signupEnvelope, "success", "message", "data", "error");
        assertSchemaProperties(
                resolveSchema(openApi, signupEnvelope.path("properties").path("data")),
                "accessToken", "refreshToken", "accessTokenExpiresAt"
        );
        assertSchemaProperties(
                resolveSchema(openApi, requestSchema(signup, "signup")),
                "signupToken", "nickname", "neighborhoodCode"
        );

        for (String browserPath : java.util.List.of("/oauth2/authorization/google", "/login/oauth2/code/google")) {
            JsonNode browserOperation = openApi.path("paths").path(browserPath).path("get");
            assertThat(browserOperation.isMissingNode()).as(browserPath).isFalse();
            assertThat(browserOperation.path("responses").has("302")).as(browserPath).isTrue();
            assertThat(browserOperation.path("responses").has("404")).as(browserPath).isTrue();
            assertThat(browserOperation.path("responses").path("302").path("headers")
                    .path("Location").isMissingNode()).as("%s Location header", browserPath).isFalse();
        }
    }

    private void assertConcreteOperation(JsonNode operation, String operationName) {
        assertThat(operation.isMissingNode()).as("%s operation", operationName).isFalse();
        assertThat(operation.isNull()).as("%s operation", operationName).isFalse();
        assertThat(operation.isObject()).as("%s operation", operationName).isTrue();
        assertThat(operation.size()).as("%s operation", operationName).isGreaterThan(0);
    }

    private JsonNode requestSchema(JsonNode operation, String operationName) {
        JsonNode requestBody = operation.path("requestBody");
        assertThat(requestBody.isMissingNode()).as("%s request body", operationName).isFalse();
        assertThat(requestBody.isNull()).as("%s request body", operationName).isFalse();
        return contentSchema(requestBody.path("content"), operationName + " request");
    }

    private JsonNode responseSchema(JsonNode operation, String statusCode, String operationName) {
        JsonNode response = operation.path("responses").path(statusCode);
        assertThat(response.isMissingNode()).as("%s %s response", operationName, statusCode).isFalse();
        assertThat(response.isNull()).as("%s %s response", operationName, statusCode).isFalse();
        return contentSchema(response.path("content"), operationName + " " + statusCode + " response");
    }

    private JsonNode contentSchema(JsonNode content, String description) {
        assertThat(content.isMissingNode()).as("%s content", description).isFalse();
        assertThat(content.isNull()).as("%s content", description).isFalse();
        assertThat(content.isObject()).as("%s content", description).isTrue();
        assertThat(content.size()).as("%s content", description).isGreaterThan(0);

        JsonNode media = content.has("application/json")
                ? content.path("application/json")
                : content.path("*/*");
        assertThat(media.isMissingNode()).as("%s JSON media type", description).isFalse();
        assertThat(media.isNull()).as("%s JSON media type", description).isFalse();
        JsonNode schema = media.path("schema");
        assertThat(schema.isMissingNode()).as("%s schema", description).isFalse();
        assertThat(schema.isNull()).as("%s schema", description).isFalse();
        assertThat(schema.isObject()).as("%s schema", description).isTrue();
        assertThat(schema.size()).as("%s schema", description).isGreaterThan(0);
        assertThat(schema.toString()).as("%s schema", description).doesNotContain("Object");
        return schema;
    }

    private JsonNode resolveSchema(JsonNode openApi, JsonNode schemaReference) {
        String reference = schemaReference.path("$ref").asText();
        assertThat(reference).as("schema reference").startsWith("#/components/schemas/");
        String schemaName = reference.substring("#/components/schemas/".length());
        assertThat(schemaName).isNotBlank().doesNotContain("Object");
        JsonNode schema = openApi.path("components").path("schemas").path(schemaName);
        assertThat(schema.isMissingNode()).as("component schema %s", schemaName).isFalse();
        assertThat(schema.isNull()).as("component schema %s", schemaName).isFalse();
        assertThat(schema.isObject()).as("component schema %s", schemaName).isTrue();
        assertThat(schema.size()).as("component schema %s", schemaName).isGreaterThan(0);
        return schema;
    }

    private void assertSchemaProperties(JsonNode schema, String... propertyNames) {
        JsonNode properties = schema.path("properties");
        JsonNode allOf = schema.path("allOf");
        assertThat((properties.isObject() && properties.size() > 0)
                || (allOf.isArray() && allOf.size() > 0))
                .as("concrete schema properties or allOf: %s", schema)
                .isTrue();
        for (String propertyName : propertyNames) {
            assertThat(hasProperty(schema, propertyName))
                    .as("schema property %s", propertyName)
                    .isTrue();
        }
    }

    private boolean hasProperty(JsonNode schema, String propertyName) {
        if (schema.path("properties").has(propertyName)) {
            return true;
        }
        JsonNode allOf = schema.path("allOf");
        if (!allOf.isArray()) {
            return false;
        }
        for (JsonNode part : allOf) {
            if (part.path("properties").has(propertyName)) {
                return true;
            }
        }
        return false;
    }

    private void assertOneOfDataSchemas(
            JsonNode openApi,
            JsonNode envelope,
            String firstSchemaName,
            String secondSchemaName
    ) {
        JsonNode variants = envelope.path("properties").path("data").path("oneOf");
        assertThat(variants.isMissingNode()).as("OAuth exchange data oneOf").isFalse();
        assertThat(variants.isArray()).as("OAuth exchange data oneOf").isTrue();
        assertThat(variants.size()).as("OAuth exchange data oneOf").isEqualTo(2);

        java.util.Set<String> variantNames = new java.util.HashSet<>();
        for (JsonNode variant : variants) {
            String reference = variant.path("$ref").asText();
            assertThat(reference).startsWith("#/components/schemas/");
            variantNames.add(reference.substring("#/components/schemas/".length()));
        }
        assertThat(variantNames).containsExactlyInAnyOrder(firstSchemaName, secondSchemaName);
        assertSchemaProperties(
                openApi.path("components").path("schemas").path(firstSchemaName),
                "accessToken", "refreshToken", "accessTokenExpiresAt"
        );
        assertSchemaProperties(
                openApi.path("components").path("schemas").path(secondSchemaName),
                "profileCompletionRequired", "signupToken", "signupTokenExpiresAt"
        );
    }
}
