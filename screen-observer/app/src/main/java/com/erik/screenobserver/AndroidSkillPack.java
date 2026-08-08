package com.erik.screenobserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in, offline Android 15/16 operating skill.
 * This is deliberately data/logic only so it can be unit tested without Android runtime.
 */
public final class AndroidSkillPack {
    /** Distinct system-owned name avoids overwriting a user-created legacy skill of the same name. */
    public static final String SKILL_NAME = "Android 15/16 — sistema";
    public static final int BUILTIN_VERSION = 3;

    private static final Map<String, List<String>> CONTROL_ALIASES = new LinkedHashMap<>();
    static {
        put("continuar", "Continuar", "Siguiente", "Next", "Continue", "Avanzar", "Listo", "Done");
        put("aceptar", "Aceptar", "OK", "De acuerdo", "Permitir", "Allow", "Confirmar", "Sí", "Yes");
        put("cancelar", "Cancelar", "Cancel", "No", "Denegar", "Deny", "Ahora no", "Not now");
        put("guardar", "Guardar", "Save", "Aplicar", "Apply", "Hecho", "Done");
        put("enviar", "Enviar", "Send", "Mandar", "Submit", "Publicar", "Post");
        put("buscar", "Buscar", "Search", "Búsqueda", "Search field");
        put("menu", "Menú", "Menu", "Más opciones", "More options", "Opciones");
        put("compartir", "Compartir", "Share");
        put("editar", "Editar", "Edit");
        put("eliminar", "Eliminar", "Borrar", "Delete", "Remove");
        put("cerrar", "Cerrar", "Close", "Descartar", "Dismiss");
        put("activar", "Activar", "On", "Encender", "Enable");
        put("desactivar", "Desactivar", "Off", "Apagar", "Disable");
        put("atras", "Atrás", "Back", "Regresar", "Volver");
    }

    private AndroidSkillPack() { }

    private static void put(String key, String... values) {
        CONTROL_ALIASES.put(key, Arrays.asList(values));
    }

    public static String builtInNotes() {
        return "Habilidad precargada para Android 15 y Android 16. "
                + "Interpreta botones, campos de texto, casillas, interruptores, listas, pestañas, diálogos, "
                + "menús, controles con descripción de contenido y acciones personalizadas expuestas por Accesibilidad. "
                + "Puede usar Atrás, Inicio, Recientes, notificaciones, ajustes rápidos, menú de energía y bloqueo de pantalla. "
                + "Para aplicaciones de terceros inspecciona dinámicamente el árbol de accesibilidad; si un control no está "
                + "expuesto, utiliza OCR y coordenadas visibles como segundo método únicamente cuando la captura corresponde "
                + "al display completo. Antes de acciones sensibles debe confirmar. No intenta eludir FLAG_SECURE, bloqueos "
                + "del sistema ni permisos que Android exija al usuario.";
    }

    /** Returns target plus the best matching Android control synonym family. */
    public static List<String> aliasesForTarget(String target) {
        List<String> out = new ArrayList<>();
        if (target != null && !target.trim().isEmpty()) out.add(target.trim());
        String n = normalize(target);
        if (n.isEmpty()) return out;

        Map.Entry<String, List<String>> best = null;
        int bestSpecificity = -1;
        for (Map.Entry<String, List<String>> entry : CONTROL_ALIASES.entrySet()) {
            String key = normalize(entry.getKey());
            boolean match = containsWholePhrase(n, key);
            if (!match) {
                for (String alias : entry.getValue()) {
                    String a = normalize(alias);
                    if (!a.isEmpty() && containsWholePhrase(n, a)) {
                        match = true;
                        break;
                    }
                }
            }
            if (match && key.length() > bestSpecificity) {
                best = entry;
                bestSpecificity = key.length();
            }
        }

        if (best != null) {
            for (String alias : best.getValue()) {
                if (!containsNormalized(out, alias)) out.add(alias);
            }
        }
        return out;
    }

    private static boolean containsWholePhrase(String value, String phrase) {
        if (value.equals(phrase)) return true;
        return (" " + value + " ").contains(" " + phrase + " ");
    }

    /** Converts short conversational imperatives to likely Android control labels. */
    public static String implicitControlTarget(String phrase) {
        String n = normalize(phrase);
        if (n.equals("continua") || n.equals("continuar") || n.equals("sigue") || n.equals("siguiente")) return "Continuar";
        if (n.equals("acepta") || n.equals("aceptar") || n.equals("confirmalo") || n.equals("confirma")) return "Aceptar";
        if (n.equals("cancela") || n.equals("cancelar")) return "Cancelar";
        if (n.equals("guarda") || n.equals("guardar") || n.equals("salva")) return "Guardar";
        if (n.equals("envia") || n.equals("enviar") || n.equals("mandalo")) return "Enviar";
        if (n.equals("comparte") || n.equals("compartir")) return "Compartir";
        if (n.equals("cierra") || n.equals("cerrar")) return "Cerrar";
        if (n.equals("menu") || n.equals("abre menu") || n.equals("abre el menu")) return "Más opciones";
        return "";
    }

    /** Only actual Android system surfaces should force the built-in skill active. */
    public static boolean looksLikeAndroidContext(String packageName, String screenText) {
        String p = normalize(packageName);
        return p.equals("com android settings")
                || p.startsWith("com android settings ")
                || p.equals("com android systemui")
                || p.startsWith("com android systemui ")
                || p.equals("com android permissioncontroller")
                || p.startsWith("com android permissioncontroller ")
                || p.equals("com google android permissioncontroller")
                || p.startsWith("com google android permissioncontroller ");
    }

    private static boolean containsNormalized(List<String> values, String candidate) {
        String c = normalize(candidate);
        for (String v : values) if (normalize(v).equals(c)) return true;
        return false;
    }

    public static String normalize(String value) {
        return TextNormalizer.normalize(value);
    }
}
