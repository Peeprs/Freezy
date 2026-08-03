# 1. Arquitectura

### 1.1 Diagrama real

```
                        ┌─────────── Vercel (Edge) ────────────┐
                        │  Frontend Next.js + Clerk (Auth.js)   │
                        │  /app/api/** proxies firmadas HMAC →  │──┐
                        └───────────────────────────────────────┘  │ HTTPS
                                                                   │
Usuarios finales ─ web/app ─┐                                      │ https://xxxxx
                            └──► tunnel trycloudflare (CAMBIA) ◄───┘ .trycloudflare.com
                                        ▲
                            ┌───────────┴────────────┐
Internet ─► WAF/edge CF ────│ cloudflared-tunnel     │  conexión de SALIDA única (QUIC/7844)
                            │ sin puertos abiertos   │  ROUTER: 0 servicios expuestos
                            └───────────┬────────────┘
                                        │ bot-network (bridge docker, NO expone :80/443/8080/5432 al host)
        ┌───────────────────┬───────────┼──────────────┬───────────────────┐
        ▼                   ▼           ▼              ▼                   ▼
   api-backend:8080    whatsapp-bot   bot-worker   local-postgres    cloudflared
   Express+Prisma      (Quick reply  (Polling IA   15-alpine         (tunnel client)
   17 rutas /api/*      oficiano)     /misiones)
        │                   │           │              │
        └───────────────────┴───────────┴──────5432───┘

Admins (tú / amigos) ──── cola TAILSCALE directa (100.100.89.61), sin pasar por Cloudflare
```

### 1.2 Componentes, recursos vivos y responsabilidades

Medido en `sa@100.100.89.61` (`docker stats`, 2026-08-02):