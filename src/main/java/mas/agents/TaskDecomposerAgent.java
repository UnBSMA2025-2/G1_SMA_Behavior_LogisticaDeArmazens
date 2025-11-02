package mas.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;

import mas.models.ProductBundle;

/**
 * TaskDecomposerAgent - cria ProductBundles para cada demanda e envia ao Coordinator (AID "ca")
 */
public class TaskDecomposerAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(TaskDecomposerAgent.class);
    private Random random = new Random();
    private int currentDemandScenario = 0;

    private final String[][] DEMAND_SCENARIOS = {
        {"P1,P2,P3,P4"},
        {"P1,P2"},
        {"P1,P3"},
        {"P2,P4"},
        {"P1,P1,P2"}
    };

    protected void setup() {
        logger.info("Dynamic TDA {} setup started.", getAID().getName());

        // periodic: envia demanda atual (e cria bundle para ela)
        addBehaviour(new TickerBehaviour(this, 10000) {
            protected void onTick() {
                sendCurrentDemand();
            }
        });

        // change scenario periodicamente e reenvia
        addBehaviour(new TickerBehaviour(this, 45000) {
            protected void onTick() {
                changeDemandScenario();
                sendCurrentDemand();
            }
        });

        // comportamento para receber pedidos urgentes de troca de demanda
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

    /**
     * Cria um ProductBundle baseado na demanda atual.
     * Ajuste lógica de decomposição aqui conforme seu domínio (ex: mapear produtos -> SKUs).
     */
    private ProductBundle createBundleForDemand(String demand) {
        // Exemplo simples: gera id único e escolhe itens dependendo da demanda
        String bundleId = "BUNDLE-" + UUID.randomUUID().toString();

        // Aqui você pode aplicar heurísticas reais de decomposição.
        ProductBundle.Builder builder = new ProductBundle.Builder()
                .id(bundleId)
                .name("Bundle para demanda " + currentDemandScenario)
                // Exemplo: sempre adiciona 2 SKUs - adapte conforme sua regra
                .addItem("SKU-001", 2)
                .addItem("SKU-002", 1)
                // bounds iniciais de sinergia (podem ser atualizados depois)
                .synergyBounds(0.15, 0.7)
                .issueWeight("price", 0.7)
                .issueWeight("time", 0.3)
                .metadata("demand_string", demand);

        return builder.build();
    }

    /**
     * Envia mensagem ao Coordinator ('ca') com o ProductBundle (serializado como Java object).
     * Mensagem contém: performative = INFORM, protocolo = "define-task-protocol".
     */
    private void sendCurrentDemand() {
        String demand = DEMAND_SCENARIOS[currentDemandScenario][0];
        logger.info("🎯 TDA SENDING DYNAMIC DEMAND: {}", demand);
        System.out.println("🎯 ===== TDA SENDING DYNAMIC DEMAND: " + demand + " =====");

        ProductBundle bundle = createBundleForDemand(demand);

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(new AID("ca", AID.ISLOCALNAME));           // Coordinator AID
        msg.setProtocol("define-task-protocol");

        // duas opções: enviar o bundle como objeto Java (setContentObject) ou enviar apenas
        // a string 'demand' e o coordinator recupera o bundle de um registro remoto.
        try {
            // Envia o bundle (ProductBundle deve implementar Serializable)
            msg.setContentObject(bundle);
            // Também pode setar a string demand como user-defined parameter (opcional)
          //  msg.setUserDefinedParameter("demandString", demand);
            send(msg);
            logger.info("TDA: Sent bundle {} to coordinator.", bundle.getId());
        } catch (IOException e) {
            logger.error("TDA: Failed to serialize ProductBundle. Falling back to plain string.", e);
            // Fallback: envie apenas a demanda como string (menos ideal)
            ACLMessage fallback = new ACLMessage(ACLMessage.REQUEST);
            fallback.addReceiver(new AID("ca", AID.ISLOCALNAME));
            fallback.setProtocol("define-task-protocol");
            fallback.setContent(demand);
            send(fallback);
        }
    }
}

