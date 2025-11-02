package mas.logic;

import mas.models.Bid;
import mas.models.NegotiationIssue;
import mas.models.ProductBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EvaluationService com suporte a parâmetros por ProductBundle (sinergia).
 *
 * - Mantém compatibilidade com a API antiga calculateUtility(...).
 * - Quando um Bid contém um ProductBundle, cria (lazy) parâmetros específicos
 *   para esse bundle derivando-os a partir dos issueParams globais e dos
 *   synergyBounds do bundle.
 * - Permite override/atualização manual via updateBundleIssueParams(...) e clearBundleParams(...).
 */
public class EvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationService.class);

    private final Map<String, double[]> tfnMapBuyer;
    private final Map<String, double[]> tfnMapSeller;

    /**
     * Mapa bundleId -> (issueName -> IssueParameters) criado a partir dos globals.
     * Thread-safe.
     */
    private final Map<String, Map<String, IssueParameters>> bundleIssueParams = new ConcurrentHashMap<>();

    public EvaluationService() {
        this.tfnMapBuyer = new HashMap<>();
        this.tfnMapSeller = new HashMap<>();
        ConfigLoader config = ConfigLoader.getInstance();
        loadTfnsFromConfig(config, "buyer", this.tfnMapBuyer);
        loadTfnsFromConfig(config, "seller", this.tfnMapSeller);
    }

    @Deprecated
    public double calculateUtility(Bid bid, Map<String, Double> weights,
                                   Map<String, IssueParameters> issueParams, double riskBeta) {
        return calculateUtility("buyer", bid, weights, issueParams, riskBeta);
    }

    public double calculateUtility(String agentType, Bid bid, Map<String, Double> weights,
                                   Map<String, IssueParameters> issueParams, double riskBeta) {
        double totalUtility = 0.0;

        if (bid == null || bid.getIssues() == null) {
            return 0.0;
        }
        if (weights == null || issueParams == null) {
            return 0.0;
        }

        // Se há um ProductBundle no Bid, tente obter (ou derivar) params específicos
        ProductBundle bundle = bid.getProductBundle();
        Map<String, IssueParameters> effectiveParams = issueParams;
        if (bundle != null && bundle.getId() != null) {
            Map<String, IssueParameters> specific = bundleIssueParams.get(bundle.getId());
            if (specific == null) {
                // Deriva e registra parâmetros para esse bundle (lazy)
                specific = deriveBundleIssueParams(bundle, issueParams);
                bundleIssueParams.put(bundle.getId(), specific);
                logger.debug("EvaluationService: Derived issueParams for bundle {} -> {}", bundle.getId(), specific.keySet());
            }
            effectiveParams = specific;
        }

        for (NegotiationIssue issue : bid.getIssues()) {
            if (issue == null || issue.getName() == null) continue;

            String issueName = issue.getName().trim().toLowerCase();
            double weight = weights.getOrDefault(issueName, 0.0);

            if (Math.abs(weight) < 1e-9) continue;

            IssueParameters params = effectiveParams.get(issueName);
            if (params == null) {
                // fallback: try original case variants
                params = effectiveParams.get(issue.getName());
            }
            if (params == null) {
                logger.debug("EvaluationService: No IssueParameters for issue '{}' (bundle-aware). Skipping.", issueName);
                continue;
            }

            double normalizedUtility = normalizeIssueUtility(agentType, issue, params, riskBeta);
            totalUtility += weight * normalizedUtility;
        }
        // Normaliza para [0,1]
        return Math.max(0.0, Math.min(1.0, totalUtility));
    }

    /**
     * Deriva parâmetros (min/max/type) para cada issue do bundle a partir dos `baseIssueParams`.
     * Estratégia:
     * - Se o bundle.metadata contém "params.<issueName>" (ex: "params.price" -> "10,100"), usa esse value direto.
     * - Caso contrário, usa synergyBounds do bundle (valores entre 0 e 1) para posicionar
     *   min/max dentro do intervalo global [globalMin, globalMax]:
     *
     *   bundleMin = globalMin + synergyMin * (globalMax - globalMin)
     *   bundleMax = globalMin + synergyMax * (globalMax - globalMin)
     *
     * - Issues qualitativos mantêm os mesmos parâmetros (tipo QUALITATIVE).
     */
    private Map<String, IssueParameters> deriveBundleIssueParams(ProductBundle bundle,
                                                                 Map<String, IssueParameters> baseIssueParams) {
        Map<String, IssueParameters> derived = new HashMap<>();
        double sMin = bundle.getSynergyMin();
        double sMax = bundle.getSynergyMax();
        // clamp 0..1
        sMin = Math.max(0.0, Math.min(1.0, sMin));
        sMax = Math.max(0.0, Math.min(1.0, sMax));
        if (sMin > sMax) {
            double t = sMin; sMin = sMax; sMax = t;
        }

        for (Map.Entry<String, IssueParameters> e : baseIssueParams.entrySet()) {
            String issueName = e.getKey().trim().toLowerCase();
            IssueParameters gp = e.getValue();
            if (gp == null) continue;

            if (gp.getType() == IssueType.QUALITATIVE) {
                derived.put(issueName, gp);
                continue;
            }

            // Primeiro, se o bundle contém metadata com explicit params, use
            Object explicit = null;
            try {
                explicit = bundle.getMetadata().get("params." + issueName);
            } catch (Exception ex) {
                explicit = null;
            }
            if (explicit instanceof String) {
                String v = ((String) explicit).trim();
                String[] parts = v.split(",");
                if (parts.length == 2) {
                    try {
                        double min = Double.parseDouble(parts[0].trim());
                        double max = Double.parseDouble(parts[1].trim());
                        derived.put(issueName, new IssueParameters(min, max, gp.getType()));
                        continue;
                    } catch (NumberFormatException nfe) {
                        // ignore and fallback to synergy mapping
                    }
                }
            }

            // Fallback: derive within global interval using synergyMin/Max as ratios
            double globalMin = gp.getMin();
            double globalMax = gp.getMax();
            double range = globalMax - globalMin;
            if (Math.abs(range) < 1e-12) {
                // degenerate, keep global
                derived.put(issueName, new IssueParameters(globalMin, globalMax, gp.getType()));
            } else {
                double bmin = globalMin + sMin * range;
                double bmax = globalMin + sMax * range;
                // ensure order
                if (bmin > bmax) { double t = bmin; bmin = bmax; bmax = t; }
                derived.put(issueName, new IssueParameters(bmin, bmax, gp.getType()));
            }
        }
        return derived;
    }

    /**
     * Limpas/override públicos para permitir atualizações manuais.
     */
    public void updateBundleIssueParams(String bundleId, Map<String, IssueParameters> params) {
        if (bundleId == null || params == null) return;
        bundleIssueParams.put(bundleId, new HashMap<>(params));
    }

    public void clearBundleParams(String bundleId) {
        if (bundleId == null) return;
        bundleIssueParams.remove(bundleId);
    }

    // --- Normalização existente (mantive suas implementações, com pequenas chamadas unificadas) ---

    private double normalizeIssueUtility(String agentType, NegotiationIssue issue, IssueParameters params, double riskBeta) {
        Object value = issue.getValue();
        if (value == null) {
            return 0.0;
        }

        if (params.getType() == IssueType.QUALITATIVE) {
            if (value instanceof String) {
                return normalizeQualitativeUtility(agentType, (String) value);
            } else {
                return 0.0;
            }
        } else {
            if (value instanceof Number) {
                return normalizeQuantitativeUtility(((Number) value).doubleValue(), params, riskBeta);
            } else {
                return 0.0;
            }
        }
    }

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

    // --- Inner classes ---

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

        public double getMin() { return min; }
        public double getMax() { return max; }
        public IssueType getType() { return type; }

        @Override
        public String toString() {
            return "IssueParameters{" + "min=" + min + ", max=" + max + ", type=" + type + '}';
        }
    }

    public enum IssueType { COST, BENEFIT, QUALITATIVE }

    // --- TFN loader (mantive sua implementação) ---
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

