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
import mas.models.NegotiationResult;
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
 * BuyerAgent atualizado para:
 * - aceitar/avaliar múltiplos bids por Proposal (bid-by-bid)
 * - gerar contrapropostas para cada bid não-aceito
 * - aceitar o melhor bid quando aplicável e informar o Coordinator ao final
 */
public class BuyerAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(BuyerAgent.class);

    private static final String PROTOCOL_REPORT_RESULT = "report-negotiation-result";
    private static final String STATE_SEND_REQUEST = "SendRequest";
    private static final String STATE_WAIT_FOR_PROPOSAL = "WaitForProposal";
    private static final String STATE_EVALUATE_PROPOSAL = "EvaluateProposal";
    private static final String STATE_ACCEPT_OFFER = "AcceptOffer";
    private static final String STATE_MAKE_COUNTER_OFFER = "MakeCounterOffer";
    private static final String STATE_END_NEGOTIATION = "EndNegotiation";

    private AID sellerAgent;
    private AID coordinatorAgent;
    private ACLMessage receivedProposalMsg;
    private Bid finalAcceptedBid = null;
    private double finalUtility = 0.0;
    private int currentRound = 0;
    private String negotiationId;
    private String lastMessageReplyWith;

    private EvaluationService evalService;
    private ConcessionService concessionService;
    private Map<String, Double> weights;
    private Map<String, IssueParameters> issueParams;
    private double acceptanceThreshold;
    private double buyerRiskBeta;
    private double buyerGamma;
    private int maxRounds;
    private double discountRate;

    // NEW: store counter bids generated in EvaluateProposal to be sent in MakeCounterOffer
    private List<Bid> pendingCounterBids = new ArrayList<>();
    // NEW: store last sent counter bids so we can handle ACCEPT_PROPOSAL when seller accepts them
    private List<Bid> lastSentCounterBids = new ArrayList<>();

    protected void setup() {
        logger.info("Buyer Agent {} is ready.", getAID().getName());

        Object[] args = getArguments();
        if (args != null && args.length > 1) {
            sellerAgent = (AID) args[0];
            coordinatorAgent = (AID) args[1];
            logger.info("{}: Assigned seller is {}", getName(), sellerAgent.getName());
        } else {
            logger.error("{}: Missing arguments (sellerAID, coordinatorAID). Terminating.", getName());
            doDelete();
            return;
        }

        setupBuyerPreferences();

        FSMBehaviour fsm = new FSMBehaviour(this) {
            @Override
            public int onEnd() {
                logger.info("{}: FSM finished negotiation with {}.", myAgent.getLocalName(), sellerAgent.getLocalName());
                myAgent.addBehaviour(new InformCoordinatorDone());
                return super.onEnd();
            }
        };

        fsm.registerFirstState(new SendRequest(), STATE_SEND_REQUEST);
        fsm.registerState(new WaitForProposal(this), STATE_WAIT_FOR_PROPOSAL);
        fsm.registerState(new EvaluateProposal(), STATE_EVALUATE_PROPOSAL);
        fsm.registerState(new AcceptOffer(), STATE_ACCEPT_OFFER);
        fsm.registerState(new MakeCounterOffer(), STATE_MAKE_COUNTER_OFFER);
        fsm.registerLastState(new EndNegotiation(), STATE_END_NEGOTIATION);

        fsm.registerDefaultTransition(STATE_SEND_REQUEST, STATE_WAIT_FOR_PROPOSAL);
        fsm.registerTransition(STATE_WAIT_FOR_PROPOSAL, STATE_EVALUATE_PROPOSAL, 1); // Propose
        fsm.registerTransition(STATE_WAIT_FOR_PROPOSAL, STATE_END_NEGOTIATION, 0);  // Timeout
        fsm.registerTransition(STATE_WAIT_FOR_PROPOSAL, STATE_END_NEGOTIATION, 2);  // Accept from seller (accepted our last counter)
        fsm.registerTransition(STATE_EVALUATE_PROPOSAL, STATE_ACCEPT_OFFER, 1);      // accepted (pick best)
        fsm.registerTransition(STATE_EVALUATE_PROPOSAL, STATE_MAKE_COUNTER_OFFER, 0);// send counters
        fsm.registerTransition(STATE_EVALUATE_PROPOSAL, STATE_END_NEGOTIATION, 2);   // fail/deadline
        fsm.registerDefaultTransition(STATE_ACCEPT_OFFER, STATE_END_NEGOTIATION);
        fsm.registerDefaultTransition(STATE_MAKE_COUNTER_OFFER, STATE_WAIT_FOR_PROPOSAL);

        addBehaviour(fsm);
    }

    private void setupBuyerPreferences() {
        ConfigLoader config = ConfigLoader.getInstance();
        this.evalService = new EvaluationService();
        this.concessionService = new ConcessionService();

        this.acceptanceThreshold = config.getDouble("buyer.acceptanceThreshold");
        this.buyerRiskBeta = config.getDouble("buyer.riskBeta");
        this.buyerGamma = config.getDouble("buyer.gamma");
        this.maxRounds = config.getInt("negotiation.maxRounds");
        this.discountRate = config.getDouble("negotiation.discountRate");

        weights = new HashMap<>();
        weights.put("price", config.getDouble("weights.price"));
        weights.put("quality", config.getDouble("weights.quality"));
        weights.put("delivery", config.getDouble("weights.delivery"));
        weights.put("service", config.getDouble("weights.service"));

        issueParams = new HashMap<>();
        loadIssueParams(config, "price", IssueType.COST);
        loadIssueParams(config, "delivery", IssueType.COST);
        issueParams.put("quality", new IssueParameters(0, 1, IssueType.QUALITATIVE));
        issueParams.put("service", new IssueParameters(0, 1, IssueType.QUALITATIVE));
    }

    private void loadIssueParams(ConfigLoader config, String issueName, IssueType type) {
        String key = "params." + issueName;
        String value = config.getString(key);
        if (value != null && !value.isEmpty()) {
            String[] parts = value.split(",");
            if (parts.length == 2) {
                try {
                    double min = Double.parseDouble(parts[0].trim());
                    double max = Double.parseDouble(parts[1].trim());
                    issueParams.put(issueName, new IssueParameters(min, max, type));
                } catch (NumberFormatException e) {
                    logger.error("{}: Error parsing params for {}: {}", getName(), issueName, value, e);
                }
            }
        }
    }

    // --- FSM behaviors ---

    private class SendRequest extends OneShotBehaviour {
        @Override
        public void action() {
            currentRound = 1;
            negotiationId = "neg-" + sellerAgent.getLocalName() + "-" + System.currentTimeMillis();
            logger.info("{} [R{}]: Sending call for proposal to {}", myAgent.getLocalName(), currentRound, sellerAgent.getLocalName());
            ACLMessage cfp = new ACLMessage(ACLMessage.REQUEST);
            cfp.addReceiver(sellerAgent);
            cfp.setContent("send-proposal");
            cfp.setConversationId(negotiationId);
            lastMessageReplyWith = "req-" + negotiationId + "-" + System.currentTimeMillis();
            cfp.setReplyWith(lastMessageReplyWith);
            myAgent.send(cfp);
        }
    }

    private class WaitForProposal extends Behaviour {
        private boolean responseReceived = false;
        private long startTime;
        private long timeoutMillis = 15000;
        private int exitValue = 0;

        public WaitForProposal(Agent a) { super(a); }

        @Override
        public void onStart() {
            responseReceived = false;
            startTime = System.currentTimeMillis();
            exitValue = 0;
        }

        @Override
        public void action() {
            MessageTemplate idTemplate = MessageTemplate.and(
                    MessageTemplate.MatchConversationId(negotiationId),
                    MessageTemplate.MatchInReplyTo(lastMessageReplyWith)
            );

            MessageTemplate performativeTemplate = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)
            );

            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(sellerAgent),
                    MessageTemplate.and(performativeTemplate, idTemplate)
            );

            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                responseReceived = true;
                receivedProposalMsg = msg;

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    exitValue = 1;
                } else { // ACCEPT_PROPOSAL
                    logger.info("{}: Seller ACCEPTED our last counter-offer.", myAgent.getLocalName());
                    // Attempt to read accepted content (if provided)
                    try {
                        Object content = msg.getContentObject();
                        if (content instanceof Proposal) {
                            Proposal acceptedProposal = (Proposal) content;
                            // choose best bid among lastSentCounterBids or acceptedProposal
                            finalAcceptedBid = pickBestBidFromList(acceptedProposal.getBids());
                            if (finalAcceptedBid != null) {
                                finalUtility = evalService.calculateUtility("buyer", finalAcceptedBid, weights, issueParams, buyerRiskBeta);
                                logger.info("{}: Seller accepted bid -> {}", myAgent.getLocalName(), finalAcceptedBid);
                            }
                        } else {
                            // fallback: if lastSentCounterBids not empty, pick best from them
                            finalAcceptedBid = pickBestBidFromList(lastSentCounterBids);
                            if (finalAcceptedBid != null) {
                                finalUtility = evalService.calculateUtility("buyer", finalAcceptedBid, weights, issueParams, buyerRiskBeta);
                                logger.info("{}: Seller accepted last sent counter bid -> {}", myAgent.getLocalName(), finalAcceptedBid);
                            }
                        }
                    } catch (UnreadableException e) {
                        logger.warn("{}: ACCEPT_PROPOSAL received but unreadable content.", myAgent.getLocalName());
                        finalAcceptedBid = pickBestBidFromList(lastSentCounterBids);
                        if (finalAcceptedBid != null) {
                            finalUtility = evalService.calculateUtility("buyer", finalAcceptedBid, weights, issueParams, buyerRiskBeta);
                        }
                    }

                    exitValue = 2;
                }
            } else {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > timeoutMillis) {
                    logger.warn("{}: Timeout waiting for proposal from {}. Ending negotiation.", myAgent.getLocalName(), sellerAgent.getLocalName());
                    responseReceived = true;
                    exitValue = 0;
                } else {
                    block(500);
                }
            }
        }

        @Override
        public boolean done() { return responseReceived; }

        @Override
        public int onEnd() { return exitValue; }
    }

    private class EvaluateProposal extends OneShotBehaviour {
        private int transitionEvent = 2; // default: fail/deadline

        @Override
        public void action() {
            currentRound++;
            logger.info("{} [R{}]: Evaluating proposal from {}", myAgent.getLocalName(), currentRound, sellerAgent.getLocalName());

            if (currentRound > maxRounds) {
                logger.warn("{}: Deadline reached ({}/{}) . Ending negotiation.", myAgent.getLocalName(), currentRound, maxRounds);
                transitionEvent = 2;
                return;
            }

            pendingCounterBids.clear();

            try {
                Serializable content = receivedProposalMsg.getContentObject();
                if (content instanceof Proposal) {
                    Proposal p = (Proposal) content;
                    List<Bid> incoming = p.getBids();
                    if (incoming == null || incoming.isEmpty()) {
                        logger.warn("{}: Received empty proposal.", myAgent.getLocalName());
                        transitionEvent = 2;
                        return;
                    }

                    List<Bid> accepted = new ArrayList<>();
                    List<Bid> counters = new ArrayList<>();

                    // Evaluate each incoming bid individually (bid-by-bid)
                    for (Bid receivedBid : incoming) {
                        // TODO: use bundle-specific issueParams if available (sinergia)
                        double utility = evalService.calculateUtility("buyer", receivedBid, weights, issueParams, buyerRiskBeta);

                        // Hypothetical counter for next round (per bid)
                        Bid hypotheticalCounter = concessionService.generateCounterBid(receivedBid, currentRound + 1, maxRounds, buyerGamma, discountRate, issueParams, "buyer");
                        double nextCounterUtility = evalService.calculateUtility("buyer", hypotheticalCounter, weights, issueParams, buyerRiskBeta);

                        logger.info("{}: Bid {} utility = {} (threshold {})", myAgent.getLocalName(), receivedBid.getBundleId(), String.format("%.4f", utility), String.format("%.4f", acceptanceThreshold));

                        if (utility >= acceptanceThreshold && utility >= nextCounterUtility) {
                            accepted.add(receivedBid);
                        } else {
                            counters.add(hypotheticalCounter);
                        }
                    }

                    // Decide transition: if at least one accepted and no counters -> accept; else propose counters
                    if (!accepted.isEmpty() && counters.isEmpty()) {
                        // choose best accepted (highest utility)
                        finalAcceptedBid = pickBestBidFromList(accepted);
                        finalUtility = evalService.calculateUtility("buyer", finalAcceptedBid, weights, issueParams, buyerRiskBeta);
                        transitionEvent = 1; // AcceptOffer
                        logger.info("{}: Will accept bid {} (best among accepted)", myAgent.getLocalName(), finalAcceptedBid.getBundleId());
                    } else if (!counters.isEmpty()) {
                        pendingCounterBids.addAll(counters);
                        transitionEvent = 0; // MakeCounterOffer
                        logger.info("{}: Will send {} counter bids.", myAgent.getLocalName(), pendingCounterBids.size());
                    } else {
                        // no accepted and no counters (edge) -> end
                        transitionEvent = 2;
                    }

                } else {
                    logger.error("{}: Received unexpected content type: {}", myAgent.getLocalName(), (content == null ? "null" : content.getClass().getName()));
                    transitionEvent = 2;
                }
            } catch (UnreadableException e) {
                logger.error("{}: Failed to read proposal content.", myAgent.getLocalName(), e);
                transitionEvent = 2;
            }
        }

        @Override
        public int onEnd() {
            return transitionEvent;
        }
    }

    private class MakeCounterOffer extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Generating counter-offers (count={})", myAgent.getLocalName(), currentRound, pendingCounterBids.size());
            if (pendingCounterBids.isEmpty()) {
                logger.warn("{}: No pending counters to send.", myAgent.getLocalName());
                return;
            }
            Proposal counterProposal = new Proposal(pendingCounterBids);
            lastSentCounterBids.clear();
            lastSentCounterBids.addAll(pendingCounterBids);

            ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
            proposeMsg.addReceiver(sellerAgent);
            proposeMsg.setConversationId(negotiationId);
            proposeMsg.setInReplyTo(receivedProposalMsg.getReplyWith());
            lastMessageReplyWith = "prop-" + negotiationId + "-" + System.currentTimeMillis();
            proposeMsg.setReplyWith(lastMessageReplyWith);
            try {
                proposeMsg.setContentObject(counterProposal);
                myAgent.send(proposeMsg);
                logger.info("{}: Sent counter-proposal with {} bid(s).", myAgent.getLocalName(), pendingCounterBids.size());
            } catch (IOException e) {
                logger.error("{}: Error sending counter-proposal", myAgent.getLocalName(), e);
            }
            // after sending counters, clear pendingCounterBids (we keep lastSentCounterBids for possible ACCEPT)
            pendingCounterBids.clear();
        }
    }

    private class AcceptOffer extends OneShotBehaviour {
        @Override
        public void action() {
            if (finalAcceptedBid == null) {
                logger.warn("{}: AcceptOffer state reached but no finalAcceptedBid is set.", myAgent.getLocalName());
                return;
            }
            logger.info("{}: Sending acceptance message to {}", myAgent.getLocalName(), sellerAgent.getLocalName());
            // Build a Proposal containing the accepted bid to inform the seller explicitly
            Proposal acceptedProposal = new Proposal(List.of(finalAcceptedBid));
            ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            accept.addReceiver(sellerAgent);
            accept.setConversationId(negotiationId);
            accept.setInReplyTo(receivedProposalMsg.getReplyWith());
            try {
                accept.setContentObject(acceptedProposal);
            } catch (IOException e) {
                accept.setContent("Offer accepted.");
            }
            myAgent.send(accept);
        }
    }

    private static class EndNegotiation extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{}: Negotiation process finished.", myAgent.getLocalName());
        }
    }

    private class InformCoordinatorDone extends OneShotBehaviour {
        @Override
        public void action() {
            ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
            doneMsg.addReceiver(coordinatorAgent);
            doneMsg.setProtocol(PROTOCOL_REPORT_RESULT);
            try {
                if (finalAcceptedBid != null) {
                    NegotiationResult result = new NegotiationResult(finalAcceptedBid, finalUtility, sellerAgent.getLocalName());
                    doneMsg.setContentObject(result);
                    logger.info("{}: Informing Coordinator of successful negotiation.", myAgent.getLocalName());
                } else {
                    doneMsg.setContent("NegotiationFailed");
                    logger.info("{}: Informing Coordinator of failed negotiation.", myAgent.getLocalName());
                }
                myAgent.send(doneMsg);
            } catch (IOException e) {
                logger.error("{}: Error sending result to Coordinator", myAgent.getLocalName(), e);
            }
        }
    }

    // -- utility helpers --

    private Bid pickBestBidFromList(List<Bid> bids) {
        if (bids == null || bids.isEmpty()) return null;
        Bid best = null;
        double bestU = Double.NEGATIVE_INFINITY;
        for (Bid b : bids) {
            double u = evalService.calculateUtility("buyer", b, weights, issueParams, buyerRiskBeta);
            if (u > bestU) {
                bestU = u;
                best = b;
            }
        }
        return best;
    }
}

