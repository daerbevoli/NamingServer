package be.uantwerpen.fti.ei.namingserver;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.logging.Logger;
import jade.core.Agent;

public class FailureAgent extends Agent implements Runnable, Serializable {

    private static final Logger logger = Logger.getLogger(FailureAgent.class.getName());

    private final Node currentNode;
    private final Node failedNode;
    private final int startingNodeId;

    public FailureAgent(Node currentNode, Node failedNode, int startingNodeId) {
        this.currentNode = currentNode;
        this.failedNode = failedNode;
        this.startingNodeId = startingNodeId;
    }


}
