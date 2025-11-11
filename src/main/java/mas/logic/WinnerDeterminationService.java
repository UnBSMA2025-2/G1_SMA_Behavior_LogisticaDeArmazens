package mas.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mas.models.NegotiationResult;

public class WinnerDeterminationService {

    private List<NegotiationResult> bestCombination;
    private double maxUtility;
    private int[] productDemand;

    /**
     * Resolve o Problema de Determinação do Vencedor (WDP) usando Branch-and-Bound.
     * Encontra a combinação de lances que maximiza a utilidade total, sujeita às restrições.
     * @param results A lista de todos os lances finais bem-sucedidos.
     * @param productDemand Um array indicando os produtos requeridos (ex: [1, 1, 0, 1]).
     * @return A lista de lances que compõem a solução ótima.
     */
    public List<NegotiationResult> solveWDPWithBranchAndBound(List<NegotiationResult> results, int[] productDemand) {
        this.bestCombination = new ArrayList<>();
        this.maxUtility = 0.0;
        this.productDemand = productDemand;
        results.sort(Comparator.comparingDouble(NegotiationResult::getUtility).reversed());
        branchAndBoundRecursive(results, 0, new ArrayList<>(), 0.0, new HashSet<>());

        return this.bestCombination;
    }

    /**
     * Função recursiva que implementa a lógica de Branch-and-Bound.
     * @param allResults Lista de todos os resultados (ordenados).
     * @param index O índice do resultado que estamos considerando atualmente.
     * @param currentCombination A combinação parcial de lances neste nó da árvore.
     * @param currentUtility A utilidade da combinação parcial.
     * @param usedSuppliers Um conjunto para rastrear fornecedores já incluídos na combinação.
     */
    private void branchAndBoundRecursive(List<NegotiationResult> allResults, int index,
                                         List<NegotiationResult> currentCombination, double currentUtility,
                                         Set<String> usedSuppliers) {
        double potentialUtility = currentUtility;
        for (int i = index; i < allResults.size(); i++) {
            potentialUtility += allResults.get(i).getUtility();
        }
        if (potentialUtility <= maxUtility) {
            return;
        }
        if (index == allResults.size()) {
            if (satisfiesDemand(currentCombination) && currentUtility > maxUtility) {
                this.maxUtility = currentUtility;
                this.bestCombination = new ArrayList<>(currentCombination);
            }
            return;
        }

        NegotiationResult currentResult = allResults.get(index);
        if (!usedSuppliers.contains(currentResult.getSupplierName())) {
            currentCombination.add(currentResult);
            usedSuppliers.add(currentResult.getSupplierName());
            branchAndBoundRecursive(allResults, index + 1, currentCombination,
                                    currentUtility + currentResult.getUtility(), usedSuppliers);
            usedSuppliers.remove(currentResult.getSupplierName());
            currentCombination.remove(currentCombination.size() - 1);
        }
        branchAndBoundRecursive(allResults, index + 1, currentCombination, currentUtility, usedSuppliers);
    }

    /**
     * Verifica se uma combinação de lances satisfaz a demanda de todos os produtos requeridos.
     * Implementa a restrição da Equação 9 do artigo.
     */
    private boolean satisfiesDemand(List<NegotiationResult> combination) {
        int[] coveredDemand = new int[this.productDemand.length];
        for (NegotiationResult result : combination) {
            int[] productsInBundle = result.getFinalBid().getProductBundle().getProducts();
            for (int i = 0; i < productsInBundle.length; i++) {
                if (productsInBundle[i] == 1) {
                    coveredDemand[i] = 1;
                }
            }
        }

        for (int i = 0; i < this.productDemand.length; i++) {
            if (this.productDemand[i] == 1 && coveredDemand[i] == 0) {
                return false; // Se um produto requerido não foi coberto, a demanda não é satisfeita.
            }
        }
        return true;
    }
}
