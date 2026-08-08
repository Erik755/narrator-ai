from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/IntentAgent.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v25_settings_phrase_fix: expected one {label}, found {count}")
    text = text.replace(old, new, 1)


replacements = [
    ('startsCommand(n, "ajustes de wifi", "configuracion de wifi", "abre wifi", "configura wifi", "redes wifi")',
     'startsCommand(n, "abre ajustes de wifi", "abre configuracion de wifi", "ajustes de wifi", "configuracion de wifi", "abre wifi", "configura wifi", "redes wifi")',
     'wifi settings phrases'),
    ('startsCommand(n, "ajustes de bluetooth", "configuracion de bluetooth", "abre bluetooth", "configura bluetooth")',
     'startsCommand(n, "abre ajustes de bluetooth", "abre configuracion de bluetooth", "ajustes de bluetooth", "configuracion de bluetooth", "abre bluetooth", "configura bluetooth")',
     'bluetooth settings phrases'),
    ('startsCommand(n, "ajustes de sonido", "configuracion de sonido", "ajustes de audio", "configuracion de audio")',
     'startsCommand(n, "abre ajustes de sonido", "abre configuracion de sonido", "abre ajustes de audio", "ajustes de sonido", "configuracion de sonido", "ajustes de audio", "configuracion de audio")',
     'sound settings phrases'),
    ('startsCommand(n, "ajustes de pantalla", "configuracion de pantalla", "ajustes de display")',
     'startsCommand(n, "abre ajustes de pantalla", "abre configuracion de pantalla", "ajustes de pantalla", "configuracion de pantalla", "ajustes de display")',
     'display settings phrases'),
    ('startsCommand(n, "ajustes de bateria", "configuracion de bateria", "ahorro de bateria")',
     'startsCommand(n, "abre ajustes de bateria", "abre configuracion de bateria", "ajustes de bateria", "configuracion de bateria", "ahorro de bateria")',
     'battery settings phrases'),
    ('startsCommand(n, "ajustes de ubicacion", "configuracion de ubicacion", "ajustes de localizacion")',
     'startsCommand(n, "abre ajustes de ubicacion", "abre configuracion de ubicacion", "ajustes de ubicacion", "configuracion de ubicacion", "ajustes de localizacion")',
     'location settings phrases'),
    ('startsCommand(n, "ajustes de aplicaciones", "configuracion de aplicaciones", "lista de aplicaciones", "administrar aplicaciones")',
     'startsCommand(n, "abre ajustes de aplicaciones", "abre configuracion de aplicaciones", "ajustes de aplicaciones", "configuracion de aplicaciones", "lista de aplicaciones", "administrar aplicaciones")',
     'apps settings phrases'),
    ('startsCommand(n, "ajustes de notificaciones", "configuracion de notificaciones")',
     'startsCommand(n, "abre ajustes de notificaciones", "abre configuracion de notificaciones", "ajustes de notificaciones", "configuracion de notificaciones")',
     'notification settings phrases'),
    ('startsCommand(n, "ajustes de seguridad", "configuracion de seguridad")',
     'startsCommand(n, "abre ajustes de seguridad", "abre configuracion de seguridad", "ajustes de seguridad", "configuracion de seguridad")',
     'security settings phrases'),
    ('startsCommand(n, "ajustes de accesibilidad", "configuracion de accesibilidad", "abre accesibilidad")',
     'startsCommand(n, "abre ajustes de accesibilidad", "abre configuracion de accesibilidad", "ajustes de accesibilidad", "configuracion de accesibilidad", "abre accesibilidad")',
     'accessibility settings phrases'),
]

for old, new, label in replacements:
    replace_once(old, new, label)

path.write_text(text, encoding="utf-8")
print("patch_v25_settings_phrase_fix: explicit settings-section phrases covered")
