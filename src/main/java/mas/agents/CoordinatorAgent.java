package mas.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.SequentialBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import mas.logic.WinnerDeterminationService;
import mas.models.NegotiationResult;
import mas.models.ProductBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agente central que orquestra o processo de seleção de fornecedores.
 */
public class CoordinatorAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(CoordinatorAgent.class);

    private static final String PROTOCOL_GET_BUNDLES = "get-bundles-protocol";
    private static final String PROTOCOL_REPORT_RESULT = "report-negotiation-result";
    private static final String PROTOCOL_DEFINE_TASK = "define-task-protocol";

    private List<AID> sellerAgents;
    private int finishedCounter = 0;
    private WinnerDeterminationService wds;
    private List<NegotiationResult> negotiationResults;
    private int[] productDemand;
    private List<ProductBundle> preferredBundles; // Armazena os pacotes preferidos

    // Registry que mapeia bundleId -> ProductBundle (thread-safe)
    private final Map<String, ProductBundle> bundleRegistry = new ConcurrentHashMap<>();

    protected void setup() {
        logger.info("Coordinator Agent {} is ready.", getAID().getName());

        this.wds = new WinnerDeterminationService();
        this.negotiationResults = new ArrayList<>();
        this.preferredBundles = new ArrayList<>();

        SequentialBehaviour preparationPhase = new SequentialBehaviour();
        // Aguarda que o TaskDecomposer envie bundles (via INFORM / define-task-protocol)
        preparationPhase.addSubBehaviour(new WaitForTask());
        // Mantemos a requisição ao SDA caso exista lógica adicional (mas pode retornar rapidamente)
        preparationPhase.addSubBehaviour(new RequestProductBundles());
        // Tenta iniciar as negociações — se ainda não houver bundles/demand, adia até estarem disponíveis
        preparationPhase.addSubBehaviour(new StartNegotiations());

        addBehaviour(preparationPhase);
    }

    // --- Comportamentos da Fase de Preparação ---

    /**
     * Estado 1: Aguarda receber ProductBundle(s) do TaskDecomposerAgent (TDA).
     * O TDA envia performative=INFORM, protocol="define-task-protocol" e o bundle como contentObject.
     */
    private class WaitForTask extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchProtocol(PROTOCOL_DEFINE_TASK)
            );
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                try {
                    Object content = msg.getContentObject();
                    if (content instanceof ProductBundle) {
                        ProductBundle bundle = (ProductBundle) content;
                        // registra no registry e na lista de preferred bundles
                        registerBundle(bundle);
                        preferredBundles.add(bundle);
                        logger.info("CA: Received bundle {} from {}", bundle.getId(), msg.getSender().getLocalName());

                        // tenta extrair demanda (pode ter sido enviada como metadata dentro do bundle ou como user parameter)
                        String demandString = null;
                        try {
                            // prioridade: metadata dentro do bundle
                            Object meta = bundle.getMetadata().get("demand_string");
                            if (meta != null) {
                                demandString = meta.toString();
                            }
                        } catch (Exception e) {
                            // ignore
                        }

                        // fallback: parâmetro user-defined
                        if (demandString == null) {
                            String userParam = msg.getUserDefinedParameter("demandString");
                            if (userParam != null && !userParam.isEmpty()) demandString = userParam;
                        }

                        if (demandString != null) {
                            parseAndSetDemand(demandString);
                        } else {
                            logger.debug("CA: No demand string found in bundle {} - productDemand remains as is.", bundle.getId());
                        }

                    } else {
                        // Caso o TDA tenha enviado apenas uma string (fallback)
                        String productList = msg.getContent();
                        if (productList != null && !productList.isEmpty()) {
                            logger.info("🎯 ===== CA RECEIVED DYNAMIC DEMAND (as string): {} =====", productList);
                            parseAndSetDemand(productList);
                        } else {
                            logger.warn("CA: Received INFORM define-task-protocol but content not recognized.");
                        }
                    }
                } catch (UnreadableException e) {
                    logger.error("CA: Failed to read content object from message.", e);
                }
            } else {
                block();
            }
        }
    }

    /**
     * Estado 2: (Opcional) Solicita os pacotes de produtos (bundles) preferidos ao SDA.
     * Mantido para compatibilidade com implementação anterior do SDA.
     */
    private class RequestProductBundles extends OneShotBehaviour {
        public void action() {
            logger.info("CA: Requesting preferred product bundles from SDA...");
            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
            msg.addReceiver(new AID("sda", AID.ISLOCALNAME));
            msg.setContent("generate-bundles");
            msg.setProtocol(PROTOCOL_GET_BUNDLES);
            msg.setReplyWith("req-bundles-" + System.currentTimeMillis());
            myAgent.send(msg);

            // Aguarda resposta curta (se houver)
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchProtocol(PROTOCOL_GET_BUNDLES)
            );
            ACLMessage reply = myAgent.blockingReceive(mt, 2000); // timeout 2s para não travar
            if (reply == null) {
                logger.debug("CA: No reply received for bundle request (ok if TDA provides bundles).");
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                List<ProductBundle> bundles = (List<ProductBundle>) reply.getContentObject();
                if (bundles != null && !bundles.isEmpty()) {
                    for (ProductBundle b : bundles) {
                        registerBundle(b);
                        preferredBundles.add(b);
                    }
                    logger.info("CA: Received {} preferred bundles from SDA.", bundles.size());
                } else {
                    logger.warn("CA: Received null or empty bundle list from SDA.");
                }
            } catch (UnreadableException e) {
                logger.error("CA: Failed to read bundles object from SDA.", e);
            }
        }
    }

    /**
     * Estado 3: Tenta iniciar negociações. Se não houver bundles/demand, aguarda tentando novamente.
     */
    private class StartNegotiations extends OneShotBehaviour {
        public void action() {
            logger.info("CA: Attempting to start negotiation orchestration...");

            // Se ainda não tivermos bundles ou demand definidos, cria um ticker que tentará novamente
            if (preferredBundles.isEmpty() || productDemand == null) {
                logger.info("CA: Bundles or demand not ready yet. Will retry until available.");
                myAgent.addBehaviour(new TickerBehaviour(myAgent, 2000) {
                    protected void onTick() {
                        if (!preferredBundles.isEmpty() && productDemand != null) {
                            logger.info("CA: Bundles and demand available — starting negotiations now.");
                            startBuyerCreationAndWait();
                            stop(); // interrompe este TickerBehaviour
                        } else {
                            logger.debug("CA: Still waiting for bundles/demand (bundles={}, demandSet={}).",
                                    preferredBundles.size(), productDemand != null);
                        }
                    }
                });
            } else {
                // já temos tudo: inicia imediatamente
                startBuyerCreationAndWait();
            }
        }

        private void startBuyerCreationAndWait() {
            finishedCounter = 0;
            negotiationResults.clear();

            sellerAgents = new ArrayList<>();
            sellerAgents.add(new AID("s1", AID.ISLOCALNAME));
            sellerAgents.add(new AID("s2", AID.ISLOCALNAME));
            sellerAgents.add(new AID("s3", AID.ISLOCALNAME));

            for (AID seller : sellerAgents) {
                createBuyerFor(seller);
            }

            myAgent.addBehaviour(new WaitForResults());
        }
    }

    // --- Métodos e Comportamentos de Orquestração ---

    private void createBuyerFor(AID sellerAgent) {
        String buyerName = "buyer_for_" + sellerAgent.getLocalName() + "_" + System.currentTimeMillis();
        logger.info("CA: Creating {} to negotiate with {}", buyerName, sellerAgent.getLocalName());

        try {
            Object[] args = new Object[]{
                    sellerAgent,
                    getAID(),
            };

            AgentController buyerController = getContainerController().createNewAgent(
                    buyerName,
                    "mas.agents.BuyerAgent",
                    args
            );
            buyerController.start();
            logger.debug("CA: Buyer agent {} started successfully.", buyerName);
        } catch (StaleProxyException e) {
            logger.error("CA: Failed to create/start buyer agent " + buyerName, e);
        }
    }

    private class WaitForResults extends TickerBehaviour {
        public WaitForResults() {
            super(CoordinatorAgent.this, 1000);
        }

        protected void onTick() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchProtocol(PROTOCOL_REPORT_RESULT)
            );
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                finishedCounter++;
                try {
                    Object content = msg.getContentObject();
                    if (content instanceof NegotiationResult) {
                        NegotiationResult result = (NegotiationResult) content;
                        negotiationResults.add(result);
                        logger.info("CA: Result received from {} -> {}", msg.getSender().getLocalName(), result);
                    } else {
                        logger.info("CA: Notification received from {} -> {}", msg.getSender().getLocalName(), msg.getContent());
                    }
                } catch (UnreadableException e) {
                    logger.warn("CA: Received non-object notification from {}", msg.getSender().getLocalName());
                }
            }

            if (finishedCounter >= (sellerAgents == null ? 0 : sellerAgents.size())) {
                logger.info("--- CA: All negotiations concluded. Determining winners... ---");

                List<NegotiationResult> optimalSolution = wds.solveWDPWithBranchAndBound(negotiationResults, productDemand);

                logger.info("\n--- OPTIMAL SOLUTION FOUND ---");
                if (optimalSolution == null || optimalSolution.isEmpty()) {
                    logger.info("No combination of bids could satisfy the demand.");
                } else {
                    double totalUtility = 0;
                    for (NegotiationResult res : optimalSolution) {
                        logger.info("-> {}", res);
                        totalUtility += res.getUtility();
                    }
                    logger.info("Total Maximized Utility: {}", totalUtility);
                }

                negotiationResults.clear();
                finishedCounter = 0;

                // Opcional: encerrar agente ou reiniciar processo
                doDelete();
            }
        }
    }

    // --- Utilitários ---

    private void registerBundle(ProductBundle bundle) {
        bundleRegistry.put(bundle.getId(), bundle);
        logger.debug("CA: Bundle {} registered (registry size={}).", bundle.getId(), bundleRegistry.size());
        // Se houver um EvaluationService que precisa ser notificado, chame aqui (ex: evaluationService.updateBoundsForBundle(...));
    }

    private void parseAndSetDemand(String productList) {
        logger.info("CA: Parsing demand vector from '{}'", productList);
        String[] products = productList.split(",");
        productDemand = new int[4];
        for (String product : products) {
            switch (product.trim()) {
                case "P1":
                    productDemand[0]++;
                    break;
                case "P2":
                    productDemand[1]++;
                    break;
                case "P3":
                    productDemand[2]++;
                    break;
                case "P4":
                    productDemand[3]++;
                    break;
                default:
                    logger.warn("CA: Unknown product token '{}'", product);
            }
        }
        logger.info("CA: Parsed demand vector: P1={}, P2={}, P3={}, P4={}",
                productDemand[0], productDemand[1], productDemand[2], productDemand[3]);
    }
}

