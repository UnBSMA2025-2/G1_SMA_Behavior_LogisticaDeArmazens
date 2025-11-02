package mas.logic;

import mas.models.NegotiationResult;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * WinnerDeterminationService robusto que não depende de classes auxiliares estarem
 * presentes em tempo de compilação (usa reflection para extrair bundles/produtos).
 *
 * Funcionalidade:
 *  - Branch-and-Bound com upper bound por fornecedor (melhor oferta restante por fornecedor)
 *  - Verificação de cobertura de demanda (productDemand pode conter valores > 1)
 *  - Suporta vários formatos de representação de "products" retornados pelos modelos:
 *      int[], Integer[], List<Integer>, List<Number>, Map<Integer,Integer>
 */
public class WinnerDeterminationService {

    private List<NegotiationResult> allResults;
    private int[] productDemand; // demanda por produto (pode conter valores > 1)
    private double maxUtility;
    private List<NegotiationResult> bestCombination;

    public WinnerDeterminationService() {
        // construtor vazio
    }

    /**
     * Entry point: resolve o WDP com Branch-and-Bound.
     *
     * @param results lista de negotiation results (ofertas finais)
     * @param productDemand array com demanda de cada índice de produto (ex.: [2,0,1,...])
     * @return a melhor combinação de NegotiationResult que satisfaz demanda e maximiza utilidade.
     */
    public List<NegotiationResult> solveWDPWithBranchAndBound(List<NegotiationResult> results, int[] productDemand) {
        if (results == null) return new ArrayList<>();
        this.allResults = new ArrayList<>(results);
        this.productDemand = (productDemand == null) ? new int[0] : productDemand.clone();
        this.maxUtility = Double.NEGATIVE_INFINITY;
        this.bestCombination = new ArrayList<>();

        // ordenar por utilidade decrescente (bom para poda)
        Collections.sort(this.allResults, new Comparator<NegotiationResult>() {
            @Override
            public int compare(NegotiationResult o1, NegotiationResult o2) {
                return Double.compare(o2.getUtility(), o1.getUtility());
            }
        });

        // caso trivial: se não há demanda, retorna combinação vazia com utilidade 0
        boolean hasDemand = false;
        for (int d : this.productDemand) if (d > 0) { hasDemand = true; break; }
        if (!hasDemand) {
            this.maxUtility = 0.0;
            this.bestCombination = new ArrayList<>();
            return this.bestCombination;
        }

        // começar busca recursiva
        branchAndBound(0, 0.0, new ArrayList<>(), new HashSet<String>());

        // se nenhuma combinação válida encontrada, retorna lista vazia
        if (this.maxUtility == Double.NEGATIVE_INFINITY) {
            return new ArrayList<>();
        }
        return this.bestCombination;
    }

    /**
     * Busca recursiva com poda. Mantém conjunto de fornecedores já usados (cada supplier no máximo 1 vez).
     */
    private void branchAndBound(int index, double currentUtility, List<NegotiationResult> currentCombination, Set<String> usedSuppliers) {
        // cálculo de upper bound mais forte: soma a melhor utilidade por fornecedor disponível
        double potentialUtility = currentUtility;
        Map<String, Double> supplierBest = new HashMap<>();
        for (int i = index; i < allResults.size(); i++) {
            NegotiationResult r = allResults.get(i);
            if (r == null) continue;
            String sname = safeGetSupplierName(r);
            if (sname == null) continue;
            if (usedSuppliers.contains(sname)) continue; // se fornecedor já usado, não pode contar
            supplierBest.merge(sname, r.getUtility(), Math::max);
        }
        for (Double v : supplierBest.values()) potentialUtility += v;

        // poda
        if (potentialUtility <= this.maxUtility) {
            return;
        }

        // se chegamos ao fim da lista, checar demanda
        if (index >= allResults.size()) {
            if (satisfiesDemand(currentCombination)) {
                // se válido, atualiza melhor solução
                if (currentUtility > this.maxUtility) {
                    this.maxUtility = currentUtility;
                    this.bestCombination = new ArrayList<>(currentCombination);
                }
            }
            return;
        }

        // ramo: incluir o resultado atual (se possível)
        NegotiationResult current = allResults.get(index);
        if (current != null) {
            String supplier = safeGetSupplierName(current);
            if (supplier != null && !usedSuppliers.contains(supplier)) {
                usedSuppliers.add(supplier);
                currentCombination.add(current);

                branchAndBound(index + 1, currentUtility + current.getUtility(), currentCombination, usedSuppliers);

                // desfazer
                currentCombination.remove(currentCombination.size() - 1);
                usedSuppliers.remove(supplier);
            }
        }

        // ramo: excluir o resultado atual
        branchAndBound(index + 1, currentUtility, currentCombination, usedSuppliers);
    }

    /**
     * Tenta extrair o nome do fornecedor de NegotiationResult de forma defensiva.
     */
    private String safeGetSupplierName(NegotiationResult r) {
        if (r == null) return null;
        try {
            // assume que existe getSupplierName()
            Method m = r.getClass().getMethod("getSupplierName");
            Object o = m.invoke(r);
            return (o != null) ? o.toString() : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // tenta outros nomes comuns
            try {
                Method m2 = r.getClass().getMethod("getSellerName");
                Object o = m2.invoke(r);
                return (o != null) ? o.toString() : null;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * Verifica se a combinação de NegotiationResult cobre a demanda productDemand.
     * Usa reflection para obter um vetor de quantidades por produto (int[]).
     */
    private boolean satisfiesDemand(List<NegotiationResult> combination) {
        if (this.productDemand == null || this.productDemand.length == 0) {
            // sem demanda -> considerado satisfeito por definição (ou ajustar conforme regra)
            return true;
        }

        int[] covered = new int[this.productDemand.length];

        for (NegotiationResult r : combination) {
            if (r == null) continue;

            int[] products = extractProductsArray(r);
            if (products == null) continue;

            int len = Math.min(products.length, covered.length);
            for (int i = 0; i < len; i++) {
                covered[i] += products[i];
            }
        }

        // verificar se cobrimos cada demanda
        for (int i = 0; i < this.productDemand.length; i++) {
            if (this.productDemand[i] > 0) {
                if (covered[i] < this.productDemand[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Tenta extrair a representação de produtos (quantidades por índice) de um NegotiationResult.
     * Suporta várias formas de retorno: int[], Integer[], List<Integer>, List<Number>, Map<Integer,Integer>.
     * Retorna null se não encontrou nada utilizável.
     */
    private int[] extractProductsArray(NegotiationResult r) {
        // tentativas em ordem de preferência:
        // 1) result.getProductBundle().getProducts()
        // 2) result.getFinalBid().getProductBundle().getProducts()
        // 3) result.getProducts()
        // 4) result.getBundle() / getProductMap()

        // helper: try method chain of arbitrary length and return object
        Object o;

        // 1) getProductBundle().getProducts()
        o = tryInvokeChain(r, new String[] {"getProductBundle", "getProducts"});
        if (o == null) {
            // 2) getFinalBid().getProductBundle().getProducts()
            o = tryInvokeChain(r, new String[] {"getFinalBid", "getProductBundle", "getProducts"});
        }
        if (o == null) {
            // 3) getProducts()
            o = tryInvokeChain(r, new String[] {"getProducts"});
        }
        if (o == null) {
            // 4) getBundle().getProducts()
            o = tryInvokeChain(r, new String[] {"getBundle", "getProducts"});
        }
        if (o == null) {
            // 5) getProductMap() -> Map<Integer,Integer>
            o = tryInvokeChain(r, new String[] {"getProductMap"});
        }

        if (o == null) return null;

        // normalize to int[]
        if (o.getClass().isArray()) {
            Class<?> component = o.getClass().getComponentType();
            if (component == int.class) {
                return (int[]) o;
            } else if (component == Integer.class) {
                Integer[] arr = (Integer[]) o;
                int[] res = new int[arr.length];
                for (int i = 0; i < arr.length; i++) res[i] = (arr[i] != null) ? arr[i] : 0;
                return res;
            } else {
                // try convert other primitive/object arrays to ints via reflection
                int n = Array.getLength(o);
                int[] res = new int[n];
                for (int i = 0; i < n; i++) {
                    Object elem = Array.get(o, i);
                    res[i] = (elem instanceof Number) ? ((Number) elem).intValue() : 0;
                }
                return res;
            }
        } else if (o instanceof List) {
            List<?> list = (List<?>) o;
            int[] res = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                if (elem instanceof Number) res[i] = ((Number) elem).intValue();
                else {
                    try { res[i] = Integer.parseInt(elem.toString()); }
                    catch (Exception e) { res[i] = 0; }
                }
            }
            return res;
        } else if (o instanceof Map) {
            // assume Map<Integer,Integer> where key=index and value=quantity
            Map<?,?> map = (Map<?,?>) o;
            // find max key
            int max = -1;
            for (Object k : map.keySet()) {
                if (k instanceof Number) {
                    int kk = ((Number) k).intValue();
                    if (kk > max) max = kk;
                } else {
                    try {
                        int kk = Integer.parseInt(k.toString());
                        if (kk > max) max = kk;
                    } catch (Exception ignore) {}
                }
            }
            if (max < 0) return null;
            int[] res = new int[max+1];
            for (Map.Entry<?,?> e : map.entrySet()) {
                int kk;
                if (e.getKey() instanceof Number) kk = ((Number) e.getKey()).intValue();
                else {
                    try { kk = Integer.parseInt(e.getKey().toString()); } catch (Exception ex) { continue; }
                }
                int vv = 0;
                Object val = e.getValue();
                if (val instanceof Number) vv = ((Number) val).intValue();
                else {
                    try { vv = Integer.parseInt(val.toString()); } catch (Exception ex) { vv = 0; }
                }
                if (kk >= 0 && kk < res.length) res[kk] = vv;
            }
            return res;
        } else {
            // try to parse numeric string like "0,1,0,2"
            String s = o.toString();
            if (s.contains(",")) {
                String[] parts = s.split(",");
                int[] res = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    try { res[i] = Integer.parseInt(parts[i].trim()); } catch (Exception ex) { res[i] = 0; }
                }
                return res;
            }
        }

        return null;
    }

    /**
     * Tenta invocar uma cadeia de métodos (por nomes) sobre um objeto base.
     * Ex: tryInvokeChain(obj, ["getFinalBid","getProductBundle","getProducts"])
     * Retorna o objeto final ou null se qualquer step falhar.
     */
    private Object tryInvokeChain(Object base, String[] methodNames) {
        Object cur = base;
        for (String mname : methodNames) {
            if (cur == null) return null;
            try {
                Method m = cur.getClass().getMethod(mname);
                cur = m.invoke(cur);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                return null;
            }
        }
        return cur;
    }

    /**
     * Opcional: retorna a utilidade máxima encontrada (Double.NEGATIVE_INFINITY se nenhuma solução).
     */
    public double getMaxUtility() {
        return this.maxUtility;
    }
}

