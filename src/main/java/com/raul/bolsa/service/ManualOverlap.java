package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.web.dto.OperationForm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconoce, entre las operaciones que trae un fichero de broker, las que el usuario ya había
 * apuntado a mano.
 *
 * <p>Hace falta en la primera importación de cualquier broker: el fichero trae el histórico
 * completo, así que todo lo que el usuario llevaba tecleado desde que abrió la cuenta volvería a
 * entrar por segunda vez. Las operaciones ya importadas antes no pasan por aquí —esas se
 * reconocen por el identificador que el importador les dejó en las notas—, y la alternativa de
 * reemplazar la cartera entera se llevaría por delante las operaciones de los demás brokers.
 *
 * <p>La identidad es fecha, valor y sentido, sin el importe: al teclear una operación es fácil
 * haber redondeado la comisión de otra forma, y exigir que cuadre al céntimo haría que el
 * duplicado no se detectara justo en los casos en que más molesta.
 */
class ManualOverlap {

    /** Tope de ejecuciones de un mismo valor y día al buscar combinaciones (2^16 como máximo). */
    private static final int MAX_GROUP_FOR_SUBSET = 16;

    /** Cantidades ya registradas a mano de cada día, valor y sentido. */
    private final Map<String, List<BigDecimal>> quantities = new HashMap<>();

    /** Apunta una operación que el usuario ya tenía guardada sin haberla importado. */
    void add(Operation op) {
        quantities.computeIfAbsent(key(op.getDate(), op.getAssetName(), op.getType()),
                k -> new ArrayList<>()).add(op.getQuantity());
    }

    /**
     * Quita de {@code candidates} las operaciones que ya estaban en la cartera y devuelve cuántas
     * ha quitado.
     *
     * <p>Dentro de cada día, valor y sentido se emparejan primero las cantidades idénticas. Con lo
     * que quede, si el total del fichero coincide con el total ya registrado, se trata de la misma
     * orden apuntada de otra forma —una sola línea por varias ejecuciones— y se descarta entera.
     */
    int drop(List<OperationForm> candidates) {
        if (quantities.isEmpty()) return 0;

        Map<String, List<OperationForm>> byKey = new LinkedHashMap<>();
        for (OperationForm f : candidates) {
            byKey.computeIfAbsent(key(f.getDate(), f.getAssetName(), f.getType()),
                    k -> new ArrayList<>()).add(f);
        }

        List<OperationForm> drop = new ArrayList<>();
        for (Map.Entry<String, List<OperationForm>> entry : byKey.entrySet()) {
            List<BigDecimal> registered = quantities.get(entry.getKey());
            if (registered == null) continue;

            List<BigDecimal> pending = new ArrayList<>(registered);
            List<OperationForm> unmatched = new ArrayList<>();
            for (OperationForm f : entry.getValue()) {
                int i = indexOfQuantity(pending, f.getQuantity());
                if (i >= 0) {
                    pending.remove(i);
                    drop.add(f);
                } else {
                    unmatched.add(f);
                }
            }
            // Lo que queda del fichero puede ser el desglose de una operación agrupada: se busca,
            // para cada cantidad aún sin emparejar, el grupo de ejecuciones que suma justo eso.
            for (BigDecimal quantity : pending) {
                List<OperationForm> group = subsetSummingTo(unmatched, quantity);
                if (group.isEmpty()) continue;
                drop.addAll(group);
                unmatched.removeAll(group);
            }
        }

        // Por identidad: dos filas del fichero pueden ser iguales campo a campo y aun así ser
        // operaciones distintas, así que no vale eliminar "las que sean iguales a esta".
        candidates.removeIf(f -> drop.stream().anyMatch(d -> d == f));
        return drop.size();
    }

    private static int indexOfQuantity(List<BigDecimal> quantities, BigDecimal qty) {
        for (int i = 0; i < quantities.size(); i++) {
            if (quantities.get(i).compareTo(qty) == 0) return i;
        }
        return -1;
    }

    /**
     * Subconjunto de {@code forms} cuyas cantidades suman exactamente {@code target}, o vacío si
     * no hay ninguno. Se prueban todas las combinaciones, así que se limita a grupos pequeños:
     * son las ejecuciones de un mismo valor en un mismo día, que nunca son muchas. Con cantidades
     * de seis o más decimales, que una combinación sume el total por casualidad es despreciable.
     */
    private static List<OperationForm> subsetSummingTo(List<OperationForm> forms, BigDecimal target) {
        if (forms.isEmpty() || forms.size() > MAX_GROUP_FOR_SUBSET) return List.of();

        List<OperationForm> best = List.of();
        for (int mask = 1; mask < (1 << forms.size()); mask++) {
            BigDecimal sum = BigDecimal.ZERO;
            List<OperationForm> subset = new ArrayList<>();
            for (int i = 0; i < forms.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(forms.get(i));
                    sum = sum.add(forms.get(i).getQuantity());
                }
            }
            // Con el menor número de filas: es la lectura más conservadora del solapamiento.
            if (sum.compareTo(target) == 0 && (best.isEmpty() || subset.size() < best.size())) {
                best = subset;
            }
        }
        return best;
    }

    private static String key(LocalDate date, String isin, OperationType type) {
        return date + "|" + isin.trim().toUpperCase() + "|" + type;
    }
}
