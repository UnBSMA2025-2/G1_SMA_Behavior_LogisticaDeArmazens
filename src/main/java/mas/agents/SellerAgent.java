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
import mas.logic.EvaluationService.IssueParameters;
import mas.logic.EvaluationService.IssueType;
import mas.models.Bid;
import mas.models.NegotiationIssue;
import mas.models.ProductBundle;
import mas.models.Proposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representa um fornecedor (supplier) na negociação bilateral.
 * Este agente responde ao "Call for Proposal" do BuyerAgent e entra
 * na barganha de oferta alternada.
 * <p>
 * TODO (Simplificação de Arquitetura):
 * Assim como o BuyerAgent, esta implementação negocia apenas UM ÚNICO LANCE (Bid).
 * O artigo exige que o SA envie uma Proposta com MÚLTIPLOS LANCES,
 * um para cada pacote (bundle) que ele deseja ofertar.
 * A lógica deve ser refatorada para gerar e avaliar múltiplos lances "bid-by-bid".
 */
public class SellerAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(SellerAgent.class);

    private static final String STATE_WAIT_FOR_REQUEST = "WaitForRequest";
    private static final String STATE_SEND_INITIAL_PROPOSAL = "SendInitialProposal";
    private static final String STATE_WAIT_FOR_RESPONSE = "WaitForResponse";
    private static final String STATE_EVALUATE_COUNTER = "EvaluateCounterProposal";
    private static final String STATE_ACCEPT_COUNTER = "AcceptCounterOffer";
    private static final String STATE_MAKE_NEW_PROPOSAL = "MakeNewProposal";
    private static final String STATE_END_NEGOTIATION = "EndNegotiation";

    private AID buyerAgent;
    private ACLMessage receivedCounterMsg;
    private int currentRound = 0;
    private String negotiationId;
    private ACLMessage initialRequestMsg;
    
    private EvaluationService evalService;
    private ConcessionService concessionService;
    private Map<String, Double> sellerWeights;
    private Map<String, IssueParameters> sellerIssueParams;
    private double sellerAcceptanceThreshold;
    private double sellerRiskBeta;
    private double sellerGamma;
    private int maxRounds;
    private double discountRate;

    protected void setup() {
        logger.info("Seller Agent {} is ready.", getAID().getName());
        setupSellerPreferences();
        startNegotiationFSM();
    }

    /**
     * Cria e adiciona uma nova FSM para negociação.
     * Este método é chamado no setup e também após cada negociação terminar,
     * permitindo que o SellerAgent atenda múltiplas negociações.
     */
    private void startNegotiationFSM() {
        buyerAgent = null;
        receivedCounterMsg = null;
        currentRound = 0;
        negotiationId = null;
        initialRequestMsg = null;
        
        FSMBehaviour fsm = new FSMBehaviour(this) {
            @Override
            public int onEnd() {
                logger.info("{}: FSM finished.", myAgent.getLocalName());
                startNegotiationFSM();
                return super.onEnd();
            }
        };

        fsm.registerFirstState(new WaitForRequest(), STATE_WAIT_FOR_REQUEST);
        fsm.registerState(new SendInitialProposal(), STATE_SEND_INITIAL_PROPOSAL);
        fsm.registerState(new WaitForResponse(this), STATE_WAIT_FOR_RESPONSE);
        fsm.registerState(new EvaluateCounterProposal(), STATE_EVALUATE_COUNTER);
        fsm.registerState(new AcceptCounterOffer(), STATE_ACCEPT_COUNTER);
        fsm.registerState(new MakeNewProposal(), STATE_MAKE_NEW_PROPOSAL);
        fsm.registerLastState(new EndNegotiation(), STATE_END_NEGOTIATION);

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
    private void setupSellerPreferences() {
        ConfigLoader config = ConfigLoader.getInstance();
        this.evalService = new EvaluationService();
        this.concessionService = new ConcessionService();

        this.sellerAcceptanceThreshold = config.getDouble("seller.acceptanceThreshold");
        this.sellerRiskBeta = config.getDouble("seller.riskBeta");
        this.sellerGamma = config.getDouble("seller.gamma");
        this.maxRounds = config.getInt("negotiation.maxRounds");
        this.discountRate = config.getDouble("negotiation.discountRate");

        sellerWeights = new HashMap<>();
        sellerWeights.put("price", config.getDouble("seller.weights.price"));
        sellerWeights.put("quality", config.getDouble("seller.weights.quality"));
        sellerWeights.put("delivery", config.getDouble("seller.weights.delivery"));
        sellerWeights.put("service", config.getDouble("seller.weights.service"));


        sellerIssueParams = new HashMap<>();
        loadIssueParams(config, "price", IssueType.COST, "seller.params.");
        loadIssueParams(config, "delivery", IssueType.COST, "seller.params.");
        sellerIssueParams.put("quality", new IssueParameters(0, 1, IssueType.QUALITATIVE));
        sellerIssueParams.put("service", new IssueParameters(0, 1, IssueType.QUALITATIVE));

    }

    private void loadIssueParams(ConfigLoader config, String issueName, IssueType type, String prefix) {
        String key = prefix + issueName;
        String value = config.getString(key);
        if (value != null && !value.isEmpty()) {
            String[] parts = value.split(",");
            if (parts.length == 2) {
                try {
                    double min = Double.parseDouble(parts[0].trim());
                    double max = Double.parseDouble(parts[1].trim());
                    sellerIssueParams.put(issueName, new IssueParameters(min, max, type));
                } catch (NumberFormatException e) {
                    logger.error("{}: Error parsing seller params for {}: {}", getName(), issueName, value, e);
                }
            }
        } else {
            logger.warn("{}: Missing seller params for {} (key: {})", getName(), issueName, key);
        }
    }


    /**
     * Estado 1: Aguarda o "Call for Proposal" (CFP) do BuyerAgent.
     */
    private class WaitForRequest extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{}: Waiting for negotiation request...", myAgent.getLocalName());
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage msg = myAgent.blockingReceive(mt);
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
     * Na implementação atual (simplificada), envia UM ÚNICO lance,
     * com um pacote de produtos hard-coded baseado no nome do agente.
     */
    private class SendInitialProposal extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Sending initial proposal to {}", myAgent.getLocalName(), currentRound, buyerAgent != null ? buyerAgent.getLocalName() : "unknown");
            ConfigLoader config = ConfigLoader.getInstance();
            double initialPrice = config.getDouble("seller.initial.price");
            String initialQuality = config.getString("seller.initial.quality");
            double initialDelivery = config.getDouble("seller.initial.delivery");
            String initialService = config.getString("seller.initial.service");

            List<NegotiationIssue> issues = new ArrayList<>();
            issues.add(new NegotiationIssue("Price", initialPrice));
            issues.add(new NegotiationIssue("Quality", initialQuality));
            issues.add(new NegotiationIssue("Delivery", initialDelivery));
            issues.add(new NegotiationIssue("Service", initialService));
            ProductBundle pb;
            int[] quantities;
            String myName = myAgent.getLocalName();

            if (myName.equals("s1")) {
                pb = new ProductBundle(new int[]{1, 1, 0, 0}); // P1+P2
                quantities = new int[]{1000, 1000, 0, 0};
                logger.info("{}: Offering bundle P1+P2", myName);
            } else if (myName.equals("s2")) {
                pb = new ProductBundle(new int[]{0, 0, 1, 1}); // P3+P4
                quantities = new int[]{0, 0, 2000, 2000};
                logger.info("{}: Offering bundle P3+P4", myName);
            } else { // s3
                pb = new ProductBundle(new int[]{1, 0, 1, 0}); // P1+P3
                quantities = new int[]{1000, 0, 2000, 0};
                logger.info("{}: Offering bundle P1+P3", myName);
            }
            Bid initialBid = new Bid(pb, issues, quantities);

            Proposal proposal = new Proposal(List.of(initialBid));

            ACLMessage msg = new ACLMessage(ACLMessage.PROPOSE);
            msg.addReceiver(buyerAgent);
            msg.setConversationId(negotiationId);
            msg.setInReplyTo(initialRequestMsg.getReplyWith());
            msg.setReplyWith("prop-" + negotiationId + "-" + System.currentTimeMillis());
            try {
                msg.setContentObject(proposal);
                String readableContent = String.format("INITIAL PROPOSAL - Bundle: %s, Price: %.2f", 
                    initialBid.getProductBundle().getProducts(), 
                    initialPrice);
                msg.addUserDefinedParameter("readable-content", readableContent);
                myAgent.send(msg);
                logger.info("{}: Sent initial proposal -> Price: {}", myAgent.getLocalName(), initialPrice);
            } catch (IOException e) {
                logger.error("{}: Error sending initial proposal", myAgent.getLocalName(), e);
            }
        }
    }

    /**
     * Estado 3: Aguarda a resposta do Comprador (Aceitação ou Contraproposta).
     * Esta lógica está correta (ao contrário do BuyerAgent).
     */
    private class WaitForResponse extends Behaviour {
        private boolean responseReceived = false;
        private int nextTransition = 0; // 0=Timeout/Accept, 1=CounterProposal
        private long startTime;

        public WaitForResponse(Agent a) {
            super(a);
        }

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
                    logger.info("{} [R{}]: Buyer ACCEPTED my last offer!", myAgent.getLocalName(), currentRound);
                    nextTransition = 0; // Vai para EndNegotiation
                } else { // É PROPOSE (contraproposta)
                    logger.info("{} [R{}]: Received COUNTER-PROPOSAL from buyer.", myAgent.getLocalName(), currentRound);
                    receivedCounterMsg = msg;
                    nextTransition = 1; // Vai para EvaluateCounterProposal
                }
                responseReceived = true;
            } else {
                long elapsed = System.currentTimeMillis() - startTime;
                long timeoutMillis = 15000;
                if (elapsed > timeoutMillis) {
                    logger.info("{}: Timeout waiting for response from {}. Ending negotiation.", myAgent.getLocalName(), buyerAgent != null ? buyerAgent.getLocalName() : "unknown");
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
     * 
     * IMPROVED: Now processes ALL bids in the counter-proposal.
     */
    private class EvaluateCounterProposal extends OneShotBehaviour {
        private int transitionEvent = 2; // Default: falha/deadline
        private List<Bid> acceptedBids = new ArrayList<>();
        private List<Bid> rejectedBids = new ArrayList<>();

        @Override
        public void action() {
            currentRound++;
            logger.info("{} [R{}]: Evaluating counter-proposal from {}", myAgent.getLocalName(), currentRound, buyerAgent != null ? buyerAgent.getLocalName() : "unknown");

            if (currentRound > maxRounds) {
                logger.info("{}: Deadline reached ({}/{}). Ending negotiation.", myAgent.getLocalName(), currentRound, maxRounds);
                transitionEvent = 2;
                return;
            }

            try {
                Serializable content = receivedCounterMsg.getContentObject();
                if (content instanceof Proposal) {
                    Proposal p = (Proposal) content;
                    if (p.getBids() != null && !p.getBids().isEmpty()) {
                        for (Bid counterBid : p.getBids()) {
                            double utilityForSeller = evalService.calculateUtility("seller", counterBid, sellerWeights, sellerIssueParams, sellerRiskBeta);
                            logger.debug("{}: Bid {} counter utility = {} (Threshold = {})",
                                    myAgent.getLocalName(),
                                    counterBid.getProductBundle().getProducts(),
                                    String.format("%.4f", utilityForSeller),
                                    String.format("%.4f", sellerAcceptanceThreshold));

                            if (utilityForSeller >= sellerAcceptanceThreshold) {
                                logger.info("{}: Bid {} ACCEPTABLE (utility {} >= threshold {})",
                                    myAgent.getLocalName(),
                                    counterBid.getProductBundle().getProducts(),
                                    String.format("%.4f", utilityForSeller),
                                    String.format("%.4f", sellerAcceptanceThreshold));
                                acceptedBids.add(counterBid);
                            } else {
                                logger.debug("{}: Bid {} REJECTED (utility {} < threshold {})",
                                    myAgent.getLocalName(),
                                    counterBid.getProductBundle().getProducts(),
                                    String.format("%.4f", utilityForSeller),
                                    String.format("%.4f", sellerAcceptanceThreshold));
                                rejectedBids.add(counterBid);
                            }
                        }
                        if (!acceptedBids.isEmpty()) {
                            logger.info("{}: Accepting {} out of {} bids", 
                                myAgent.getLocalName(), acceptedBids.size(), p.getBids().size());
                            transitionEvent = 1; // Accept
                        } else {
                            logger.info("{}: All {} bids rejected. Will make new proposal for round {}", 
                                myAgent.getLocalName(), rejectedBids.size(), (currentRound + 1));
                            transitionEvent = 0; // Reject/Counter
                        }
                    } else {
                        transitionEvent = 2;
                    }
                } else {
                    transitionEvent = 2;
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
            try {
                Proposal receivedP = (Proposal) receivedCounterMsg.getContentObject();
                if (!receivedP.getBids().isEmpty()) {
                    Bid acceptedBid = receivedP.getBids().get(0);
                    Object priceValue = acceptedBid.getIssues().get(0).getValue();
                    String content = String.format("ACCEPTED - Bundle: %s, Final Price: %s", 
                        acceptedBid.getProductBundle().getProducts(), 
                        priceValue);
                    acceptMsg.setContent(content);
                } else {
                    acceptMsg.setContent("Accepted your counter-offer.");
                }
            } catch (Exception e) {
                acceptMsg.setContent("Accepted your counter-offer.");
            }
            
            myAgent.send(acceptMsg);
        }
    }

    /**
     * Estado 6: Gera e envia uma nova proposta (rejeitando a contraproposta).
     * 
     * IMPROVED: Now generates new proposals for ALL bids in the counter-proposal.
     */
    private class MakeNewProposal extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Generating new proposal...", myAgent.getLocalName(), currentRound);

            try {
                Proposal receivedP = (Proposal) receivedCounterMsg.getContentObject();
                List<Bid> newSellerBids = new ArrayList<>();
                for (Bid receivedB : receivedP.getBids()) {
                    Bid newSellerBid = concessionService.generateCounterBid(
                            receivedB,
                            currentRound,
                            maxRounds,
                            sellerGamma,
                            discountRate,
                            sellerIssueParams,
                            "seller"
                    );
                    newSellerBids.add(newSellerBid);
                    logger.debug("{}: Generated new proposal for bundle {} -> Price: {}", 
                        myAgent.getLocalName(),
                        newSellerBid.getProductBundle().getProducts(),
                        newSellerBid.getIssues().get(0).getValue());
                }

                Proposal newProposal = new Proposal(newSellerBids);
                ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
                proposeMsg.addReceiver(buyerAgent);
                proposeMsg.setConversationId(negotiationId);
                proposeMsg.setInReplyTo(receivedCounterMsg.getReplyWith());
                proposeMsg.setReplyWith("prop-" + negotiationId + "-" + System.currentTimeMillis());
                proposeMsg.setContentObject(newProposal);
                myAgent.send(proposeMsg);
                logger.info("{}: Sent new proposal (Round {}) with {} bids", 
                    myAgent.getLocalName(), currentRound, newSellerBids.size());

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
}