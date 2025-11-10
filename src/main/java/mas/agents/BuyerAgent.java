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
import mas.models.*; // Importa todos os modelos
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representa a empresa compradora na negociação bilateral.
 * Este agente é criado pelo CoordinatorAgent para negociar com um SellerAgent
 * específico.
 * Implementa uma Máquina de Estados Finitos (FSM) para gerenciar o protocolo de
 * oferta alternada (alternating-offer protocol).
 *
 * VERSÃO REFATORADA:
 * - Implementa a avaliação "bid-by-bid" (multi-lance).
 * - Utiliza os serviços (Evaluation, Concession) que buscam parâmetros de
 * sinergia do ConfigLoader, em vez de usar um mapa 'issueParams' local.
 */
public class BuyerAgent extends Agent {
    private static final Logger logger = LoggerFactory.getLogger(BuyerAgent.class);

    private static final String PROTOCOL_REPORT_RESULT = "report-negotiation-result";
    // Nomes dos estados
    private static final String STATE_SEND_REQUEST = "SendRequest";
    private static final String STATE_WAIT_FOR_PROPOSAL = "WaitForProposal";
    private static final String STATE_EVALUATE_PROPOSAL = "EvaluateProposal";
    private static final String STATE_ACCEPT_OFFER = "AcceptOffer";
    private static final String STATE_MAKE_COUNTER_OFFER = "MakeCounterOffer";
    private static final String STATE_END_NEGOTIATION = "EndNegotiation";

    // Variáveis de estado da negociação
    private AID sellerAgent;
    private AID coordinatorAgent;
    private ACLMessage receivedProposalMsg;

    // Modificado para suportar múltiplos lances aceitos
    private List<Bid> finalAcceptedBids = null;
    private List<Bid> lastSentCounterProposal = null; // Rastreia a última contraproposta

    private int currentRound = 0;
    private String negotiationId;
    private String lastMessageReplyWith;

    // Serviços e Configurações
    private EvaluationService evalService;
    private ConcessionService concessionService;
    private Map<String, Double> weights;
    private double acceptanceThreshold;
    private double buyerRiskBeta;
    private double buyerGamma;
    private int maxRounds;
    private double discountRate;
    // O 'issueParams' local foi removido

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
                logger.info("{}: FSM finished negotiation with {}.", myAgent.getLocalName(),
                        sellerAgent.getLocalName());
                myAgent.addBehaviour(new InformCoordinatorDone());
                return super.onEnd();
            }
        };

        // REGISTRA ESTADOS
        fsm.registerFirstState(new SendRequest(), STATE_SEND_REQUEST);
        fsm.registerState(new WaitForProposal(this), STATE_WAIT_FOR_PROPOSAL);
        fsm.registerState(new EvaluateProposal(), STATE_EVALUATE_PROPOSAL);
        fsm.registerState(new AcceptOffer(), STATE_ACCEPT_OFFER);
        fsm.registerState(new MakeCounterOffer(), STATE_MAKE_COUNTER_OFFER);
        fsm.registerLastState(new EndNegotiation(), STATE_END_NEGOTIATION);

        // REGISTRA TRANSIÇÕES
        fsm.registerDefaultTransition(STATE_SEND_REQUEST, STATE_WAIT_FOR_PROPOSAL);
        fsm.registerTransition(STATE_WAIT_FOR_PROPOSAL, STATE_EVALUATE_PROPOSAL, 1); // 1 = Proposta recebida
        fsm.registerTransition(STATE_WAIT_FOR_PROPOSAL, STATE_END_NEGOTIATION, 0); // 0 = Timeout ou erro
        fsm.registerTransition(STATE_WAIT_FOR_PROPOSAL, STATE_END_NEGOTIATION, 2); // 2 = Vendedor aceitou
        fsm.registerTransition(STATE_EVALUATE_PROPOSAL, STATE_ACCEPT_OFFER, 1); // Utilidade aceitável
        fsm.registerTransition(STATE_EVALUATE_PROPOSAL, STATE_MAKE_COUNTER_OFFER, 0);// Utilidade baixa, fazer
                                                                                     // contraproposta
        fsm.registerTransition(STATE_EVALUATE_PROPOSAL, STATE_END_NEGOTIATION, 2); // Deadline atingido ou falha
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

        // O 'issueParams' não é mais carregado aqui
    }

    // --- Comportamentos da FSM ---

    /**
     * Estado 1: Envia o "Call for Proposal" (CFP) para o Vendedor.
     */
    private class SendRequest extends OneShotBehaviour {
        @Override
        public void action() {
            currentRound = 1;
            negotiationId = "neg-" + sellerAgent.getLocalName() + "-" + System.currentTimeMillis();
            logger.info("{} [R{}]: Sending call for proposal to {}", myAgent.getLocalName(), currentRound,
                    sellerAgent.getLocalName());
            ACLMessage cfp = new ACLMessage(ACLMessage.REQUEST);
            cfp.addReceiver(sellerAgent);
            cfp.setContent("send-proposal");
            cfp.setConversationId(negotiationId);
            lastMessageReplyWith = "req-" + negotiationId + "-" + System.currentTimeMillis();
            cfp.setReplyWith(lastMessageReplyWith);
            myAgent.send(cfp);
        }
    }

    /**
     * Estado 2: Aguarda uma resposta (Proposta OU Aceitação) do Vendedor.
     */
    private class WaitForProposal extends Behaviour {
        private boolean responseReceived = false;
        private long startTime;
        private long timeoutMillis = 15000;
        private int exitValue = 0; // 0=Timeout, 1=Propose (avaliar), 2=Accept (terminar)

        public WaitForProposal(Agent a) {
            super(a);
        }

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
                    MessageTemplate.MatchInReplyTo(lastMessageReplyWith));
            MessageTemplate proposeTemplate = MessageTemplate.MatchPerformative(ACLMessage.PROPOSE);
            MessageTemplate acceptTemplate = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
            MessageTemplate performativeTemplate = MessageTemplate.or(proposeTemplate, acceptTemplate);
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(sellerAgent),
                    MessageTemplate.and(performativeTemplate, idTemplate));

            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                responseReceived = true;
                receivedProposalMsg = msg;

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    exitValue = 1; // Proposta recebida, ir para EvaluateProposal
                } else { // Deve ser ACCEPT_PROPOSAL
                    logger.info("{}: Seller ACCEPTED my last counter-proposal.", myAgent.getLocalName());
                    if (lastSentCounterProposal != null) {
                        // O Vendedor aceitou nossa última contraproposta
                        finalAcceptedBids = lastSentCounterProposal;
                    } else {
                        logger.warn("{}: Seller accepted, but lastSentCounterProposal is null!",
                                myAgent.getLocalName());
                    }
                    exitValue = 2; // Termina a negociação
                }

            } else {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > timeoutMillis) {
                    logger.warn("{}: Timeout waiting for proposal from {}. Ending negotiation.", myAgent.getLocalName(),
                            sellerAgent.getLocalName());
                    responseReceived = true;
                    exitValue = 0;
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
            return exitValue; // Retorna 0 (Timeout), 1 (Propose) ou 2 (Accept)
        }
    }

    /**
     * Estado 3: Avalia a proposta recebida do Vendedor.
     * LÓGICA REFATORADA: Avalia "bid-by-bid" e adota uma
     * estratégia "all-or-nothing" para contraproposta.
     */
    private class EvaluateProposal extends OneShotBehaviour {
        private int transitionEvent = 2; // Default: falha/deadline

        @Override
        public void action() {
            currentRound++;
            logger.info("{} [R{}]: Evaluating proposal from {}", myAgent.getLocalName(), currentRound,
                    sellerAgent.getLocalName());

            if (currentRound > maxRounds) {
                logger.warn("{}: Deadline reached ({}/{}) . Ending negotiation.", myAgent.getLocalName(), currentRound,
                        maxRounds);
                finalAcceptedBids = null;
                transitionEvent = 2;
                return;
            }

            try {
                Serializable content = receivedProposalMsg.getContentObject();
                if (!(content instanceof Proposal)) {
                    logger.error("{}: Received unexpected content type: {}", myAgent.getLocalName(),
                            (content == null ? "null" : content.getClass().getName()));
                    transitionEvent = 2;
                    return;
                }

                Proposal p = (Proposal) content;
                if (p.getBids() == null || p.getBids().isEmpty()) {
                    logger.warn("{}: Received empty proposal.", myAgent.getLocalName());
                    transitionEvent = 2;
                    return;
                }

                // LÓGICA MULTI-LANCE: "all-or-nothing-counter"
                boolean allBidsAcceptable = true;
                List<Bid> acceptableBids = new ArrayList<>();

                // Itera lance-a-lance
                for (Bid receivedBid : p.getBids()) {
                    // Avalia a utilidade deste lance
                    // O 'agentName' é "buyer" (genérico)
                    double utility = evalService.calculateUtility("buyer", "buyer", receivedBid, weights,
                            buyerRiskBeta);

                    // Gera o contra-lance hipotético do comprador para t+1
                    Bid hypotheticalCounter = concessionService.generateCounterBid(
                            receivedBid, currentRound + 1, maxRounds,
                            buyerGamma, discountRate, "buyer", "buyer");

                    double nextCounterUtility = evalService.calculateUtility("buyer", "buyer", hypotheticalCounter,
                            weights, buyerRiskBeta);

                    logger.info("{}: Evaluating Bid {}: Utility ({}). Threshold ({}). Next Counter ({}).",
                            myAgent.getLocalName(), getBundleId(receivedBid.getProductBundle()),
                            String.format("%.4f", utility), acceptanceThreshold,
                            String.format("%.4f", nextCounterUtility));

                    // Implementação da Eq. 7
                    if (utility >= acceptanceThreshold && utility >= nextCounterUtility) {
                        acceptableBids.add(receivedBid);
                    } else {
                        // Se QUALQUER lance falhar, rejeitamos a proposta inteira
                        allBidsAcceptable = false;
                        logger.info("{}: Bid {} is NOT acceptable. Will counter.",
                                myAgent.getLocalName(), getBundleId(receivedBid.getProductBundle()));
                        break; // Para o loop, já decidimos contra-ofertar
                    }
                }

                if (allBidsAcceptable) {
                    logger.info("{}: All {} bids in proposal are acceptable. Accepting.",
                            myAgent.getLocalName(), acceptableBids.size());
                    finalAcceptedBids = acceptableBids; // Salva a lista de lances
                    transitionEvent = 1; // (ACEITAR)
                } else {
                    logger.info("{}: Proposal rejected. Will make counter-offer.", myAgent.getLocalName());
                    transitionEvent = 0; // (CONTRA-OFERTA)
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

    /**
     * Estado 4: Gera e envia uma contraproposta ao Vendedor.
     * LÓGICA REFATORADA: Gera uma contraproposta multi-lance.
     */
    private class MakeCounterOffer extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("{} [R{}]: Generating counter-offer...", myAgent.getLocalName(), currentRound);

            try {
                Proposal receivedP = (Proposal) receivedProposalMsg.getContentObject();

                // LÓGICA MULTI-LANCE: Gera um contra-lance para CADA lance
                List<Bid> counterBids = new ArrayList<>();

                // Usamos os lances recebidos como *referência* para quais pacotes ofertar
                for (Bid referenceBid : receivedP.getBids()) {
                    Bid counterBid = concessionService.generateCounterBid(
                            referenceBid, // O lance de referência (para o pacote)
                            currentRound, // A rodada atual
                            maxRounds,
                            buyerGamma,
                            discountRate,
                            "buyer",
                            "buyer" // agentName genérico
                    );
                    counterBids.add(counterBid);
                }

                // Salva a contraproposta, caso o vendedor a aceite
                lastSentCounterProposal = counterBids;

                Proposal counterProposal = new Proposal(counterBids);
                ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
                proposeMsg.addReceiver(sellerAgent);
                proposeMsg.setConversationId(negotiationId);
                proposeMsg.setInReplyTo(receivedProposalMsg.getReplyWith());
                lastMessageReplyWith = "prop-" + negotiationId + "-" + System.currentTimeMillis();
                proposeMsg.setReplyWith(lastMessageReplyWith);
                proposeMsg.setContentObject(counterProposal);
                myAgent.send(proposeMsg);
                logger.info("{}: Sent counter-proposal with {} bids.", myAgent.getLocalName(), counterBids.size());

            } catch (UnreadableException | IOException e) {
                logger.error("{}: Error creating/sending counter-proposal", myAgent.getLocalName(), e);
            }
        }
    }

    /**
     * Estado 5: Envia uma mensagem de aceitação para a proposta do Vendedor.
     */
    private class AcceptOffer extends OneShotBehaviour {
        @Override
        public void action() {
            // A lógica é a mesma, mas agora aceita a *proposta inteira*
            // que foi validada no estado EvaluateProposal.
            logger.info("{}: Sending acceptance message to {}", myAgent.getLocalName(), sellerAgent.getLocalName());
            ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            accept.addReceiver(sellerAgent);
            accept.setConversationId(negotiationId);
            accept.setInReplyTo(receivedProposalMsg.getReplyWith());
            accept.setContent("Offer accepted.");
            myAgent.send(accept);
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
     * Comportamento Final: Informa o Coordenador sobre o resultado da negociação.
     * LÓGICA REFATORADA: Envia UMA mensagem contendo uma LISTA de todos
     * os lances aceitos.
     */
    private class InformCoordinatorDone extends OneShotBehaviour {
        @Override
        public void action() {
            ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
            doneMsg.addReceiver(coordinatorAgent);
            doneMsg.setProtocol(PROTOCOL_REPORT_RESULT);

            if (finalAcceptedBids != null && !finalAcceptedBids.isEmpty()) {
                logger.info("{}: Informing Coordinator of {} successful bids.",
                        myAgent.getLocalName(), finalAcceptedBids.size());

                // Crie uma lista de resultados
                List<NegotiationResult> resultsList = new ArrayList<>();

                for (Bid acceptedBid : finalAcceptedBids) {
                    // Recalcula a utilidade para este lance específico
                    double utility = evalService.calculateUtility("buyer", "buyer", acceptedBid, weights,
                            buyerRiskBeta);
                    resultsList.add(new NegotiationResult(acceptedBid, utility, sellerAgent.getLocalName()));
                }

                // Envia a LISTA como o objeto de conteúdo
                try {
                    doneMsg.setContentObject((Serializable) resultsList);
                } catch (IOException e) {
                    logger.error("{}: Error serializing results list for Coordinator", myAgent.getLocalName(), e);
                    doneMsg.setContent("NegotiationFailed"); // Fallback
                }
            } else {
                // Informa falha (como antes)
                logger.info("{}: Informing Coordinator of failed negotiation.", myAgent.getLocalName());
                doneMsg.setContent("NegotiationFailed");
            }

            myAgent.send(doneMsg);

            // Auto-destrói o BuyerAgent após informar o CA
            myAgent.doDelete();
        }
    }

    /**
     * Método auxiliar para converter ProductBundle em um ID de string.
     */
    private String getBundleId(ProductBundle pb) {
        if (pb == null || pb.getProducts() == null)
            return "default";
        StringBuilder sb = new StringBuilder();
        for (int p : pb.getProducts()) {
            sb.append(p);
        }
        return sb.toString();
    }
}