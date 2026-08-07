# Cabecera generada: pega el bloque completo dentro de native-lib.cpp en el
# lugar de xor_cipher/getNativeString (marcadores XOR_SECTION_BEGIN/END).
#
# Ofuscación XOR multi-byte: cada byte usa una posición de la clave,
# así el cifrado ya no es reversible con un xor de un solo byte (0x55).
XOR_KEY = bytes([0xF3, 0x71, 0x29, 0xA4, 0x0C, 0x6B, 0xD8, 0x52])


def xor_encrypt(s, key=XOR_KEY):
    data = s.encode("utf-8")
    # Cifrar TODOS los bytes y AGREGAR un byte 0x00 al final.
    # El C++ usa sizeof(s) - 1 = exactamente los bytes del mensaje,
    # y el último 0x00 nunca se descifra (queda como terminador C).
    # Con un array [] += [0] todos los mensajes salen con byte corrupto.
    return [data[i] ^ key[i % len(key)] for i in range(len(data))] + [0]


strings = {
    1: "https://licenciasfreezy.vercel.app/api/keys/verify",
    2: "INICIAR FREEZY",
    3: "Validando conexion y licencia...",
    4: "Lanzando motor Freezy...",
    5: "Acceso Concedido",
    6: "Licencia invalida o inexistente. Adquiere una oficial.",
    7: "Por favor, completa todos los campos.",
    8: "VERIFICANDO...",
    9: "Cerrar Burbuja",
    10: "Tipo de Activacion",
    11: "Informacion de Licencia",
    12: "!! DESCARGO DE RESPONSABILIDAD",
    13: "Si bien esta herramienta NO altera los archivos originales del juego, te otorga una ventaja extrema.\n\n⚠️ Uso de Datos y Dispositivo:\nSolicitamos acceso al 'Uso de Datos' para monitorear la ejecución del juego y activar las funciones correctamente. También almacenamos el nombre de tu dispositivo para la detección y prevención de fallas técnicas específicas reportadas anteriormente en modelos similares.\n\nEl uso abusivo puede causar baneos. El uso de esta herramienta es bajo tu propia responsabilidad.",
    14: "AJUSTES",
    15: "ACEPTO EL RIESGO",
    16: "Freezy Activo",
    17: "Toca la burbuja para activar",
    18: "No-Recoil: OFF",
    19: "Efectividad: ",
    20: "Radio FOV: ",
    21: "Fake Lag Activado",
    22: "Error al obtener permisos Root",
    23: "Fake Lag Desactivado",
    24: "Error de conexion. Sesion cerrada.",
    25: "Licencia Expirada",
    26: "REGISTROS (LOGS)",
    27: "CERRAR",
    28: "LIMPIAR",
    29: "Registros limpiados.",
    30: "VER REGISTROS (LOGS)",
    31: "CERRAR SESION",
    32: "ROOT DETECTADO",
    33: "ROOT NO DETECTADO",
    34: "Root Permitido",
    35: "Root Denegado",
    36: "Version actual",
    37: "Por favor, otorga acceso de uso a Freezy",
    38: "FREEZY MENU",
    39: "NoRecoil External",
    40: "FOV External",
    41: "Auto",
    42: "Custom",
    43: "Manual",
    44: "Segundos a Congelar",
    45: " Segundos",
    46: "Activacion:",
    47: "Expiracion:",
    48: "SISTEMA",
    49: "Permitir Root",
    50: "INFORMACION",
    51: "CUENTA Y SOPORTE",
    52: "FREEZY",
    53: "USUARIO",
    54: "Ingresa tu usuario",
    55: "LICENCIA",
    56: "Pega tu licencia aqui",
    57: "INGRESAR",
    58: "Necesitas dar permiso para mostrar sobre otras apps.",
    59: "Permiso Root no disponible o denegado.",
    60: "Validando licencia...",
    61: "ACCESO CONCEDIDO",
    62: "ACTUALIZACION",
    63: "ENTENDIDO",
    64: "Datos incompletos.",
    65: "Error de conexion al iniciar.",
    66: "Modo Root activado.",
    67: "Acceso Root denegado.",
    68: "Otorga el permiso de superposicion.",
    69: "Otorga el permiso de acceso de uso.",
    70: "Free Fire no detectado.",
    71: "Debugger detectado.",
    72: "Por favor espera...",
    73: "JUEGO OBJETIVO",
    74: "Free Fire",
    75: "FF MAX",
    76: "AJUSTES DE RED (QoS)",
    77: "Jitter Buffer",
    78: "Descarte de Paquetes",
    # Pines TLS actuales del servidor (Sha256 SPKI, Base64), separados por
    # comas. WebSecurity los usa SOLO si la lista no está vacía.
    # Pines TLS actuales del servidor (Sha256 SPKI, Base64), separados por comas.
    # verificado 2026-08-03: leaf=vercel.app intermedio=...
    79: "ft9JFh9fyiSD0LI4vCAyVHDM1OKStfDBooxsWHHvngY=,yDu9og255NN5GEf+Bwa9rTrqFQ0EydZ0r1FCh9TdAW4=",
    80: "Para evitar desincronizacion (antiban en gama baja), selecciona SIN RESTRICCIONES en el ahorro de bateria para Freezy.",
    81: "Entorno no seguro. Cerrando.",
    # ── Strings sensibles (comandos, rutas y paquetes) ──────────────
    82: "su",
    83: "id\n",
    84: "exit\n",
     85: "iptables -D INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG",
     86: "iptables -F FREEZY_FAKELAG",
     87: "iptables -X FREEZY_FAKELAG",
     88: "iptables -N FREEZY_FAKELAG",
     89: "iptables -I INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG",
     90: "iptables -A FREEZY_FAKELAG -p udp -m length --length 0:60 -j ACCEPT",
     111: "iptables -A FREEZY_FAKELAG -p udp -m length --length 61:1500 -m limit --limit 3/sec --limit-burst 1 -j ACCEPT",
     112: "iptables -A FREEZY_FAKELAG -j DROP",
    91: "STOP_VPN",
    92: "TARGET_PACKAGE",
    93: "FreezyProxy",
    94: "10.0.0.2",
    95: "0.0.0.0",
    96: "/system/bin/su,/system/xbin/su,/sbin/su,/system/su,/su/bin/su,/data/local/xbin/su,/data/local/bin/su,/system/sd/xbin/su,/data/local/su",
    97: "com.topjohnwu.magisk,eu.chainfire.supersu,me.weishu.kernelsu,com.kingroot.kinguser,com.koushikdutta.superuser,com.noshufou.android.su,com.yellowes.su,io.github.vvb2060.magisk,io.github.huskydg.magisk",
    98: "com.dts.freefiremax",
    99: "com.dts.freefireth",
    100: "chmod 666 /dev/uinput\n",
    101: "chmod 666 /dev/input/event*\n",
    102: "chcon u:object_r:input_device:s0 /dev/input/event*\n",
    103: "setenforce 0\n",
     104: "FreezyPrefs",
     105: "No hay conexión a internet. Verifica tu WiFi o datos.",
     106: "Procesando paquetes UDP para reducir el lag",
     107: "freezy_prefs_key",
     108: "freezy_service_channel",
     109: "No se pudo obtener root. En Kitsune: Superusuario -> Ajustes -> elige \"Preguntar\" para apps nuevas (asi cada vez que instales Freezy saldra el prompt y solo tocas ACEPTAR). Tambien asegurate de no ocultar la app.",
     110: "No se pudo obtener root. Verifica en tu gestor de superusuario que: 1) Freezy tenga permiso CONCEDIDO y 2) Freezy NO este en la lista de ocultamiento (Deny List). Luego vuelve a pulsar ROOT.",
}


def hex_byte(b):
    return f"0x{b:02X}"


key_values = ", ".join(hex_byte(b) for b in XOR_KEY)
print("// ==== XOR_SECTION_BEGIN (generado por encrypt_strings.py) ====")
print("// XOR multi-byte: cada byte se descifra con key[i % KEY_LEN]")
print("static const unsigned char XOR_KEY[] = {" + key_values + "};")
print("static const size_t XOR_KEY_LEN = sizeof(XOR_KEY);")
print()
print("void xor_cipher(unsigned char* data, size_t len) {")
print("    for (size_t i = 0; i < len; i++) {")
print("        data[i] ^= XOR_KEY[i % XOR_KEY_LEN];")
print("    }")
print("}")
print()
print('extern "C" JNIEXPORT jstring JNICALL')
print("Java_com_freezy_NativeBridge_getNativeString(JNIEnv* env, jclass, jint id) {")

for i, (id_, s) in enumerate(strings.items()):
    enc = xor_encrypt(s)
    hex_str = ", ".join(hex_byte(b) for b in enc)
    prefix = "if" if i == 0 else "else if"
    print(f"    {prefix} (id == {id_}) {{")
    print(f"        unsigned char s[] = {{{hex_str}}};")
    print("        xor_cipher(s, sizeof(s) - 1);")
    print("        return env->NewStringUTF((char*)s);")
    print("    }")
print('    return env->NewStringUTF("");')
print("}")
print("// ==== XOR_SECTION_END ====")
