package mas.models;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Representa um lance (Bid) para um pacote de produtos específico.
 * Estruturado conforme a definição do artigo: ⟨PB, Issues, Quantities⟩.
 *
 * Observações:
 * - Quantities deve ter o mesmo tamanho que bundle.getItems().size() (quantidade por item do bundle).
 * - Esta classe é serializável para ser enviada via msg.setContentObject(...)
 */
public class Bid implements Serializable {
    private static final long serialVersionUID = 1L;

    // Bid = ⟨PB, I1,...,Ik, Q⟩
    private final ProductBundle productBundle; // PB
    private final List<NegotiationIssue> issues; // I1, ..., Ik
    private final int[] quantities; // Q (quantidade por item do bundle, ordem segue productBundle.getItems())

    public Bid(ProductBundle productBundle, List<NegotiationIssue> issues, int[] quantities) {
        this.productBundle = Objects.requireNonNull(productBundle, "productBundle");
        this.issues = (issues == null) ? Collections.emptyList() : Collections.unmodifiableList(issues);
        this.quantities = (quantities == null) ? new int[0] : Arrays.copyOf(quantities, quantities.length);

        // Validação: se quisermos mapear Q por item do bundle, garantir compatibilidade
        int expected = productBundle.getItems().size();
        if (this.quantities.length != expected) {
            throw new IllegalArgumentException(String.format(
                    "Invalid quantities length: expected %d (bundle items), got %d",
                    expected, this.quantities.length));
        }
    }

    public ProductBundle getProductBundle() {
        return productBundle;
    }

    public List<NegotiationIssue> getIssues() {
        return issues;
    }

    /**
     * Retorna uma cópia defensiva do vetor de quantidades.
     */
    public int[] getQuantities() {
        return Arrays.copyOf(quantities, quantities.length);
    }

    /**
     * Quantidade total somando o vetor Q.
     */
    public int getTotalQuantity() {
        int s = 0;
        for (int q : quantities) s += q;
        return s;
    }

    /**
     * Conveniência: retorna a quantidade no índice i (ordem do bundle items).
     */
    public int getQuantityAt(int index) {
        if (index < 0 || index >= quantities.length) throw new IndexOutOfBoundsException("" + index);
        return quantities[index];
    }

    public String getBundleId() {
        return productBundle != null ? productBundle.getId() : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Bid { bundleId=").append(getBundleId())
          .append(", totalQty=").append(getTotalQuantity())
          .append(", quantities=").append(Arrays.toString(quantities))
          .append(", issues=[");
        for (int i = 0; i < issues.size(); i++) {
            sb.append(issues.get(i).toString());
            if (i < issues.size() - 1) sb.append(", ");
        }
        sb.append("] }");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bid)) return false;
        Bid bid = (Bid) o;
        return Objects.equals(productBundle, bid.productBundle)
                && Objects.equals(issues, bid.issues)
                && Arrays.equals(quantities, bid.quantities);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(productBundle, issues);
        result = 31 * result + Arrays.hashCode(quantities);
        return result;
    }
}

