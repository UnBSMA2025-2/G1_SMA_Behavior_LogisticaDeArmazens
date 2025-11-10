package mas.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import mas.logic.ConcessionService;
import mas.logic.ConfigLoader;
import mas.logic.EvaluationService;
import mas.logic.EvaluationService.IssueType;
import mas.logic.EvaluationService.IssueParameters;
import mas.models.Bid;
import mas.models.NegotiationIssue;
import mas.models.ProductBundle;
import mas.models.Proposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representa um fornecedor (supplier) na negociação bilateral.
 *
 * VERSÃO REFATORADA:
 * - Propõe múltiplos lances (para diferentes pacotes) na proposta inicial.
 * - Avalia contrapropostas "bid-by-bid".
 * - Carrega seus próprios parâmetros de sinergia (baseados no 'agentName'
 * ex: 'seller.s1.riskBeta') do config.properties.
 */
public class SellerAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(SellerAgent.class);

    // Nomes dos estados
    private static final String STATE_WAIT_FOR_REQUEST = "WaitForRequest";
    private static final String STATE_SEND_INITIAL_PROPOSAL = "SendInitialProposal";
    private static final String STATE_WAIT_FOR_RESPONSE = "WaitForResponse";
    private static final String STATE_EVALUATE_COUNTER = "EvaluateCounterProposal";
    private static final String STATE_ACCEPT_COUNTER = "AcceptCounterOffer";
    private static final String STATE_MAKE_NEW_PROPOSAL = "MakeNewProposal";
    private static final String STATE_END_NEGOTIATION = "EndNegotiation";

    // Variáveis de estado
    private AID buyerAgent;
    private ACLMessage receivedCounterMsg;
    private int currentRound = 0;
    private String negotiationId;
    private ACLMessage initialRequestMsg;
    
    // Serviços e Configurações
    private EvaluationService evalService;
    private ConcessionService concessionService;
    private Map<String, Double> sellerWeights;
    private double sellerAcceptanceThreshold;
    private double sellerRiskBeta;
    private double sellerGamma;
    private int maxRounds;
    private double discountRate;
    // O 'sellerIssueParams' local foi removido

    protected void setup() {
        logger.info("Seller Agent {} is ready.", getAID().getName());
        setupSellerPreferences();

        FSMBehaviour fsm = new FSMBehaviour(this) {
            @Override
            public int onEnd() {
                logger.info("{}: FSM finished.", myAgent.getLocalName());
                return super.onEnd();
            }
        };

        // REGISTRA ESTADOS
        fsm.registerFirstState(new WaitForRequest(), STATE_WAIT_FOR_REQUEST);
        fsm.registerState(new SendInitialProposal(), STATE_SEND_INITIAL_PROPOSAL);
        fsm.registerState(new WaitForResponse(this), STATE_WAIT_FOR_RESPONSE);
        fsm.registerState(new EvaluateCounterProposal(), STATE_EVALUATE_COUNTER);
        fsm.registerState(new AcceptCounterOffer(), STATE_ACCEPT_COUNTER);
        fsm.registerState(new MakeNewProposal(), STATE_MAKE_NEW_PROPOSAL);
        fsm.registerLastState(new EndNegotiation(), STATE_END_NEGOTIATION);

        // REGISTRA TRANSIÇÕES
        fsm.registerDefaultTransition(STATE_WAIT_FOR_REQUEST, STATE_SEND_INITIAL_PROPOSAL);
        fsm.registerDefaultTransition(STATE_SEND_INITIAL_PROPOSAL, STATE_WAIT_FOR_RESPONSE);
        fsm.registerTransition(STATE_WAIT_FOR_RESPONSE, STATE_EVALUATE_COUNTER, 1); // Counter recebido
        fsm.registerTransition(STATE_WAIT_FOR_RESPONSE, STATE_END_NEGOTIATION, 0);  // Aceitação recebida ou Timeout
        fsm.registerTransition(STATE_EVALUATE_COUNTER, STATE_ACCEPT_COUNTER, 1);
        fsm.registerTransition(STATE_EVALUATE_COUNTER, STATE_MAKE_NEW_PROPOSAL, 0);
        fsm.registerTransition(STATE_EVALUATE_COUNTER, STATE_END_NEGOTIATION, 2);
        fsm.registerDefaultTransition(STATE_ACCEPT_COUNTER, STATE_END_NEGOTIATION);
        fsm.registerDefaultTransition(STATE_MAKE_NEW_PROPOSAL, STATE_WAIT_FOR_RESPONSE);

        addBehaviour(fsm);
    }

    /**
     * Carrega as preferências (pesos, beta, gamma) deste agente específico
     * do config.properties, usando seu 'localName' (ex: s1, s2).
     */
    private void setupSellerPreferences() {
        ConfigLoader config = ConfigLoader.getInstance();
        String myName = getLocalName();

        this.evalService = new EvaluationService();
        this.concessionService = new ConcessionService();
        this.maxRounds = config.getInt("negotiation.maxRounds");
        this.discountRate = config.getDouble("negotiation.discountRate");

        // Pega valores específicos ou cai para o padrão
        this.sellerAcceptanceThreshold = getDoubleConfig(config, myName, "acceptanceThreshold", "seller.acceptanceThreshold");
        this.sellerRiskBeta = getDoubleConfig(config, myName, "riskBeta", "seller.riskBeta");
        this.sellerGamma = getDoubleConfig(config, myName, "gamma", "seller.gamma");

        sellerWeights = new HashMap<>();
        sellerWeights.put("price", getDoubleConfig(config, myName, "weights.price", "seller.weights.price"));
        sellerWeights.put("quality", getDoubleConfig(config, myName, "weights.quality", "seller.weights.quality"));
        sellerWeights.put("delivery", getDoubleConfig(config, myName, "weights.delivery", "seller.weights.delivery"));
        sellerWeights.put("service", getDoubleConfig(config, myName, "weights.service", "seller.weights.service"));
        
        logger.info("{}: Preferências carregadas. Beta={}, Gamma={}", myName, this.sellerRiskBeta, this.sellerGamma);
    }

    /**
     * Helper para carregar config com fallback para um valor padrão.
     */
    private double getDoubleConfig(ConfigLoader config, String agentName, String suffix, String defaultKey) {
        try {
            // Tenta pegar o específico (ex: seller.s1.riskBeta)
            return config.getDouble("seller." + agentName + "." + suffix);
        } catch (Exception e) {
            try {
                // Falha? Tenta pegar o padrão (ex: seller.riskBeta)
                return config.getDouble(defaultKey);
            } catch (Exception e2) {
                // Falha total? Retorna um valor de emergência
                logger.warn("{}: Falha ao carregar config '{}' e default '{}'. Usando 0.0.", getLocalName(), "seller." + agentName + "." + suffix, defaultKey);
                return 0.0;
            }
        }
    }


    // --- Comportamentos da FSM ---

    /**
     * Estado 1: Aguarda o "Call for Proposal" (CFP) do BuyerAgent.
     */
    private class WaitForRequest extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{}: Waiting for negotiation request...", myAgent.getLocalName());
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage msg = myAgent.blockingReceive(mt); // Bloqueia até o BA chamar
            if (msg != null) {
                initialRequestMsg = msg;
                buyerAgent = msg.getSender();
                negotiationId = msg.getConversationId();
                currentRound = 1;
                logger.info("{} [R{}]: Received request from {}", myAgent.getLocalName(), currentRound, buyerAgent.getLocalName());
            } else {
                logger.error("{}: Failed to receive initial request. Terminating.", myAgent.getLocalName());
                myAgent.doDelete();
            }
        }

        @Override
        public int onEnd() {
            return (buyerAgent != null ? 0 : 1);
        }
    }

    /**
     * Estado 2: Envia a Proposta inicial.
     * LÓGICA REFATORADA: Envia múltiplos lances com base nos pacotes
     * que este agente (s1, s2, etc.) pode fornecer.
     */
    private class SendInitialProposal extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Sending initial proposal to {}", myAgent.getLocalName(), currentRound, buyerAgent.getLocalName());

            List<Bid> initialBids = new ArrayList<>();
            ConfigLoader config = ConfigLoader.getInstance();
            String myName = myAgent.getLocalName();

            // Simula os pacotes que este vendedor pode fornecer (Tabela 4)
            if (myName.equals("s1")) {
                initialBids.add(createInitialBid(config, myName, "1000", new int[]{1000, 0, 0, 0}));
                initialBids.add(createInitialBid(config, myName, "0100", new int[]{0, 1000, 0, 0}));
                initialBids.add(createInitialBid(config, myName, "1100", new int[]{1000, 1000, 0, 0}));
            } else if (myName.equals("s2")) {
                initialBids.add(createInitialBid(config, myName, "1000", new int[]{1000, 0, 0, 0}));
                initialBids.add(createInitialBid(config, myName, "0010", new int[]{0, 0, 2000, 0}));
                initialBids.add(createInitialBid(config, myName, "1010", new int[]{1000, 0, 2000, 0}));
            } else if (myName.equals("s3")) {
                initialBids.add(createInitialBid(config, myName, "1000", new int[]{1000, 0, 0, 0}));
                initialBids.add(createInitialBid(config, myName, "0001", new int[]{0, 0, 0, 2000}));
                initialBids.add(createInitialBid(config, myName, "1001", new int[]{1000, 0, 0, 2000}));
            }
            // Outros vendedores (s4-s8) podem ser adicionados com a mesma lógica

            Proposal proposal = new Proposal(initialBids);

            ACLMessage msg = new ACLMessage(ACLMessage.PROPOSE);
            msg.addReceiver(buyerAgent);
            msg.setConversationId(negotiationId);
            msg.setInReplyTo(initialRequestMsg.getReplyWith());
            msg.setReplyWith("prop-" + negotiationId + "-" + System.currentTimeMillis());
            try {
                msg.setContentObject(proposal);
                myAgent.send(msg);
                logger.info("{}: Sent initial proposal with {} bids.", myAgent.getLocalName(), initialBids.size());
            } catch (IOException e) {
                logger.error("{}: Error sending initial proposal", myAgent.getLocalName(), e);
            }
        }
    }

    /**
     * Helper para criar um lance inicial usando os valores MÁXIMOS (pior para o comprador)
     * dos parâmetros de sinergia do vendedor.
     */
    private Bid createInitialBid(ConfigLoader config, String agentName, String bundleId, int[] quantities) {
        ProductBundle pb = new ProductBundle(Arrays.stream(bundleId.split("")).mapToInt(Integer::parseInt).toArray());

        // Usa o nome do agente para buscar os parâmetros corretos
        IssueParameters priceParams = config.getSynergyParams("seller", agentName, bundleId, "price", IssueType.COST);
        IssueParameters deliveryParams = config.getSynergyParams("seller", agentName, bundleId, "delivery", IssueType.COST);

        // O vendedor começa oferecendo seu MAX (preço mais alto, entrega mais longa)
        // (Baseado na Tabela 7, os vendedores começam com seus piores valores)
        double initialPrice = (priceParams != null) ? priceParams.getMax() : 0.0;
        double initialDelivery = (deliveryParams != null) ? deliveryParams.getMax() : 0.0;

        List<NegotiationIssue> issues = new ArrayList<>();
        issues.add(new NegotiationIssue("Price", initialPrice));
        issues.add(new NegotiationIssue("Quality", "very poor")); // O "melhor" do Vendedor (Tabela 5)
        issues.add(new NegotiationIssue("Delivery", initialDelivery));
        issues.add(new NegotiationIssue("Service", "very poor")); // O "melhor" do Vendedor

        return new Bid(pb, issues, quantities);
    }

    /**
     * Estado 3: Aguarda a resposta do Comprador (Aceitação ou Contraproposta).
     */
    private class WaitForResponse extends Behaviour {
        private boolean responseReceived = false;
        private int nextTransition = 0; // 0=Timeout/Accept, 1=CounterProposal
        private long startTime;

        public WaitForResponse(Agent a) { super(a); }

        @Override
        public void onStart() {
            responseReceived = false;
            startTime = System.currentTimeMillis();
        }

        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(buyerAgent),
                    MessageTemplate.and(
                            MessageTemplate.or(
                                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE)
                            ),
                            MessageTemplate.MatchConversationId(negotiationId)
                    )
            );
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    logger.info("{}: Buyer accepted my last offer!", myAgent.getLocalName());
                    nextTransition = 0; // Vai para EndNegotiation
                } else { // É PROPOSE (contraproposta)
                    receivedCounterMsg = msg;
                    nextTransition = 1; // Vai para EvaluateCounterProposal
                }
                responseReceived = true;
            } else {
                long elapsed = System.currentTimeMillis() - startTime;
                long timeoutMillis = 15000;
                if (elapsed > timeoutMillis) {
                    logger.info("{}: Timeout waiting for response from {}. Ending negotiation.", myAgent.getLocalName(), buyerAgent.getLocalName());
                    nextTransition = 0;
                    responseReceived = true;
                } else {
                    block(500);
                }
            }
        }

        @Override
        public boolean done() {
            return responseReceived;
        }

        @Override
        public int onEnd() {
            return nextTransition;
        }
    }

    /**
     * Estado 4: Avalia a contraproposta recebida do Comprador.
     * LÓGICA REFATORADA: Avalia "bid-by-bid".
     */
    private class EvaluateCounterProposal extends OneShotBehaviour {
        private int transitionEvent = 2; // Default: falha/deadline

        @Override
        public void action() {
            currentRound++;
            logger.info("{} [R{}]: Evaluating counter-proposal from {}", myAgent.getLocalName(), currentRound, buyerAgent.getLocalName());

            if (currentRound > maxRounds) {
                logger.info("{}: Deadline reached ({}/{}). Ending negotiation.", myAgent.getLocalName(), currentRound, maxRounds);
                transitionEvent = 2;
                return;
            }

            try {
                Serializable content = receivedCounterMsg.getContentObject();
                 if (!(content instanceof Proposal)) { /* ... (erro) ... */ transitionEvent = 2; return; }
                
                Proposal p = (Proposal) content;
                if (p.getBids() == null || p.getBids().isEmpty()) { /* ... (erro) ... */ transitionEvent = 2; return; }

                // LÓGICA MULTI-LANCE: "all-or-nothing-counter"
                boolean allBidsAcceptable = true;

                for (Bid counterBid : p.getBids()) {
                    // Avalia da perspectiva do VENDEDOR
                    double utilityForSeller = evalService.calculateUtility(
                            "seller", myAgent.getLocalName(), counterBid, sellerWeights, sellerRiskBeta);

                    logger.info("{}: Evaluating Bid {}: Utility ({}). Threshold ({}).",
                            myAgent.getLocalName(), getBundleId(counterBid.getProductBundle()),
                            String.format("%.4f", utilityForSeller), sellerAcceptanceThreshold);

                    // Eq. 7 (Lado do Vendedor)
                    if (utilityForSeller >= sellerAcceptanceThreshold) {
                        // Este lance é bom
                    } else {
                        logger.info("{}: Bid {} NOT acceptable (Utility {} < Threshold {}). Will counter.",
                                myAgent.getLocalName(), getBundleId(counterBid.getProductBundle()),
                                utilityForSeller, sellerAcceptanceThreshold);
                        allBidsAcceptable = false;
                        break;
                    }
                }

                if (allBidsAcceptable) {
                    logger.info("{}: Counter-offer is acceptable. Accepting.", myAgent.getLocalName());
                    transitionEvent = 1; // Aceitar
                } else {
                    logger.info("{}: Counter-offer not acceptable. Will make new proposal for round {}", myAgent.getLocalName(), (currentRound + 1));
                    transitionEvent = 0; // Rejeitar (fazer contraproposta)
                }
            } catch (UnreadableException e) {
                logger.error("{}: Failed to read counter-proposal content.", myAgent.getLocalName(), e);
                transitionEvent = 2;
            }
        }

        @Override
        public int onEnd() {
            return transitionEvent;
        }
    }

    /**
     * Estado 5: Aceita a contraproposta do Comprador.
     */
    private class AcceptCounterOffer extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{}: Sending acceptance for buyer's counter-offer.", myAgent.getLocalName());
            ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            acceptMsg.addReceiver(buyerAgent);
            acceptMsg.setConversationId(negotiationId);
            acceptMsg.setInReplyTo(receivedCounterMsg.getReplyWith());
            acceptMsg.setContent("Accepted your counter-offer.");
            myAgent.send(acceptMsg);
        }
    }

    /**
     * Estado 6: Gera e envia uma nova proposta (rejeitando a contraproposta).
     * LÓGICA REFATORADA: Gera uma contraproposta multi-lance.
     */
    private class MakeNewProposal extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Generating new proposal...", myAgent.getLocalName(), currentRound);

            try {
                Proposal receivedP = (Proposal) receivedCounterMsg.getContentObject();
                
                // LÓGICA MULTI-LANCE: Gera uma contra-oferta para cada lance
                List<Bid> counterBids = new ArrayList<>();

                for (Bid referenceBid : receivedP.getBids()) {
                    Bid newSellerBid = concessionService.generateCounterBid(
                            referenceBid,
                            currentRound,
                            maxRounds,
                            sellerGamma,
                            discountRate,
                            "seller",
                            myAgent.getLocalName() // Passa o nome do agente
                    );
                    counterBids.add(newSellerBid);
                }

                Proposal newProposal = new Proposal(counterBids);
                ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
                proposeMsg.addReceiver(buyerAgent);
                proposeMsg.setConversationId(negotiationId);
                proposeMsg.setInReplyTo(receivedCounterMsg.getReplyWith());
                proposeMsg.setReplyWith("prop-" + negotiationId + "-" + System.currentTimeMillis());
                proposeMsg.setContentObject(newProposal);
                myAgent.send(proposeMsg);
                logger.info("{}: Sent new proposal (Round {}) with {} bids.",
                        myAgent.getLocalName(), currentRound, counterBids.size());

            } catch (UnreadableException | IOException e) {
                logger.error("{}: Error creating/sending new proposal", myAgent.getLocalName(), e);
            }
        }
    }

    /**
     * Estado Final (FSM): Ações de finalização da negociação.
     */
    private static class EndNegotiation extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{}: Negotiation process finished.", myAgent.getLocalName());
        }
    }
    
    /**
     * Método auxiliar para converter ProductBundle em um ID de string.
     */
    private String getBundleId(ProductBundle pb) {
        if (pb == null || pb.getProducts() == null) return "default";
        StringBuilder sb = new StringBuilder();
        for (int p : pb.getProducts()) {
            sb.append(p);
        }
        return sb.toString();
    }
}