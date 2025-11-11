package mas.logic;

import mas.models.Bid;
import mas.models.NegotiationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Contém a lógica de negócio para avaliar lances (Bids).
 * Carrega TFNs do ConfigLoader e avalia da perspectiva do 'buyer' ou 'seller'.
 * Implementa as equações de avaliação de utilidade do artigo.
 * <p>
 * TODO (Simplificação de Sinergia):
 * Este serviço implementa corretamente as FÓRMULAS (Eq. 1-4), mas não
 * implementa a LÓGICA DE SINERGIA. O artigo afirma que a sinergia é
 * modelada por "atribuir diferentes [min_k, max_k] para diferentes
 * pacotes de produtos".
 * A implementação atual usa um único conjunto de [min, max] genérico
 * (ex: 'params.price') para todos os lances, ignorando o ProductBundle.
 */
public class EvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationService.class);
    private final Map<String, double[]> tfnMapBuyer;
    private final Map<String, double[]> tfnMapSeller;

    public EvaluationService() {
        this.tfnMapBuyer = new HashMap<>();
        this.tfnMapSeller = new HashMap<>();
        ConfigLoader config = ConfigLoader.getInstance();
        loadTfnsFromConfig(config, "buyer", this.tfnMapBuyer);
        loadTfnsFromConfig(config, "seller", this.tfnMapSeller);
    }

    /**
     * Backwards-compatible overload: mantém a assinatura antiga usada por testes/consumidores
     * que não informam o agentType. Por padrão assume 'buyer'.
     * @deprecated Preferir a versão com agentType explícito.
     */
    @Deprecated
    public double calculateUtility(Bid bid, Map<String, Double> weights,
                                   Map<String, IssueParameters> issueParams, double riskBeta) {
        return calculateUtility("buyer", bid, weights, issueParams, riskBeta);
    }

    /**
     * Calcula a utilidade agregada de um Bid (Eq. 4).
     *
     * @param agentType   "buyer" ou "seller".
     * @param bid         O lance a ser avaliado.
     * @param weights     Mapa de pesos (ωk) do agente.
     * @param issueParams Mapa com parâmetros (min, max, tipo) do agente.
     * @param riskBeta    O fator de risco (β) do agente.
     * @return A utilidade total (0-1).
     */
    public double calculateUtility(String agentType, Bid bid, Map<String, Double> weights,
                                   Map<String, IssueParameters> issueParams, double riskBeta) {
        double totalUtility = 0.0;

        if (bid == null || bid.getIssues() == null) { /* ... (tratamento de erro) ... */
            return 0.0;
        }
        if (weights == null || issueParams == null) { /* ... (tratamento de erro) ... */
            return 0.0;
        }

        for (NegotiationIssue issue : bid.getIssues()) {
            if (issue == null || issue.getName() == null) continue;

            String issueName = issue.getName().toLowerCase();
            double weight = weights.getOrDefault(issueName, 0.0);

            if (Math.abs(weight) < 1e-9) continue;
            IssueParameters params = issueParams.get(issueName);
            if (params == null) {
                continue;
            }

            double normalizedUtility = normalizeIssueUtility(agentType, issue, params, riskBeta);
            totalUtility += weight * normalizedUtility;
        }
        return Math.max(0.0, Math.min(1.0, totalUtility));
    }

    /**
     * Normaliza a utilidade de um único issue (Qualitativo ou Quantitativo).
     */
    private double normalizeIssueUtility(String agentType, NegotiationIssue issue, IssueParameters params, double riskBeta) {
        Object value = issue.getValue();
        if (value == null) { /* ... (tratamento de erro) ... */
            return 0.0;
        }

        if (params.getType() == IssueType.QUALITATIVE) {
            if (value instanceof String) {
                return normalizeQualitativeUtility(agentType, (String) value);
            } else { /* ... (tratamento de erro) ... */
                return 0.0;
            }
        } else {
            if (value instanceof Number) {
                return normalizeQuantitativeUtility(((Number) value).doubleValue(), params, riskBeta);
            } else { /* ... (tratamento de erro) ... */
                return 0.0;
            }
        }
    }

    /**
     * Normaliza um issue qualitativo (Eq. 3).
     * Esta implementação está CORRETA.
     */
    private double normalizeQualitativeUtility(String agentType, String linguisticValue) {
        Map<String, double[]> tfnMap = agentType.equalsIgnoreCase("seller") ? this.tfnMapSeller : this.tfnMapBuyer;
        String lookupKey = linguisticValue.replace("_", " ").trim().toLowerCase();
        double[] tfn = tfnMap.get(lookupKey);
        if (tfn == null) {
            tfn = tfnMap.get(lookupKey.replace(" ", "_"));
        }
        if (tfn == null) {
            logger.warn("EvaluationService Warning: Unknown linguistic term '{}' for agent type '{}'.", linguisticValue, agentType);
            return 0.0;
        }
        return (tfn[0] + 4 * tfn[1] + tfn[2]) / 6.0;
    }

    /**
     * Normaliza um issue quantitativo (Eqs. 1 e 2).
     * Esta implementação está CORRETA para as fórmulas, mas
     * usa parâmetros (min/max) genéricos.
     */
    private double normalizeQuantitativeUtility(double value, IssueParameters params, double riskBeta) {
        double min = params.getMin();
        double max = params.getMax();
        double range = max - min;

        if (Math.abs(range) < 1e-9) {
            double v_min_boundary = 0.1;
            if (params.getType() == IssueType.COST && value <= min) return 1.0;
            if (params.getType() == IssueType.BENEFIT && value >= min) return 1.0;
            return v_min_boundary;
        }
        double v_min = 0.1;
        value = Math.max(min, Math.min(max, value));
        double ratio;
        if (params.getType() == IssueType.COST) {
            ratio = (max - value) / range;
        } else {
            ratio = (value - min) / range;
        }
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        if (riskBeta <= 0) {
            riskBeta = 1.0;
        }
        v_min = Math.max(0.001, Math.min(0.999, v_min));

        if (riskBeta == 1.0) { // Neutro
            return v_min + (1 - v_min) * ratio;
        } else if (riskBeta < 1.0) { // Propenso a risco (Eq. 1)
            if (ratio == 0.0) return v_min;
            return v_min + (1 - v_min) * Math.pow(ratio, 1.0 / riskBeta);
        } else { // Averso a risco (Eq. 2)
            if (ratio == 1.0) return 1.0;
            return Math.exp(Math.pow(1 - ratio, riskBeta) * Math.log(v_min));
        }
    }

    /**
     * Classe auxiliar para armazenar os parâmetros de um issue.
     */
    public static class IssueParameters {
        private final double min, max;
        private final IssueType type;

        public IssueParameters(double min, double max, IssueType type) {
            if (type != IssueType.QUALITATIVE && min > max) {
                this.min = max;
                this.max = min;
            } else {
                this.min = min;
                this.max = max;
            }
            this.type = type;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }

        public IssueType getType() {
            return type;
        }
    }

    /**
     * Enumeração para os tipos de critério.
     */
    public enum IssueType {
        COST,
        BENEFIT,
        QUALITATIVE
    }

    /**
     * Método auxiliar para carregar TFNs do config.
     * Esta implementação está CORRETA.
     */
    private void loadTfnsFromConfig(ConfigLoader config, String prefix, Map<String, double[]> map) {
        String[] terms = {"very_poor", "poor", "medium", "good", "very_good"};
        for (String term : terms) {
            String key = "tfn." + prefix + "." + term;
            String value = config.getString(key);
            if (value != null && !value.isEmpty()) {
                String[] parts = value.split(",");
                if (parts.length == 3) {
                    try {
                        double m1 = Double.parseDouble(parts[0].trim());
                        double m2 = Double.parseDouble(parts[1].trim());
                        double m3 = Double.parseDouble(parts[2].trim());
                        String withSpace = term.replace("_", " ").toLowerCase();
                        String withUnderscore = term.toLowerCase();
                        String compact = withSpace.replace(" ", "");
                        double[] arr = new double[]{m1, m2, m3};
                        map.put(withSpace, arr);
                        map.put(withUnderscore, arr);
                        map.put(compact, arr);
                    } catch (NumberFormatException e) {
                        logger.error("EvaluationService: Error parsing TFN from config for key '{}', value: '{}'", key, value);
                    }
                } else {
                    logger.error("EvaluationService: Invalid TFN format in config for key '{}'", key);
                }
            } else {
                logger.warn("EvaluationService: Missing TFN configuration for key '{}'", key);
            }
        }
    }
}