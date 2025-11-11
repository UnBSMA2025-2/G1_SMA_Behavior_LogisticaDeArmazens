package mas.models;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 * Representa um lance (Bid) para um pacote de produtos específico.
 * Estruturado conforme a definição do artigo: ⟨PB, Issues, Quantities⟩. 
 */
public class Bid implements Serializable {
    private static final long serialVersionUID = 1L;
    private final ProductBundle productBundle; // PB
    private final List<NegotiationIssue> issues; // I1, ..., Ik
    private final int[] quantities; // Q

    public Bid(ProductBundle productBundle, List<NegotiationIssue> issues, int[] quantities) {
        this.productBundle = productBundle;
        this.issues = issues;
        this.quantities = quantities;
    }

    public ProductBundle getProductBundle() {
        return productBundle;
    }

    public List<NegotiationIssue> getIssues() {
        return issues;
    }

    public int[] getQuantities() {
        return quantities;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Bid {\n");
        sb.append("  ").append(productBundle.toString()).append(",\n");
        sb.append("  Quantities: ").append(Arrays.toString(quantities)).append(",\n");
        sb.append("  Issues: [\n");
        for (NegotiationIssue issue : issues) {
            sb.append("    ").append(issue.toString()).append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }
}