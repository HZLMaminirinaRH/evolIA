# evolIA Android (Plan B) — guide de validation sur appareil

La CI prouve que l'app **compile** et que les cœurs purs (valeur, crypto, auth)
sont **corrects**. Ce guide couvre ce que la CI ne peut pas : le **comportement
sur appareil** — capteurs qui émettent, photos captées, une vraie transaction
`anchorValue` signée, les binaires Go qui tournent, et le gate d'auth owner.
Procède de haut en bas ; chaque phase a des étapes concrètes et des critères de
réussite.

## 0. Prérequis

- Un appareil ou émulateur **arm64**, **API 26+** (utilise **33+** pour exercer
  la capture média et le chemin biométrique).
- **SDK + NDK Android** installés ; `adb` dans le PATH.
- Pour la validation on-chain : un nœud JSON-RPC Ethereum joignable (ex. Ganache).
  Depuis un émulateur, l'hôte est `http://10.0.2.2:8545` ; depuis un appareil
  physique, utilise l'IP LAN de l'hôte.

## 1. Build & installation

```sh
# a) Cross-compiler les binaires Go dans jniLibs (nécessite le NDK)
export ANDROID_NDK_HOME=/chemin/vers/android-ndk
bash scripts/build-android-binaries.sh
ls android/app/src/main/jniLibs/arm64-v8a/   # attendu : libevolia_net.so, libevolia_mesh_sync.so, libevolia_bridge.so

# b) Construire + installer l'APK debug
#    Ouvrir android/ dans Android Studio (il génère le wrapper Gradle) et Run,
#    ou avec un Gradle local / wrapper généré :
cd android && ./gradlew :app:installDebug
```

> Les binaires Go sont optionnels pour les couches valeur/auth/ancrage — si
> jniLibs est vide, l'app tourne quand même (la Phase 1 est simplement ignorée).
> Ils ne sont requis que pour la Phase 1 ci-dessous.

## Inspecter l'état partagé

Tout l'état réside dans le stockage privé de l'app (`EVOLIA_HOME` =
`files/evolia`). Sur un build **debug**, lis-le avec `run-as` :

```sh
adb shell run-as com.evolia.app ls -la files/evolia
adb shell run-as com.evolia.app cat files/evolia/evolia_identity_state.json
```

Dans l'app, le bouton **Rafraîchir l'état** affiche
`evolia_identity_state.json` ainsi que l'adresse du wallet — la lecture en direct
la plus rapide.

## 2. Phase 3 d'abord — le gate d'auth

L'auth est le point d'entrée : valide-la en premier.

**Premier lancement (setup) :**
1. Ouvre evolIA → la configuration PIN s'ouvre **automatiquement** (pas de bouton
   Démarrer : ouvrir l'app EST le geste de démarrage).
2. Saisis un PIN ; vérifie qu'elle refuse une entrée hors 4–6 chiffres.
3. Saisis un mot de passe ; vérifie qu'elle refuse < 8 caractères.
4. Choisis la biométrie oui/non.
5. ✅ Réussite : `adb shell run-as com.evolia.app cat files/evolia/.evolia_auth.json`
   montre `pin_hash` / `password_hash` sous forme de chaînes
   `$argon2id$v=19$...`, `owner: true`.

**Lancements suivants (vérification) :**
1. Ouvre evolIA → l'invite PIN apparaît automatiquement. Saisis un mauvais PIN
   → « incorrect, N essai(s) restant(s) » ; après 3 → « Authentification échouée »
   (le service ne démarre **pas**).
2. PIN correct → invite mot de passe (même comportement à 3 essais).
3. Si la biométrie est activée → `BiometricPrompt` apparaît ; une empreinte
   enregistrée réussit. Sans empreinte enrôlée → « Biométrie indisponible —
   étape ignorée ».
4. ✅ Réussite : en cas de succès, `.evolia_session.json` existe avec un `token`
   + un `device_id`, et la notification de premier plan « evolIA » apparaît.

> Note : la vérification Argon2 tourne sur le thread UI — une pause de ~100–300 ms
> au moment où tu touches OK est normale, ce n'est pas un blocage.

## 3. Phase 2 — le moteur de valeur

1. Service démarré, attends ~15 s (cycles de 5 s) et touche **Rafraîchir l'état**
   plusieurs fois. ✅ `cycle_count` et `total_v` augmentent.
2. Touche **Action: vidéo (+8 BTC-e)**, attends un cycle, rafraîchis. ✅ `total_v`
   bondit plus qu'un cycle calme (la vidéo a le taux d'action le plus élevé).
3. Recoupe avec les fichiers :
   ```sh
   adb shell run-as com.evolia.app cat files/evolia/evolia_value_state.json
   adb shell run-as com.evolia.app cat files/evolia/evolia_action_queue.jsonl
   ```

## 4. Phase 2b — capteurs, capture, ancrage

**Capteurs (indirect) :** les valeurs des capteurs se fondent dans la formule
plutôt que dans un fichier. Compare l'appareil **au repos, radios coupées** vs
**en mouvement avec WiFi + Bluetooth actifs** : sur des fenêtres comparables, le
plancher de `total_v` en mode actif doit être plus élevé. Accorde les permissions
localisation + appareils à proximité quand elles sont demandées.

**Capture photo/vidéo :**
1. Laisse evolIA tourner ; ouvre l'app **appareil photo** et prends une photo.
2. De retour dans evolIA, rafraîchis après un cycle. ✅ `total_v` bondit car un
   `photo_taken` est enfilé ; confirme dans `evolia_action_queue.jsonl`.
   (Nécessite la permission de lecture média accordée.)

**Ancrage — LOCAL (par défaut, sans config) :**
```sh
adb shell run-as com.evolia.app cat files/evolia/evolia_blockchain_sync.log
```
✅ Une ligne JSON toutes les ~30 s avec `"status":"local"`.

**Ancrage — on-chain (web3j) :**
1. Lis l'adresse du wallet dans le statut in-app (« Wallet à financer en gas »).
2. Sur ton nœud RPC, **finance cette adresse** avec assez de gas (ex. transfère
   de l'ETH depuis un compte Ganache).
3. Dépose la config de chaîne (lue à chaque sync — pas besoin de redémarrer) :
   ```sh
   adb shell run-as com.evolia.app sh -c \
     'printf "{\"rpc_url\":\"http://10.0.2.2:8545\",\"chain_id\":1337}" > files/evolia/evolia_chain_config.json'
   ```
4. En ~30 s :
   - ✅ `evolia_deployment.json` apparaît avec un `contract_address` (déploiement
     d'`EvoliaCore` au premier lancement).
   - ✅ `evolia_blockchain_sync.log` montre une ligne `"status":"success"` avec
     `tx_hash` + `block`.
   - ✅ Sur le nœud, le contrat existe et la transaction `anchorValue` est minée.
5. Test négatif : pointe `rpc_url` vers un hôte injoignable → les entrées
   retombent en `"status":"local"` (note `"node unreachable"`), sans jamais
   planter.

## 5. Phase 1 — supervision Go

(Seulement si tu as construit les binaires Go dans jniLibs.)

1. Service démarré, redirige et interroge le bridge :
   ```sh
   adb forward tcp:8080 tcp:8080
   curl -s http://127.0.0.1:8080/health
   ```
   ✅ Le bridge répond (il sert `/health`, `/block`, `/sync`, `/mesh/total_v`).
   Une réponse signifie que le binaire supervisé est vivant et a été lancé avec
   `EVOLIA_HOME` + l'environnement de session.
2. ✅ `evolia_peers.json` est écrit par `evolia-net` (peut ne lister que cet
   appareil).
3. ✅ Résilience aux kills : les binaires sont relancés à leur sortie (backoff de
   3 s) ; la notification de premier plan persiste (le fix signal-9).

## Checklist de validation

- [ ] Le setup crée `.evolia_auth.json` (hashes argon2id) ; un mauvais
      PIN/mot de passe bloque le démarrage après 3 essais.
- [ ] Le succès d'auth crée `.evolia_session.json` + la notification de premier
      plan.
- [ ] `total_v` / `cycle_count` avancent à chaque cycle ; une action vidéo fait
      monter `V`.
- [ ] Une vraie photo enfile `photo_taken` et fait monter `V`.
- [ ] L'ancrage LOCAL journalise `"local"` toutes les 30 s sans config.
- [ ] Avec config + wallet financé : `EvoliaCore` se déploie et `anchorValue`
      réussit on-chain (`"success"` + tx hash/block).
- [ ] (Si construit) le bridge Go répond sur `:8080` et survit aux redémarrages.

## Dépannage

- **Pas de notification / le service meurt vite :** accorde `POST_NOTIFICATIONS`
  (API 33+).
- **Binaires en crash-loop :** confirme qu'ils sont arm64 et présents dans
  jniLibs.
- **On-chain bloqué sur `local` :** vérifie que le RPC est joignable depuis
  l'appareil, que le wallet est financé, et que `chain_id` correspond au nœud.
  Le cleartext `http://` est déjà autorisé (`usesCleartextTraffic`).
- **Réinitialiser l'état :** `adb shell run-as com.evolia.app rm -rf files/evolia`
  (efface auth, wallet, valeur — le prochain lancement relance le setup et
  régénère le wallet).
