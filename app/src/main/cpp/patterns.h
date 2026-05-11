#ifndef PATTERNS_H
#define PATTERNS_H

struct RecoilPattern {
    int base_strength;      // Fuerza inicial
    float increment_factor; // Qué tan rápido sube la patada
    int max_strength;       // Límite para no terminar apuntando al suelo
    int interval_ms;        // Velocidad de los micro-ajustes
};

// Configuración sugerida para un AR (Assault Rifle)
const RecoilPattern DEFAULT_AR_PATTERN = {
    5,      // base_strength
    1.2f,   // increment_factor
    25,     // max_strength
    15      // interval_ms
};

#endif // PATTERNS_H
