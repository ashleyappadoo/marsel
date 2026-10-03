# Marsel — Application Android de Sécurité Personnelle

Marsel est une application Android de sécurité personnelle conçue pour les situations d'urgence, notamment les violences conjugales. Elle permet d'envoyer une alerte d'urgence à des contacts de confiance via SMS, internet ou un réseau mesh WiFi Direct (Marsel Relay Network) — **sans dépendance à internet** pour la fonctionnalité de base.

---

## Table des matières

1. [Architecture générale](#architecture-générale)
2. [Stack technique](#stack-technique)
3. [Structure du projet](#structure-du-projet)
4. [Configuration](#configuration)
5. [Build et installation](#build-et-installation)
6. [Permissions Android](#permissions-android)
7. [Fonctionnalités implémentées](#fonctionnalités-implémentées)
8. [Interface JavaScript ↔ Android (Bridges)](#interface-javascript--android-bridges)
9. [Protocole Marsel Relay Network](#protocole-marsel-relay-network)
10. [Base de données locale (IndexedDB)](#base-de-données-locale-indexeddb)
11. [Stockage des enregistrements audio](#stockage-des-enregistrements-audio)
12. [Flow d'urgence complet](#flow-durgence-complet)
13. [Détection des téléphones Marsel voisins](#détection-des-téléphones-marsel-voisins)
14. [Limitations et travaux futurs](#limitations-et-travaux-futurs)
15. [Tests sur 2 téléphones](#tests-sur-2-téléphones)

---

## Architecture générale

L'application **n'utilise pas Jetpack Compose** malgré la présence des dépendances dans `build.gradle.kts`. L'interface est une **SPA (Single Page Application) web** chargée dans un `WebView` :

```
MainActivity.kt (Android)
    └── WebView
          ├── file:///android_asset/app.html      ← structure HTML (10 écrans)
          ├── file:///android_asset/style.css      ← design system
          ├── file:///android_asset/config.js      ← configuration (clés API)
          └── file:///android_asset/script.js      ← toute la logique applicative
```

La communication entre le JavaScript et le code natif Kotlin se fait via deux **JavaScript Interface bridges** enregistrés sur le WebView :

| Bridge JS | Classe Kotlin | Rôle |
|-----------|---------------|------|
| `window.AndroidBridge` | `MarselBridge` | Tout sauf WiFi Direct |
| `window.AndroidWifiDirect` | `WifiDirectBridge` | WiFi Direct P2P |

---

## Stack technique

| Composant | Version |
|-----------|---------|
| Android Gradle Plugin | 8.12.0 |
| Kotlin | 2.2.10 |
| compileSdk / targetSdk | 36 |
| minSdk | 24 (Android 7.0) |
| Java source/target | 17 |
| Leaflet.js | 1.9.4 (CDN) |
| Google Fonts (Nunito) | CDN |

---

## Structure du projet

```
marsel/
├── app/
│   ├── build.gradle.kts                    ← dépendances, SDK versions
│   └── src/main/
│       ├── AndroidManifest.xml             ← toutes les permissions
│       ├── assets/
│       │   ├── app.html                    ← SPA HTML (10 écrans)
│       │   ├── style.css                   ← design system (orange #E84315, bleu #1A35C8)
│       │   ├── script.js                   ← logique JS (~1920 lignes)
│       │   └── config.js                   ← clés API (à remplir)
│       ├── java/com/example/marsel/
│       │   └── MainActivity.kt             ← activité principale (~1139 lignes)
│       └── res/
│           ├── layout/activity_main.xml    ← layout XML (contient le WebView)
│           ├── values/                     ← couleurs, strings, themes
│           └── mipmap-*/                   ← icônes launcher
├── gradle/
│   ├── libs.versions.toml                  ← catalogue de versions
│   └── wrapper/gradle-wrapper.properties
├── index.js                                ← serveur Node.js (prototype desktop)
├── docker-compose.yml                      ← backend optionnel
├── Dockerfile
└── .idea/                                  ← configuration Android Studio
```

### Fichiers clés

#### `MainActivity.kt`
Contient toute la logique native Android :
- Configuration du `WebView` (JavaScript activé, DOM storage, `usesCleartextTraffic`)
- Initialisation WiFi P2P (`WifiP2pManager`)
- `WifiP2pBroadcastReceiver` : gère les états P2P (enabled/disabled, peers found, connected)
- Serveur relay TCP sur port **8890** (fonction `startRelayServer()`)
- Serveur chat legacy TCP sur port **8888**
- GPS natif via `LocationManager` (complément du GPS browser)
- Bridges `MarselBridge` et `WifiDirectBridge`

#### `script.js`
Contient toute la logique applicative :
- Objet état global `MARSEL` (position GPS, état urgence, carte Leaflet, contacts, etc.)
- Navigation entre les 10 écrans SPA
- Gestion de la carte Leaflet (marqueurs d'incidents)
- Logique d'urgence (déclenchement, tracking, résolution)
- Routage d'alerte (SMS / internet / relay réseau)
- Réception et traitement des paquets relay
- Base de données IndexedDB (4 stores)

#### `config.js`
Fichier de configuration — **à remplir avant production** (voir section [Configuration](#configuration)).

#### `app.html`
Structure des 10 écrans :

| ID écran | Nom |
|----------|-----|
| `screen-splash` | Écran de démarrage |
| `screen-auth` | Authentification (pseudo + numéro) |
| `screen-home` | Accueil (carte + bouton urgence) |
| `screen-profile-menu` | Menu profil |
| `screen-my-profile` | Mon profil |
| `screen-settings` | Réglages |
| `screen-contacts` | Contacts de confiance (5 slots) |
| `screen-contact-detail` | Détail d'un contact |
| `screen-subscription` | Abonnement |
| `screen-messaging` | Messagerie |

---

## Configuration

Éditer `app/src/main/assets/config.js` :

```javascript
var MARSEL_CONFIG = {
    // Backend optionnel (cloud sync)
    API_URL: '',          // ex: 'https://api.marsel.io/v1'
    API_KEY: '',          // Clé API Marsel

    // Passerelle SMS cloud (fallback si pas de SIM)
    SMS_GATEWAY_URL: '',  // ex: 'https://api.twilio.com/...'
    SMS_GATEWAY_SID: '',  // Twilio Account SID
    SMS_GATEWAY_TOKEN: '', // Twilio Auth Token
    SMS_FROM_NUMBER: '',  // ex: '+33700000000'

    // Push notifications
    FCM_SERVER_KEY: '',   // Firebase Cloud Messaging

    // Cartes (laisser vide = OpenStreetMap gratuit)
    MAPBOX_TOKEN: '',     // MapBox token pour de meilleures tuiles

    // Paramètres app (valeurs par défaut recommandées)
    EMERGENCY_RELAY_PORT: 8890,
    RELAY_HOP_LIMIT: 10,
    P2P_DISCOVERY_TIMEOUT: 30000,
    LOCATION_UPDATE_INTERVAL: 10000,
    EMERGENCY_HOLD_DURATION: 5000,

    // Lieux sûrs seed (Paris — à adapter selon la région)
    SAFE_PLACES_SEED: [ ... ]
};
```

**Sans aucune clé renseignée**, l'application fonctionne en mode **entièrement offline** :
- SMS envoyés directement via la SIM (SmsManager Android)
- Cartes via OpenStreetMap/CartoDB (gratuites)
- Relay via WiFi Direct (aucun serveur tiers)

---

## Build et installation

### Prérequis

- **Android Studio Meerkat** ou supérieur
- **JDK 17** minimum
- Connexion internet pour télécharger les dépendances Gradle au premier build

### Étapes

```bash
# 1. Cloner le dépôt
git clone https://github.com/ashleyappadoo/marsel.git
cd marsel

# 2. Ouvrir dans Android Studio
# File → Open → sélectionner le dossier marsel/

# 3. Laisser Gradle synchroniser (télécharge les dépendances)

# 4. Brancher un téléphone Android en mode développeur (USB debugging activé)
#    ou créer un AVD (émulateur)

# 5. Build et run
# Run → Run 'app' (ou Shift+F10)
```

### Branche de développement active

```
test/experimentation
```

La branche `main` contient le code initial. Tous les correctifs et fonctionnalités actifs sont sur `test/experimentation`.

### Build release (APK signé)

```
Build → Generate Signed Bundle/APK → APK
```

Note : `isMinifyEnabled = false` — ProGuard désactivé pour l'instant.

---

## Permissions Android

Toutes déclarées dans `AndroidManifest.xml` et demandées dynamiquement au runtime :

| Permission | Usage |
|------------|-------|
| `ACCESS_FINE_LOCATION` | GPS précis pour l'alerte d'urgence |
| `ACCESS_COARSE_LOCATION` | GPS approximatif (fallback) |
| `ACCESS_BACKGROUND_LOCATION` | Tracking position pendant urgence |
| `SEND_SMS` | Envoi direct d'alerte SMS via SIM |
| `CHANGE_WIFI_STATE` | WiFi Direct P2P |
| `NEARBY_WIFI_DEVICES` | WiFi Direct sur Android 13+ (API 33+) |
| `ACCESS_WIFI_STATE` | Lecture état WiFi |
| `INTERNET` | Backend cloud et cartes |
| `CAMERA` | Flash d'urgence |
| `FLASHLIGHT` | Flash d'urgence |
| `RECORD_AUDIO` | Enregistrement audio discret |
| `BLUETOOTH*` | Future fonctionnalité mesh Bluetooth |
| `POST_NOTIFICATIONS` | Notifications système |
| `FOREGROUND_SERVICE` | Service en arrière-plan |
| `WAKE_LOCK` | Maintien actif pendant urgence |
| `VIBRATE` | Retour haptique |
| `RECEIVE_BOOT_COMPLETED` | Démarrage automatique (non implémenté) |

---

## Fonctionnalités implémentées

### ✅ Actif et fonctionnel

| Fonctionnalité | Détails |
|----------------|---------|
| **Bouton urgence** | Maintien 5 secondes pour déclencher |
| **GPS réel** | LocationManager Android + browser geolocation en parallèle |
| **SMS d'alerte** | Via SmsManager (SIM requise), support multipart, API 31+ |
| **Marsel Relay Network** | WiFi Direct P2P + relay TCP port 8890 |
| **Détection phones Marsel voisins** | Notification + marqueur carte |
| **Tracking position en urgence** | Mise à jour GPS toutes les 10s |
| **Fin d'alerte** | SMS de résolution + suppression marqueur |
| **Carte Leaflet** | OpenStreetMap/CartoDB, marqueurs incidents |
| **Lieux sûrs** | Commissariats, hôpitaux, safe places (seed Paris) |
| **Enregistrement audio** | Discret, sauvegardé en .m4a |
| **Flash d'urgence** | Clignotement discret |
| **Profil utilisateur** | Pseudo + numéro, persistance IndexedDB |
| **5 contacts de confiance** | Avec numéro, persistance IndexedDB |
| **Notifications système** | Alertes reçues via relay |

### ⚠️ Partiel / Nécessite configuration

| Fonctionnalité | Condition |
|----------------|-----------|
| **SMS via gateway cloud** | `SMS_GATEWAY_URL` + credentials Twilio dans `config.js` |
| **Cartes Mapbox** | `MAPBOX_TOKEN` dans `config.js` |
| **Push notifications** | `FCM_SERVER_KEY` dans `config.js` |
| **Sync backend** | `API_URL` + `API_KEY` dans `config.js` |

### ❌ Non implémenté

| Fonctionnalité | Notes |
|----------------|-------|
| **LoRa** | Mentionné dans le cahier des charges, hors scope |
| **Messagerie chiffrée** | Écran présent, logique non implémentée |
| **Abonnement / paiement** | Écran présent, logique non implémentée |
| **Boot receiver** | Permission présente, service non créé |
| **Bluetooth mesh** | Permissions présentes, logique non implémentée |
| **Lieux sûrs dynamiques** | Base Angela API non connectée |

---

## Interface JavaScript ↔ Android (Bridges)

### `window.AndroidBridge` (classe `MarselBridge`)

Méthodes appelables depuis JavaScript :

```javascript
// Flash
AndroidBridge.activateFlash(intervalMs)   // Démarre le flash
AndroidBridge.stopFlash()                 // Arrête le flash

// Audio
AndroidBridge.startAudioRecord()          // Démarre enregistrement M4A
AndroidBridge.stopAudioRecord()           // Arrête et sauvegarde

// Haptique
AndroidBridge.vibrate(durationMs)

// Réseau
AndroidBridge.getNetworkType()            // Retourne "WIFI", "MOBILE", "NONE"

// SMS
AndroidBridge.sendEmergencySMS(to, message) // Envoi SMS direct
AndroidBridge.hasSMSPermission()          // Vérifie permission SEND_SMS

// Relay
AndroidBridge.sendEmergencyViaRelay(messageJson)  // Envoie paquet relay aux peers P2P

// WiFi Direct (via AndroidBridge)
AndroidBridge.startP2PDiscovery()
AndroidBridge.stopP2PDiscovery()
AndroidBridge.getP2PPeers()               // Retourne JSON Array des peers
AndroidBridge.createP2PGroup()
AndroidBridge.removeP2PGroup()

// Notifications
AndroidBridge.showNotification(title, message)

// GPS
AndroidBridge.getLocation()               // Retourne "{lat:X,lng:Y}" ou null
AndroidBridge.startLocationUpdates()      // Démarre les updates GPS natives
AndroidBridge.stopLocationUpdates()
```

### `window.AndroidWifiDirect` (classe `WifiDirectBridge`)

Duplique certaines méthodes P2P pour compatibilité ascendante.

### Callbacks Android → JavaScript

Le code Kotlin appelle ces fonctions JS via `webView.evaluateJavascript(...)` :

```javascript
window.onLocationUpdate(lat, lng, accuracy)    // Mise à jour GPS (3 args séparés)
window.onRelayMessageReceived(jsonString)       // Paquet relay reçu
window.onP2PPeersChanged(jsonArray)            // Liste peers P2P mise à jour
window.onP2PConnected(groupOwnerAddress)       // Connexion P2P établie
window.onP2PDisconnected()                     // Déconnexion P2P
```

---

## Protocole Marsel Relay Network

### Vue d'ensemble

Le Marsel Relay Network permet la transmission d'alertes d'urgence **sans internet**, via WiFi Direct (P2P) entre téléphones à portée (~100m extérieur).

⚠️ **Le WiFi doit être activé** sur les deux téléphones (même sans connexion internet). En mode avion, le relay est physiquement impossible sauf si le WiFi est réactivé manuellement.

### Transport principal : DNS-SD service discovery (sans connexion)

`WifiP2pManager.connect()` entre deux téléphones non appairés affiche une **boîte de dialogue d'invitation** que l'autre utilisateur doit accepter — inutilisable en situation d'urgence. Le transport principal est donc le **DNS-SD service discovery** de WiFi Direct :

- Le téléphone en urgence enregistre un service local `_marsel._tcp` dont le **TXT record contient le paquet d'urgence compact** (type, messageId, pseudo, lat, lng, timestamp, hopCount).
- Tous les téléphones Marsel à portée exécutent `discoverServices()` en continu (ré-armé toutes les 20s) et reçoivent le TXT record **passivement : aucune connexion, aucun appairage, aucune action utilisateur**.
- Clés du TXT record (compactes, < 900 octets au total) : `y`=type (E/P/R), `i`=messageId, `e`=emergencyId, `p`=pseudo, `a`=lat, `o`=lng, `s`=timestamp, `h`=hopCount.
- Les contacts ne voyagent pas dans le TXT record : le téléphone émetteur envoie lui-même les SMS ; les voisins affichent et relayent uniquement.
- La position est ré-enregistrée toutes les 10s (paquet `P`) pour le tracking. Le paquet de résolution (`R`) reste diffusé 2 minutes puis s'efface.

### Transport secondaire : socket TCP (connexion P2P)

- **Group Owner (GO)** : Un téléphone devient GO après `createGroup()` ou négociation P2P.
  Son IP est toujours `192.168.49.1`.
- **Client** : Les autres téléphones se connectent au GO.
- **Paramètre `groupOwnerIntent = 0`** : Le téléphone qui déclenche l'urgence préfère être **client**, ce qui lui permet d'envoyer vers `192.168.49.1:8890` de manière fiable.
- La connexion n'est tentée **que si un message est en attente** (elle déclenche un dialogue d'invitation sur l'autre téléphone). Si l'utilisateur de l'autre téléphone accepte, le paquet complet (avec contacts) transite par socket.

### Types de paquets

```json
// Alerte d'urgence
{
  "type": "MARSEL_EMERGENCY",
  "id": "uuid",
  "senderId": "pseudo",
  "lat": 48.8566,
  "lng": 2.3522,
  "timestamp": 1234567890,
  "contacts": [...],
  "hopCount": 0
}

// Mise à jour position (tracking en urgence)
{
  "type": "MARSEL_POSITION_UPDATE",
  "emergencyId": "uuid",
  "senderId": "pseudo",
  "lat": 48.8570,
  "lng": 2.3525,
  "timestamp": 1234567890,
  "hopCount": 0
}

// Fin d'alerte
{
  "type": "MARSEL_EMERGENCY_RESOLVED",
  "emergencyId": "uuid",
  "senderId": "pseudo",
  "lat": 48.8570,
  "lng": 2.3525,
  "timestamp": 1234567890,
  "hopCount": 0
}
```

### Protocole TCP (port 8890)

Le relay est **bidirectionnel** :

```
Client                          Serveur (GO - port 8890)
  |                                      |
  |-- connexion TCP ─────────────────────>|
  |-- messageJson + "\n" ────────────────>|  (ou "" si pas de message à envoyer)
  |                                      |-- traite le message si non vide
  |                                      |-- lit pendingRelayMessages
  |<-- message1 + "\n" ─────────────────|  (pour chaque message en attente)
  |<-- message2 + "\n" ─────────────────|
  |-- fermeture connexion ───────────────>|
```

**Store-and-forward** : Si personne n'est connecté quand l'urgence arrive, les messages sont stockés dans `pendingRelayMessages` (liste thread-safe avec `synchronized`) et envoyés au prochain client qui se connecte.

**Déduplication** : Chaque paquet a un `id` unique. Les IDs traités sont stockés dans `processedMessageIds` (nettoyage automatique quand > 1000 entrées, supprime les 100 plus anciens).

**Limit de hop** : `RELAY_HOP_LIMIT = 10` — un paquet est abandonné après 10 retransmissions.

### Auto-découverte et connexion

Le `WifiP2pBroadcastReceiver` dans `MainActivity.kt` :
1. Démarre la découverte automatiquement quand le WiFi P2P est activé
2. Se connecte automatiquement au premier peer trouvé (si pas déjà GO)
3. En cas de connexion, tire les messages du GO (envoie `""` si rien à envoyer)

---

## Base de données locale (IndexedDB)

4 object stores, persistants entre les sessions :

| Store | Clé | Contenu |
|-------|-----|---------|
| `safe_places` | `id` | Lieux sûrs (commissariats, hôpitaux, safe places) |
| `emergency_events` | `id` | Historique des urgences reçues/envoyées |
| `relay_queue` | `id` | File de messages relay en attente d'envoi |
| `profile` | `key` | Profil utilisateur (pseudo, numéro) et contacts |

Accès dans `script.js` via la variable globale `MARSEL.db`.

---

## Stockage des enregistrements audio

Les enregistrements sont sauvegardés en **AAC/M4A** à :

```
/sdcard/Android/data/com.example.marsel/cache/marsel_record_[timestamp].m4a
```

Pour y accéder :
- **Android Studio** : Device File Explorer → `sdcard/Android/data/com.example.marsel/cache/`
- **ADB** :
  ```bash
  adb pull /sdcard/Android/data/com.example.marsel/cache/ ./recordings/
  ```

---

## Flow d'urgence complet

### Déclenchement (téléphone A)

1. Maintien du bouton urgence pendant **5 secondes**
2. Récupération GPS (position actuelle)
3. Récupération contacts de confiance depuis IndexedDB
4. Selon réseau disponible (`getNetworkType()`) :
   - `WIFI` ou `MOBILE` → `sendEmergencyViaInternet()` (SMS direct via SIM)
   - `NONE` → `sendEmergencyViaRelayNetwork()` (WiFi Direct P2P)
5. Démarrage tracking GPS toutes les 10s → paquets `MARSEL_POSITION_UPDATE`
6. Format SMS envoyé :
   ```
   🚨 ALERTE MARSEL
   [Pseudo] a déclenché une alerte.
   Position: https://maps.google.com/?q=[lat],[lng]
   Heure: [HH:MM]
   ```

### Réception (téléphone B, voisin Marsel)

1. Paquet `MARSEL_EMERGENCY` reçu via relay TCP
2. Callback `window.onRelayMessageReceived(jsonStr)` appelé depuis Kotlin
3. Notification système affichée
4. Navigation automatique vers l'écran d'accueil (si pas dessus)
5. Marqueur orange ajouté sur la carte après 700ms (délai init carte)
6. Mise à jour du marqueur à chaque `MARSEL_POSITION_UPDATE` reçu
7. Retransmission du paquet aux peers voisins (relay hop)

### Fin d'alerte (téléphone A)

1. Pression sur bouton "Fin d'alerte"
2. SMS de résolution envoyé aux contacts :
   ```
   ✅ FIN D'ALERTE MARSEL
   [Pseudo] est en sécurité.
   Dernière position connue: https://maps.google.com/?q=[lat],[lng]
   ```
3. Paquet `MARSEL_EMERGENCY_RESOLVED` envoyé via relay
4. Arrêt du tracking GPS

---

## Détection des téléphones Marsel voisins

La détection repose sur **WiFi Direct P2P** (pas Bluetooth, pas NFC).

### Ce que voit l'utilisateur sur le téléphone voisin

Quand un téléphone Marsel à proximité déclenche une urgence :

1. **Notification système** : "🚨 Alerte Marsel — [Pseudo] a déclenché une alerte d'urgence"
2. **Écran d'accueil ouvert automatiquement** (si l'utilisateur est sur un autre écran)
3. **Marqueur orange sur la carte** à la position GPS de la personne en danger
4. **Mise à jour en temps réel** : le marqueur se déplace selon les `POSITION_UPDATE`
5. **Disparition du marqueur** quand la personne envoie une fin d'alerte

### Portée

La portée WiFi Direct est typiquement **50-100m en extérieur**, moins en intérieur. Le relay hop permet d'étendre la portée : si 3 téléphones Marsel sont en ligne (A → B → C), l'alerte de A peut atteindre C.

---

## Limitations et travaux futurs

### Limitations actuelles

- **Un seul GO par groupe** : WiFi Direct ne supporte qu'un groupe à la fois par interface WiFi.
- **Pas de mesh simultané** : Un téléphone ne peut pas être GO et client en même temps.
- **Portée limitée** : ~100m, sans infrastructure réseau supplémentaire.
- **Android uniquement** : Pas d'app iOS.
- **minSdk 24** : Android 7.0 minimum (couvre ~98% des appareils en 2024).
- **Pas de chiffrement** : Les paquets relay et SMS transitent en clair.
- **Pas d'authentification** : N'importe quel appareil peut envoyer un paquet relay.

### Travaux futurs suggérés

- **Chiffrement E2E** : Chiffrer les paquets relay avec les clés publiques des contacts.
- **Authentification relay** : Token ou challenge-response pour éviter les faux paquets.
- **Service en arrière-plan** : `ForegroundService` pour maintenir le relay actif quand l'app est fermée.
- **LoRa** : Intégration radio longue portée (mentionné dans le cahier des charges).
- **Messagerie** : Implémenter le chiffrement et le protocole de messagerie sécurisée.
- **Base Angela** : Connecter l'API de lieux sûrs dynamiques.
- **Tests instrumentés** : Ajouter des tests UI et relay.

---

## Tests sur 2 téléphones

Pour tester le Marsel Relay Network :

### Prérequis

- 2 téléphones Android physiques (le WiFi Direct ne fonctionne pas sur émulateur)
- App installée sur les deux
- Profils configurés (pseudo + numéro)
- Contacts de confiance avec numéros mobiles

### Procédure

1. **Téléphone B (voisin)** : Ouvrir l'app → laisser en arrière-plan
   - Le WiFi Direct discovery démarre automatiquement
2. **Téléphone A (urgence)** : Maintenir le bouton urgence 5 secondes
3. **Observer sur téléphone B** :
   - Notification "🚨 Alerte Marsel"
   - Écran carte ouvert automatiquement
   - Marqueur orange à la position du téléphone A
4. **Déplacer le téléphone A** → le marqueur sur B se met à jour toutes les 10s
5. **Téléphone A** : Appuyer "Fin d'alerte" → marqueur disparaît sur B

### Logs de débogage

Dans Android Studio → Logcat, filtrer par tag `MarselBridge` ou `WifiP2p` :

```
# Voir les messages relay
adb logcat -s MarselBridge

# Voir les événements WiFi Direct
adb logcat -s WifiP2pManager
```

---

## Design system

- **Couleur principale** : Orange `#E84315`
- **Couleur secondaire** : Bleu `#1A35C8`
- **Police** : Nunito (Google Fonts, chargée via CDN)
- **Fond** : Dégradé sombre `#0D1B2A` → `#1A2A3A`
- **Assets** : `Bouton-urgence.png` (bouton principal)

---

## Structure de l'état global JS

L'objet `MARSEL` dans `script.js` contient tout l'état de l'application :

```javascript
const MARSEL = {
    currentLat: null, currentLng: null,    // Position GPS actuelle
    emergencyActive: false,                 // Urgence en cours
    emergencyId: null,                      // UUID de l'urgence active
    leafletMap: null,                       // Instance Leaflet
    mapInitialized: false,                  // Carte initialisée
    incidentMarkers: {},                    // Marqueurs incidents {senderId: marker}
    networkType: 'NONE',                    // WIFI / MOBILE / NONE
    p2pPeers: [],                           // Liste peers WiFi Direct
    relayQueue: [],                         // File relay locale
    processedMessageIds: {},                // Déduplication paquets relay
    mode: 'normal',                         // normal / emergency
    db: null,                               // Instance IndexedDB
    trackingInterval: null,                 // Timer tracking urgence
    firstGpsFix: true,                      // Pour centrer la carte au 1er fix
    contacts: []                            // Contacts de confiance chargés
};
```

---

*Dernière mise à jour : Juin 2026 — Branche `test/experimentation`*
