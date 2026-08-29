# POSIBLES OFFSETS — features implementables en Freezy

> Analizado del dump Il2CppDumper `dump.cs` (Garena COW / Free Fire, 2.2M líneas).
> Offsets de **campos** verificados en el dump. Los de **métodos** (RVA/VA) permiten
> hooks si se implementa inyección. Clasificación por dificultad de implementación y
> riesgo de ban. Actualizado: 2026-08-15.

---

## Cómo leer este documento

- **MonoBehaviour / clase de escena con offsets `0xNN`** → usable por lectura de
  memoria (como las ESP actuales).
- **Offsets `0xDBDBDBDB`** → ofuscados por el dump; NO se pueden usar estáticamente,
  solo vía escaneo en runtime o métodos (RVA). Se marcan como "requiere scan".
- **Métodos `get_...` con RVA** → accesibles solo por hook de inyección, no por
  lectura simple de memoria.
- **Nivel bajo** = lectura de memoria directa, bajo riesgo de ban, compatible con el
  ESP actual. **Nivel medio** = requiere más cadenas/escaneo o tocar variables de
  estado. **Nivel alto** = modificación de memoria / hooks, alto riesgo de ban.

---

## ✅ VERDADERAMENTE POSIBLES hoy (lectura de memoria, nivel bajo)

### 1. ESP de VIDA / ESCUDO / ENERGÍA de enemigos (NIVEL BAJO — muy recomendado)
**Player** (COW.GamePlay, línea 1236128, TypeDefIndex 30935), MonoBehaviour en partida.
- Cadena de acceso ya usada por Freezy: `Player` es el objeto de entidad (0x50 dead,
  0x2E4 bot, 0x2E0 nombre). Componente de atributos:
  - `PlayerAttributes` en **Player + 0x4BC** (offsets internos ofuscados).
- Valores de HP reales **por métodos** (requieren hook, no lectura directa):
  - `get_CurHP()` RVA 0x61D8C94, `get_MaxHP()` RVA 0x61D8DA8
  - `get_CurEP()` RVA 0x623F8C4, `get_CurAP()` RVA 0x6173110, `get_MaxAP()` RVA 0x62B6798
- **Qué hacer**: escanear `Player+0x4BC` en runtime para hallar los floats de HP/AP/EP
  (una vez, por PID) y mostrarlos sobre el ESP. Funcionalidad: barra de vida y armadura.
- **Riesgo**: bajo. Solo lectura.

### 2. ESP de VEHÍCULOS (NIVEL BAJO)
**Vehicle** (línea 1427246, TypeDefIndex 34378), MonoBehaviour, hereda de Entity → posición en **+0x38** (`CachedTransform`).
- `DriverPlayer` **0xB0** (saber si está ocupado y quién), `LastDriver` **0x110`
- `LevelTrigger` **0xB8**, `LevelVehicle` **0xBC** (tipo), `IsDuringDrift` **0x22E**
- `DriverSeat` **0xCC**, `PassengerSeat` **0xD0**
- **Qué hacer**: enumerar entidades (el dict actual), filtrar por tipo Entity
  (EntityInfo en Entity **+0x1C** con MainType/SubType), y dibujar ESP con posición
  `CachedTransform` (Transform) + worldToScreen ya existente.
- **Riesgo**: bajo. Solo lectura.

### 3. ESP de AIRDROPS / CAJAS / LOOT (NIVEL BAJO)
MonoBehaviour de escena, todos heredan de **BaseLevelObject** (línea 1294834,
TypeDefIndex 31813) → posición vía Entity/Transform.
- **LevelAirdrop** (línea 1299139, TypeDefIndex 31910): `IsLanding` **0x180**,
  `m_ModelObject` **0x154**
- **Airdrop** (línea 1224237, TypeDefIndex 30610): `BoxModel` **0x70**, `IsLanding` **0x6C**
- **LevelAmmoBox** (línea 1299792, TypeDefIndex 31916): `ItemID` **0xD4**, `ItemCount` **0xD8**
- **LevelWeaponBox** (línea 1331989, TypeDefIndex 32484): `Trigger` **0xD8**, `Model` **0xE0**
- **LevelSupply** (línea 1326428, TypeDefIndex 32388): `MissionType` **0xD4**
- **LevelReviveBox** (línea 1323211, TypeDefIndex 32327): caja de revive
- **Qué hacer**: enumerar entidades y filtrar por los SubTypes de estos Level*;
  dibujar ESP con su posición. Ítems (armas, munición) alrededor del jugador.
- **Riesgo**: bajo. Solo lectura.

### 4. ESP de RADAR (NIVEL BAJO — datos ya cocinados por el juego)
**LevelInGameRadar** (línea 1311308, TypeDefIndex 32124), MonoBehaviour.
- `PlayerPositions` **0x100** = `List<Vector3>` con **posiciones de jugadores escaneadas** por el radar del juego.
- `CenterPos` **0xF4** (Vector3), `ScanRadius` **0xF0**, `InScan` **0x108`, `RadarID` **0xD8`
- **Qué hacer**: encontrar la instancia del radar en memoria (escaneo del mapa de
  objetos) y leer `PlayerPositions` → ESP "mini-mapa" o marcadores en pantalla sin
  tocar el diccionario de entidades.
- **Riesgo**: bajo. Solo lectura. (Nota: requiere localizar la instancia, puede variar.)

### 5. Ver si el enemigo está DERRIBADO (NIVEL BAJO)
**Player** `IsFrozenKnockDown` **+0x68** (bool). Complemento al `knocked` actual.

### 6. Detectar arma actual / holding sniper (NIVEL BAJO)
**Player** `ActiveUISightingWeapon` **+0x3F4** (FDAEPHMIEPC, entidad arma).
`isHoldingSniper` ya funciona; se puede ampliar a ver qué arma lleva el enemigo.

---

## 🟡 POSIBLES CON ESFUERZO MEDIO (requieren escaneo en runtime o tocar estado)

### 7. Barra de vida "viva" con Armadura (NIVEL MEDIO)
- **PlayerAttributes** (línea 1378044, TypeDefIndex 33391): offsets **todos
  ofuscados** (`0xDBDBDBDB`), PERO es componente MonoBehaviour de runtime.
- Camps de interés (nombres legibles aunque offsets ofuscados): `CurHP`, `MaxHP`,
  `CurEP`, `CurAP`, `BuffArmorMinDurability`, `SuperArmorSkillEffecting`,
  `DamageAdditionScale`, `BuffWeaponDamageScale`.
- **Qué hacer**: escanear `Player+0x4BC` en runtime (el componente está referenciado),
  buscar los ints/floats que coinciden con HP/AP/EP. UNA vez por PID.
- **Riesgo**: bajo-medio (lectura, pero requiere scan heurístico).

### 8. NO-RECOIL / sin Spread / no-recuento de balas (NIVEL MEDIO-ALTO)
- Entidad de arma **FDAEPHMIEPC** (línea 1287061, TypeDefIndex 31684), MonoBehaviour
  con offsets reales. Datos de fuego vía getters del arma (fire interval, scatter):
  - `get_FireInterval()` RVA 0x76F06D8, `get_ScatterSpeed()` RVA 0x76F0708,
    `get_ScatterMax()` RVA 0x76F0728 (clase arma cerca de línea 368471)
  - `WeaponMultiLineData` **0x384**, `WeaponAimAssistAutoAbsorbData` **0x378**
- **PlayerAttributes** (ofuscado): `ReloadNoConsumeAmmoclip`, `ShootNoReload`,
  `BuffWeaponScatterScale`, `BuffWeaponDamageScale` — modificar estos floats en
  memoria = no recoil / no spread / munición infinita.
- **Qué hacer**: escanear offsets ofuscados de PlayerAttributes en runtime y
  **escribir** floats. Es modificación de memoria → riesgo alto de ban.
- **Riesgo**: ALTO (escritura de memoria del juego, anti-cheat lo detecta).

### 9. SPEED HACK (NIVEL MEDIO-ALTO)
- **Player** `Speed` **+0x37C** (float). Modificar = velocidad de movimiento.
- También `get_CurrentMaxSpeed()` (Player, RVA) y clases de vehículo:
  `MaxSpeed` 0x8/0x10, `SetMaxSpeedScale`, `LockMaxSpeed` (VehicleController).
- **Qué hacer**: escribir `Player+0x37C` o escalar. Escritura → riesgo ALTO.
- **Riesgo**: ALTO.

---

## 🔴 DE ALTO RIESGO / NO RECOMENDADO (modificación, hook, anti-cheat)

### 10. Aimbot "inteligente" vía hooks (NIVEL ALTO)
- Métodos con RVA del arma: `get_Fire()`, `CanFire(Player)` (línea 350109),
  `get_FireInterval()`. El aimbot actual de Freezy ya funciona por lectura (head pos);
  hooks de fuego = escritura en proceso + posible detección.

### 11. Magic Bullet / daño modificado
- `DamageAdditionScale`, `ExecuteDamageScale` en PlayerAttributes (ofuscado, escritura).

### 12. Modificar estado de equipo / invisible
- **AvatarInvisibleDesc** (línea 247606): relacionado a invisibilidad del avatar;
  pero es DTO de red, no campo de runtime del jugador. No recomendado.

### 13. TeamColor / TeamMapMark (NIVEL MEDIO, informativo)
- **Player** `TeamColorStr` **+0x528** (string), `TeamModeID` **+0x294** (uint),
  `TeamMapMark` **+0x348** (Vector3). Útil para ESP por color de equipo real.
- Lectura → bajo riesgo. Si solo se lee, baja a nivel bajo. (Ya Freezy usa team==2.)

---

## Tabla resumen priorizada

| # | Feature | Clase / offset | Dificultad | Riesgo ban | ¿Vale la pena? |
|---|---|---|---|---|---|
| 1 | Vida/escudo/enemigo | Player+0x4BC (scan) | Baja | Bajo | ✅ Sí |
| 2 | ESP vehículos | Vehicle+0x38 pos, 0xB0 driver | Baja | Bajo | ✅ Sí |
| 3 | ESP airdrop/cajas/loot | LevelAirdrop/LevelAmmoBox/... | Baja | Bajo | ✅ Sí |
| 4 | ESP radar | LevelInGameRadar+0x100 | Baja | Bajo | ⚠️ Requiere instancia |
| 5 | Knockdown flag | Player+0x68 | Baja | Bajo | ✅ Ya usado |
| 6 | Arma enemigo | Player+0x3F4 | Baja | Bajo | ✅ Sí |
| 7 | Barra de vida con AP/EP | PlayerAttributes scan | Media | Bajo | ⚠️ Scan heurístico |
| 8 | No-recoil/spread/muni | PlayerAttributes (write) | Media-Alta | **ALTO** | ❌ Riesgo |
| 9 | Speed hack | Player+0x37C (write) | Media-Alta | **ALTO** | ❌ Riesgo |
| 10 | Aimbot hooks | RVA métodos | Alta | **ALTO** | ❌ No |
| 11-13 | Daño/magic/invisible | write/DTO | Alta | **ALTO** | ❌ No |

---

## Cadenas de acceso resumidas (para implementar #1-#4)

```
# Posición de cualquier entidad (Entity base)
Entity + 0x38 = Transform (CachedTransform) → posición mundial

# Player (jugador en partida)
Player + 0x50 = isDead            (ya en uso)
Player + 0x2E0 = nombre           (ya en uso)
Player + 0x2E4 = IsClientBot      (ya en uso)
Player + 0x4BC = PlayerAttributes (HP/AP/EP, scan interno)
Player + 0x4C0 = AvatarManager    (bones/nombre, ya en uso)
Player + 0x5D4 = Vehicle          (vehículo actual)
Player + 0x3F4 = arma activa
Player + 0x68  = IsFrozenKnockDown
Player + 0x294 = TeamModeID   |  0x528 = TeamColorStr

# Vehicle
Vehicle + 0x38 = Transform (posición)
Vehicle + 0xB0 = DriverPlayer | 0xBC = LevelVehicle (tipo)

# Airdrop / loot
LevelAirdrop + 0x180 = IsLanding | 0x154 = model
LevelAmmoBox  + 0xD4 = ItemID    | 0xD8 = ItemCount
LevelWeaponBox+ 0xD8 = Trigger   | 0xE0 = Model

# Radar (mini-mapa)
LevelInGameRadar + 0x100 = List<Vector3> PlayerPositions
LevelInGameRadar + 0xF4  = CenterPos | 0xF0 = ScanRadius
```

---

## Recomendación final

Implementar en este orden (todas de lectura, bajo riesgo, alto valor visual):
1. **Barra de vida + AP/EP** sobre el ESP actual (scan de `Player+0x4BC`).
2. **ESP de vehículos** (mismo diccionario de entidades, filtrar por tipo).
3. **ESP de airdrops/cajas/loot** (mismo diccionario, subtipos Level*).
4. **Arma del enemigo** (solo lectura de `Player+0x3F4`).

NO tocar speed hack, no-recoil/spread, daño o hooks: el usuario ya fue baneado por
activar opciones del "cráneo"; esas features son escritura en memoria y disparan el
anti-cheat en minutos.