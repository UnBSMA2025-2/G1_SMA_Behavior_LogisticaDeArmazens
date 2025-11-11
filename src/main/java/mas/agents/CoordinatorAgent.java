package mas.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
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

import java.io.*;
import java.util.*;

/**
 * CoordinatorAgent ajustado: espera define-task-protocol para iniciar pipeline.
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
    private List<ProductBundle> preferredBundles;

    @Override
    protected void setup() {
        logger.info("Coordinator Agent {} is ready.", getAID().getName());

        this.wds = new WinnerDeterminationService();
        this.negotiationResults = new ArrayList<>();
        this.preferredBundles = new ArrayList<>();

        addBehaviour(new WaitForTask());
    }

    private class WaitForTask extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchProtocol(PROTOCOL_DEFINE_TASK)
            );
            ACLMessage msg = myAgent.receive(mt);

            if (msg == null) {
                block();
                return;
            }

            String content = msg.getContent();
            logger.info("🎯 CA RECEIVED define-task message: {}", content);
            if (content == null || content.trim().isEmpty() || "START".equalsIgnoreCase(content.trim())) {
                loadConfigProperties();
                productDemand = new int[]{1, 1, 1, 1};
                logger.info("CA: Using fallback static product demand: {}", Arrays.toString(productDemand));
            } else {
                parseProductDemand(content);
            }

            negotiationResults.clear();
            finishedCounter = 0;
            myAgent.addBehaviour(new RequestProductBundles());
        }
    }

    private void loadConfigProperties() {
        File f = new File("config.properties");
        if (!f.exists()) {
            logger.warn("CA: config.properties not found. Using defaults.");
            return;
        }
        try (InputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            logger.info("CA: Loaded config.properties:");
            for (String key : p.stringPropertyNames()) {
                logger.info("   {} = {}", key, p.getProperty(key));
            }
        } catch (IOException e) {
            logger.error("CA: Error loading config.properties", e);
        }
    }

    private void parseProductDemand(String productList) {
        logger.info("CA: Parsing dynamic demand: {}", productList);
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
                    logger.warn("CA: Unknown product '{}'", product);
            }
        }
        logger.info("CA: Parsed demand vector: {}", Arrays.toString(productDemand));
    }

    private class RequestProductBundles extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("CA: Requesting preferred product bundles from SDA...");
            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
            msg.addReceiver(new AID("sda", AID.ISLOCALNAME));
            msg.setContent("generate-bundles");
            msg.setProtocol(PROTOCOL_GET_BUNDLES);
            msg.setReplyWith("req-bundles-" + System.currentTimeMillis());
            myAgent.send(msg);

            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchProtocol(PROTOCOL_GET_BUNDLES)
            );
            ACLMessage reply = myAgent.blockingReceive(mt, 8000);
            if (reply == null) {
                logger.warn("CA: No reply from SDA (timeout). Proceeding without preferred bundles.");
            } else {
                try {
                    Object content = reply.getContentObject();
                    if (content instanceof List<?>) {
                        List<?> list = (List<?>) content;
                        preferredBundles.clear();
                        for (Object o : list) {
                            if (o instanceof ProductBundle) preferredBundles.add((ProductBundle) o);
                        }
                        logger.info("CA: Received {} preferred bundles from SDA.", preferredBundles.size());
                    } else {
                        logger.warn("CA: Unexpected reply content from SDA.");
                    }
                } catch (UnreadableException e) {
                    logger.error("CA: Failed to read bundles from SDA.", e);
                }
            }
            myAgent.addBehaviour(new StartNegotiations());
        }
    }

    private class StartNegotiations extends OneShotBehaviour {
        @Override
        public void action() {
            logger.info("CA: Starting negotiations...");

            finishedCounter = 0;
            negotiationResults.clear();

            sellerAgents = Arrays.asList(
                    new AID("s1", AID.ISLOCALNAME),
                    new AID("s2", AID.ISLOCALNAME),
                    new AID("s3", AID.ISLOCALNAME)
            );

            for (AID seller : sellerAgents) createBuyerFor(seller);
            myAgent.addBehaviour(new WaitForResults());
        }
    }

    private void createBuyerFor(AID sellerAgent) {
        String buyerName = "buyer_for_" + sellerAgent.getLocalName() + "_" + System.currentTimeMillis();
        logger.info("CA: Creating buyer {} for seller {}", buyerName, sellerAgent.getLocalName());
        try {
            Object[] args = new Object[]{sellerAgent, getAID()};
            AgentController ac = getContainerController().createNewAgent(buyerName, "mas.agents.BuyerAgent", args);
            ac.start();
            addBuyerToSniffer(buyerName);
            
        } catch (StaleProxyException e) {
            logger.error("CA: Failed to start buyer {}", buyerName, e);
        }
    }

    private class WaitForResults extends TickerBehaviour {
        public WaitForResults() { super(CoordinatorAgent.this, 1000); }

        @Override
        protected void onTick() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchProtocol(PROTOCOL_REPORT_RESULT)
            );
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                try {
                    Object obj = msg.getContentObject();
                    if (obj instanceof NegotiationResult) {
                        negotiationResults.add((NegotiationResult) obj);
                        logger.info("CA: Received negotiation result from {}", msg.getSender().getLocalName());
                    } else {
                        logger.info("CA: Received non-object inform from {}", msg.getSender().getLocalName());
                    }
                } catch (UnreadableException e) {
                    logger.warn("CA: Could not read message from {}", msg.getSender().getLocalName());
                }
                finishedCounter++;
            }

            if (finishedCounter >= (sellerAgents == null ? 0 : sellerAgents.size())) {
                logger.info("--- CA: All negotiations concluded. Determining winners... ---");
                List<NegotiationResult> optimal = wds.solveWDPWithBranchAndBound(negotiationResults, productDemand);
                if (optimal == null || optimal.isEmpty()) {
                    logger.info("CA: No combination of bids could satisfy the demand.");
                } else {
                    double tot = optimal.stream().mapToDouble(NegotiationResult::getUtility).sum();
                    logger.info("CA: Optimal solution (total utility = {})", tot);
                    for (NegotiationResult r : optimal) logger.info("CA Winner -> {}", r);
                }
                negotiationResults.clear();
                finishedCounter = 0;
            }
        }
    }
    
    /**
     * Envia mensagem ao Sniffer para monitorar um novo BuyerAgent dinamicamente
     */
    private void addBuyerToSniffer(String buyerName) {
        try {
            Thread.sleep(100);
            
            AID snifferAID = new AID("sniffer", AID.ISLOCALNAME);
            ACLMessage sniffMsg = new ACLMessage(ACLMessage.REQUEST);
            sniffMsg.addReceiver(snifferAID);
            sniffMsg.setOntology("JADE-Sniffer");
            sniffMsg.setContent(buyerName + ";");
            send(sniffMsg);
            
            logger.debug("CA: Added {} to Sniffer monitoring", buyerName);
        } catch (InterruptedException e) {
            logger.warn("CA: Failed to add {} to Sniffer", buyerName, e);
        }
    }
}

