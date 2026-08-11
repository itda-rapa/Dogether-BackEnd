package itda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DogetherApplication {

    public static void main(String[] args) {
        SpringApplication.run(DogetherApplication.class, args);
    }

}
