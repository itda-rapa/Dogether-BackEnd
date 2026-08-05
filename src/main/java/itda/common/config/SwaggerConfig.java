package itda.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Dogether API",
                version = "v1",
                description = "같이놀개 백엔드 API"
        )
)
public class SwaggerConfig {

    @Bean
    OpenAPI openApi() {
        String schemeName = "bearerAuth";
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                ))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }

    @Bean
    public GroupedOpenApi neighborApi(){
        return GroupedOpenApi.builder()
                .group("Neighborhood API")
                .pathsToMatch("/neighborhoods", "/neighborhoods/**")
                .build();
    }

    @Bean
    public GroupedOpenApi authApi(){
        return GroupedOpenApi.builder()
                .group("Auth API")
                .pathsToMatch("/auth", "/auth/**")
                .build();
    }

    @Bean
    public GroupedOpenApi meApi(){
        return GroupedOpenApi.builder()
                .group("Me API")
                .pathsToMatch("/me", "/me/**")
                .pathsToExclude("/me/blocks")
                .build();
    }

    @Bean
    public GroupedOpenApi petApi(){
        return GroupedOpenApi.builder()
                .group("Pet API")
                .pathsToMatch("/pets", "/pets/**")
                .pathsToExclude("/pets/*/friends", "/pets/*/friends/*")
                .build();
    }

    @Bean
    public GroupedOpenApi petVerificationApi(){
        return GroupedOpenApi.builder()
                .group("Pet Verification API")
                .pathsToMatch("/pet-verification", "/pet-verification/**")
                .build();
    }

    @Bean
    public GroupedOpenApi setLogApi(){
        return GroupedOpenApi.builder()
                .group("Set Log API")
                .pathsToMatch("/setlogs", "/setlogs/**")
                .build();
    }

    @Bean
    public GroupedOpenApi frientRequestApi(){
        return GroupedOpenApi.builder()
                .group("Friend Request API")
                .pathsToMatch("/friend-requests", "/friend-requests/**", "/pets/*/friends", "/pets/*/friends/*")
                .build();
    }

    @Bean
    public GroupedOpenApi chatApi(){
        return GroupedOpenApi.builder()
                .group("Chat API")
                .pathsToMatch("/chat/**")
                .pathsToExclude("/chat/rooms/*/card-drafts")
                .build();
    }

    @Bean
    public GroupedOpenApi meetingCardApi(){
        return GroupedOpenApi.builder()
                .group("Meeting Card API")
                .pathsToMatch("/meeting-cards", "/meeting-cards/**", "/chat/rooms/*/card-drafts")
                .build();
    }

    @Bean
    public GroupedOpenApi blockApi(){
        return GroupedOpenApi.builder()
                .group("Block API")
                .pathsToMatch("/me/blocks")
                .build();
    }

    @Bean
    public GroupedOpenApi reportApi(){
        return GroupedOpenApi.builder()
                .group("Report API")
                .pathsToMatch("/reports", "/reports/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi(){
        return GroupedOpenApi.builder()
                .group("Admin API")
                .pathsToMatch("/admin/**")
                .build();
    }
}
