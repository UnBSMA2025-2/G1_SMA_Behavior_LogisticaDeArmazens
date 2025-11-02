package mas.models;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ProductBundle melhorado para suportar:
 * - identificação única (id)
 * - itens com SKU e quantidade
 * - bounds de sinergia (min, max)
 * - pesos por issue (ex: price, leadTime) para avaliação
 * - metadata livre para extensões
 * - builder para criação segura e validação
 */
public final class ProductBundle implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final List<Item> items;

    // parâmetros de sinergia (usados pelo EvaluationService)
    private final double synergyMin;
    private final double synergyMax;

    // pesos por issue (ex: "price" -> 0.7, "time" -> 0.3)
    private final Map<String, Double> issueWeights;

    // campo livre para qualquer metadado necessário
    private final Map<String, Object> metadata;

    private ProductBundle(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.name = b.name == null ? this.id : b.name;
        this.items = Collections.unmodifiableList(new ArrayList<>(b.items));
        this.synergyMin = b.synergyMin;
        this.synergyMax = b.synergyMax;
        this.issueWeights = Collections.unmodifiableMap(new LinkedHashMap<>(b.issueWeights));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
        validateState();
    }

    private void validateState() {
        if (synergyMin > synergyMax) throw new IllegalArgumentException("synergyMin > synergyMax");
        if (items.isEmpty()) throw new IllegalArgumentException("bundle must have at least one item");
        if (issueWeights.values().stream().anyMatch(d -> d < 0.0)) throw new IllegalArgumentException("weights must be >= 0");
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public List<Item> getItems() { return items; }
    public double getSynergyMin() { return synergyMin; }
    public double getSynergyMax() { return synergyMax; }
    public Map<String, Double> getIssueWeights() { return issueWeights; }
    public Map<String, Object> getMetadata() { return metadata; }

    public int getTotalQuantity() {
        return items.stream().mapToInt(Item::getQuantity).sum();
    }

    public Set<String> getSkus() {
        return items.stream().map(Item::getSku).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Calcula um valor agregado para o bundle a partir de um map de features (issue -> value).
     * Os weights do bundle são usados para ponderar cada issue.
     * Se uma issue não estiver presente em featureValues, assume-se 0.
     */
    public double computeWeightedScore(Map<String, Double> featureValues) {
        double sumW = issueWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sumW == 0.0) return 0.0;
        double acc = 0.0;
        for (Map.Entry<String, Double> e : issueWeights.entrySet()) {
            double v = featureValues.getOrDefault(e.getKey(), 0.0);
            acc += e.getValue() * v;
        }
        return acc / sumW;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductBundle)) return false;
        ProductBundle that = (ProductBundle) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "ProductBundle{" + "id='" + id + '\'' + ", name='" + name + '\'' + ", items=" + items + '}';
    }

    // Builder
    public static class Builder {
        private String id;
        private String name;
        private final List<Item> items = new ArrayList<>();
        private double synergyMin = 0.0;
        private double synergyMax = 1.0;
        private final Map<String, Double> issueWeights = new LinkedHashMap<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder addItem(String sku, int quantity) { this.items.add(new Item(sku, quantity)); return this; }
        public Builder items(Collection<Item> items) { this.items.clear(); this.items.addAll(items); return this; }
        public Builder synergyBounds(double min, double max) { this.synergyMin = min; this.synergyMax = max; return this; }
        public Builder issueWeight(String issue, double weight) { this.issueWeights.put(issue, weight); return this; }
        public Builder metadata(String k, Object v) { this.metadata.put(k, v); return this; }
        public ProductBundle build() { return new ProductBundle(this); }
    }

    /**
     * Item interno representando SKU e quantidade.
     * Pode ser extraído para uma classe independente se o projeto preferir.
     */
    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String sku;
        private final int quantity;

        public Item(String sku, int quantity) {
            this.sku = Objects.requireNonNull(sku);
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
            this.quantity = quantity;
        }

        public String getSku() { return sku; }
        public int getQuantity() { return quantity; }

        public int[] getProducts() {
            int[] vec = new int[4]; // P1,P2,P3,P4
            if (items == null || items.isEmpty()) return vec;
            for (Item it : items) {
                if (it == null) continue;
                String sku = (it.getSku() == null) ? "" : it.getSku().trim().toUpperCase();
                int qty = it.getQuantity();
                // igualdade direta
                if (sku.equals("P1")) { vec[0] += qty; continue; }
                if (sku.equals("P2")) { vec[1] += qty; continue; }
                if (sku.equals("P3")) { vec[2] += qty; continue; }
                if (sku.equals("P4")) { vec[3] += qty; continue; }

                // se SKU contiver a token (ex: "SKU-P1", "item_p2")
                if (sku.contains("P1")) { vec[0] += qty; continue; }
                if (sku.contains("P2")) { vec[1] += qty; continue; }
                if (sku.contains("P3")) { vec[2] += qty; continue; }
                if (sku.contains("P4")) { vec[3] += qty; continue; }
                // SKUs não mapeados são ignorados
            }
            return vec;
        }

        /**
         * Método alternativo que converte a lista interna de items para um vetor de quantidades
         * respeitando a ordem dos items no bundle. Útil se algum código precisar dessa forma.
         */
        public int[] getQuantitiesVector() {
            if (items == null) return new int[0];
            int[] v = new int[items.size()];
            for (int i = 0; i < items.size(); i++) v[i] = items.get(i).getQuantity();
            return v;
        }
        
        
 
        @Override
        public String toString() { return "Item{" + "sku='" + sku + '\'' + ", q=" + quantity + '}'; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Item)) return false;
            Item item = (Item) o;
            return quantity == item.quantity && sku.equals(item.sku);
        }

        @Override
        public int hashCode() { return Objects.hash(sku, quantity); }
    }
}

