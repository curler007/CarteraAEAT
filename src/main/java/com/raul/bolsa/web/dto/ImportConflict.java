package com.raul.bolsa.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Una operación del fichero que ya está en la cartera —el mismo uid— pero con algún dato distinto.
 *
 * <p>No se decide por ella. Una fila idéntica se omite sin preguntar, porque no hay nada que
 * elegir; una que ha cambiado puede ser una corrección hecha en la hoja de cálculo o un fichero
 * viejo que revertiría cambios recientes, y desde dentro del importador las dos se ven igual.
 * Así que se para la importación entera, se enseña campo a campo qué cambiaría, y elige quien
 * sabe cuál de los dos es el bueno.
 *
 * @param differences solo los campos que difieren; los iguales no aportan nada a la decisión
 */
public record ImportConflict(String uid, LocalDate date, String ticker, String isin,
                             List<FieldDiff> differences) {

    /** Un campo que no coincide, con los dos valores ya formateados para enseñarlos. */
    public record FieldDiff(String field, String current, String incoming) {}
}
