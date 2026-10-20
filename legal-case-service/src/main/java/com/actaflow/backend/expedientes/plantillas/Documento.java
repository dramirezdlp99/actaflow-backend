package com.actaflow.backend.expedientes.plantillas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.actaflow.backend.expedientes.plantillas.Lenguaje.Condicion;
import com.actaflow.backend.expedientes.plantillas.Lenguaje.Nodo;
import com.actaflow.backend.expedientes.plantillas.Lenguaje.Para;
import com.actaflow.backend.expedientes.plantillas.Lenguaje.Si;
import com.actaflow.backend.expedientes.plantillas.Lenguaje.Texto;
import com.actaflow.backend.expedientes.plantillas.Lenguaje.Valor;
import com.actaflow.contracts.common.v1.CalendarDate;
import com.actaflow.contracts.common.v1.Money;
import com.actaflow.contracts.templates.v1.TemplateValue;

/**
 * Convierte el arbol de una plantilla en HTML. Todo el texto (el de la plantilla y los valores) se
 * escapa; las unicas etiquetas son las que se generan aqui: {@code h2}, {@code h3}, {@code p},
 * {@code br}, {@code strong}, {@code span class="valor"} y {@code mark class="falta"}.
 *
 * <p>Formato del texto: una linea en blanco separa parrafos, una linea que empieza con {@code # } o
 * {@code ## } al inicio de un parrafo es un titulo, y {@code **texto**} va en negrita. Un valor que falta se ve como
 * {@code [ruta]} resaltado, para completarlo antes de cerrar el acta.
 */
public final class Documento {

    /** Limite del texto generado, para que una lista enorme en un bucle no agote la memoria. */
    public static final int MAX_CARACTERES = 1_000_000;

    // Marcas internas (uso privado de Unicode) que se vuelven etiquetas despues de escapar.
    private static final char VALOR = '';
    private static final char FIN_VALOR = '';
    private static final char FALTA = '';
    private static final char FIN_FALTA = '';
    private static final char ASTERISCO = '';
    private static final Pattern MARCAS = Pattern.compile("[\\uE000-\\uE004]");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern PARRAFOS = Pattern.compile("\\n[ \\t]*\\n\\s*");
    private static final Pattern NEGRITA = Pattern.compile("\\*\\*([^*\\n]+?)\\*\\*");
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    /** El documento resulto demasiado grande. */
    public static final class DemasiadoGrande extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DemasiadoGrande() {
            super("El documento supera " + MAX_CARACTERES + " caracteres");
        }
    }

    private final Map<String, TemplateValue> valores;
    private final StringBuilder plano = new StringBuilder();

    private Documento(Map<String, TemplateValue> valores) {
        this.valores = valores;
    }

    /** HTML del documento con estos valores (por ruta de variable). */
    public static String html(List<Nodo> nodos, Map<String, TemplateValue> valores) {
        Documento documento = new Documento(valores);
        documento.escribir(nodos, Map.of());
        return documento.bloques();
    }

    // -------------------------------------------------------------------------------- recorrido

    private void agregar(CharSequence texto) {
        plano.append(texto);
        if (plano.length() > MAX_CARACTERES) {
            throw new DemasiadoGrande();
        }
    }

    private void escribir(List<Nodo> nodos, Map<String, TemplateValue> locales) {
        for (Nodo nodo : nodos) {
            switch (nodo) {
                case Texto t -> agregar(limpiar(t.texto()));
                case Valor v -> {
                    Optional<TemplateValue> valor = buscar(v.ruta(), locales);
                    if (valor.isPresent()) {
                        String texto = limpiar(formatear(valor.get(), v.formatos())).replace('\n', ' ')
                                .replace('*', ASTERISCO);
                        agregar(VALOR + texto + FIN_VALOR);
                    } else {
                        agregar(FALTA + "[" + v.ruta() + "]" + FIN_FALTA);
                    }
                }
                case Si s -> escribir(cumple(s.condicion(), locales) ? s.entonces() : s.sino(), locales);
                case Para p -> {
                    if (p.lista() == null) {
                        continue;
                    }
                    Optional<TemplateValue> lista = buscar(p.lista(), locales);
                    if (lista.isEmpty() || !lista.get().hasList()) {
                        continue;
                    }
                    for (TemplateValue elemento : lista.get().getList().getItemsList()) {
                        Map<String, TemplateValue> dentro = new HashMap<>(locales);
                        dentro.put(p.variable(), elemento);
                        escribir(p.cuerpo(), dentro);
                    }
                }
            }
        }
    }

    private static String limpiar(String texto) {
        return CONTROL.matcher(MARCAS.matcher(texto).replaceAll("")).replaceAll("");
    }

    /**
     * Busca la ruta primero entre los elementos de los bucles y luego entre los valores: la clave
     * mas larga que coincide con el inicio de la ruta, y desde ahi por los campos de los objetos.
     */
    private Optional<TemplateValue> buscar(String ruta, Map<String, TemplateValue> locales) {
        String[] s = ruta.split("\\.");
        if (locales.containsKey(s[0])) {
            return bajar(locales.get(s[0]), s, 1);
        }
        for (int k = s.length; k >= 1; k--) {
            TemplateValue valor = valores.get(String.join(".", java.util.Arrays.copyOfRange(s, 0, k)));
            if (valor != null) {
                return bajar(valor, s, k);
            }
        }
        return Optional.empty();
    }

    private static Optional<TemplateValue> bajar(TemplateValue valor, String[] s, int desde) {
        TemplateValue actual = valor;
        for (int i = desde; i < s.length; i++) {
            if (!actual.hasObject() || !actual.getObject().containsFields(s[i])) {
                return Optional.empty();
            }
            actual = actual.getObject().getFieldsOrThrow(s[i]);
        }
        return Optional.of(actual);
    }

    // -------------------------------------------------------------------------------- condiciones

    private boolean cumple(Condicion c, Map<String, TemplateValue> locales) {
        if (c == null) {
            return false;
        }
        Optional<TemplateValue> valor = buscar(c.ruta(), locales);
        if (c.operador() == null) {
            return c.negada() != valor.map(Documento::tieneValor).orElse(false);
        }
        if (valor.isEmpty()) {
            return false;
        }
        TemplateValue v = valor.get();
        return switch (c.literal()) {
            case Long n -> numero(v).map(x -> comparar(x.compareTo(BigDecimal.valueOf(n)), c.operador()))
                    .orElse(false);
            case String texto -> v.hasText() && ("==".equals(c.operador()) == v.getText().equals(texto));
            case Boolean b -> v.hasBoolean() && ("==".equals(c.operador()) == (v.getBoolean() == b));
            case null, default -> false;
        };
    }

    private static boolean comparar(int resultado, String operador) {
        return switch (operador) {
            case "==" -> resultado == 0;
            case "!=" -> resultado != 0;
            case ">" -> resultado > 0;
            case "<" -> resultado < 0;
            case ">=" -> resultado >= 0;
            default -> resultado <= 0;
        };
    }

    private static Optional<BigDecimal> numero(TemplateValue v) {
        if (v.hasInteger()) {
            return Optional.of(BigDecimal.valueOf(v.getInteger()));
        }
        if (v.hasMoney()) {
            return Optional.of(decimal(v.getMoney()));
        }
        return Optional.empty();
    }

    private static boolean tieneValor(TemplateValue v) {
        return switch (v.getKindCase()) {
            case TEXT -> !v.getText().isBlank();
            case MONEY -> v.getMoney().getUnits() != 0 || v.getMoney().getNanos() != 0;
            case INTEGER -> v.getInteger() != 0;
            case BOOLEAN -> v.getBoolean();
            case LIST -> v.getList().getItemsCount() > 0;
            case OBJECT -> v.getObject().getFieldsCount() > 0;
            case DATE -> true;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------------- formatos

    static String formatear(TemplateValue v, List<String> formatos) {
        String texto = null;
        for (String formato : formatos) {
            texto = switch (formato) {
                case "moneda" -> dinero(v).map(Documento::moneda).orElse(texto);
                case "moneda_en_letras" -> dinero(v).map(Documento::monedaEnLetras).orElse(texto);
                case "fecha" -> v.hasDate() ? fecha(v.getDate()) : texto;
                case "fecha_en_letras" -> v.hasDate() ? fechaEnLetras(v.getDate()) : texto;
                case "numero" -> v.hasInteger() ? agrupar(v.getInteger()) : texto;
                case "numero_en_letras" -> v.hasInteger()
                        ? EnLetras.numero(v.getInteger()) + " (" + agrupar(v.getInteger()) + ")" : texto;
                case "mayusculas" -> (texto == null ? mostrar(v) : texto).toUpperCase(ES);
                case "minusculas" -> (texto == null ? mostrar(v) : texto).toLowerCase(ES);
                default -> texto;
            };
        }
        return texto == null ? mostrar(v) : texto;
    }

    /** Como se ve un valor sin formato. */
    static String mostrar(TemplateValue v) {
        return switch (v.getKindCase()) {
            case TEXT -> v.getText();
            case MONEY -> moneda(v.getMoney());
            case DATE -> fecha(v.getDate());
            case INTEGER -> agrupar(v.getInteger());
            case BOOLEAN -> v.getBoolean() ? "sí" : "no";
            case OBJECT -> {
                TemplateValue nombre = v.getObject().getFieldsMap().get("nombre");
                yield nombre != null && nombre.hasText() ? nombre.getText()
                        : v.getObject().getFieldsMap().values().stream().map(Documento::mostrar)
                                .collect(Collectors.joining(", "));
            }
            case LIST -> v.getList().getItemsList().stream().map(Documento::mostrar).collect(Collectors.joining(", "));
            default -> "";
        };
    }

    private static Optional<Money> dinero(TemplateValue v) {
        if (v.hasMoney()) {
            return Optional.of(v.getMoney());
        }
        if (v.hasInteger()) {
            return Optional.of(Money.newBuilder().setCurrencyCode("COP").setUnits(v.getInteger()).build());
        }
        return Optional.empty();
    }

    private static BigDecimal decimal(Money m) {
        return BigDecimal.valueOf(m.getUnits()).add(BigDecimal.valueOf(m.getNanos(), 9));
    }

    static String agrupar(long n) {
        String digitos = Long.toString(Math.abs(n));
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < digitos.length(); i++) {
            if (i > 0 && (digitos.length() - i) % 3 == 0) {
                texto.append('.');
            }
            texto.append(digitos.charAt(i));
        }
        return (n < 0 ? "-" : "") + texto;
    }

    /** $1.500.000 o $1.500.000,50; otras monedas con su codigo: USD 1.500,00. */
    static String moneda(Money m) {
        BigDecimal valor = decimal(m).setScale(2, RoundingMode.HALF_UP);
        long entero = valor.abs().longValue();
        int centavos = valor.abs().remainder(BigDecimal.ONE).movePointRight(2).intValue();
        boolean pesos = "COP".equals(m.getCurrencyCode()) || m.getCurrencyCode().isEmpty();
        String cifra = agrupar(entero) + (centavos != 0 || !pesos ? "," + String.format("%02d", centavos) : "");
        String signo = valor.signum() < 0 ? "-" : "";
        return pesos ? signo + "$" + cifra : signo + m.getCurrencyCode() + " " + cifra;
    }

    /** "un millon quinientos mil pesos ($1.500.000)". */
    static String monedaEnLetras(Money m) {
        BigDecimal valor = decimal(m).setScale(2, RoundingMode.HALF_UP);
        long entero = valor.abs().longValue();
        int centavos = valor.abs().remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String[] nombre = switch (m.getCurrencyCode()) {
            case "", "COP" -> new String[] {"peso", "pesos"};
            case "USD" -> new String[] {"dólar", "dólares"};
            case "EUR" -> new String[] {"euro", "euros"};
            default -> new String[] {m.getCurrencyCode(), m.getCurrencyCode()};
        };
        StringBuilder texto = new StringBuilder(valor.signum() < 0 ? "menos " : "");
        texto.append(EnLetras.delante(entero)).append(EnLetras.pideDe(entero) ? " de " : " ")
                .append(entero == 1 ? nombre[0] : nombre[1]);
        if (centavos != 0) {
            texto.append(" con ").append(EnLetras.delante(centavos)).append(centavos == 1 ? " centavo" : " centavos");
        }
        return texto.append(" (").append(moneda(m)).append(')').toString();
    }

    /** "5 de noviembre de 2026". */
    static String fecha(CalendarDate d) {
        if (d.getMonth() < 1 || d.getMonth() > 12) {
            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
        }
        return d.getDay() + " de " + MESES[d.getMonth() - 1] + " de " + d.getYear();
    }

    /** "cinco (5) de noviembre de dos mil veintiseis (2026)". */
    static String fechaEnLetras(CalendarDate d) {
        if (d.getMonth() < 1 || d.getMonth() > 12) {
            return fecha(d);
        }
        return EnLetras.numero(d.getDay()) + " (" + d.getDay() + ") de " + MESES[d.getMonth() - 1] + " de "
                + EnLetras.numero(d.getYear()) + " (" + d.getYear() + ")";
    }

    // -------------------------------------------------------------------------------- HTML

    private String bloques() {
        StringBuilder html = new StringBuilder();
        for (String bloque : PARRAFOS.split(plano.toString().strip())) {
            String b = bloque.strip();
            if (b.isEmpty()) {
                continue;
            }
            if (b.startsWith("# ") || b.startsWith("## ")) {
                int salto = b.indexOf('\n');
                String titulo = salto < 0 ? b : b.substring(0, salto);
                String etiqueta = titulo.startsWith("## ") ? "h3" : "h2";
                etiqueta(html, etiqueta, titulo.substring(etiqueta.equals("h3") ? 3 : 2).strip());
                b = salto < 0 ? "" : b.substring(salto + 1).strip();
                if (b.isEmpty()) {
                    continue;
                }
            }
            etiqueta(html, "p", b);
        }
        return html.toString();
    }

    private static void etiqueta(StringBuilder html, String etiqueta, String texto) {
        String cuerpo = NEGRITA.matcher(escapar(texto)).replaceAll("<strong>$1</strong>")
                .replace("\n", "<br>")
                .replace(String.valueOf(VALOR), "<span class=\"valor\">")
                .replace(String.valueOf(FIN_VALOR), "</span>")
                .replace(String.valueOf(FALTA), "<mark class=\"falta\">")
                .replace(String.valueOf(FIN_FALTA), "</mark>")
                .replace(String.valueOf(ASTERISCO), "*");
        html.append('<').append(etiqueta).append('>').append(cuerpo).append("</").append(etiqueta).append(">\n");
    }

    private static String escapar(String texto) {
        StringBuilder s = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '&' -> s.append("&amp;");
                case '<' -> s.append("&lt;");
                case '>' -> s.append("&gt;");
                case '"' -> s.append("&quot;");
                case '\'' -> s.append("&#39;");
                default -> s.append(c);
            }
        }
        return s.toString();
    }
}
