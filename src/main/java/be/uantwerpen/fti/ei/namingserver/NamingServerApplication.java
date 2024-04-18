package be.uantwerpen.fti.ei.namingserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication; // Used to mark a class as the main Spring Boot application class. It enables auto-configuration features provided by Spring Boot.

@SpringBootApplication
public class NamingServerApplication {
    // Cmd: mvn spring-boot:run -Dspring-boot.run.main-class=be.uantwerpen.fti.ei.namingserver.NamingServerApplication
    public static void main(String[] args) {
        // Start the Spring Boot application
        SpringApplication.run(NamingServerApplication.class, args);

    }

}
