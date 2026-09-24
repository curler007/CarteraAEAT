package com.raul.bolsa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconocimiento del Activity Statement de Interactive Brokers.
 *
 * <p>Va aparte del resto de la importación porque no necesita ni base de datos ni contexto: es
 * lo que decide, antes de nada, a qué importador va a parar el fichero que sube el usuario. Y
 * equivocarse aquí es caro en las dos direcciones: no reconocerlo manda el extracto al lector del
 * formato propio, que lo rechaza con un error que no dice nada útil; reconocer de más se lleva
 * por delante los ficheros de los otros brokers.
 */
class InteractiveBrokersDetectionTest {

    private static List<List<String>> rows(String text) {
        return OperationCsvService.parse(text, ',');
    }

    private static List<List<String>> fixture(String name) throws IOException {
        return rows(Files.readString(Path.of("src/test/resources", name), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Reconoce el extracto por su estructura, no por la extensión ni por el nombre")
    void acceptsStatements() throws IOException {
        assertTrue(InteractiveBrokersCsvService.matches(fixture("ibkr-ejemplo.csv")));
        assertTrue(InteractiveBrokersCsvService.matches(fixture("ibkr-desglose-ordenes.csv")),
                "Un extracto con el desglose de ejecuciones sigue siendo el mismo formato");
        assertTrue(InteractiveBrokersCsvService.matches(fixture("ibkr-sin-instrumentos.csv")),
                "Sin la sección de instrumentos el fichero es reconocible igual: lo que no se "
                        + "puede es resolver el ISIN, y eso se dice después con un error claro");
    }

    @Test
    @DisplayName("Basta con la sección de operaciones, aunque falte la cabecera del extracto")
    void acceptsATradesSectionAlone() {
        assertTrue(InteractiveBrokersCsvService.matches(rows(
                "Trades,Header,DataDiscriminator,Asset Category,Currency,Symbol,Date/Time,Quantity\n"
                        + "Trades,Data,Order,Stocks,EUR,ITX,\"2025-06-12, 09:30:00\",100\n")));
    }

    @Test
    @DisplayName("No se queda con los ficheros de los otros formatos")
    void rejectsEverythingElse() {
        assertFalse(InteractiveBrokersCsvService.matches(rows(
                        "Fecha;Tipo;Ticker;ISIN;Broker;Cantidad;Total;Comision;Grupo AEAT;Notas\n"
                                + "05/08/2025;BUY;APPLE;US0378331005;Trade Republic;2;501;1;GROUP_3;\n")),
                "El CSV propio va por punto y coma y no tiene secciones");
        assertFalse(InteractiveBrokersCsvService.matches(rows(
                        "datetime,date,account_type,category,type,asset_class,name,symbol,shares\n"
                                + "2025-07-28T09:45:12,2025-07-28,SECURITIES,TRADING,BUY,STOCK,Apple,US0378331005,10\n")),
                "El fichero de Trade Republic también se separa por comas, pero no trae secciones");
        assertFalse(InteractiveBrokersCsvService.matches(rows("")),
                "Un fichero vacío no es de nadie");
        assertFalse(InteractiveBrokersCsvService.matches(rows("Trades,Data,Order,Stocks,EUR,ITX\n")),
                "Sin una cabecera que diga qué es cada columna no hay nada que leer");
    }
}
