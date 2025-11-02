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
 * Compatibilidades:
 * - Aceita quantities com tamanho igual ao número de items do bundle (recomendado).
 * - Aceita quantities com tamanho 4 representando [P1,P2,P3,P4] (legacy) e converte
 *   automaticamente para o vetor por item do bundle, mapeando SKUs P1..P4.
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

        // Defensive: if quantities is null, set zero-length array
        int[] q = (quantities == null) ? new int[0] : Arrays.copyOf(quantities, quantities.length);

        int expected = productBundle.getItems().size();

        if (q.length == expected) {
            // OK: already per-item quantities
            this.quantities = Arrays.copyOf(q, q.length);
        } else if (q.length == 4) {
            // Legacy format: vector P1..P4 -> need to map into per-item vector
            this.quantities = mapLegacyVectorToBundleItems(productBundle, q);
        } else {
            throw new IllegalArgumentException(String.format(
                    "Invalid quantities length: expected %d (bundle items) or 4 (legacy P1..P4), got %d",
                    expected, q.length));
        }
    }

    private int[] mapLegacyVectorToBundleItems(ProductBundle bundle, int[] legacy) {
        // legacy: legacy[0]=P1, [1]=P2, [2]=P3, [3]=P4
        int n = bundle.getItems().size();
        int[] out = new int[n];
        List<ProductBundle.Item> items = bundle.getItems();
        for (int i = 0; i < n; i++) {
            ProductBundle.Item it = items.get(i);
            String sku = (it.getSku() == null) ? "" : it.getSku().trim().toUpperCase();
            int qty = 0;
            if ("P1".equals(sku) || sku.contains("P1")) qty = legacy[0];
            else if ("P2".equals(sku) || sku.contains("P2")) qty = legacy[1];
            else if ("P3".equals(sku) || sku.contains("P3")) qty = legacy[2];
            else if ("P4".equals(sku) || sku.contains("P4")) qty = legacy[3];
            else {
                // If SKU doesn't map to P1..P4, leave 0 (legacy vector has no info for custom SKUs)
                qty = 0;
            }
            out[i] = Math.max(0, qty);
        }
        return out;
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

