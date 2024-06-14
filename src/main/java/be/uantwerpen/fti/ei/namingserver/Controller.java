package be.uantwerpen.fti.ei.namingserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/NS") // NS = Naming Server
public class Controller {

    Server server = new Server();

    @PostMapping("/add/{ip}")
    public ResponseEntity<String> addNode(@PathVariable String ip){
        if (server.addNode(ip)){
            return ResponseEntity.ok(ip + " added to the network");
        } else {
            return ResponseEntity.ok(ip + " already in the network");
        }
    }

    @DeleteMapping("/remove/{ip}")
    public ResponseEntity<String> removeNode(@PathVariable String ip){
        if (server.removeNode(ip)){
            return ResponseEntity.ok(ip + " successfully removed from the network\n");
        } else {
            return ResponseEntity.ok(ip + " not in the network\n");
        }
    }

    @GetMapping("/get/{filename}")
    public ResponseEntity<String> getHost(@PathVariable String filename){
        return ResponseEntity.ok(server.getFileHost(filename));
    }




}
