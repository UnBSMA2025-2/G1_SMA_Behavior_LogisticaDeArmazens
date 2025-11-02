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
import java.util.UUID;

/**
 * SynergyDeterminationAgent (SDA)
 *
 * - Responde a REQUEST do Coordinator (protocolo "get-bundles-protocol") com uma
 *   lista de ProductBundle (contentObject: List<ProductBundle>).
 * - Gera ProductBundle usando o Builder (id único, itens, synergyBounds, issueWeights, metadata).
 *
 * Observação: ProductBundle deve implementar Serializable (implementação anterior já cobre isso).
 */
public class SynergyDeterminationAgent extends Agent {

    private static final Logger logger = LoggerFactory.getLogger(SynergyDeterminationAgent.class);
    private static final String PROTOCOL_GET_BUNDLES = "get-bundles-protocol";

    @Override
    protected void setup() {
        logger.info("SDA {} is ready.", getAID().getName());

        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchProtocol(PROTOCOL_GET_BUNDLES)
                );
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    logger.info("SDA: Received request for product bundles from {}", msg.getSender().getLocalName());

                    List<ProductBundle> preferredBundles = generatePreferredBundles();

                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setProtocol(PROTOCOL_GET_BUNDLES);

                    try {
                        // envia a lista de bundles como objeto serializável
                        reply.setContentObject((Serializable) preferredBundles);
                        myAgent.send(reply);
                        logger.info("SDA: Sent {} preferred product bundles back to CA (to {}).", preferredBundles.size(), msg.getSender().getLocalName());
                    } catch (IOException e) {
                        logger.error("SDA: Failed to set content or send reply to {}.", msg.getSender().getLocalName(), e);
                    }
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Gera uma lista de bundles de exemplo.
     * - usa ids únicos
     * - define synergyBounds diferentes para pacotes simples e combinados
     * - define issueWeights (price/time) como exemplo
     * - adiciona metadata (ex: origem/experimento)
     */
    private List<ProductBundle> generatePreferredBundles() {
        List<ProductBundle> bundles = new ArrayList<>();

        // Pacotes simples (unidades)
        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P1")
                .addItem("P1", 1)
                .synergyBounds(0.05, 0.25)       // pouca sinergia esperada para item isolado
                .issueWeight("price", 0.8)
                .issueWeight("time", 0.2)
                .metadata("origin", "SDA")
                .metadata("type", "singleton")
                .build());

        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P2")
                .addItem("P2", 1)
                .synergyBounds(0.05, 0.25)
                .issueWeight("price", 0.75)
                .issueWeight("time", 0.25)
                .metadata("origin", "SDA")
                .metadata("type", "singleton")
                .build());

        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P3")
                .addItem("P3", 1)
                .synergyBounds(0.05, 0.25)
                .issueWeight("price", 0.7)
                .issueWeight("time", 0.3)
                .metadata("origin", "SDA")
                .metadata("type", "singleton")
                .build());

        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P4")
                .addItem("P4", 1)
                .synergyBounds(0.05, 0.25)
                .issueWeight("price", 0.7)
                .issueWeight("time", 0.3)
                .metadata("origin", "SDA")
                .metadata("type", "singleton")
                .build());

        // Pacotes combinados (sinergia maior)
        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P1P2")
                .addItem("P1", 1)
                .addItem("P2", 1)
                .synergyBounds(0.2, 0.8)         // maior variação por sinergia
                .issueWeight("price", 0.7)
                .issueWeight("time", 0.3)
                .metadata("origin", "SDA")
                .metadata("type", "combo")
                .build());

        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P1P3")
                .addItem("P1", 1)
                .addItem("P3", 1)
                .synergyBounds(0.15, 0.7)
                .issueWeight("price", 0.65)
                .issueWeight("time", 0.35)
                .metadata("origin", "SDA")
                .metadata("type", "combo")
                .build());

        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P2P3")
                .addItem("P2", 1)
                .addItem("P3", 1)
                .synergyBounds(0.18, 0.72)
                .issueWeight("price", 0.68)
                .issueWeight("time", 0.32)
                .metadata("origin", "SDA")
                .metadata("type", "combo")
                .build());

        bundles.add(new ProductBundle.Builder()
                .id("PB-" + UUID.randomUUID())
                .name("Bundle_P3P4")
                .addItem("P3", 1)
                .addItem("P4", 1)
                .synergyBounds(0.22, 0.75)
                .issueWeight("price", 0.7)
                .issueWeight("time", 0.3)
                .metadata("origin", "SDA")
                .metadata("type", "combo")
                .build());

        return bundles;
    }
}

