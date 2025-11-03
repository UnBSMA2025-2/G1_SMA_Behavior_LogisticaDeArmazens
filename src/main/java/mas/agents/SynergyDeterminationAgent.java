package mas.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import mas.models.ProductBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SynergyDeterminationAgent extends Agent {

    private static final Logger logger = LoggerFactory.getLogger(SynergyDeterminationAgent.class);

    protected void setup() {
        logger.info("SDA {} is ready.", getAID().getName());

        // Comportamento para aguardar e responder a pedidos de pacotes de produtos
        addBehaviour(new CyclicBehaviour() {
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchProtocol("get-bundles-protocol") // Usa a mesma string do CA
                );
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    logger.info("SDA: Received request for product bundles from {}", msg.getSender().getName());

                    // Simula o algoritmo de determinação de pacotes.
                    // Baseado no Experimento 1, Passo 3.
                    List<ProductBundle> preferredBundles = generatePreferredBundles();

                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    try {
                        reply.setContentObject((Serializable) preferredBundles);
                        myAgent.send(reply);
                        logger.info("SDA: Sent preferred product bundles back to CA (to {}).", msg.getSender().getName());
                    } catch (IOException e) {
                        logger.error("SDA: Failed to set content or send reply to {}.", msg.getSender().getName(), e);
                    }
                } else {
                    block();
                    logger.debug("SDA: No matching message received, behaviour blocked.");
                }
            }
        });
    }

    private List<ProductBundle> generatePreferredBundles() {
        List<ProductBundle> bundles = new ArrayList<>();
        // Pacotes de um único produto
        bundles.add(new ProductBundle(new int[]{1, 0, 0, 0})); // P1
        bundles.add(new ProductBundle(new int[]{0, 1, 0, 0})); // P2
        bundles.add(new ProductBundle(new int[]{0, 0, 1, 0})); // P3
        bundles.add(new ProductBundle(new int[]{0, 0, 0, 1})); // P4
        // Pacotes de dois produtos (sinergia)
        bundles.add(new ProductBundle(new int[]{1, 1, 0, 0})); // P1P2
        bundles.add(new ProductBundle(new int[]{1, 0, 1, 0})); // P1P3
        bundles.add(new ProductBundle(new int[]{1, 0, 0, 1})); // P1P4
        bundles.add(new ProductBundle(new int[]{0, 1, 1, 0})); // P2P3
        bundles.add(new ProductBundle(new int[]{0, 1, 0, 1})); // P2P4
        bundles.add(new ProductBundle(new int[]{0, 0, 1, 1})); // P3P4
        return bundles;
    }
}
