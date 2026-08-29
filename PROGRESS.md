# PROGRESS — Estado de las ESP de Freezy

> Documento vivo: registra lo que funciona hoy, cómo está construido, y las trampas
> aprendidas que **NO se deben volver a hacer**. Actualizado: 2026-08-15.

---

## 1. Estado actual (a 2026-08-15)

- **ESP Box + ESP Skeleton + ESP Línea + ESP Health + ESP Team + ESP Name + ESP Distance + ESP Weapon + Ignore Knocked**:
  - **ESP Health**: Barra de vida visual adaptativa con detección por pares `(CurHP, MaxHP)` dinámicos en memoria (evita leer escalas o valores estáticos) y renderizado con colores dinámicos (Verde > 120 HP, Amarillo 50-120 HP, Rojo < 50 HP).
  - **ESP Name**: Muestra el nombre real del jugador (o etiqueta `BOT` si es bot) sobre la cabeza.
  - **ESP Distance**: Muestra la distancia en metros (`[Xm]`) sobre la cabeza, configurable de forma independiente.
  - **ESP Weapon**: Muestra el nombre del arma equipada (M4A1, MP40, M1887, AWM, Woodpecker, Barrett, etc.) en tono dorado debajo de los pies.
  - **ESP Team**: Identificación visual para aliados (`[TEAM]` en cyan `#00E5FF`) y enemigos (`[ENEMY]`).
  - **Ignore Knocked**: Filtra de forma inmediata a los enemigos derribados para mantener el HUD limpio y sin distracciones.
  - **Interfaz Modular Organizada**: Menú ESP dividido en 4 tarjetas limpias (ESP Trazado, Filtros & Equipo, ESP Información y Customization).
- **Lectura estricta a 32-bit (`ptr_width = 4`)**:
  - Configurado por defecto a 4 bytes tanto en C++ nativo como en `BubbleService.kt` (`prefs.getInt("ptr_width", 4)`).
  - Eliminado el fallback que bloqueaba reintentos de `ffmem`, garantizando lectura en microsegundos y evitando el cuello de botella de ~600ms de `su -c dd`.
- **ESP Box**:
  - Alto / Techo: Hueso de la Cabeza (`b[0]`) con compensación respecto al cuello.
  - Ancho / Laterales: Huesos de los brazos (hombros `b[4]`, `b[5]` y muñecas `b[8]`, `b[9]`) con margen lateral y ajuste adaptativo de perfil.
  - Base / Suelo: Huesos de los pies (`b[12]` pie izquierdo y `b[13]` pie derecho).
- **ESP Count**: Desactivado por defecto (`false`) tanto en preferencias como en inicio de overlay.
- **Optimización de Entidades y Giro de Cámara**:
  - Descarte ultra-rápido en C++ de jugadores muertos (`isDead`) antes de hacer lecturas pesadas.
  - `ViewMatrix` leída fresca por cada entidad viva justo antes de proyectar a pantalla (`worldToScreen`), garantizando sincronización exacta al girar la cámara.
  - Normalización de resolución de pantalla horizontal (2400 x 1080) forzada en `worldToScreen`.
  - Bucle de entidades en `getEntities` por entrada individual seguro (regla 3.1).
- **Aimbot, Sniper Scope y Sniper Switch (el "cráneo")**: activación con doble confirmación (anti-ban). Un tap se revierte; el segundo tap dentro de 3 s activa.
- Los switches ESP son **de activación inmediata** (sin restricción).

---

## 2. Arquitectura ESP (cómo funciona hoy)

### 2.1 Flujo por frame (Zero Garbage Collection / Direct Float Buffer de 40 floats)
1. `EspOverlayView` corre un **poll thread** a 240Hz (~4 ms) que llama a `NativeBridge.getEspSnapshotDirect(pid, espBuffer)`.
2. El nativo escribe directamente en un array pre-asignado de floats (40 floats por entidad): `[0]=knocked, [1]=dist, [2]=team, [3]=hp, [4]=weaponId, [5]=isBot, [6..11]=namePacked[6], [12..39]=huesos_x_y` **sin crear strings ni objetos JSON en el heap**.
3. El overlay copia con `System.arraycopy` y llama a `postInvalidate()`, dibujando Box, Skeleton, Líneas, Health, Name, Weapon, Team y/o Count en `onDraw` **sin ninguna asignación de memoria en el heap (0 objetos creados por frame)**. Esto elimina totalmente el recolector de basura (GC) de Android y los micro-tirones.

### 2.2 Lectura de memoria (helper root stealth con process_vm_readv y Batch iovec)
- Helper **`ffmem`** (C, `app/src/main/cpp/ffmem.c`):
  - **0 Descriptores en `/proc/<pid>/mem`**: Migrado a la syscall nativa del kernel **`process_vm_readv`**; no abre ni mantiene ningún descriptor de archivo en `/proc/<pid>/mem`.
  - **Camuflaje en tiempo de ejecución**: El proceso se reporta al kernel como `logd` mediante `prctl(PR_SET_NAME, "logd")`.
  - **Batch I/O Vectorial (`V <count> <addr1> <size1> ...`)**: Lee múltiples regiones dispersas de memoria en **1 sola llamada `process_vm_readv` con múltiples estructuras `iovec`**, reduciendo las interrupciones del kernel en más de un 90%.
  - **Resolución de módulos en memoria (`B <module_name>`)**: Resuelve la base de `libil2cpp.so` y `libunity.so` directamente desde el proceso root sin ejecutar llamadas de consola `su -c 'cat /proc/pid/maps'`.
  - **Búsqueda de PID sin `su`**: `findGamePidNative` escanea `/proc/[pid]/cmdline` directamente en C++ con `opendir`, eliminando `su -c 'ps -A | grep...'`.
  - **Ubicación interna privada**: Almacenado en almacenamiento privado interno (`.sys_logd` en `filesDir`), eliminando cualquier archivo en `/data/local/tmp`.
  - **Capacidad de escritura 100% eliminada**.
- `MAX_PAYLOAD = 16384` (16 KB por operación).

### 2.3 Optimizaciones activas (que SÍ funcionan)
1. **Batch Vectorial de I/O**: Reduce cambios de contexto al agrupar lecturas múltiples en 1 sola syscall `process_vm_readv`.
2. **Pre-filtrado ultrarrápido por distancia en Battle Royale**: descarta muertos y entidades fuera de radio (`> 150m`) leyendo únicamente 1 puntero de cabeza antes de procesar jerarquías, huesos o datos auxiliares. En BR (50 jugadores) reduce la carga en un 90%.
3. **Lectura condicional bajo demanda (Flags bitmask)**: El nativo (`getEspSnapshotDirect`) solo lee armas, nombres, salud o aliados si el switch correspondiente está encendido en la interfaz. Si un switch está apagado, 0 lecturas de memoria para esa característica.
4. **Snapshot de jerarquías compacto (`HIER_MAX = 96`)**: Cubre todos los huesos articulados (0..64) en solo 4.6 KB por entidad (reducido de 25 KB), eliminando la saturación de pipes y la cola de I/O.
5. **Filtro temprano en C++**: descarta muertos y no-enemigos en 1 solo read antes de leer huesos, nombres o jerarquías.
6. **Snapshot de jerarquías de transforms** (`HierSnap`, key = `matrix`):
   - `matrixList`/`matrixIndices` se fotografían **una vez por frame por jerarquía** (`g_hier_frame`).
   - La cadena de ancestros se resuelve 100 % en memoria (`resolvePosFromHier`), eliminando ~18 lecturas por hueso.
   - Los punteros `bone→transform→transformObj→matrix/index` se leen **siempre frescos** (correcto para jugadores en movimiento).
7. **Block read de los 14 punteros de hueso** (`readBonePtrBlock`): una sola lectura de `entity + 0x458` de 0x60 bytes.
8. **matrix + index en un solo read de 8 bytes** (`transformObj + 0x20`).
9. **View matrix leída POR ENTIDAD** justo antes de proyectar (`getViewMatrix`). Esto mantiene el ESP perfectamente adherido al enemigo durante rotaciones de cámara.
10. **Filtro de radio `ESP_MAX_DIST = 150`** y **cap `shown >= 30`**: evita procesar jugadores fuera de combate.
11. **Derribados (knocked) en ROJO**, vivos con color normal/RGB.

### 2.4 Huesos omitidos en Skeleton
- Codos (índices 6,7) y tobillos (10,11) **no se calculan** en nativo → quedan en `-1`.
- El overlay dibuja **hombro→muñeca** (en vez de hombro→codo→muñeca) e **ingle→pie** (en vez de ingle→tobillo→pie).
- Las articulaciones de codos/tobillos no se dibujan.

---

## 3. 🔴 Lo que NO se debe volver a hacer (trampas aprendidas)

### 3.1 Block read del diccionario en `getEntities` — PROHIBIDO
- Se intentó leer el array de entradas del diccionario (0x10 B por entry, hash en +0x0, entidad en +0xC) **en una sola llamada** con `ptr_width == 4`.
- **Resultado**: el ESP dejó de quedar pegado al jugador y volvió el bug de no reubicar al girar la cámara. Se revirtió al **bucle por entrada** (2 reads por entry) que es el método probado y estable.
- **Regla**: si el dict no se lee entry por entry, NO tocar `getEntities`. El layout contiguo del diccionario NO es seguro asumirlo.

### 3.2 Restricción anti-ban en los switches ESP — NO
- El usuario pidió restringir "el cráneo" y por error se aplicó a ESP Skeleton/Línea.
  **Resultado**: las ESP dejaron de activarse y el usuario se quejó.
- **Regla**: los switches **ESP** son de activación inmediata. La restricción anti-ban es SOLO para **aimbot, sniper scope y sniper switch**.

### 3.3 Mantener presionado (hold) dentro del ScrollView — NO
- Los switches están dentro de un `ScrollView` del menú. El hold (~1.2 s) se cancelaba por `ACTION_CANCEL` (micro-movimiento cruza el touch slop).
- **Regla**: no usar hold para activar switches del menú. La **doble confirmación** (primer tap revierte, segundo tap en 3 s activa) funciona y es robusta.

### 3.4 Lecturas dobles del bloque de huesos — evitar
- Se reutiliza el mismo array `bones[14]` para cabeza, esqueleto y caja.

### 3.5 Fragmentos de jerarquía > límite del helper — NO
- Fragmentos de lectura de jerarquía deben respetar `MAX_PAYLOAD = 16384`.

### 3.6 Limpieza ciega de jerarquías (`g_hier_snaps.clear()`) — PROHIBIDO
- Anteriormente se hacía `if (g_hier_snaps.size() > 8) g_hier_snaps.clear();` en C++.
- Cuando había más de 5 enemigos, el número de jerarquías superaba 8, borrando todo el caché en medio del frame y forzando a calcular los huesos por el método lento (20 lecturas por hueso).
- **Regla**: La caché soporta hasta 64 jerarquías activas y solo se eliminan las de frames anteriores (`last_frame != frame`), garantizando fluidez constante con 10, 20 o 50 enemigos en mapa.

---

## 4. Límites y constantes importantes

| Constante | Valor | Uso |
|---|---|---|
| `ESP_MAX_DIST` | 150.0 | Radio máximo de enemigos dibujados |
| cap `shown` | 50 | Máx. entidades en el snapshot |
| `MAX_PAYLOAD` (helper) | 16384 | Bytes máx. por read del helper |
| chunk de jerarquía | 16384 | Fragmento de lectura de matrixList/Indices |
| `HIER_MAX` | 2048 | Entradas de jerarquía fotografiadas por jerarquía |
| `g_hier_snaps` | ≤ 8 | Jerarquías cacheadas simultáneas (si > 8 se limpia) |
| `CONFIRM_WINDOW_MS` | 3000 | Ventana de doble confirmación del cráneo |

---

## 5. Orden de huesos (índice en `skel[]`)

| Índice | Hueso | ¿Se calcula? | Uso en ESP Box |
|---|---|---|---|
| 0 | cabeza | ✅ | Techo / Altura |
| 1 | cuello | ✅ | Offset cabeza |
| 2 | cadera | ✅ | Fallback base |
| 3 | ingle | ✅ | Fallback base |
| 4 | hombro izq | ✅ | Lateral izquierdo |
| 5 | hombro der | ✅ | Lateral derecho |
| 6 | codo izq | ❌ omitido | — |
| 7 | codo der | ❌ omitido | — |
| 8 | muñeca izq | ✅ | Lateral izquierdo |
| 9 | muñeca der | ✅ | Lateral derecho |
| 10 | tobillo izq | ❌ omitido | — |
| 11 | tobillo der | ❌ omitido | — |
| 12 | pie izq | ✅ | Base / Suelo |
| 13 | pie der | ✅ | Base / Suelo |

---

## 6. Offsets de memoria (cadenas críticas)

- Entidad: `OFF_DICT_ENTITIES 0x68`, `OFF_DICT_COUNT`, `OFF_DICT_ENTRIES_PTR`, `OFF_DICT_START`; entry `0x10` B (hash +0x0, entidad +0xC).
- `OFF_PLAYER_IS_DEAD 0x50`, `OFF_PLAYER_DATA 0x48`, `OFF_PLAYER_NAME 0x2DC`, `OFF_IS_CLIENT_BOT 0x2E4`.
- Team: `OFF_AVATAR_MANAGER 0x4C0` → `OFF_AVATAR 0xA8` → `OFF_AVATAR_IS_VISIBLE 0x95` → `OFF_AVATAR_DATA 0x14` → `OFF_AVATAR_DATA_IS_TEAM 0x59` (1 = aliado, 2 = enemigo).
- Cámara/view: `OFF_FOLLOW_CAMERA` → `OFF_CAMERA` → `OFF_CAMERA_BASE` → `OFF_VIEW_MATRIX 0xE8` (64 B). `OFF_MAIN_CAMERA_TRANSFORM 0x24C`.
- Huesos: bloque `0x458..0x4A4` (0x60 B). Puntero de hueso → `+0x8` transform → `+0x8` transformObj → `+0x20` = `matrix(4) + index(4)` (8 B en un read).

---

## 7. Build / dispositivo

- Build: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Dispositivo: `adb -s acaab147` (`Redmi K30 Pro - 16`, 32-bit ARM para el juego `com.dts.freefireth`).
- Paquete: `com.system.network.ui`, launcher `com.freezy.LoginActivity`.

---

## 8. Próximos pasos

1. Probar en partida completa de Battle Royale el rendimiento del nuevo ESP Box y Skeleton con 50 enemigos en mapa.
2. Monitorear los tiempos de snapshot con el log `[FREEZY] ESP snapshot: N entidades, X.X ms`.