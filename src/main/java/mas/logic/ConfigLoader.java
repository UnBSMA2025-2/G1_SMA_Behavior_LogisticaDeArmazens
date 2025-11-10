package mas.logic;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import mas.logic.EvaluationService.IssueParameters;
import mas.logic.EvaluationService.IssueType;

/**
 * Classe utilitária para carregar configurações do arquivo config.properties.
 * Usa o padrão Singleton para garantir que o arquivo seja lido apenas uma vez.
 */
public class ConfigLoader {

    private static ConfigLoader instance = null;
    private Properties properties;

    private ConfigLoader() {
        properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                System.out.println("Sorry, unable to find config.properties");
                return;
            }
            properties.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Busca os parâmetros [min, max] para um agente, pacote e issue específicos.
     * 
     * @param agentType "buyer" ou "seller"
     * @param agentName O nome do agente (ex: "s1", "s2"). Usado apenas se
     *                  agentType="seller".
     * @param bundleId  Ex: "1100"
     * @param issueName Ex: "price"
     * @param type      Ex: IssueType.COST
     * @return Um objeto IssueParameters ou null se não for encontrado.
     */
    public IssueParameters getSynergyParams(String agentType, String agentName, String bundleId, String issueName,
            IssueType type) {
        String key;
        if ("buyer".equalsIgnoreCase(agentType)) {
            key = "params.buyer." + bundleId + "." + issueName.toLowerCase();
        } else {
            key = "params.seller." + agentName.toLowerCase() + "." + bundleId + "." + issueName.toLowerCase();
        }

        String value = getString(key);

        if (value != null && !value.isEmpty()) {
            String[] parts = value.split(",");
            if (parts.length == 2) {
                try {
                    double min = Double.parseDouble(parts[0].trim());
                    double max = Double.parseDouble(parts[1].trim());
                    return new IssueParameters(min, max, type);
                } catch (NumberFormatException e) {
                    System.err.println("Erro ao parsear config: " + key);
                }
            }
        }
        // Retorna null se a chave específica do pacote não for encontrada
        return null;
    }

    public static ConfigLoader getInstance() {
        if (instance == null) {
            instance = new ConfigLoader();
        }
        return instance;
    }

    public String getString(String key) {
        return properties.getProperty(key);
    }

    public double getDouble(String key) {
        return Double.parseDouble(properties.getProperty(key));
    }

    public int getInt(String key) {
        return Integer.parseInt(properties.getProperty(key));
    }
}