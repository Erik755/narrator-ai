package com.erik.screenobserver;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in, offline Android 15/16 operating skill.
 * This is deliberately data/logic only so it can be unit tested without Android runtime.
 */
public final class AndroidSkillPack {
    public static final String SKILL_NAME = "Android 15 y 16";

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
                + "expuesto, utiliza OCR y coordenadas visibles como segundo método. Antes de acciones sensibles debe confirmar. "
                + "No intenta eludir FLAG_SECURE, bloqueos del sistema ni permisos que Android exija al usuario.";
    }

    /** Returns target plus useful visual/accessibility synonyms, ordered by preference. */
    public static List<String> aliasesForTarget(String target) {
        List<String> out = new ArrayList<>();
        if (target != null && !target.trim().isEmpty()) out.add(target.trim());
        String n = normalize(target);
        if (n.isEmpty()) return out;

        for (Map.Entry<String, List<String>> entry : CONTROL_ALIASES.entrySet()) {
            String key = entry.getKey();
            boolean match = n.equals(key) || n.contains(key);
            if (!match) {
                for (String alias : entry.getValue()) {
                    String a = normalize(alias);
                    if (n.equals(a) || (a.length() > 3 && n.contains(a))) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                for (String alias : entry.getValue()) {
                    if (!containsNormalized(out, alias)) out.add(alias);
                }
            }
        }
        return out;
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

    public static boolean looksLikeAndroidContext(String packageName, String screenText) {
        String p = normalize(packageName);
        String s = normalize(screenText);
        return p.startsWith("com android") || p.startsWith("com google android") ||
                s.contains("ajustes") || s.contains("settings") || s.contains("permiso") ||
                s.contains("permission") || s.contains("notificaciones") || s.contains("notifications");
    }

    private static boolean containsNormalized(List<String> values, String candidate) {
        String c = normalize(candidate);
        for (String v : values) if (normalize(v).equals(c)) return true;
        return false;
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }
}
