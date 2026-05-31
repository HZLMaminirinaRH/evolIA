# Architecture Go/UDP pour le Chat WiFi - Phase 2

## Vue d'ensemble

**Objectif** : Pipeline WiFi/UDP fonctionnel et efficace pour le chat peer-to-peer direct, sans multi-hop (Phase 3).

**Trois flux parallèles** :
1. **Valeur** (blocs de V) : peer-to-peer UDP directed
2. **Chat** (messages E2E) : peer-to-peer UDP directed  
3. **Découverte** (announcing) : broadcast LAN sur UDP

---

## 🔴 **Problème identifié : Collision de ports**

**Situation actuelle** (BUG) :
```
mesh-sync:
  - Listen blocks   → :5555
  - Listen chat     → :5556  ← Reçoit les messages chat

evolia-net:
  - Listen announces → :5556  ← 🔴 COLLISION!
  - Broadcast → 255.255.255.255:5556
```

Le port `:5556` est utilisé à la fois pour le chat ET pour les announces. Quand deux datagrams arrivent simultanément, l'un est perdu.

**Solution** : Utiliser un port dédié pour la découverte.

---

## 📋 **Architecture proposée**

### **Ports UDP**

| Port | But | Source → Dest | Protocole |
|------|-----|--------------|-----------|
| **5555** | Valeur (blocs V) | Peer → Peer (directed) | JSON signed block |
| **5556** | Chat (messages E2E) | Peer → Peer (directed) | JSON message envelope |
| **5557** | Découverte (announces) | Broadcast | JSON announce (LAN) |

### **Trois binaires Go**

#### **1. `mesh-sync` (main relay)**
```
Responsabilités:
  ✅ Recevoir les blocs de valeur sur :5555
  ✅ Recevoir les messages de chat sur :5556
  ✅ Émettre la valeur locale aux pairs sur :5555 (chaque cycle)
  ✅ Relayer les messages de chat aux pairs sur :5556 (chaque cycle)
  ✅ Stocker les blocs dans le vault
  ✅ Appender les messages dans l'inbox
  ✅ Appliquer la défense adaptative (attaques, throttling)
  ✅ Décayer la défense sur les cycles tranquilles

Cycle (par défaut 5s) :
  1. Lire le fichier de valeur locale (evolia_identity_state.json)
  2. Charger les peers (EVOLIA_PEERS + evolia_peers.json)
  3. Envoyer le bloc de valeur à tous les peers
  4. Relayer les messages de chat en queue vers tous les peers
  5. Appliquer la défense (throttle, decay)
  6. Sleep et recommencer
```

#### **2. `evolia-net` (peer discovery)**
```
Responsabilités:
  ✅ Écouter les announces sur :5557 (port dédié!)
  ✅ Broadcaster ses announces sur 255.255.255.255:5557 (LAN broadcast)
  ✅ Construire la liste des peers découverts
  ✅ Persister aux peers à evolia_peers.json
  ✅ mesh-sync relit le fichier chaque cycle

Cycle (par défaut 30s) :
  1. Envoyer une announce (device_id) en broadcast
  2. Recevoir les announces des pairs
  3. Mettre à jour le registry (last_seen)
  4. Sauvegarder à evolia_peers.json
```

#### **3. `evolia-bridge` (HTTP intake) [Optionnel Phase 2]
```
Responsabilités (future):
  ✅ Accepter les blocs en POST /block
  ✅ Accepter les syncs en POST /sync
  ✅ Relayer vers le vault (mesh.StorePeerBlock)
  ✅ Appliquer la défense adaptative (gate, injection detection)
  ✅ Decay sur les ticks tranquilles

Cas d'usage: Fallback quand UDP est bloqué ou non-fiable
```

---

## 📊 **Flux de données**

### **Scénario : Deux peers (A et B) font un chat**

```
Device A                                    Device B
─────────────────────────────────────────────────────

UI: "Envoyer message"
   │
   ├─→ ChatManager.seal()
   │    └─→ evolia_chat_outbox.jsonl
   │        (opaque, E2E sealed)
   │
   └─→ [next mesh-sync cycle]
        mesh-sync.relayChat()
        │
        ├─→ chat.DrainOutbox()
        │   (atomic rename)
        │
        └─→ for each peer:
             UDP send → peer:5556
                           │
                           └──────────────────────→ Device B recv :5556
                                                    mesh-sync.listenChat()
                                                    │
                                                    ├─→ chat.ParseIncoming()
                                                    │   (validation, no decrypt)
                                                    │
                                                    ├─→ chat.AddressedTo()
                                                    │   (match fingerprint)
                                                    │
                                                    └─→ chat.AppendInbox()
                                                        evolia_chat_inbox.jsonl
                                                        │
                                                        └─→ UI reads inbox
                                                            ChatManager.incomingTransfers()
                                                            (E2E decrypt dans l'app)
```

### **Scénario : Valeur synchronisée**

```
Device A                                    Device B + Peers
─────────────────────────────────────────────────────────────

[Every 5s cycle]
 mesh-sync.main()
 │
 ├─→ readLocalBlock()
 │    └─→ evolia_work_proof.json (avec PoW)
 │        ou evolia_identity_state.json (fallback)
 │
 ├─→ loadPeers()
 │    └─→ EVOLIA_PEERS (config)
 │    └─→ evolia_peers.json (discovered)
 │
 └─→ sendBlock(self, v, work, params, peers)
      │
      └─→ for each peer:
           UDP send → peer:5555
                         │
                         └──────→ Device B recv :5555
                                  mesh-sync.listenBlocks()
                                  │
                                  ├─→ defense.Gate (throttle if overload)
                                  │
                                  ├─→ mesh.StoreIncoming()
                                  │   (verify sig, check PoW, detect injection)
                                  │
                                  ├─→ Record attack if hostile
                                  │   (feeds adaptive defense)
                                  │
                                  └─→ mesh.StorePeerBlock()
                                      evolia_peers/recv_<device_id>.json
                                      │
                                      └─→ Dashboard reads + aggregates
                                          mesh.TotalV()
```

---

## 🛡️ **Défense adaptative**

### **Points d'entrée des attaques**

| Port | Entrée | Classification |
|------|--------|-----------------|
| 5555 | Injection, mauvaise sig, PoW forgé, malformed | `mesh.ErrInjection`, `ErrBadSignature`, `ErrForgedWork`, `ErrMalformed` |
| 5556 | Injection, malformed, trop gros | `chat.ErrInjection`, `ErrMalformed`, `ErrTooLarge` |
| 5557 | Malformed announces (non-applicable, pas de decrypt) | Aucun (simple registry) |

### **Gate (token bucket)**

- Throttle **par IP source** sous pression
- Burst/refill shrink → floor (0.25×) quand defense level monte
- UDP dropé (throttled) = pas enregistré comme attaque (sinon boucle feedback)
- Breathe back: `Decay()` sur cycles tranquilles

### **Logs**

Chaque event → `evolia_mesh_sync.log` (JSON) :
```json
{"timestamp": "2026-05-31T14:30:00Z", "message": "received recv_<device>.json from 192.168.1.5"}
{"timestamp": "2026-05-31T14:30:01Z", "message": "chat received id=msg_123 from 192.168.1.6"}
{"timestamp": "2026-05-31T14:30:02Z", "message": "injection from 192.168.1.7 rejected (defense=0.45)"}
```

---

## 🔄 **Configuration et bootstrap**

### **Variables d'environnement**

```bash
# Config réseau
EVOLIA_MESH_KEY=<shared HMAC key or empty for unsigned>
EVOLIA_MESH_CYCLE_SECONDS=5  # emit/decay cadence
EVOLIA_PEERS=192.168.1.10,192.168.1.11  # hardcoded peers (fallback)
EVOLIA_GENESIS_UNIX=1609459200  # proof-of-work ceiling (fleet-wide)

# Chemins
EVOLIA_HOME=$HOME/evolia
```

### **Fichiers clés**

```
$EVOLIA_HOME/
  evolia_chat_outbox.jsonl          ← App writes here
  evolia_chat_inbox.jsonl           ← Go writes here, app reads
  evolia_chat_fingerprint.txt       ← App writes its identity here
  
  evolia_identity_state.json        ← Python writes (total_v + cycle_count)
  evolia_work_proof.json            ← Python writes (PoW each cycle)
  
  evolia_peers.json                 ← evolia-net writes (discovered peers)
  mesh_vault/                       ← Go mesh-sync writes peer blocks
    recv_<device_id>.json
  
  evolia_mesh_sync.log              ← Go logs
  evolia_network.log                ← evolia-net logs
```

---

## ✅ **Checklist - Phase 2 Effective**

- [x] **Port 5557 alloué à la découverte (fix collision)** ✅ commit (this PR)
- [x] **`evolia-net` écoute sur `:5557` et broadcast sur `:5557`** ✅
- [x] **`mesh-sync` continue `:5555` (valeur) et `:5556` (chat)** ✅
- [x] **Chat opaque routing (no decrypt in Go)** ✅ (existing `go/chat`)
- [x] **Defense gate + throttle par IP** ✅ (existing `go/defense`)
- [x] **Dedup par message ID (survive restart)** ✅ (existing `chat.LoadSeenIDs`)
- [x] **Logs JSON pour debugging** ✅ (existing `newLogger`)
- [x] **Android APK inclut les 3 binaires (.so)** ✅ commit eba3170 (CI NDK fix)
- [x] **Cycle 5s : emit + relay + decay** ✅ (existing `cycleInterval()`)
- [x] **Peers viennent de EVOLIA_PEERS (config) + evolia-net discovery** ✅

## 📊 **Monitoring sur device**

Une fois le nouvel APK installé, vérifier les logs dans `$EVOLIA_HOME/` :

```bash
# Logs mesh-sync (blocs valeur + chat)
tail -f $EVOLIA_HOME/evolia_mesh_sync.log

# Logs evolia-net (découverte)
tail -f $EVOLIA_HOME/evolia_network.log

# Peers découverts
cat $EVOLIA_HOME/evolia_peers.json

# Stats Bluetooth (déjà persistées)
cat $EVOLIA_HOME/evolia_chat_bt_stats.json
```

**Indicateurs de bon fonctionnement** :
- `evolia_network.log` montre `peer discovered: <id> @ <ip>`
- `evolia_peers.json` contient au moins un peer
- `evolia_mesh_sync.log` montre `sent device=<self> -> <peer>:5555`
- Sur le destinataire : `evolia_mesh_sync.log` montre `received recv_<id>.json from <ip>`
- Pour le chat : `chat sent -> <ip>:5556` (émetteur) et `chat received id=<msg> from <ip>` (receveur)

---

## 🎯 **Prochaines étapes (Phase 3+)**

- **Multi-hop store-and-forward** : relayer les blocs non-addressés
- **HTTP bridge** : fallback quand UDP bloqué
- **ACK/retry** : sur base optionnelle du chat
- **Batching** : regrouper les messages si réseau slow
- **Compression** : si payloads gros
