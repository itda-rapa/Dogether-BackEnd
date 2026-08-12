package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class SetlogUploadPropertiesTest {

    @Test
    void productionRequiresVersionIdWhileLocalAndTestAllowVersionlessStorage() throws Exception {
        assertThat(profileValue("src/main/resources/application.yaml")).isTrue();
        assertThat(profileValue("src/main/resources/application-prod.yaml")).isTrue();
        assertThat(profileValue("src/main/resources/application-local.yaml")).isFalse();
        assertThat(profileValue("src/test/resources/application-test.yaml")).isFalse();
    }

    private static boolean profileValue(String path) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load(path, new FileSystemResource(path))
                .forEach(environment.getPropertySources()::addLast);
        return environment.getRequiredProperty(
                "app.setlog-upload.require-version-id", Boolean.class);
    }
}
