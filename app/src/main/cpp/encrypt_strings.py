def xor_encrypt(s, key=0x55):
    return [ord(c) ^ key for c in s] + [0]

strings = {
    1: "https://licencias-freezy.onrender.com/api/keys/verify",
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
    59: "Permiso Root no disponible o denegado."
}

for id, s in strings.items():
    enc = xor_encrypt(s)
    hex_str = ", ".join([f"0x{b:02X}" for b in enc])
    print(f"    }} else if (id == {id}) {{")
    print(f"        char s[] = {{{hex_str}}};")
    print(f"        xor_cipher(s, sizeof(s) - 1);")
    print(f"        return env->NewStringUTF(s);")
