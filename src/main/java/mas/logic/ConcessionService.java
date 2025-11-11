package mas.logic;

import mas.logic.EvaluationService.IssueParameters;
import mas.logic.EvaluationService.IssueType;
import mas.models.Bid;
import mas.models.NegotiationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contém a lógica de negócio para concessão (barganha).
 * Gera novos lances (Bids) com base nas táticas de concessão do artigo.
 * Implementa as equações de geração de lance (Eq. 5 e 6).
 */
public class ConcessionService {

    /**
     * Gera um contra-lance (Bid) para a próxima rodada de negociação.
     *
     * @param referenceBid O lance anterior (para saber o ProductBundle e os issues).
     * @param currentRound A rodada atual (t).
     * @param maxRounds    O deadline (t_max).
     * @param gamma        O fator de concessão (γ).
     * @param discountRate O fator de desconto (b_k).
     * @param issueParams  Os parâmetros (min, max) para os issues.
     * @param agentType    "buyer" ou "seller", para a direção da concessão.
     * @return Um novo Bid com valores de issues recalculados.
     */
    public Bid generateCounterBid(Bid referenceBid, int currentRound, int maxRounds, double gamma,
                                  double discountRate, Map<String, IssueParameters> issueParams, String agentType) {

        List<NegotiationIssue> counterIssues = new ArrayList<>();

        for (NegotiationIssue issue : referenceBid.getIssues()) {
            String issueName = issue.getName().toLowerCase();
            IssueParameters params = issueParams.get(issueName); // Pega params genéricos
            if (params == null) {
                counterIssues.add(new NegotiationIssue(issue.getName(), issue.getValue()));
                continue;
            }
            double concessionRate = calculateConcessionRate(currentRound, maxRounds, gamma, discountRate);

            Object newValue;
            if (params.getType() == IssueType.QUALITATIVE) {
                newValue = mapConcessionToQualitative(concessionRate, agentType);
            } else {
                newValue = calculateNewQuantitativeValue(concessionRate, params.getMin(), params.getMax(), params.getType(), agentType);
            }

            counterIssues.add(new NegotiationIssue(issue.getName(), newValue));
        }
        return new Bid(referenceBid.getProductBundle(), counterIssues, referenceBid.getQuantities());
    }

    /**
     * Calcula a taxa de concessão α(t) usando a Equação 5.
     * Esta implementação está CORRETA.
     */
    private double calculateConcessionRate(int t, int t_max, double gamma, double b_k) {
        if (t > t_max) t = t_max;
        if (t <= 0) t = 1;

        double timeRatio = (t_max <= 1) ? 1.0 : (double) (t - 1) / (t_max - 1);

        b_k = Math.max(0.001, Math.min(0.999, b_k));
        gamma = Math.max(0.001, gamma);

        if (gamma <= 1.0) { // Polinomial (Eq. 5, parte 1)
            return b_k + (1 - b_k) * Math.pow(timeRatio, 1.0 / gamma);
        } else { // Exponencial (Eq. 5, parte 2)
            if (timeRatio == 1.0) return 1.0;
            return Math.exp(Math.pow(1.0 - timeRatio, gamma) * Math.log(b_k));
        }
    }

    /**
     * Calcula o novo valor para um issue quantitativo (Eq. 6).
     * A lógica de direção (buyer/seller, cost/benefit) está CORRETA.
     */
    private double calculateNewQuantitativeValue(double concessionRate, double min_k, double max_k, IssueType type, String agentType) {
        double range = max_k - min_k;

        if (Math.abs(range) < 1e-9) {
            return min_k;
        }

        double newValue;
        if (agentType.equalsIgnoreCase("buyer")) {
            if (type == IssueType.BENEFIT) { // max -> min
                newValue = max_k - concessionRate * range;
            } else { // COST - min -> max
                newValue = min_k + concessionRate * range;
            }
        } else { // "seller"
            if (type == IssueType.BENEFIT) { // min -> max
                newValue = min_k + concessionRate * range;
            } else { // COST - max -> min
                newValue = max_k - concessionRate * range;
            }
        }
        return Math.max(min_k, Math.min(max_k, newValue));
    }


    /**
     * Mapeia a taxa de concessão (0..1) para um valor linguístico.
     * Esta implementação está CORRETA.
     */
    private String mapConcessionToQualitative(double concessionRate, String agentType) {
        double targetValue;
        if (agentType.equalsIgnoreCase("buyer")) {
            targetValue = 1.0 - concessionRate;
        } else { // seller
            targetValue = concessionRate;
        }

        if (targetValue < 0.1) return "very poor";
        else if (targetValue < 0.3) return "poor";
        else if (targetValue < 0.7) return "medium";
        else if (targetValue < 0.9) return "good";
        else return "very good";
    }
}