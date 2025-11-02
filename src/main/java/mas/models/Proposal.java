package mas.models;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Representa uma proposta (ou contraproposta), que é um conjunto de múltiplos Bids.
 * Permite que um agente faça ofertas para diferentes pacotes de produtos de uma só vez.
 *
 * A classe é imutável (internamente) e serializável.
 */
public class Proposal implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<Bid> bids;

    public Proposal(List<Bid> bids) {
        if (bids == null) {
            this.bids = Collections.emptyList();
        } else {
            // cópia defensiva
            List<Bid> copy = new ArrayList<>(bids.size());
            for (Bid b : bids) {
                copy.add(Objects.requireNonNull(b, "bid in list"));
            }
            this.bids = Collections.unmodifiableList(copy);
        }
    }

    public List<Bid> getBids() {
        return bids;
    }

    public boolean isEmpty() {
        return bids.isEmpty();
    }

    public int size() {
        return bids.size();
    }

    /**
     * Obtém todos os bundleIds presentes nesta proposta.
     */
    public Set<String> getAllBundleIds() {
        return bids.stream()
                .map(Bid::getBundleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Retorna uma lista de bids que correspondem ao bundleId informado.
     */
    public List<Bid> getBidsForBundle(String bundleId) {
        if (bundleId == null) return Collections.emptyList();
        return bids.stream()
                .filter(b -> bundleId.equals(b.getBundleId()))
                .collect(Collectors.toList());
    }

    /**
     * Soma total das quantidades de todos os bids.
     */
    public int getTotalQuantityAllBids() {
        int sum = 0;
        for (Bid b : bids) sum += b.getTotalQuantity();
        return sum;
    }

    /**
     * Mapa bundleId -> soma de quantidade para aquele bundle dentro desta proposta.
     */
    public Map<String, Integer> getQuantityPerBundle() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Bid b : bids) {
            String id = b.getBundleId();
            map.put(id, map.getOrDefault(id, 0) + b.getTotalQuantity());
        }
        return map;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Proposal containing ").append(bids.size()).append(" bid(s):\n");
        for (Bid bid : bids) {
            sb.append("---\n");
            sb.append(bid.toString()).append("\n");
        }
        sb.append("---");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Proposal)) return false;
        Proposal proposal = (Proposal) o;
        return Objects.equals(bids, proposal.bids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bids);
    }
}

