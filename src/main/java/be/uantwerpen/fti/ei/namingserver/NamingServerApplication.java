package be.uantwerpen.fti.ei.namingserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NamingServerApplication {
    // Cmd: mvn spring-boot:run -Dspring-boot.run.main-class=be.uantwerpen.fti.ei.namingserver.NamingServerApplication
    public static void main(String[] args) {
        System.out.println("Starting Server and joining multicast group");
        MulticastListener listener = new MulticastListener();
        Thread listenerThread = new Thread(listener);
        listenerThread.start();
        System.out.println("Server is now listening for multicast messages.");
        // Start the Spring Boot application
        SpringApplication.run(NamingServerApplication.class, args);

    }

}
