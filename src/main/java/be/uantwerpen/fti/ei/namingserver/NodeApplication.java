package be.uantwerpen.fti.ei.namingserver;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NodeApplication {
    // Cmd: mvn spring-boot:run -Dspring-boot.run.main-class=be.uantwerpen.fti.ei.namingserver.NodeApplication
    public static void main(String[] args) {
        System.out.println("Starting node and joining multicast group");
        MulticastListener listener = new MulticastListener();
        Thread listenerThread = new Thread(listener);
        listenerThread.start();
        System.out.println("Node is now listening for multicast messages.");
    }
}

