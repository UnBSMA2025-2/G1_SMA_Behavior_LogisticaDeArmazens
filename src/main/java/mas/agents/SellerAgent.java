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
 * SellerAgent atualizado para:
 * - enviar uma Proposal contendo MULTIPLOS Bids (simulando diferentes pacotes)
 * - avaliar contrapropostas bid-by-bid
 * - aceitar bids individuais e enviar ACCEPT_PROPOSAL contendo aceites (quando aplicável)
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

    // Keep track of last sent proposal bids (to match possible ACCEPTs)
    private List<Bid> lastSentProposalBids = new ArrayList<>();

    protected void setup() {
        logger.info("Seller Agent {} is ready.", getAID().getLocalName());
        setupSellerPreferences();

        FSMBehaviour fsm = new FSMBehaviour(this) {
            @Override
            public int onEnd() {
                logger.info("{}: FSM finished.", myAgent.getLocalName());
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
        fsm.registerTransition(STATE_WAIT_FOR_RESPONSE, STATE_EVALUATE_COUNTER, 1); // Counter received
        fsm.registerTransition(STATE_WAIT_FOR_RESPONSE, STATE_END_NEGOTIATION, 0);  // Accept or Timeout
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

    // --- FSM behaviors ---

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

    private class SendInitialProposal extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Sending initial proposal to {}", myAgent.getLocalName(), currentRound, buyerAgent != null ? buyerAgent.getLocalName() : "unknown");

            // Generate multiple bids (simulate ability to offer different bundles)
            List<Bid> allBids = new ArrayList<>();

            // Example: create 2-3 alternative bundles (adapt this to your domain)
            // Bundle A (2 items)
            ProductBundle bA = new ProductBundle.Builder()
                    .id("SB-" + getLocalName() + "-A")
                    .name(getLocalName() + "-bundle-A")
                    .addItem("SKU-P1", 100)
                    .addItem("SKU-P2", 100)
                    .synergyBounds(0.1, 0.6)
                    .issueWeight("price", 0.8)
                    .issueWeight("delivery", 0.2)
                    .build();
            int[] qA = new int[]{100, 100};
            List<NegotiationIssue> issuesA = new ArrayList<>();
            issuesA.add(new NegotiationIssue("Price", ConfigLoader.getInstance().getDouble("seller.initial.price")));
            issuesA.add(new NegotiationIssue("Quality", ConfigLoader.getInstance().getString("seller.initial.quality")));
            issuesA.add(new NegotiationIssue("Delivery", ConfigLoader.getInstance().getDouble("seller.initial.delivery")));
            issuesA.add(new NegotiationIssue("Service", ConfigLoader.getInstance().getString("seller.initial.service")));
            allBids.add(new Bid(bA, issuesA, qA));

            // Bundle B (2 items)
            ProductBundle bB = new ProductBundle.Builder()
                    .id("SB-" + getLocalName() + "-B")
                    .name(getLocalName() + "-bundle-B")
                    .addItem("SKU-P3", 200)
                    .addItem("SKU-P4", 200)
                    .synergyBounds(0.2, 0.7)
                    .issueWeight("price", 0.7)
                    .issueWeight("delivery", 0.3)
                    .build();
            int[] qB = new int[]{200, 200};
            List<NegotiationIssue> issuesB = new ArrayList<>();
            issuesB.add(new NegotiationIssue("Price", ConfigLoader.getInstance().getDouble("seller.initial.price")));
            issuesB.add(new NegotiationIssue("Quality", ConfigLoader.getInstance().getString("seller.initial.quality")));
            issuesB.add(new NegotiationIssue("Delivery", ConfigLoader.getInstance().getDouble("seller.initial.delivery")));
            issuesB.add(new NegotiationIssue("Service", ConfigLoader.getInstance().getString("seller.initial.service")));
            allBids.add(new Bid(bB, issuesB, qB));

            // Optionally a third bundle
            ProductBundle bC = new ProductBundle.Builder()
                    .id("SB-" + getLocalName() + "-C")
                    .name(getLocalName() + "-bundle-C")
                    .addItem("SKU-P1", 150)
                    .addItem("SKU-P3", 150)
                    .synergyBounds(0.05, 0.5)
                    .issueWeight("price", 0.6)
                    .issueWeight("delivery", 0.4)
                    .build();
            int[] qC = new int[]{150, 150};
            List<NegotiationIssue> issuesC = new ArrayList<>();
            issuesC.add(new NegotiationIssue("Price", ConfigLoader.getInstance().getDouble("seller.initial.price")));
            issuesC.add(new NegotiationIssue("Quality", ConfigLoader.getInstance().getString("seller.initial.quality")));
            issuesC.add(new NegotiationIssue("Delivery", ConfigLoader.getInstance().getDouble("seller.initial.delivery")));
            issuesC.add(new NegotiationIssue("Service", ConfigLoader.getInstance().getString("seller.initial.service")));
            allBids.add(new Bid(bC, issuesC, qC));

            Proposal proposal = new Proposal(allBids);
            lastSentProposalBids.clear();
            lastSentProposalBids.addAll(allBids);

            ACLMessage msg = new ACLMessage(ACLMessage.PROPOSE);
            msg.addReceiver(buyerAgent);
            msg.setConversationId(negotiationId);
            msg.setInReplyTo(initialRequestMsg.getReplyWith());
            msg.setReplyWith("prop-" + negotiationId + "-" + System.currentTimeMillis());
            try {
                msg.setContentObject(proposal);
                myAgent.send(msg);
                logger.info("{}: Sent initial proposal with {} bids.", myAgent.getLocalName(), allBids.size());
            } catch (IOException e) {
                logger.error("{}: Error sending initial proposal", myAgent.getLocalName(), e);
            }
        }
    }

    private class WaitForResponse extends Behaviour {
        private boolean responseReceived = false;
        private int nextTransition = 0;
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
                    logger.info("{}: Buyer accepted some of my offers!", myAgent.getLocalName());
                    nextTransition = 0; // go to end (we will react in AcceptCounterOffer)
                } else { // PROPOSE (counterproposal)
                    receivedCounterMsg = msg;
                    nextTransition = 1; // EvaluateCounterProposal
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
        public boolean done() { return responseReceived; }

        @Override
        public int onEnd() { return nextTransition; }
    }

    private class EvaluateCounterProposal extends OneShotBehaviour {
        private int transitionEvent = 2;

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
                    List<Bid> incoming = p.getBids();
                    if (incoming == null || incoming.isEmpty()) {
                        transitionEvent = 2;
                        return;
                    }

                    List<Bid> accepted = new ArrayList<>();
                    List<Bid> newProposals = new ArrayList<>();

                    // Evaluate each counter bid individually
                    for (Bid counterBid : incoming) {
                        double utilityForSeller = evalService.calculateUtility("seller", counterBid, sellerWeights, sellerIssueParams, sellerRiskBeta);
                        logger.info("{}: Counter bid {} utility (seller) = {} (threshold {})",
                                myAgent.getLocalName(),
                                counterBid.getBundleId(),
                                String.format("%.4f", utilityForSeller),
                                String.format("%.4f", sellerAcceptanceThreshold));

                        if (utilityForSeller >= sellerAcceptanceThreshold) {
                            accepted.add(counterBid);
                        } else {
                            // generate own concession (new seller bid) for this bundle
                            Bid newSellerBid = concessionService.generateCounterBid(
                                    counterBid,
                                    currentRound,
                                    maxRounds,
                                    sellerGamma,
                                    discountRate,
                                    sellerIssueParams,
                                    "seller"
                            );
                            newProposals.add(newSellerBid);
                        }
                    }

                    if (!accepted.isEmpty()) {
                        // If there are accepted bids, accept them (send ACCEPT_PROPOSAL containing accepted bids)
                        // We choose to accept and end negotiation (could be extended to accept some and continue for others)
                        ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                        acceptMsg.addReceiver(buyerAgent);
                        acceptMsg.setConversationId(negotiationId);
                        acceptMsg.setInReplyTo(receivedCounterMsg.getReplyWith());
                        try {
                            Proposal acceptProposal = new Proposal(accepted);
                            acceptMsg.setContentObject(acceptProposal);
                            myAgent.send(acceptMsg);
                            logger.info("{}: Sent ACCEPT_PROPOSAL with {} accepted bids.", myAgent.getLocalName(), accepted.size());
                        } catch (IOException e) {
                            logger.error("{}: Error sending ACCEPT_PROPOSAL", myAgent.getLocalName(), e);
                        }
                        transitionEvent = 1; // AcceptCounterOffer -> end
                    } else if (!newProposals.isEmpty()) {
                        // send new proposals (list) back to buyer
                        Proposal newProposalObj = new Proposal(newProposals);
                        ACLMessage prop = new ACLMessage(ACLMessage.PROPOSE);
                        prop.addReceiver(buyerAgent);
                        prop.setConversationId(negotiationId);
                        prop.setInReplyTo(receivedCounterMsg.getReplyWith());
                        prop.setReplyWith("prop-" + negotiationId + "-" + System.currentTimeMillis());
                        try {
                            prop.setContentObject(newProposalObj);
                            lastSentProposalBids.clear();
                            lastSentProposalBids.addAll(newProposals);
                            myAgent.send(prop);
                            logger.info("{}: Sent new proposal with {} bids.", myAgent.getLocalName(), newProposals.size());
                        } catch (IOException e) {
                            logger.error("{}: Error sending new proposal", myAgent.getLocalName(), e);
                        }
                        transitionEvent = 0; // MakeNewProposal -> continue
                    } else {
                        transitionEvent = 2; // nothing feasible
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
        public int onEnd() { return transitionEvent; }
    }

    private class AcceptCounterOffer extends OneShotBehaviour {
        @Override
        public void action() {
            // Called when buyer accepted some of our offers (or we timed out)
            logger.info("{}: AcceptCounterOffer reached - finalizing and ending.", myAgent.getLocalName());
            // Optionally, could notify Coordinator here with accepted bids information.
            // In current flow, Buyer informs Coordinator of the final accepted bid.
        }
    }

    private class MakeNewProposal extends OneShotBehaviour {
        @Override
        public void action() {
            // This state is covered by EvaluateCounterProposal which already sends new proposals.
            logger.info("{}: MakeNewProposal (no-op - proposals sent in EvaluateCounterProposal).", myAgent.getLocalName());
        }
    }

    private static class EndNegotiation extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{}: Negotiation process finished.", myAgent.getLocalName());
        }
    }
}

