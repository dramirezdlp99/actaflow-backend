package com.actaflow.backend.expedientes.plantillas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.actaflow.contracts.templates.v1.DiagnosticSeverity;
import com.actaflow.contracts.templates.v1.EntitySource;
import com.actaflow.contracts.templates.v1.TemplateDiagnostic;
import com.actaflow.contracts.templates.v1.TemplateVariable;
import com.actaflow.contracts.templates.v1.VariableType;

/**
 * Lenguaje de plantillas de ActaFlow.
 *
 * <pre>
 *   {{ partes.convocante.nombre }}                      variable
 *   {{ acuerdo.monto | moneda_en_letras }}              variable con formato
 *   {% si acuerdo.cuotas &gt; 0 %} ... {% sino %} ... {% fin %}   condicional
 *   {% para cada cuota en acuerdo.plan %} ... {% fin %}  clausula repetible
 *   {# comentario #}
 * </pre>
 *
 * <p>Una etiqueta {@code {% %}} o un comentario solos en su linea no dejan la linea vacia. El
 * analisis tolera errores: siempre devuelve un arbol (para la vista previa) junto a los
 * diagnosticos.
 */
public final class Lenguaje {

    /** Bloques {@code si} y {@code para} anidados como maximo. */
    public static final int MAX_ANIDAMIENTO = 20;

    /** Formatos disponibles despues de {@code |}. */
    public static final List<String> FORMATOS = List.of("moneda", "moneda_en_letras", "fecha", "fecha_en_letras",
            "numero", "numero_en_letras", "mayusculas", "minusculas");

    static final Pattern RUTA = Pattern.compile("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*$");
    private static final Pattern CONDICION = Pattern.compile(
            "^(no\\s+)?(\\S+?)\\s*(?:(==|!=|>=|<=|>|<)\\s*(-?\\d{1,18}|\"[^\"]*\"|verdadero|falso))?$");
    private static final Pattern PARA = Pattern.compile("^cada\\s+(\\S+)\\s+en\\s+(\\S+)$");
    private static final Pattern NOMBRE = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** Nodo del arbol. */
    public sealed interface Nodo permits Texto, Valor, Si, Para {
    }

    /** Texto literal. */
    public record Texto(String texto) implements Nodo {
    }

    /** {@code {{ ruta | formato }}}. */
    public record Valor(String ruta, List<String> formatos, int linea, int columna) implements Nodo {
    }

    /** {@code {% si %}}; sin condicion valida nunca se cumple. */
    public record Si(Condicion condicion, List<Nodo> entonces, List<Nodo> sino) implements Nodo {
    }

    /** {@code {% para cada variable en lista %}}; sin cabecera valida no repite nada. */
    public record Para(String variable, String lista, List<Nodo> cuerpo, int linea, int columna) implements Nodo {
    }

    /**
     * Condicion de un {@code si}: la ruta sola (tiene valor), negada, o comparada con un literal
     * ({@link Long}, {@link String} o {@link Boolean}).
     */
    public record Condicion(String ruta, boolean negada, String operador, Object literal, int linea, int columna) {
    }

    /** Resultado del analisis. */
    public record Analisis(List<Nodo> nodos, List<TemplateDiagnostic> diagnosticos,
                           List<TemplateVariable> variables) {

        public boolean tieneErrores() {
            return diagnosticos.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR);
        }

        /** El primer error, para el mensaje del fallo. */
        public TemplateDiagnostic primerError() {
            return diagnosticos.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR)
                    .findFirst().orElseThrow();
        }
    }

    private Lenguaje() {
    }

    public static Analisis analizar(String fuente) {
        Analizador analizador = new Analizador(fuente.replace("\r\n", "\n").replace('\r', '\n'));
        List<Nodo> nodos = analizador.ejecutar();
        List<TemplateVariable> variables = new Variables(analizador).recorrer(nodos);
        List<TemplateDiagnostic> diagnosticos = new ArrayList<>(analizador.diagnosticos);
        diagnosticos.sort(Comparator.comparingInt(TemplateDiagnostic::getLine)
                .thenComparingInt(TemplateDiagnostic::getColumn));
        return new Analisis(List.copyOf(nodos), List.copyOf(diagnosticos), variables);
    }

    // --------------------------------------------------------------------------------- analisis

    /** Bloque abierto. */
    private static final class Marco {
        final boolean esSi;
        final int linea;
        final int columna;
        final Condicion condicion;
        final String variable;
        final String lista;
        final List<Nodo> entonces = new ArrayList<>();
        final List<Nodo> sino = new ArrayList<>();
        boolean enSino;

        Marco(boolean esSi, int linea, int columna, Condicion condicion, String variable, String lista) {
            this.esSi = esSi;
            this.linea = linea;
            this.columna = columna;
            this.condicion = condicion;
            this.variable = variable;
            this.lista = lista;
        }

        List<Nodo> destino() {
            return enSino ? sino : entonces;
        }

        Nodo cerrar() {
            return esSi ? new Si(condicion, List.copyOf(entonces), List.copyOf(sino))
                    : new Para(variable, lista, List.copyOf(entonces), linea, columna);
        }
    }

    private static final class Analizador {
        private final String f;
        private final List<Integer> inicios = new ArrayList<>();
        final List<TemplateDiagnostic> diagnosticos = new ArrayList<>();
        private final List<Nodo> raiz = new ArrayList<>();
        private final Deque<Marco> pila = new ArrayDeque<>();
        private final StringBuilder texto = new StringBuilder();

        Analizador(String fuente) {
            this.f = fuente;
            inicios.add(0);
            for (int i = 0; i < f.length(); i++) {
                if (f.charAt(i) == '\n') {
                    inicios.add(i + 1);
                }
            }
        }

        int linea(int posicion) {
            int bajo = 0;
            int alto = inicios.size() - 1;
            while (bajo < alto) {
                int medio = (bajo + alto + 1) >>> 1;
                if (inicios.get(medio) <= posicion) {
                    bajo = medio;
                } else {
                    alto = medio - 1;
                }
            }
            return bajo + 1;
        }

        int columna(int posicion) {
            return posicion - inicios.get(linea(posicion) - 1) + 1;
        }

        void diagnostico(DiagnosticSeverity severidad, int posicion, String mensaje) {
            diagnosticos.add(TemplateDiagnostic.newBuilder().setSeverity(severidad).setLine(linea(posicion))
                    .setColumn(columna(posicion)).setMessage(mensaje).build());
        }

        void error(int posicion, String mensaje) {
            diagnostico(DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR, posicion, mensaje);
        }

        void advertencia(int posicion, String mensaje) {
            diagnostico(DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING, posicion, mensaje);
        }

        private List<Nodo> destino() {
            return pila.isEmpty() ? raiz : pila.peek().destino();
        }

        private void soltarTexto() {
            if (!texto.isEmpty()) {
                destino().add(new Texto(texto.toString()));
                texto.setLength(0);
            }
        }

        private int siguienteApertura(int desde) {
            int i = f.indexOf('{', desde);
            while (i >= 0 && i + 1 < f.length()) {
                char c = f.charAt(i + 1);
                if (c == '{' || c == '%' || c == '#') {
                    return i;
                }
                i = f.indexOf('{', i + 1);
            }
            return -1;
        }

        List<Nodo> ejecutar() {
            int i = 0;
            int n = f.length();
            while (i < n) {
                int j = siguienteApertura(i);
                if (j < 0) {
                    texto.append(f, i, n);
                    break;
                }
                texto.append(f, i, j);
                char tipo = f.charAt(j + 1);
                String cierre = tipo == '{' ? "}}" : tipo == '%' ? "%}" : "#}";
                int k = f.indexOf(cierre, j + 2);
                if (k < 0) {
                    String que = tipo == '{' ? "la variable" : tipo == '%' ? "la etiqueta" : "el comentario";
                    error(j, "Falta " + cierre + " para cerrar " + que + " abierta en la línea " + linea(j));
                    texto.append(f, j, n);
                    break;
                }
                int fin = k + 2;
                if (tipo != '{') {
                    fin = recortarLinea(j, fin);
                }
                soltarTexto();
                String dentro = f.substring(j + 2, k).strip();
                switch (tipo) {
                    case '{' -> variable(j, dentro);
                    case '%' -> etiqueta(j, dentro);
                    default -> {
                        // Comentario: no deja nada.
                    }
                }
                i = fin;
            }
            soltarTexto();
            while (!pila.isEmpty()) {
                Marco abierto = pila.pop();
                error(inicios.get(abierto.linea - 1) + abierto.columna - 1,
                        "Falta {% fin %} para el bloque abierto en la línea " + abierto.linea);
                destino().add(abierto.cerrar());
            }
            return raiz;
        }

        /** Si la etiqueta esta sola en su linea, la linea entera desaparece del resultado. */
        private int recortarLinea(int inicio, int fin) {
            int inicioLinea = f.lastIndexOf('\n', inicio - 1) + 1;
            if (!f.substring(inicioLinea, inicio).isBlank()) {
                return fin;
            }
            int e = fin;
            while (e < f.length() && f.charAt(e) != '\n' && Character.isWhitespace(f.charAt(e))) {
                e++;
            }
            if (e < f.length() && f.charAt(e) != '\n') {
                return fin;
            }
            int sobrante = inicio - inicioLinea;
            if (sobrante <= texto.length()) {
                texto.setLength(texto.length() - sobrante);
            }
            return e < f.length() ? e + 1 : e;
        }

        private void variable(int posicion, String dentro) {
            if (dentro.isEmpty()) {
                error(posicion, "Variable vacía: escribe un nombre entre {{ y }}");
                return;
            }
            String[] partes = dentro.split("\\|", -1);
            String ruta = partes[0].strip();
            boolean valida = true;
            if (!RUTA.matcher(ruta).matches()) {
                error(posicion, "Nombre de variable no válido: «" + ruta + "». Usa minúsculas, números y guiones "
                        + "bajos, separados por puntos (por ejemplo acuerdo.monto)");
                valida = false;
            }
            List<String> formatos = new ArrayList<>();
            for (int i = 1; i < partes.length; i++) {
                String formato = partes[i].strip();
                if (formato.isEmpty()) {
                    error(posicion, "Falta el nombre del formato después de |");
                    valida = false;
                } else if (!FORMATOS.contains(formato)) {
                    error(posicion, "Formato desconocido: «" + formato + "». Disponibles: "
                            + String.join(", ", FORMATOS));
                    valida = false;
                } else {
                    formatos.add(formato);
                }
            }
            if (valida) {
                destino().add(new Valor(ruta, List.copyOf(formatos), linea(posicion), columna(posicion)));
            }
        }

        private void etiqueta(int posicion, String dentro) {
            if (dentro.isEmpty()) {
                error(posicion, "Etiqueta vacía: usa si, sino, fin o para cada");
                return;
            }
            String[] partes = dentro.split("\\s+", 2);
            String palabra = partes[0];
            String resto = partes.length > 1 ? partes[1].strip() : "";
            switch (palabra) {
                case "si" -> abrirSi(posicion, resto);
                case "sino" -> sino(posicion, resto);
                case "fin" -> fin(posicion, resto);
                case "para" -> abrirPara(posicion, resto);
                default -> error(posicion, "Etiqueta desconocida: {% " + palabra
                        + " %}. Usa si, sino, fin o para cada");
            }
        }

        private void abrir(int posicion, Marco marco) {
            if (pila.size() >= MAX_ANIDAMIENTO) {
                error(posicion, "Demasiados bloques anidados (máximo " + MAX_ANIDAMIENTO + ")");
            }
            pila.push(marco);
        }

        private void abrirSi(int posicion, String resto) {
            Condicion condicion = null;
            Matcher m = CONDICION.matcher(resto);
            if (resto.isEmpty() || !m.matches()) {
                error(posicion, "Condición no válida: «" + resto + "». Ejemplos: {% si acuerdo.cuotas > 0 %},"
                        + " {% si no acuerdo.total %}");
            } else if (!RUTA.matcher(m.group(2)).matches()) {
                error(posicion, "Nombre de variable no válido en la condición: «" + m.group(2) + "»");
            } else {
                Object literal = null;
                String texto = m.group(4);
                if (texto != null) {
                    literal = switch (texto) {
                        case "verdadero" -> Boolean.TRUE;
                        case "falso" -> Boolean.FALSE;
                        default -> texto.startsWith("\"") ? texto.substring(1, texto.length() - 1)
                                : Long.valueOf(texto);
                    };
                    if (literal instanceof String && !"==".equals(m.group(3)) && !"!=".equals(m.group(3))) {
                        error(posicion, "Un texto solo se compara con == o !=");
                    }
                    if (m.group(1) != null) {
                        error(posicion, "Usa «no» solo sin comparación: {% si no acuerdo.total %}");
                    }
                }
                condicion = new Condicion(m.group(2), m.group(1) != null, m.group(3), literal, linea(posicion),
                        columna(posicion));
            }
            abrir(posicion, new Marco(true, linea(posicion), columna(posicion), condicion, null, null));
        }

        private void abrirPara(int posicion, String resto) {
            Matcher m = PARA.matcher(resto);
            String variable = null;
            String lista = null;
            if (!m.matches()) {
                error(posicion, "Bloque para no válido. Escribe {% para cada cuota en acuerdo.plan %}");
            } else if (!NOMBRE.matcher(m.group(1)).matches()) {
                error(posicion, "Nombre no válido para el elemento: «" + m.group(1) + "»");
            } else if (!RUTA.matcher(m.group(2)).matches()) {
                error(posicion, "Nombre de lista no válido: «" + m.group(2) + "»");
            } else {
                variable = m.group(1);
                lista = m.group(2);
                for (Marco abierto : pila) {
                    if (variable.equals(abierto.variable)) {
                        advertencia(posicion, "«" + variable + "» ya nombra el elemento de un para cada exterior");
                    }
                }
            }
            abrir(posicion, new Marco(false, linea(posicion), columna(posicion), null, variable, lista));
        }

        private void sino(int posicion, String resto) {
            if (!resto.isEmpty()) {
                error(posicion, "{% sino %} no lleva condición; usa otro {% si %} dentro");
            }
            Marco arriba = pila.peek();
            if (arriba == null || !arriba.esSi) {
                error(posicion, "{% sino %} sin un {% si %} abierto");
            } else if (arriba.enSino) {
                error(posicion, "Este {% si %} ya tiene un {% sino %}");
            } else {
                arriba.enSino = true;
            }
        }

        private void fin(int posicion, String resto) {
            if (!resto.isEmpty()) {
                error(posicion, "{% fin %} no lleva nada más");
            }
            if (pila.isEmpty()) {
                error(posicion, "{% fin %} sin un bloque abierto");
                return;
            }
            Marco cerrado = pila.pop();
            if (vacio(cerrado.entonces) && vacio(cerrado.sino)) {
                advertencia(posicion, "El bloque abierto en la línea " + cerrado.linea + " está vacío");
            }
            destino().add(cerrado.cerrar());
        }

        private static boolean vacio(List<Nodo> nodos) {
            return nodos.stream().allMatch(n -> n instanceof Texto t && t.texto().isBlank());
        }
    }

    // -------------------------------------------------------------------------------- variables

    /** Recorre el arbol y deduce el tipo, la etiqueta y el origen de cada variable de primer nivel. */
    private static final class Variables {
        private final Analizador analizador;
        private final Map<String, VariableType> tipos = new LinkedHashMap<>();
        private final Map<String, Boolean> requeridas = new LinkedHashMap<>();

        Variables(Analizador analizador) {
            this.analizador = analizador;
        }

        List<TemplateVariable> recorrer(List<Nodo> nodos) {
            visitar(nodos, Set.of(), false);
            List<TemplateVariable> variables = new ArrayList<>();
            tipos.forEach((ruta, tipo) -> {
                String[] s = ruta.split("\\.");
                if (tipo == VariableType.VARIABLE_TYPE_TEXT && s.length == 2 && "partes".equals(s[0])) {
                    tipo = VariableType.VARIABLE_TYPE_PARTY;
                }
                variables.add(TemplateVariable.newBuilder().setPath(ruta).setType(tipo).setLabel(etiqueta(s))
                        .setRequired(requeridas.get(ruta)).setSource(origen(s, tipo)).build());
            });
            return List.copyOf(variables);
        }

        private void visitar(List<Nodo> nodos, Set<String> locales, boolean condicional) {
            for (Nodo nodo : nodos) {
                switch (nodo) {
                    case Texto t -> {
                        // Nada que registrar.
                    }
                    case Valor v -> registrar(v.ruta(), tipoPorFormatos(v.formatos()), !condicional, locales,
                            v.linea(), v.columna());
                    case Si s -> {
                        if (s.condicion() != null) {
                            Condicion c = s.condicion();
                            registrar(c.ruta(), tipoPorLiteral(c.literal()), false, locales, c.linea(), c.columna());
                        }
                        visitar(s.entonces(), locales, true);
                        visitar(s.sino(), locales, true);
                    }
                    case Para p -> {
                        Set<String> dentro = locales;
                        if (p.lista() != null) {
                            registrar(p.lista(), VariableType.VARIABLE_TYPE_LIST, !condicional, locales, p.linea(),
                                    p.columna());
                            dentro = new HashSet<>(locales);
                            dentro.add(p.variable());
                        }
                        visitar(p.cuerpo(), dentro, true);
                    }
                }
            }
        }

        private void registrar(String ruta, VariableType tipo, boolean requerida, Set<String> locales, int linea,
                               int columna) {
            if (locales.contains(ruta.split("\\.", 2)[0])) {
                return;
            }
            requeridas.merge(ruta, requerida, Boolean::logicalOr);
            VariableType antes = tipos.get(ruta);
            if (antes == null || (!fuerte(antes) && (fuerte(tipo) || tipo == VariableType.VARIABLE_TYPE_TEXT))) {
                tipos.put(ruta, tipo);
            } else if (fuerte(antes) && fuerte(tipo) && antes != tipo) {
                analizador.diagnosticos.add(TemplateDiagnostic.newBuilder()
                        .setSeverity(DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING).setLine(linea).setColumn(columna)
                        .setMessage("«" + ruta + "» se usa como " + nombre(antes) + " y como " + nombre(tipo))
                        .build());
            }
        }

        private static boolean fuerte(VariableType tipo) {
            return tipo != VariableType.VARIABLE_TYPE_TEXT && tipo != VariableType.VARIABLE_TYPE_BOOLEAN;
        }

        private static VariableType tipoPorFormatos(List<String> formatos) {
            VariableType tipo = VariableType.VARIABLE_TYPE_TEXT;
            for (String formato : formatos) {
                if (formato.startsWith("moneda")) {
                    tipo = VariableType.VARIABLE_TYPE_MONEY;
                } else if (formato.startsWith("fecha")) {
                    tipo = VariableType.VARIABLE_TYPE_DATE;
                } else if (formato.startsWith("numero")) {
                    tipo = VariableType.VARIABLE_TYPE_INTEGER;
                }
            }
            return tipo;
        }

        private static VariableType tipoPorLiteral(Object literal) {
            return switch (literal) {
                case Long l -> VariableType.VARIABLE_TYPE_INTEGER;
                case String s -> VariableType.VARIABLE_TYPE_TEXT;
                case null, default -> VariableType.VARIABLE_TYPE_BOOLEAN;
            };
        }

        private static String nombre(VariableType tipo) {
            return switch (tipo) {
                case VARIABLE_TYPE_MONEY -> "dinero";
                case VARIABLE_TYPE_DATE -> "fecha";
                case VARIABLE_TYPE_INTEGER -> "número";
                case VARIABLE_TYPE_LIST -> "lista";
                case VARIABLE_TYPE_PARTY -> "parte";
                case VARIABLE_TYPE_BOOLEAN -> "sí o no";
                default -> "texto";
            };
        }

        private static String etiqueta(String[] s) {
            String ultimo = s[s.length - 1].replace('_', ' ').strip();
            String texto = s.length >= 2 ? mayuscula(s[s.length - 2].replace('_', ' ').strip()) + ": " + ultimo
                    : mayuscula(ultimo);
            return texto.length() > 120 ? texto.substring(0, 120) : texto;
        }

        private static String mayuscula(String texto) {
            return texto.isEmpty() ? texto : texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
        }

        private static EntitySource origen(String[] s, VariableType tipo) {
            String todo = String.join(".", s);
            if ("partes".equals(s[0])) {
                return EntitySource.ENTITY_SOURCE_PARTY;
            }
            if (Set.of("caso", "expediente", "audiencia").contains(s[0])) {
                return EntitySource.ENTITY_SOURCE_CASE;
            }
            if (todo.contains("plazo") || todo.contains("vencimiento")) {
                return EntitySource.ENTITY_SOURCE_DEADLINE;
            }
            if (todo.contains("penal") || todo.contains("multa")) {
                return EntitySource.ENTITY_SOURCE_PENALTY;
            }
            if (todo.contains("entrega") || todo.contains("compromiso")) {
                return EntitySource.ENTITY_SOURCE_COMMITMENT;
            }
            return tipo == VariableType.VARIABLE_TYPE_MONEY ? EntitySource.ENTITY_SOURCE_AMOUNT
                    : EntitySource.ENTITY_SOURCE_UNSPECIFIED;
        }
    }
}
