package be.uantwerpen.fti.ei.namingserver;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.logging.Logger;
import jade.core.Agent;

public class FailureAgent extends Agent implements Runnable, Serializable {

    private static final Logger logger = Logger.getLogger(FailureAgent.class.getName());

    private final Node currentNode;
    private final int failingNodeId;
    private final int startingNodeId;

    public FailureAgent(Node currentNode, int failingNodeId, int startingNodeId) {
        this.currentNode = currentNode;
        this.failingNodeId = failingNodeId;
        this.startingNodeId = startingNodeId;
    }


}
