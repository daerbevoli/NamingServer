package be.uantwerpen.fti.ei.namingserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NamingServerApplication {
    // Cmd: mvn spring-boot:run -Dspring-boot.run.main-class=be.uantwerpen.fti.ei.namingserver.NamingServerApplication
    public static void main(String[] args) {
        // Start the Spring Boot application
        SpringApplication.run(NamingServerApplication.class, args);

    }

}
