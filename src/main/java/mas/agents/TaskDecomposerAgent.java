package mas.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class TaskDecomposerAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(TaskDecomposerAgent.class);
    private Random random = new Random();
    private int currentDemandScenario = 0;
    
    private final String[][] DEMAND_SCENARIOS = {
        {"P1,P3"},
        {"P1"},
        {"P3"},
        {"P2"},
        {"P1,P2"}  
    };

    protected void setup() {
        logger.info("Dynamic TDA {} setup started.", getAID().getName());
        addBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
                try {
                    Thread.sleep(2000); // Pequeno delay para garantir que outros agentes estejam prontos
                    sendCurrentDemand();
                    logger.info("TDA: Initial demand sent on startup");
                } catch (InterruptedException e) {
                    logger.error("TDA: Error in initial demand", e);
                }
            }
        });

        addBehaviour(new TickerBehaviour(this, 10000) {
            protected void onTick() {
                sendCurrentDemand();
            }
        });

        addBehaviour(new TickerBehaviour(this, 45000) {
            protected void onTick() {
                changeDemandScenario();
                sendCurrentDemand();
            }
        });

        addBehaviour(new CyclicBehaviour() {
            public void action() {
                ACLMessage msg = receive(MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
                if (msg != null && "URGENT_DEMAND_CHANGE".equals(msg.getContent())) {
                    logger.info("TDA: Received urgent demand change request");
                    changeDemandScenario();
                    sendCurrentDemand();
                } else {
                    block();
                }
            }
        });
    }

    private void changeDemandScenario() {
        int newScenario;
        do {
            newScenario = random.nextInt(DEMAND_SCENARIOS.length);
        } while (newScenario == currentDemandScenario && DEMAND_SCENARIOS.length > 1);
        
        currentDemandScenario = newScenario;
        logger.info("TDA: Changed to demand scenario {}: {}", currentDemandScenario, 
                   DEMAND_SCENARIOS[currentDemandScenario][0]);
    }

    private void sendCurrentDemand() {
        String demand = DEMAND_SCENARIOS[currentDemandScenario][0];
                System.out.println("🎯 ===== TDA SENDING DYNAMIC DEMAND: " + demand + " =====");
        logger.info("🎯 TDA SENDING DYNAMIC DEMAND: {}", demand);

        ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
        msg.addReceiver(new AID("ca", AID.ISLOCALNAME));
        msg.setContent(demand);
        msg.setProtocol("define-task-protocol");

        send(msg);
    }
}
