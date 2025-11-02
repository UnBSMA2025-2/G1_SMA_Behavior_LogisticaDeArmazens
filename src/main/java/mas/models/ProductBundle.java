package mas.models;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * ProductBundle final, imutável e serializável.
 * - id, name
 * - lista de items (SKU, quantity)
 * - synergy bounds (min/max)
 * - issueWeights (ex: price -> 0.7)
 * - metadata (livre)
 *
 * Inclui métodos de compatibilidade:
 * - getProducts(): int[4] representando P1..P4 (legacy)
 * - getQuantitiesVector(): quantidades na ordem dos items
 * - construtor ProductBundle(int[] products) para compatibilidade com código legado/tests
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
        this.items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(b.items, "items")));
        this.synergyMin = b.synergyMin;
        this.synergyMax = b.synergyMax;
        this.issueWeights = Collections.unmodifiableMap(new LinkedHashMap<>(b.issueWeights));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
        validateState();
    }

    private void validateState() {
        if (synergyMin > synergyMax) throw new IllegalArgumentException("synergyMin > synergyMax");
        if (items.isEmpty()) throw new IllegalArgumentException("bundle must have at least one item");
        if (issueWeights.values().stream().anyMatch(d -> d == null || d < 0.0)) throw new IllegalArgumentException("weights must be >= 0");
    }

    // --- Compat constructor for legacy code/tests ---

    /**
     * Construtor compatível com código legado que chamava new ProductBundle(int[]).
     * Cria um ProductBundle simples com SKUs "P1".."P4" conforme quantidades.
     */
    public ProductBundle(int[] products) {
        this(builderFromVector(products));
    }

    private static Builder builderFromVector(int[] products) {
        if (products == null) throw new IllegalArgumentException("products vector cannot be null");
        int[] vec = new int[4];
        for (int i = 0; i < Math.min(products.length, 4); i++) vec[i] = Math.max(0, products[i]);

        Builder b = new Builder()
                .id("PB-" + UUID.randomUUID())
                .name("bundle-from-vector-" + UUID.randomUUID().toString())
                .synergyBounds(0.0, 1.0)
                .issueWeight("price", 0.7)
                .issueWeight("time", 0.3);

        if (vec[0] > 0) b.addItem("P1", vec[0]);
        if (vec[1] > 0) b.addItem("P2", vec[1]);
        if (vec[2] > 0) b.addItem("P3", vec[2]);
        if (vec[3] > 0) b.addItem("P4", vec[3]);

        boolean any = vec[0] > 0 || vec[1] > 0 || vec[2] > 0 || vec[3] > 0;
        if (!any) {
            // garantia mínima para passar na validação
            b.addItem("P1", 1);
        }
        return b;
    }

    // --- Getters / utilitários ---

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
     * Compatibilidade com código legado:
     * retorna int[4] com quantidades mapeadas para P1..P4.
     * Mapeia por igualdade (P1,P2,P3,P4) ou por substring (SKU-P1, ...).
     */
    public int[] getProducts() {
        int[] vec = new int[4]; // P1,P2,P3,P4
        if (items == null || items.isEmpty()) return vec;
        for (Item it : items) {
            if (it == null) continue;
            String sku = (it.getSku() == null) ? "" : it.getSku().trim().toUpperCase();
            int qty = Math.max(0, it.getQuantity());
            if ("P1".equals(sku)) { vec[0] += qty; continue; }
            if ("P2".equals(sku)) { vec[1] += qty; continue; }
            if ("P3".equals(sku)) { vec[2] += qty; continue; }
            if ("P4".equals(sku)) { vec[3] += qty; continue; }

            if (sku.contains("P1")) { vec[0] += qty; continue; }
            if (sku.contains("P2")) { vec[1] += qty; continue; }
            if (sku.contains("P3")) { vec[2] += qty; continue; }
            if (sku.contains("P4")) { vec[3] += qty; continue; }
            // outros SKUs são ignorados (compatibilidade)
        }
        return vec;
    }

    /**
     * Retorna vetor de quantidades na ordem dos items no bundle.
     */
    public int[] getQuantitiesVector() {
        if (items == null) return new int[0];
        int[] v = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            v[i] = (it == null) ? 0 : it.getQuantity();
        }
        return v;
    }

    /**
     * Score agregado usando os issueWeights do bundle.
     * Se featureValues não contém alguma issue, assume 0.
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

