package mas;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;

public class App {
    public static void main(String[] args) throws Exception {
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, "true");
        ContainerController cc = rt.createMainContainer(p);

        AgentController sniffer = cc.createNewAgent("sniffer", "jade.tools.sniffer.Sniffer", new Object[]{});
        sniffer.start();
        
        AgentController httpBridge = cc.createNewAgent("httpbridge", "mas.agents.HttpBridgeAgent", null);
        AgentController ca = cc.createNewAgent("ca", "mas.agents.CoordinatorAgent", null);
        AgentController sda = cc.createNewAgent("sda", "mas.agents.SynergyDeterminationAgent", null);
        AgentController tda = cc.createNewAgent("tda", "mas.agents.TaskDecomposerAgent", null);
        AgentController s1 = cc.createNewAgent("s1", "mas.agents.SellerAgent", null);
        AgentController s2 = cc.createNewAgent("s2", "mas.agents.SellerAgent", null);
        AgentController s3 = cc.createNewAgent("s3", "mas.agents.SellerAgent", null);

        httpBridge.start();
        ca.start();
        sda.start();
        tda.start();
        s1.start();
        s2.start();
        s3.start();
        
        AgentController snifferConfig = cc.createNewAgent("snifferConfig", "mas.agents.SnifferConfigAgent", null);
        snifferConfig.start();
    }
}
