package com.actaflow.backend.expedientes.plantillas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.actaflow.backend.expedientes.plantillas.Lenguaje.Analisis;
import com.actaflow.contracts.common.v1.CalendarDate;
import com.actaflow.contracts.common.v1.Money;
import com.actaflow.contracts.templates.v1.DiagnosticSeverity;
import com.actaflow.contracts.templates.v1.EntitySource;
import com.actaflow.contracts.templates.v1.TemplateDiagnostic;
import com.actaflow.contracts.templates.v1.TemplateList;
import com.actaflow.contracts.templates.v1.TemplateObject;
import com.actaflow.contracts.templates.v1.TemplateValue;
import com.actaflow.contracts.templates.v1.TemplateVariable;
import com.actaflow.contracts.templates.v1.VariableType;

import org.junit.jupiter.api.Test;

/** Lenguaje de plantillas: numeros en letras, diagnosticos, variables y HTML. Sin base de datos. */
class LenguajeTest {

    private static TemplateValue texto(String t) {
        return TemplateValue.newBuilder().setText(t).build();
    }

    private static TemplateValue pesos(long unidades) {
        return TemplateValue.newBuilder().setMoney(Money.newBuilder().setCurrencyCode("COP").setUnits(unidades))
                .build();
    }

    private static TemplateValue entero(long n) {
        return TemplateValue.newBuilder().setInteger(n).build();
    }

    private static TemplateValue fecha(int a, int m, int d) {
        return TemplateValue.newBuilder().setDate(CalendarDate.newBuilder().setYear(a).setMonth(m).setDay(d)).build();
    }

    private static TemplateValue objeto(Map<String, TemplateValue> campos) {
        return TemplateValue.newBuilder().setObject(TemplateObject.newBuilder().putAllFields(campos)).build();
    }

    private static TemplateValue lista(TemplateValue... items) {
        return TemplateValue.newBuilder().setList(TemplateList.newBuilder().addAllItems(List.of(items))).build();
    }

    private static String html(String fuente, Map<String, TemplateValue> valores) {
        Analisis a = Lenguaje.analizar(fuente);
        assertFalse(a.tieneErrores(), () -> "errores inesperados: " + a.diagnosticos());
        return Documento.html(a.nodos(), valores);
    }

    private static List<String> errores(String fuente) {
        return Lenguaje.analizar(fuente).diagnosticos().stream()
                .filter(d -> d.getSeverity() == DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR)
                .map(d -> d.getLine() + ":" + d.getColumn() + " " + d.getMessage()).toList();
    }

    private static TemplateVariable variable(Analisis a, String ruta) {
        return a.variables().stream().filter(v -> v.getPath().equals(ruta)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------ en letras

    @Test
    void escribeNumerosEnLetrasComoEnLasActas() {
        assertEquals("cero", EnLetras.numero(0));
        assertEquals("veintiuno", EnLetras.numero(21));
        assertEquals("veintiún", EnLetras.delante(21));
        assertEquals("cien", EnLetras.numero(100));
        assertEquals("ciento uno", EnLetras.numero(101));
        assertEquals("ciento un mil", EnLetras.numero(101_000));
        assertEquals("treinta y un mil quinientos", EnLetras.numero(31_500));
        assertEquals("mil", EnLetras.numero(1000));
        assertEquals("un millón quinientos mil", EnLetras.numero(1_500_000));
        assertEquals("veintiún millones", EnLetras.numero(21_000_000));
        assertEquals("mil millones", EnLetras.numero(1_000_000_000));
        assertEquals("un billón dos", EnLetras.numero(1_000_000_000_002L));
        assertEquals("dos mil veintiséis", EnLetras.numero(2026));
        assertEquals("setecientos setenta y siete", EnLetras.numero(777));
        assertEquals("menos quince", EnLetras.numero(-15));
        assertTrue(EnLetras.pideDe(3_000_000));
        assertFalse(EnLetras.pideDe(3_500_000));
    }

    @Test
    void formateaDineroYFechasAlEstiloColombiano() {
        assertEquals("$1.500.000", Documento.moneda(pesos(1_500_000).getMoney()));
        assertEquals("$1.500.000,50", Documento.moneda(Money.newBuilder().setCurrencyCode("COP").setUnits(1_500_000)
                .setNanos(500_000_000).build()));
        assertEquals("USD 20,00", Documento.moneda(Money.newBuilder().setCurrencyCode("USD").setUnits(20).build()));
        assertEquals("un millón quinientos mil pesos ($1.500.000)",
                Documento.monedaEnLetras(pesos(1_500_000).getMoney()));
        assertEquals("un millón de pesos ($1.000.000)", Documento.monedaEnLetras(pesos(1_000_000).getMoney()));
        assertEquals("un peso ($1)", Documento.monedaEnLetras(pesos(1).getMoney()));
        assertEquals("veintiún pesos con cincuenta centavos ($21,50)", Documento.monedaEnLetras(Money.newBuilder()
                .setCurrencyCode("COP").setUnits(21).setNanos(500_000_000).build()));
        assertEquals("5 de noviembre de 2026", Documento.fecha(fecha(2026, 11, 5).getDate()));
        assertEquals("cinco (5) de noviembre de dos mil veintiséis (2026)",
                Documento.fechaEnLetras(fecha(2026, 11, 5).getDate()));
    }

    // ------------------------------------------------------------------------------ diagnosticos

    @Test
    void reportaLosErroresConLineaYColumnaEnEspanol() {
        assertEquals(List.of("2:1 Falta {% fin %} para el bloque abierto en la línea 2"),
                errores("Hola\n{% si acuerdo.total %}\nTexto"));
        assertEquals(List.of("1:5 {% fin %} sin un bloque abierto"), errores("Hola{% fin %}"));
        assertTrue(errores("{{ Acuerdo.Monto }}").getFirst().contains("Nombre de variable no válido"));
        assertTrue(errores("{{ acuerdo.monto | dinero }}").getFirst().contains("Formato desconocido: «dinero»"));
        assertTrue(errores("{% repetir %}{% fin %}").getFirst().contains("Etiqueta desconocida"));
        assertTrue(errores("{% si %}x{% fin %}").getFirst().contains("Condición no válida"));
        assertTrue(errores("{% si nombre > \"a\" %}x{% fin %}").getFirst().contains("solo se compara con == o !="));
        assertTrue(errores("{% sino %}").getFirst().contains("sin un {% si %} abierto"));
        assertTrue(errores("{% si a %}1{% sino %}2{% sino %}3{% fin %}").getFirst().contains("ya tiene un"));
        assertTrue(errores("{% para cuota en plan %}{% fin %}").getFirst().contains("Bloque para no válido"));
        assertTrue(errores("Texto {{ acuerdo.monto").getFirst().contains("Falta }}"));
        assertTrue(errores("{# sin cerrar").getFirst().contains("Falta #}"));
        assertTrue(errores("{{ }}").getFirst().contains("Variable vacía"));
        int n = Lenguaje.MAX_ANIDAMIENTO + 1;
        String anidado = "{% si a %}".repeat(n) + "{% fin %}".repeat(n);
        assertTrue(errores(anidado).getFirst().contains("Demasiados bloques anidados"));
    }

    @Test
    void advierteBloquesVaciosYTiposEnConflicto() {
        List<TemplateDiagnostic> d = Lenguaje.analizar("{{ x | moneda }}\n{{ x | fecha }}\n{% si y %}{% fin %}")
                .diagnosticos();
        assertTrue(d.stream().allMatch(x -> x.getSeverity() == DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING));
        assertEquals("2:1 «x» se usa como dinero y como fecha", d.get(0).getLine() + ":" + d.get(0).getColumn() + " "
                + d.get(0).getMessage());
        assertTrue(d.get(1).getMessage().contains("está vacío"));
    }

    // ------------------------------------------------------------------------------ variables

    @Test
    void deduceTipoEtiquetaOrigenYObligatoriedadDeLasVariables() {
        Analisis a = Lenguaje.analizar("""
                {{ caso.radicado }} {{ partes.convocante.nombre }} {{ partes.convocado }}
                {{ acuerdo.monto | moneda_en_letras }} {{ acuerdo.fecha_pago | fecha }}
                {% si acuerdo.cuotas > 0 %}{{ acuerdo.cuotas | numero }}{% fin %}
                {% si acuerdo.clausula_penal %}{{ acuerdo.clausula_penal | moneda }}{% fin %}
                {% para cada cuota en acuerdo.plan %}{{ cuota.valor | moneda }}{% fin %}
                {% si acuerdo.firmado == verdadero %}ok{% fin %}
                """);
        assertFalse(a.tieneErrores());
        assertEquals(List.of("caso.radicado", "partes.convocante.nombre", "partes.convocado", "acuerdo.monto",
                "acuerdo.fecha_pago", "acuerdo.cuotas", "acuerdo.clausula_penal", "acuerdo.plan", "acuerdo.firmado"),
                a.variables().stream().map(TemplateVariable::getPath).toList());
        assertEquals(VariableType.VARIABLE_TYPE_MONEY, variable(a, "acuerdo.monto").getType());
        assertEquals(EntitySource.ENTITY_SOURCE_AMOUNT, variable(a, "acuerdo.monto").getSource());
        assertEquals("Acuerdo: monto", variable(a, "acuerdo.monto").getLabel());
        assertTrue(variable(a, "acuerdo.monto").getRequired());
        assertEquals(VariableType.VARIABLE_TYPE_DATE, variable(a, "acuerdo.fecha_pago").getType());
        assertEquals(VariableType.VARIABLE_TYPE_INTEGER, variable(a, "acuerdo.cuotas").getType());
        assertFalse(variable(a, "acuerdo.cuotas").getRequired());
        assertEquals(VariableType.VARIABLE_TYPE_MONEY, variable(a, "acuerdo.clausula_penal").getType());
        assertEquals(EntitySource.ENTITY_SOURCE_PENALTY, variable(a, "acuerdo.clausula_penal").getSource());
        assertEquals(VariableType.VARIABLE_TYPE_LIST, variable(a, "acuerdo.plan").getType());
        assertEquals(VariableType.VARIABLE_TYPE_PARTY, variable(a, "partes.convocado").getType());
        assertEquals(EntitySource.ENTITY_SOURCE_PARTY, variable(a, "partes.convocante.nombre").getSource());
        assertEquals(EntitySource.ENTITY_SOURCE_CASE, variable(a, "caso.radicado").getSource());
        assertEquals(VariableType.VARIABLE_TYPE_BOOLEAN, variable(a, "acuerdo.firmado").getType());
    }

    // ------------------------------------------------------------------------------ HTML

    @Test
    void generaParrafosTitulosNegritaYMarcaLoQueFalta() {
        String html = html("""
                # ACTA No. {{ caso.radicado }}

                **Convocante:** {{ partes.convocante.nombre }}
                Convocado: {{ partes.convocado.nombre }}

                ## Acuerdo
                Monto: {{ acuerdo.monto | moneda_en_letras }}""",
                Map.of("caso.radicado", texto("CC-1"), "partes.convocante", objeto(Map.of("nombre", texto("Ana"))),
                        "acuerdo.monto", pesos(1_500_000)));
        assertEquals("""
                <h2>ACTA No. <span class="valor">CC-1</span></h2>
                <p><strong>Convocante:</strong> <span class="valor">Ana</span><br>Convocado: \
                <mark class="falta">[partes.convocado.nombre]</mark></p>
                <h3>Acuerdo</h3>
                <p>Monto: <span class="valor">un millón quinientos mil pesos ($1.500.000)</span></p>
                """, html);
    }

    @Test
    void escapaTodoElTextoYLosValores() {
        String html = html("<script>alert(1)</script> {{ x }} & **{{ y }}**", Map.of(
                "x", texto("<img src=x onerror=alert(1)> "),
                "y", texto("a**b\nc\u0007")));
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt; <span class=\"valor\">&lt;img src=x "
                + "onerror=alert(1)&gt; </span> &amp; <strong><span class=\"valor\">a**b c</span></strong></p>\n",
                html);
    }

    @Test
    void lasEtiquetasSolasEnSuLineaNoDejanLineasVacias() {
        String fuente = """
                Inicio
                {% si mostrar %}
                Clausula
                {% fin %}
                {# nota interna #}
                Final""";
        assertEquals("<p>Inicio<br>Clausula<br>Final</p>\n", html(fuente, Map.of("mostrar",
                TemplateValue.newBuilder().setBoolean(true).build())));
        assertEquals("<p>Inicio<br>Final</p>\n", html(fuente, Map.of()));
    }

    @Test
    void condicionesSinoYBuclesConElementosAnidados() {
        String fuente = """
                {% si acuerdo.cuotas > 1 %}
                En {{ acuerdo.cuotas | numero_en_letras }} cuotas:
                {% para cada cuota en acuerdo.plan %}
                {{ cuota.valor | moneda }} el {{ cuota.fecha | fecha }}{% si no cuota.ultima %};{% sino %}.{% fin %}
                {% fin %}
                {% sino %}
                En un solo pago.
                {% fin %}""";
        TemplateValue plan = lista(
                objeto(Map.of("valor", pesos(500_000), "fecha", fecha(2026, 12, 1))),
                objeto(Map.of("valor", pesos(500_000), "fecha", fecha(2027, 1, 1),
                        "ultima", TemplateValue.newBuilder().setBoolean(true).build())));
        String dos = html(fuente, Map.of("acuerdo.cuotas", entero(2), "acuerdo.plan", plan));
        assertTrue(dos.contains("En <span class=\"valor\">dos (2)</span> cuotas:<br><span class=\"valor\">$500.000"
                + "</span> el <span class=\"valor\">1 de diciembre de 2026</span>;<br>"), dos);
        assertTrue(dos.contains("1 de enero de 2027</span>.</p>"), dos);
        assertEquals("<p>En un solo pago.</p>\n", html(fuente, Map.of("acuerdo.cuotas", entero(1))));
        assertEquals("<p>En un solo pago.</p>\n", html(fuente, Map.of()));
        assertTrue(html("{% si estado == \"firmado\" %}si{% sino %}no{% fin %}", Map.of("estado", texto("firmado")))
                .contains("si"));
        assertTrue(html("{% si total >= 1000 %}alto{% fin %}", Map.of("total", pesos(1000))).contains("alto"));
    }

    @Test
    void mayusculasSobreOtroFormatoYValoresSinFormato() {
        assertEquals("<p><span class=\"valor\">UN MILLÓN DE PESOS ($1.000.000)</span></p>\n",
                html("{{ m | moneda_en_letras | mayusculas }}", Map.of("m", pesos(1_000_000))));
        assertEquals("<p><span class=\"valor\">sí</span> <span class=\"valor\">Ana, Beto</span></p>\n",
                html("{{ b }} {{ l }}", Map.of("b", TemplateValue.newBuilder().setBoolean(true).build(),
                        "l", lista(texto("Ana"), texto("Beto")))));
    }

    @Test
    void unaListaEnormeEnBuclesAnidadosSeCorta() {
        TemplateValue[] muchos = new TemplateValue[2000];
        java.util.Arrays.fill(muchos, texto("x".repeat(100)));
        TemplateValue grande = lista(muchos);
        Analisis a = Lenguaje.analizar("{% para cada a en l %}{% para cada b en l %}{{ b }}{% fin %}{% fin %}");
        assertThrows(Documento.DemasiadoGrande.class, () -> Documento.html(a.nodos(), Map.of("l", grande)));
    }

    // ------------------------------------------------------------------------------ semillas

    @Test
    void lasPlantillasDelSistemaNoTienenErrores() throws IOException {
        String sql;
        try (InputStream v4 = getClass().getResourceAsStream("/db/migration/V4__plantillas.sql")) {
            sql = new String(v4.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\\$fuente\\$(.*?)\\$fuente\\$", Pattern.DOTALL).matcher(sql);
        List<Analisis> fuentes = new ArrayList<>();
        while (m.find()) {
            fuentes.add(Lenguaje.analizar(m.group(1)));
        }
        assertEquals(3, fuentes.size());
        for (Analisis a : fuentes) {
            assertTrue(a.diagnosticos().isEmpty(), () -> "diagnosticos: " + a.diagnosticos());
            assertFalse(a.variables().isEmpty());
            assertFalse(Documento.html(a.nodos(), Map.of()).isEmpty());
        }
    }
}
