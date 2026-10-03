# Cahier des charges technique

## Industrialisation de l’application Marsel et du Marsel Relay Network

**Projet :** Marsel
**Dépôt :** `ashleyappadoo/marsel`
**Branche de travail :** `test/experimentation`
**Version du document :** 1.0
**Date :** 5 août 2026
**Nature du document :** audit technique, spécifications de correction et critères de mise en production

---

# 1. Objet du document

Le présent cahier des charges définit les travaux nécessaires pour transformer l’application Marsel actuellement présente sur la branche `test/experimentation` en une application Android :

* fonctionnelle de bout en bout ;
* fiable en arrière-plan ;
* robuste face aux coupures réseau et aux changements de topologie Wi-Fi Direct ;
* sécurisée contre l’injection et la falsification de messages ;
* respectueuse de la confidentialité des utilisateurs ;
* testable automatiquement ;
* distribuable sous forme d’APK ;
* préparée pour une future publication en production.

Le chantier concerne particulièrement le **Marsel Relay Network**, ci-après « MRN », qui s’appuie actuellement sur :

* le Wi-Fi Direct Android ;
* la découverte de services DNS-SD ;
* la réémission de messages entre téléphones ;
* des sockets TCP ;
* l’envoi de SMS par un téléphone relais disposant d’une connectivité mobile.

Le développeur doit travailler sur la branche :

`test/experimentation`

Aucune modification ne doit être fusionnée dans la branche principale avant validation complète des critères définis dans ce document.

---

# 2. Résultat attendu

À l’issue du chantier, une alerte Marsel doit pouvoir :

1. être déclenchée avec ou sans position GPS disponible ;
2. être conservée localement de manière durable ;
3. être diffusée aux téléphones Marsel à proximité ;
4. être relayée sur plusieurs sauts Wi-Fi Direct ;
5. continuer à circuler lorsque l’application n’est plus affichée ;
6. être mise à jour lorsqu’une nouvelle position est obtenue ;
7. être terminée explicitement ;
8. ne jamais réapparaître après sa résolution ;
9. déléguer l’envoi de SMS à un relais autorisé ;
10. éviter tout envoi de SMS en double ;
11. fournir un résultat réel par destinataire ;
12. ne pas exposer les contacts, les numéros, l’identité réelle ou les coordonnées sensibles dans les annonces DNS-SD ;
13. rejeter les messages falsifiés, expirés ou malformés ;
14. produire des journaux techniques exploitables sans données personnelles ;
15. être compilée et testée automatiquement par GitHub Actions.

---

# 3. État actuel et synthèse de l’audit

## 3.1 Conclusion générale

La branche `test/experimentation` constitue un prototype technique avancé, mais elle ne peut pas encore être considérée comme une version de production.

Le fonctionnement actuel ressemble à un système de diffusion opportuniste :

* découverte DNS-SD ;
* réannonce de certains messages ;
* connexion Wi-Fi Direct ponctuelle ;
* échange TCP avec un Group Owner ;
* déduplication locale.

Il ne s’agit pas encore d’un réseau maillé disposant :

* d’une identité cryptographique fiable ;
* d’un routage déterministe ;
* d’un état durable par message ;
* d’une élection de relais ;
* d’une gestion transactionnelle des SMS ;
* d’un transport authentifié ;
* d’une supervision fiable en arrière-plan.

## 3.2 Points positifs existants

Les éléments suivants doivent être conservés ou réutilisés :

* séparation partielle de la logique pure dans `MarselProtocol.kt` ;
* tests unitaires JVM du protocole ;
* déduplication persistante via `DedupLedger` ;
* distinction des types d’événements : urgence, position, résolution, demandes SMS et accusés ;
* limitation du nombre de sauts ;
* filtrage des pairs Marsel découverts par DNS-SD ;
* réenregistrement périodique des services ;
* reprise partielle d’une alerte après redémarrage ;
* fonctionnement possible sans connexion Internet ;
* interface WebView embarquée dans l’APK ;
* classification native de la qualité réseau.

## 3.3 Blocages de production identifiés

### P0 — absence de fonctionnement fiable en arrière-plan

Le `WifiP2pBroadcastReceiver` est enregistré dans `onResume()` et désenregistré dans `onPause()`. La découverte, les sockets, le GPS et le timer restent donc liés de manière incohérente au cycle de vie de l’activité. Le dépôt reconnaît lui-même que le MRN ne survit pas de façon fiable à l’arrière-plan, à Doze ou à la destruction du processus.

### P0 — messages non authentifiés

Les messages MRN ne possèdent actuellement aucune signature cryptographique fiable. Un tiers peut donc théoriquement fabriquer :

* une fausse alerte ;
* une fausse résolution ;
* une fausse position ;
* une demande d’envoi SMS ;
* un faux accusé de réception.

Ce point est également identifié dans le fichier `TODO.md`.

### P0 — exposition de données dans DNS-SD et TCP

Le protocole actuel peut transporter dans les annonces ou messages :

* le pseudo ;
* les coordonnées ;
* les numéros des contacts ;
* le statut de délégation SMS.

Les contacts d’urgence apparaissent notamment dans la clé TXT `c`. Les sockets ne disposent pas de chiffrement applicatif ni d’authentification mutuelle.

### P0 — délégation SMS insuffisamment contrôlée

Un appareil peut recevoir une demande de SMS et tenter d’envoyer les messages sans mécanisme de confiance fort entre l’émetteur et le relais.

Il n’existe pas encore :

* d’autorisation cryptographique du demandeur ;
* d’élection d’un relais unique ;
* de bail de traitement ;
* de transaction par destinataire ;
* de limitation robuste du nombre d’envois ;
* de prévention distribuée des doublons.

### P0 — coordonnées GPS obligatoires dans une partie du protocole

L’interface JavaScript permet de déclencher une alerte sans GPS et produit alors `lat: null` et `lng: null`. Cependant, le protocole Kotlin actuel refuse de construire ou de décoder certains enregistrements TXT sans latitude et longitude. Le comportement attendu et l’implémentation sont donc contradictoires.

### P0 — accusé SMS incomplet

L’application suit principalement le premier destinataire ou le premier résultat d’un envoi multipart. Les autres destinataires peuvent échouer sans que l’état global de l’opération soit exact.

### P0 — même identifiant utilisé pour des états différents

Une alerte envoyée initialement sans délégation SMS peut être réémise avec le même `messageId` après l’échec du SMS local. Un téléphone ayant déjà dédupliqué le premier message peut ignorer la nouvelle version demandant la délégation SMS.

Une demande SMS doit toujours être un message distinct de l’alerte initiale.

### P0 — serveurs TCP insuffisamment durcis

L’activité démarre deux serveurs :

* port historique `8888` ;
* port relais `8890`.

Le serveur relais crée un thread par connexion et utilise `readLine()` sans authentification de session, taille maximale stricte ou délai de lecture explicite. Le port historique doit être supprimé.

### P1 — files de messages non transactionnelles

Certaines files sont vidées avant que la réussite de l’envoi asynchrone ne soit confirmée. Une erreur après retrait peut provoquer une perte de message.

À l’inverse, certains messages restent disponibles sans état d’expiration ou de remise explicite et peuvent être rejoués.

### P1 — stockage sensible non protégé

Des informations sont conservées dans :

* `localStorage` ;
* IndexedDB ;
* SharedPreferences ;
* les journaux ;
* des fichiers audio.

Les données comprennent le profil, les contacts, les alertes, les positions et l’historique. Le manifeste autorise actuellement la sauvegarde Android.

### P1 — WebView trop permissif

La WebView autorise actuellement :

* JavaScript ;
* DOM Storage ;
* l’accès aux fichiers ;
* l’accès au contenu ;
* la géolocalisation ;
* deux interfaces JavaScript natives.

Les URL autres que `tel:`, `mailto:` et `sms:` ne sont pas explicitement refusées. Les assets sont chargés au moyen de `file:///android_asset/`.

Android recommande de ne rendre un pont natif accessible qu’à du contenu entièrement maîtrisé et présente `WebViewAssetLoader` comme une alternative plus sûre au chargement `file://`.

### P1 — secrets potentiels dans l’APK

`config.js` prévoit directement des champs pour :

* une clé API ;
* un identifiant Twilio ;
* un token Twilio ;
* une clé serveur FCM.

Tout secret renseigné dans ce fichier serait extractible depuis l’APK.

### P1 — CI insuffisante

La CI actuelle exécute uniquement :

`./gradlew testDebugUnitTest`

Elle ne réalise pas :

* `assembleDebug` ;
* `lintDebug` ;
* de test instrumenté ;
* d’analyse statique ;
* d’archivage d’APK ;
* de contrôle de dépendances.

### P1 — architecture monolithique

`MainActivity.kt` concentre notamment :

* le WebView ;
* les permissions ;
* le Wi-Fi Direct ;
* DNS-SD ;
* les sockets ;
* le GPS ;
* les SMS ;
* les notifications ;
* l’audio ;
* le bridge JavaScript ;
* la déduplication.

`script.js` concentre l’interface, le stockage, la logique d’urgence, les SMS, le MRN, la carte et l’authentification.

Cette architecture rend les régressions difficiles à isoler et les tests complexes.

### P2 — configuration de build de prototype

Le projet utilise encore :

* `applicationId = "com.example.marsel"` ;
* `versionCode = 1` ;
* `versionName = "1.0"` ;
* `isMinifyEnabled = false` en release ;
* des dépendances Compose alors que l’activité utilise essentiellement une WebView.

---

# 4. Périmètre du chantier

## 4.1 Inclus

Le chantier comprend :

* architecture native du MRN ;
* protocole MRN version 2 ;
* cycle de vie en arrière-plan ;
* persistance Room ;
* sécurité des messages ;
* sécurisation des sockets ;
* gestion des alertes ;
* gestion des positions ;
* gestion des résolutions ;
* délégation SMS ;
* gestion des accusés ;
* sécurisation de la WebView ;
* stockage sécurisé ;
* notifications ;
* journalisation ;
* tests automatisés ;
* CI Android ;
* production d’un APK debug ;
* préparation du build release ;
* documentation technique.

## 4.2 Hors périmètre initial

Sont hors périmètre du premier lot de stabilisation :

* une application iOS ;
* un portail d’administration complet ;
* une messagerie instantanée générale ;
* le transfert de fichiers audio au travers du MRN ;
* la synchronisation cloud des historiques ;
* un algorithme de routage mondial ;
* la garantie de fonctionnement sur une distance non couverte par des appareils Marsel intermédiaires ;
* la certification définitive Google Play ;
* la garantie de livraison opérateur d’un SMS après acceptation par le modem.

Le MMS audio reste un mécanisme best-effort séparé.

---

# 5. Principes d’architecture obligatoires

## 5.1 Séparation des responsabilités

La logique critique ne doit plus être pilotée principalement par JavaScript.

Le code natif doit devenir propriétaire de :

* l’état des alertes ;
* la génération des identifiants ;
* la persistance ;
* le protocole ;
* la validation ;
* la signature ;
* le chiffrement ;
* la déduplication ;
* la découverte ;
* les sockets ;
* la délégation SMS ;
* les accusés ;
* les délais ;
* les notifications ;
* la reprise après arrêt.

La WebView doit uniquement gérer :

* l’affichage ;
* les interactions utilisateur ;
* la carte ;
* les formulaires ;
* la présentation des états fournis par le natif.

## 5.2 Arborescence native recommandée

Créer les packages suivants :

```text
com.marsel.app
├── app
│   ├── MainActivity
│   └── MarselApplication
├── bridge
│   ├── MarselJsBridge
│   └── BridgeModels
├── data
│   ├── MarselDatabase
│   ├── dao
│   ├── entity
│   ├── repository
│   └── migration
├── mrn
│   ├── model
│   ├── protocol
│   ├── security
│   ├── discovery
│   ├── transport
│   ├── routing
│   ├── service
│   └── diagnostics
├── emergency
│   ├── EmergencyCoordinator
│   ├── EmergencyStateMachine
│   └── EmergencyRepository
├── sms
│   ├── SmsRelayCoordinator
│   ├── SmsSendTracker
│   ├── SmsClaimCoordinator
│   └── SmsTemplateFactory
├── location
│   └── EmergencyLocationCoordinator
├── notification
│   └── MarselNotificationManager
└── security
    ├── DeviceIdentityManager
    ├── SecureStorage
    └── LocalAuthManager
```

## 5.3 Bibliothèques recommandées

Le développeur doit privilégier :

* Room pour la persistance transactionnelle ;
* Kotlin coroutines et Flow ;
* `kotlinx.serialization` pour les modèles de protocole ;
* Android Keystore pour les clés locales ;
* AES-GCM pour le chiffrement symétrique ;
* une bibliothèque cryptographique reconnue telle que Tink lorsque pertinent ;
* WorkManager uniquement pour les tâches différées non temps réel ;
* un ForegroundService pour le MRN actif ;
* `WebViewAssetLoader` pour servir les ressources embarquées.

Les clés cryptographiques sensibles doivent être protégées par Android Keystore. Android recommande Keystore lorsque les clés nécessitent une protection renforcée et recommande de ne pas coder les secrets en dur dans l’application.

---

# 6. Modèle de données persistant

## 6.1 Base Room obligatoire

Créer une base Room remplaçant progressivement :

* `localStorage['marsel_emergency']` ;
* IndexedDB `emergency_events` ;
* IndexedDB `relay_queue` ;
* les maps en mémoire utilisées comme source de vérité ;
* les SharedPreferences de déduplication non structurées.

## 6.2 Entité `EmergencyEntity`

Champs minimum :

```text
emergencyId
originNodeId
displayAlias
realOwnerNameEncrypted
createdAt
updatedAt
expiresAt
status
lastLatitudeEncrypted
lastLongitudeEncrypted
lastAccuracy
lastPositionSequence
isLocal
smsStatus
resolvedAt
protocolVersion
```

États possibles :

```text
CREATED
ACTIVE_LOCAL
ACTIVE_REMOTE
RELAYING
RESOLVING
RESOLVED
EXPIRED
REJECTED
```

## 6.3 Entité `MrnMessageEntity`

Champs minimum :

```text
messageId
emergencyId
type
originNodeId
senderNodeId
sequence
createdAt
expiresAt
hopCount
maxHops
payload
payloadHash
signature
verificationStatus
processingStatus
retryCount
nextAttemptAt
receivedAt
forwardedAt
lastError
```

États de traitement :

```text
RECEIVED
VERIFIED
REJECTED
PENDING_FORWARD
FORWARDING
FORWARDED
ACKNOWLEDGED
EXPIRED
FAILED_RETRYABLE
FAILED_FINAL
```

## 6.4 Entité `SmsRequestEntity`

Champs minimum :

```text
requestId
emergencyId
requestType
originNodeId
createdAt
expiresAt
state
claimOwnerNodeId
claimExpiresAt
recipientCount
templateType
attemptCount
completedAt
```

États :

```text
PENDING
CLAIMING
CLAIMED_BY_SELF
CLAIMED_BY_OTHER
SENDING
PARTIAL_SUCCESS
SUCCESS
FAILED_RETRYABLE
FAILED_FINAL
EXPIRED
```

## 6.5 Entité `SmsRecipientResultEntity`

Un enregistrement doit être créé pour chaque destinataire :

```text
requestId
recipientIdHash
partCount
partsAccepted
partsFailed
sentStatus
deliveredStatus
lastResultCode
updatedAt
```

Le numéro en clair ne doit pas être utilisé comme clé ni apparaître dans les logs.

---

# 7. Machine à états de l’urgence

## 7.1 Déclenchement

Lors de l’activation :

1. générer un `emergencyId` cryptographiquement aléatoire ;
2. créer un message `ALERT` possédant son propre `messageId` ;
3. enregistrer l’urgence dans Room avant toute opération réseau ;
4. démarrer le ForegroundService ;
5. créer la notification persistante ;
6. démarrer la localisation si autorisée ;
7. diffuser l’alerte même si aucune position n’est disponible ;
8. lancer le chemin SMS direct ou la création d’un `SMS_REQUEST`.

Le bouton d’urgence ne doit jamais rester bloqué dans l’attente :

* du GPS ;
* du Wi-Fi Direct ;
* d’Internet ;
* de l’envoi SMS ;
* de la connexion à un pair.

## 7.2 Alerte sans GPS

Une alerte sans GPS est valide.

Le modèle doit distinguer :

```text
locationStatus = UNAVAILABLE
locationStatus = ACQUIRING
locationStatus = AVAILABLE
locationStatus = STALE
```

Les champs `latitude` et `longitude` doivent être optionnels pour :

* `ALERT` ;
* `RESOLVED` ;
* `SMS_REQUEST` ;
* `SMS_CLAIM` ;
* `SMS_RESULT` ;
* `ACK`.

Ils sont obligatoires uniquement pour `POSITION`.

Lorsqu’un premier GPS valide arrive :

1. incrémenter `positionSequence` ;
2. enregistrer la position ;
3. produire un nouveau message `POSITION` avec un nouveau `messageId` ;
4. diffuser immédiatement cette position ;
5. ne pas modifier l’identifiant de l’alerte initiale.

## 7.3 Mise à jour de position

Chaque position doit posséder :

* un `messageId` unique ;
* un `emergencyId` stable ;
* un numéro de séquence croissant ;
* un timestamp ;
* une précision ;
* une expiration courte.

Un message de position ne peut remplacer la position locale que si :

```text
nouvelleSequence > séquenceActuelle
```

En cas de séquences égales, le timestamp le plus récent peut être utilisé.

Les positions plus anciennes doivent être ignorées.

## 7.4 Résolution

Une résolution doit :

1. produire un message `RESOLVED` distinct ;
2. enregistrer localement l’urgence comme résolue avant diffusion ;
3. supprimer les files d’émission obsolètes ;
4. arrêter les mises à jour GPS liées à l’urgence ;
5. arrêter l’audio lié à l’urgence ;
6. supprimer ou archiver le marqueur ;
7. annuler les timers ;
8. diffuser la résolution ;
9. empêcher définitivement le rejeu d’anciennes alertes ou positions ;
10. conserver une tombstone de résolution jusqu’à expiration globale de l’urgence.

La tombstone doit être conservée plus longtemps que n’importe quelle file de messages pouvant contenir l’alerte.

---

# 8. Protocole MRN version 2

## 8.1 Enveloppe commune

Tous les messages MRN version 2 doivent utiliser une enveloppe commune :

```json
{
  "protocolVersion": 2,
  "type": "ALERT",
  "messageId": "uuid",
  "emergencyId": "uuid",
  "originNodeId": "pseudonymous-id",
  "senderNodeId": "current-hop-id",
  "createdAt": 0,
  "expiresAt": 0,
  "sequence": 0,
  "hopCount": 0,
  "maxHops": 5,
  "nonce": "base64",
  "payload": {},
  "payloadHash": "base64",
  "signature": "base64"
}
```

## 8.2 Types obligatoires

Remplacer progressivement les noms actuels par :

```text
ALERT
POSITION
RESOLVED
SMS_REQUEST
SMS_CLAIM
SMS_RESULT
ACK
```

Un champ de compatibilité peut temporairement mapper :

```text
MARSEL_EMERGENCY             → ALERT
MARSEL_POSITION_UPDATE       → POSITION
MARSEL_EMERGENCY_RESOLVED    → RESOLVED
MARSEL_RESOLVED_SMS_REQUEST  → SMS_REQUEST
MARSEL_TIMEOUT_SMS_REQUEST   → SMS_REQUEST
MARSEL_SMS_ACK               → ACK
```

## 8.3 Identifiants

Règles :

* chaque événement possède un `messageId` unique ;
* tous les événements liés à une même urgence partagent `emergencyId` ;
* une modification du contenu fonctionnel produit un nouveau `messageId` ;
* une demande SMS n’utilise jamais le `messageId` de l’alerte ;
* un ACK référence explicitement le message acquitté par `ackedMessageId` ;
* les identifiants ne doivent jamais être tronqués silencieusement ;
* toute longueur excédant la limite doit provoquer un rejet explicite.

## 8.4 Validation des entrées

Avant traitement, vérifier :

* taille totale du message ;
* version supportée ;
* type connu ;
* format des identifiants ;
* timestamp plausible ;
* `expiresAt > createdAt` ;
* non-expiration ;
* `0 <= hopCount <= maxHops` ;
* `maxHops` plafonné par la politique locale ;
* format du payload ;
* signature ;
* hash ;
* taille des listes ;
* taille des chaînes ;
* validité des coordonnées ;
* monotonie de la séquence.

Le `maxHops` fourni par un pair ne doit jamais permettre de dépasser la limite locale.

## 8.5 Expiration

Cibles initiales :

```text
ALERT       : durée de l’urgence, maximum 30 minutes sans renouvellement
POSITION    : 60 à 120 secondes
RESOLVED    : 30 minutes
SMS_REQUEST : 10 minutes
SMS_CLAIM   : durée du bail, généralement 30 à 60 secondes
SMS_RESULT  : 30 minutes
ACK         : 30 minutes
```

Les durées exactes doivent être centralisées dans une configuration native.

## 8.6 Déduplication

La clé de déduplication doit inclure :

```text
protocolVersion + type + messageId
```

La déduplication ne doit pas empêcher l’exécution d’un effet différé.

Exemple :

* une demande SMS reçue sans réseau est enregistrée comme connue ;
* elle reste cependant dans l’état `PENDING` ;
* lorsque le réseau revient, elle est réévaluée ;
* elle n’est pas rejetée au motif qu’elle a déjà été vue.

Séparer strictement :

* message déjà reçu ;
* message déjà affiché ;
* message déjà relayé ;
* effet SMS déjà revendiqué ;
* effet SMS déjà exécuté ;
* ACK déjà émis.

---

# 9. Sécurité cryptographique

## 9.1 Identité d’appareil

Chaque installation doit posséder une paire de clés créée au premier lancement.

Recommandation de compatibilité :

* ECDSA P-256 pour la signature ;
* clé privée non exportable dans Android Keystore ;
* identifiant de nœud dérivé du hash de la clé publique ;
* rotation d’un identifiant pseudonyme pour les annonces publiques.

## 9.2 Modèle de confiance

Deux modes doivent être distingués.

### Mode communautaire

Un téléphone communautaire peut :

* découvrir des alertes ;
* relayer des messages signés ;
* afficher une alerte pseudonymisée ;
* transporter un payload chiffré qu’il ne peut pas lire.

Il ne doit pas pouvoir, par défaut :

* accéder aux numéros des proches ;
* connaître le nom réel ;
* modifier le contenu ;
* envoyer un SMS pour un tiers sans autorisation explicite.

### Mode relais SMS de confiance

Un téléphone autorisé à envoyer des SMS doit disposer :

* d’un opt-in utilisateur explicite ;
* d’une identité vérifiable ;
* d’un certificat ou d’un appairage de confiance ;
* d’une politique de débit ;
* d’un historique local des opérations ;
* d’une possibilité de désactivation immédiate.

Pour une diffusion publique à grande échelle, une infrastructure minimale de certification Marsel est nécessaire.

En l’absence de backend de certification, la délégation SMS doit être limitée à :

* des appareils appairés manuellement ;
* des appareils professionnels préprovisionnés ;
* ou des relais enregistrés par QR code.

Une application communautaire totalement ouverte ne doit pas accepter de demandes arbitraires d’envoi SMS.

## 9.3 Signature

Tous les messages doivent être signés.

La signature doit couvrir une représentation canonique comprenant au minimum :

```text
protocolVersion
type
messageId
emergencyId
originNodeId
senderNodeId
createdAt
expiresAt
sequence
hopCount
maxHops
payloadHash
nonce
```

La réémission ne doit pas détruire la signature de l’origine.

Prévoir :

* une signature de l’origine sur le contenu ;
* éventuellement une attestation de saut séparée signée par chaque relais.

## 9.4 Chiffrement

Les données suivantes ne doivent jamais circuler en clair dans DNS-SD :

* numéros de téléphone ;
* noms des contacts ;
* nom réel de l’émetteur ;
* données de profil ;
* contenu audio ;
* token ;
* secret ;
* historique ;
* coordonnées exactes lorsque le destinataire n’y est pas autorisé.

Pour les sessions TCP :

* effectuer un échange de clés authentifié ;
* dériver une clé de session ;
* chiffrer les messages avec AES-GCM ;
* utiliser un nonce unique ;
* rejeter toute réutilisation de nonce ;
* vérifier l’identité du pair avant de transmettre le bundle sensible.

## 9.5 Anonymisation

Créer au minimum :

```text
realUserId
publicNodeId
emergencyAlias
```

Les utilisateurs à proximité voient par défaut :

```text
Alerte Marsel #A7F29C
```

Ils ne voient pas :

```text
Alice Dupont
+336...
```

Le vrai nom reste disponible :

* dans les SMS adressés aux proches ;
* dans un canal chiffré destiné à un proche reconnu ;
* dans l’interface de l’émetteur.

Pour les coordonnées, prévoir deux niveaux :

```text
coarseLocation
exactLocationEncrypted
```

La politique d’affichage exacte doit être définie produit par produit.

---

# 10. Wi-Fi Direct et découverte DNS-SD

## 10.1 Usage de DNS-SD

DNS-SD doit être utilisé comme canal de découverte et d’annonce légère, non comme base de données contenant toutes les informations de l’urgence.

Android prend officiellement en charge la découverte de services Wi-Fi Direct par DNS-SD et l’échange de données par sockets Java après établissement de la connexion.

## 10.2 Contenu TXT autorisé

Le TXT doit contenir uniquement des métadonnées compactes :

```text
v   version
n   identifiant pseudonyme du nœud
c   capacités
q   identifiant ou digest de file
t   type d’annonce
x   expiration
s   signature compacte
```

Le TXT ne doit pas contenir :

```text
lat
lng
pseudo réel
contacts
numéros
corps SMS
clé API
token
```

## 10.3 Capacités annoncées

Exemple :

```text
RELAY
SMS_RELAY_TRUSTED
INTERNET_GATEWAY
LOCATION_HELPER
PROTOCOL_V2
```

Une capacité déclarée ne doit pas être considérée comme fiable avant authentification de la session.

## 10.4 Sérialisation des opérations P2P

Les appels à `WifiP2pManager` doivent être coordonnés au moyen d’une file d’opérations unique.

Interdire les appels concurrents non contrôlés à :

* `clearServiceRequests` ;
* `addServiceRequest` ;
* `discoverServices` ;
* `clearLocalServices` ;
* `addLocalService` ;
* `connect` ;
* `removeGroup`.

Implémenter un état :

```text
IDLE
REGISTERING
DISCOVERING
CONNECTING
CONNECTED
RECOVERING
BACKOFF
```

Une nouvelle opération incompatible doit attendre la fin ou l’expiration de l’opération courante.

## 10.5 Watchdog

Le watchdog ne doit pas déclencher une récupération complète simplement parce qu’aucun autre appareil n’est visible.

Il doit distinguer :

* absence normale de pair ;
* callback récent mais aucun résultat ;
* opération bloquée ;
* channel P2P perdu ;
* permission manquante ;
* Wi-Fi désactivé ;
* framework retournant `BUSY` de façon répétée.

Appliquer un backoff progressif :

```text
2 s
5 s
10 s
20 s
30 s
60 s
```

Réinitialiser le backoff après une opération réussie.

## 10.6 Group Owner

L’application doit gérer explicitement :

* changement de Group Owner ;
* perte du groupe ;
* adresse devenue invalide ;
* reconnexion ;
* changement de rôle client/serveur ;
* plusieurs messages en attente ;
* nettoyage du groupe bloqué.

Les messages ne doivent jamais être supprimés de Room avant la confirmation de transmission.

---

# 11. Transport TCP sécurisé

## 11.1 Suppression du port historique

Supprimer :

```text
port 8888
startWifiServer()
legacyServerSocket
```

Aucun mécanisme de compatibilité ne doit conserver un serveur non authentifié.

## 11.2 Serveur principal

Conserver un port unique configurable, actuellement `8890`.

Le serveur doit :

* utiliser un pool borné ;
* limiter le nombre de connexions simultanées ;
* définir un délai d’acceptation ;
* définir un délai de lecture ;
* définir un délai d’écriture ;
* limiter la taille d’une trame ;
* fermer les connexions lentes ;
* rejeter les messages non authentifiés ;
* ne jamais utiliser une lecture illimitée par `readLine()` ;
* utiliser un framing explicite.

Format de trame recommandé :

```text
4 octets longueur
N octets payload
```

Taille maximale initiale recommandée :

```text
64 Kio par trame
```

Toute trame supérieure doit être rejetée avant allocation complète.

## 11.3 Protocole de session

Séquence minimale :

```text
HELLO
IDENTITY
CHALLENGE
AUTH
CAPABILITIES
SYNC_SUMMARY
MESSAGE_REQUEST
MESSAGE_TRANSFER
MESSAGE_ACK
CLOSE
```

## 11.4 Synchronisation

Ne pas pousser aveuglément toute la file à chaque connexion.

Échanger d’abord un résumé :

```text
messageId
type
sequence
expiresAt
payloadHash
```

Le pair demande uniquement les messages absents ou plus récents.

---

# 12. Délégation et envoi des SMS

## 12.1 Conformité produit

La permission `SEND_SMS` est considérée comme sensible et son utilisation est restreinte pour les applications distribuées sur Google Play. Une déclaration et une validation de l’usage peuvent être requises.

Avant publication, Marsel doit disposer :

* d’une justification claire de la fonctionnalité principale ;
* d’une politique de confidentialité ;
* d’une information visible avant l’activation ;
* d’un consentement explicite ;
* d’une procédure Play Console ;
* d’un mécanisme alternatif si l’autorisation est refusée.

## 12.2 Interdiction de l’envoi silencieux non consenti

Le mode relais SMS ne doit pas être activé secrètement.

L’utilisateur relais doit avoir accepté une option explicite du type :

```text
Autoriser mon téléphone à transmettre des SMS d’urgence Marsel
```

L’écran doit préciser :

* que des SMS peuvent être facturés par l’opérateur ;
* que le numéro du relais peut être techniquement visible par le destinataire ;
* la limite quotidienne ;
* le mode de désactivation ;
* les conditions de confiance.

Une notification ou un historique local doit indiquer les opérations réalisées.

## 12.3 Création d’une demande

Un `SMS_REQUEST` doit contenir :

```text
requestId
emergencyId
requestType
templateType
encryptedRecipientBundle
createdAt
expiresAt
originSignature
```

Le bundle chiffré contient :

* les numéros ;
* les variables du modèle de message ;
* le nom réel destiné aux proches ;
* éventuellement la position exacte.

Le corps libre arbitraire doit être interdit.

Seuls des modèles internes versionnés sont autorisés :

```text
ALERT_INITIAL
ALERT_POSITION_UPDATE
ALERT_STILL_ACTIVE
ALERT_RESOLVED
```

## 12.4 Élection d’un relais unique

Lorsqu’un appareil autorisé reçoit un `SMS_REQUEST` :

1. vérifier la signature ;
2. vérifier la confiance ;
3. vérifier l’expiration ;
4. vérifier la limite de débit ;
5. vérifier la capacité SIM ;
6. vérifier le réseau cellulaire ;
7. calculer un délai de candidature ;
8. diffuser un `SMS_CLAIM` ;
9. attendre une courte fenêtre ;
10. abandonner si un meilleur claim valide existe ;
11. envoyer uniquement s’il possède le claim actif.

Score recommandé :

```text
trustedRelay
networkRegistered
signalQuality
batteryLevel
hopCount
stableConnection
randomJitter
```

## 12.5 Bail de claim

Le claim doit posséder :

```text
claimId
requestId
relayNodeId
createdAt
leaseExpiresAt
score
signature
```

Les autres relais mettent la demande en état :

```text
CLAIMED_BY_OTHER
```

Si le bail expire sans `SMS_RESULT`, une nouvelle élection peut commencer.

## 12.6 Suivi par destinataire et par partie

Pour chaque numéro :

1. découper le message avec `divideMessage` ;
2. créer un `PendingIntent` par partie ;
3. créer un `PendingIntent` de livraison si utilisé ;
4. suivre toutes les parties ;
5. considérer le destinataire comme envoyé uniquement si toutes les parties sont acceptées ;
6. considérer la demande comme réussie uniquement si tous les destinataires obligatoires sont acceptés.

Ne plus utiliser « le premier callback fait verdict ».

## 12.7 Résultats

Produire un `SMS_RESULT` contenant :

```text
requestId
relayNodeId
overallStatus
successCount
failureCount
recipientResults
completedAt
signature
```

Les destinataires doivent être référencés par un hash salé lié à la requête, pas par leur numéro en clair.

États globaux :

```text
SUCCESS
PARTIAL_SUCCESS
FAILED
EXPIRED
```

## 12.8 Reprise après perte réseau

Toute demande valide reste dans Room.

Lors du retour du réseau :

* recharger les demandes `PENDING` ;
* vérifier leur expiration ;
* vérifier qu’elles ne sont pas déjà revendiquées ;
* relancer l’élection ;
* ne pas utiliser la déduplication de réception comme blocage.

## 12.9 Limitation d’abus

Implémenter au minimum :

```text
maximum de demandes par origine et par minute
maximum de destinataires par demande
maximum de SMS relayés par heure
maximum de SMS relayés par jour
maximum de longueur par modèle
liste de types autorisés
expiration stricte
blocage temporaire après anomalies
```

Toutes les limites doivent être configurables côté natif.

---

# 13. ForegroundService MRN

## 13.1 Création

Créer un service :

```text
MrnForegroundService
```

Il devient propriétaire de :

* `WifiP2pManager.Channel` ;
* `BroadcastReceiver` P2P ;
* découverte DNS-SD ;
* services locaux ;
* serveur TCP ;
* connexions sortantes ;
* files Room ;
* tracking de la position d’urgence ;
* timers ;
* état des alertes ;
* notifications MRN ;
* reprise des effets SMS.

## 13.2 Modes du service

### Mode `ACTIVE_EMERGENCY`

Activé lorsqu’une urgence locale est active.

Fonctions :

* découverte rapide ;
* publication de l’alerte ;
* GPS ;
* audio si activé ;
* positions ;
* résolution ;
* notification persistante prioritaire.

### Mode `RELAY_OPT_IN`

Activé lorsque l’utilisateur a explicitement autorisé le mode relais.

Fonctions :

* découverte basse consommation ;
* réception et relais ;
* notification persistante indiquant que le réseau Marsel fonctionne ;
* bascule temporaire en fréquence active lorsqu’une urgence est détectée.

### Mode `STOPPED`

Aucune découverte permanente.

L’utilisateur doit pouvoir choisir ce mode.

## 13.3 Déclaration Android

Pour une cible Android moderne, les types de ForegroundService doivent être explicitement déclarés. L’accès à la localisation ou au microphone dans le service nécessite les types et permissions correspondants. Android 16 impose également la cohérence entre l’usage réel et le type déclaré.

Prévoir selon le mode :

```xml
android:foregroundServiceType="connectedDevice|location|microphone"
```

Permissions possibles :

```text
FOREGROUND_SERVICE
FOREGROUND_SERVICE_CONNECTED_DEVICE
FOREGROUND_SERVICE_LOCATION
FOREGROUND_SERVICE_MICROPHONE
```

Ne pas déclarer un type non utilisé.

## 13.4 Notification persistante

La notification doit présenter :

* état du MRN ;
* urgence locale active ou non ;
* nombre de messages en attente ;
* action « Ouvrir Marsel » ;
* action « Terminer l’alerte » si applicable ;
* action « Désactiver le mode relais » si applicable.

## 13.5 Timer de vingt minutes

Le timer ne doit plus dépendre uniquement de `setTimeout` JavaScript.

Stocker :

```text
emergencyStartedAt
reminderDueAt
```

Pendant le service actif, utiliser un mécanisme natif.

La relance à vingt minutes ne nécessite normalement pas une précision à la seconde. Utiliser de préférence une alarme compatible Doze et tolérant une légère dérive.

Les alarmes exactes sont soumises à des restrictions et leur autorisation n’est pas accordée par défaut à la majorité des nouvelles installations ciblant Android 13 ou supérieur. Elles doivent être réservées aux interruptions réellement critiques.

## 13.6 Démarrage automatique

Ne pas démarrer silencieusement un service permanent au boot avant validation :

* des restrictions Android ;
* de la politique Google Play ;
* du consentement ;
* de l’impact batterie.

Le mode relais permanent doit être une décision utilisateur explicite.

---

# 14. Sécurisation de la WebView

## 14.1 Chargement des assets

Remplacer :

```text
file:///android_asset/app.html
```

par :

```text
https://appassets.androidplatform.net/assets/app.html
```

au moyen de `WebViewAssetLoader`.

## 14.2 Paramètres

Configurer :

```text
allowFileAccess = false
allowContentAccess = false
allowFileAccessFromFileURLs = false
allowUniversalAccessFromFileURLs = false
mixedContentMode = NEVER_ALLOW
safeBrowsingEnabled = true
```

Conserver JavaScript uniquement car l’interface actuelle en dépend.

## 14.3 Navigation

`shouldOverrideUrlLoading` doit :

* autoriser les ressources `appassets.androidplatform.net` ;
* gérer explicitement `tel:`, `mailto:` et éventuellement `sms:` ;
* refuser toute navigation HTTP ;
* ouvrir les URL HTTPS externes dans un navigateur système, jamais dans la WebView privilégiée ;
* empêcher le chargement de domaines non autorisés ;
* bloquer les popups et fenêtres secondaires.

## 14.4 Bridge

Réduire les méthodes exposées.

Interdire une méthode générique acceptant un JSON arbitraire et déclenchant directement un effet sensible.

Préférer :

```text
createEmergency()
resolveEmergency()
updateProfile()
setRelayOptIn()
requestPermission()
getApplicationState()
```

Chaque méthode doit :

* valider le schéma ;
* vérifier l’état courant ;
* appliquer une limite de taille ;
* refuser les champs inconnus lorsque pertinent ;
* ne jamais accepter de numéro ou de contenu SMS provenant directement du DOM sans validation native.

## 14.5 Content Security Policy

Ajouter une CSP dans `app.html`.

Cible initiale :

```text
default-src 'self'
script-src 'self'
style-src 'self' 'unsafe-inline'
img-src 'self' data: https:
connect-src https:
frame-src 'none'
object-src 'none'
base-uri 'none'
form-action 'self'
```

Réduire progressivement `unsafe-inline`.

---

# 15. Stockage local et authentification

## 15.1 Suppression du faux login

Le pseudo-login local actuel ne doit plus accepter arbitrairement un email et un mot de passe.

Implémenter une authentification locale réelle :

* PIN ou mot de passe ;
* dérivation lente du secret ;
* sel aléatoire ;
* verrouillage progressif ;
* fallback obligatoire ;
* biométrie facultative.

## 15.2 Données à chiffrer

Chiffrer au repos :

* profil ;
* contacts ;
* nom réel ;
* historique ;
* coordonnées ;
* alertes ;
* bundles SMS ;
* résultats ;
* secrets d’appairage ;
* clés de session persistées.

## 15.3 Sauvegardes

Passer par défaut à :

```xml
android:allowBackup="false"
```

Une éventuelle réactivation nécessitera des règles excluant toutes les données sensibles. Android recommande de chiffrer les données sensibles sauvegardées et de protéger les clés avec Keystore.

## 15.4 Migration

Prévoir une migration au premier lancement :

1. lire les données existantes de `localStorage` et IndexedDB ;
2. valider chaque structure ;
3. importer dans Room ;
4. chiffrer ;
5. supprimer les anciennes données ;
6. enregistrer la version de migration ;
7. ne jamais réimporter une seconde fois.

---

# 16. Configuration et secrets

## 16.1 `config.js`

Supprimer de l’APK les champs destinés à recevoir des secrets privés :

```text
API_KEY
SMS_GATEWAY_SID
SMS_GATEWAY_TOKEN
FCM_SERVER_KEY
```

Un token public Mapbox peut rester s’il est correctement restreint.

## 16.2 Backend

Toute passerelle SMS cloud doit passer par un backend Marsel.

L’APK appelle :

```text
POST /v1/emergencies/{id}/sms
```

Le backend détient le secret fournisseur.

L’APK ne doit jamais contenir :

* un token Twilio privé ;
* une clé FCM serveur ;
* une clé d’administration ;
* un secret partagé global de signature.

## 16.3 Environnements

Créer :

```text
debug
staging
release
```

Utiliser :

* `BuildConfig` pour les valeurs non sensibles ;
* GitHub Secrets pour le pipeline ;
* fichiers locaux non versionnés pour les développeurs ;
* aucune vraie clé dans Git.

---

# 17. Manifeste et permissions

## 17.1 Durcissement

Modifier :

```xml
android:allowBackup="false"
android:usesCleartextTraffic="false"
```

Ajouter une `network_security_config` uniquement si nécessaire.

## 17.2 Permissions minimales

Réévaluer chaque permission.

La localisation en arrière-plan ne doit être demandée que si la fonctionnalité est activée et clairement expliquée. Google Play exige que les applications demandent le niveau d’accès minimal nécessaire.

Les permissions doivent être demandées au moment de l’usage :

* proximité lors de l’activation du MRN ;
* localisation lors de l’usage de la carte ou de l’alerte ;
* microphone lors de l’activation du shield audio ;
* SMS lors de l’activation du canal SMS ;
* arrière-plan lors de l’activation explicite de la continuité écran éteint.

## 17.3 Identifiant d’application

Remplacer avant publication :

```text
com.example.marsel
```

par un identifiant définitif, par exemple :

```text
com.marsel.app
```

Ce changement doit être décidé avant la mise en production afin d’éviter une migration de package tardive.

---

# 18. Notifications

## 18.1 Identifiants uniques

Ne plus utiliser un identifiant fixe unique pour toutes les alertes.

Calculer un identifiant stable à partir de :

```text
type + emergencyId
```

Canaux recommandés :

```text
marsel_active_emergency
marsel_nearby_alerts
marsel_relay_status
marsel_sms_status
marsel_diagnostics
```

## 18.2 Contenu

Ne jamais afficher le vrai pseudo à un utilisateur non autorisé.

Exemple :

```text
🚨 Alerte Marsel à proximité
Une personne a besoin d’aide à environ 180 mètres.
```

## 18.3 Actions

Prévoir :

* ouvrir la carte ;
* confirmer que l’utilisateur va aider ;
* ignorer localement ;
* terminer sa propre alerte ;
* désactiver le relais.

---

# 19. Journalisation et observabilité

## 19.1 Logger structuré

Créer un logger natif avec catégories :

```text
MRN_DISCOVERY
MRN_TRANSPORT
MRN_PROTOCOL
MRN_SECURITY
EMERGENCY
LOCATION
SMS
SERVICE
DATABASE
WEBVIEW
```

## 19.2 Redaction obligatoire

Ne jamais journaliser :

* numéro complet ;
* coordonnées exactes en production ;
* nom réel ;
* payload chiffré ;
* clé ;
* token ;
* signature complète ;
* contenu SMS complet.

Exemples autorisés :

```text
recipientHash=ab82c1
latBucket=48.85
messageIdSuffix=91af
```

## 19.3 Builds

En debug :

* logs détaillés ;
* export manuel possible ;
* diagnostics réseau.

En release :

* logs minimaux ;
* données anonymisées ;
* pas de console JavaScript contenant des données personnelles.

---

# 20. Refonte du build

## 20.1 Dépendances

Décider entre :

### Option A — conserver la WebView

Supprimer les dépendances Compose inutilisées.

### Option B — migration vers Compose

Créer un plan de migration progressif et supprimer la WebView lorsque toutes les fonctions sont natives.

Pour le chantier actuel, l’option A est recommandée afin de concentrer les efforts sur la fiabilité du MRN.

## 20.2 Release

Configurer :

```text
isMinifyEnabled = true
isShrinkResources = true
```

Ajouter les règles ProGuard nécessaires pour :

* Room ;
* serialization ;
* JavascriptInterface ;
* modèles cryptographiques.

## 20.3 Signature

Les clés de signature release :

* ne doivent jamais être commitées ;
* doivent être protégées par GitHub Secrets ou un système de signature dédié ;
* ne doivent pas être partagées dans un canal non sécurisé.

---

# 21. CI/CD GitHub Actions

## 21.1 Déclenchement

La CI doit s’exécuter sur :

```yaml
push:
  branches:
    - test/experimentation

pull_request:
  branches:
    - test/experimentation
```

## 21.2 Jobs obligatoires

### Job `unit-tests`

```text
./gradlew testDebugUnitTest
```

### Job `lint`

```text
./gradlew lintDebug
```

### Job `assemble-debug`

```text
./gradlew assembleDebug
```

### Job `static-analysis`

Ajouter Detekt ou équivalent.

### Job `instrumented-tests`

Exécuter les tests Android sur émulateur lorsque possible.

### Job `dependency-check`

Contrôler les dépendances vulnérables et obsolètes.

## 21.3 Artefacts

Archiver :

```text
app-debug.apk
rapports JUnit
rapport Android Lint
rapport Detekt
rapport de couverture
logs des tests instrumentés
```

## 21.4 Protection de branche

Après stabilisation, exiger avant fusion :

* build réussi ;
* tests unitaires réussis ;
* lint sans erreur bloquante ;
* revue de code ;
* aucun secret détecté.

---

# 22. Stratégie de tests

## 22.1 Tests unitaires du protocole

Ajouter des tests pour :

* alerte sans GPS ;
* résolution sans GPS ;
* ACK sans GPS ;
* identifiant trop long ;
* paquet trop volumineux ;
* type inconnu ;
* signature invalide ;
* hash invalide ;
* message expiré ;
* timestamp futur excessif ;
* hop négatif ;
* hop supérieur au maximum ;
* séquence de position plus ancienne ;
* contacts trop nombreux ;
* numéro malformé ;
* répétition de nonce ;
* changement du payload après signature ;
* distinction ALERT/SMS_REQUEST ;
* reprise d’une demande après retour du réseau.

## 22.2 Tests de propriété et fuzzing

Le parseur ne doit jamais :

* crasher ;
* allouer une quantité excessive de mémoire ;
* bloquer indéfiniment ;
* accepter un message partiellement valide.

Fuzzer :

* tailles ;
* encodages ;
* guillemets ;
* Unicode ;
* nombres extrêmes ;
* JSON incomplet ;
* longueur des TXT ;
* champs dupliqués ;
* tableaux imbriqués.

## 22.3 Tests Room

Tester :

* migration depuis l’ancien stockage ;
* transaction de file ;
* reprise après crash ;
* expiration ;
* tombstone de résolution ;
* état SMS par destinataire ;
* rollback en cas d’erreur.

## 22.4 Tests SMS

Scénarios :

* aucun contact ;
* un contact ;
* cinq contacts ;
* message simple ;
* multipart ;
* première partie échouée ;
* dernière partie échouée ;
* un destinataire échoué ;
* tous réussis ;
* aucune SIM ;
* double SIM ;
* SIM non prête ;
* mode avion ;
* réseau perdu pendant l’envoi ;
* retour réseau ;
* deux relais candidats ;
* claim expiré ;
* relais détruit pendant l’envoi ;
* ACK perdu puis rejoué.

## 22.5 Tests de sécurité

Tester :

* faux ALERT ;
* faux RESOLVED ;
* faux ACK ;
* faux SMS_REQUEST ;
* rejeu d’un message signé ;
* modification d’un hop ;
* usurpation de nodeId ;
* connexion TCP non authentifiée ;
* Slowloris ;
* trame de 100 Mio ;
* trop grand nombre de connexions ;
* injection JavaScript ;
* navigation vers un domaine externe ;
* iframe malveillante ;
* extraction de secrets depuis l’APK ;
* sauvegarde Android.

## 22.6 Tests physiques Wi-Fi Direct

Un émulateur ne suffit pas à valider le MRN.

Prévoir au minimum :

* deux téléphones ;
* trois téléphones ;
* cinq téléphones ;
* au moins un Pixel ;
* au moins un Samsung ;
* au moins un Xiaomi ou appareil fortement personnalisé.

Scénarios terrain :

1. A déclenche, B reçoit ;
2. A déclenche, B relaie, C reçoit ;
3. A est sans Internet, B sans Internet, C avec SIM ;
4. deux relais avec SIM apparaissent simultanément ;
5. Group Owner change ;
6. un téléphone éteint son écran ;
7. application fermée ;
8. processus tué ;
9. Wi-Fi désactivé puis réactivé ;
10. téléphone déplacé hors de portée ;
11. résolution pendant une reconnexion ;
12. ancienne alerte rejouée après résolution ;
13. retour GPS après alerte sans position ;
14. retour réseau après demande SMS ;
15. cinq urgences simultanées.

## 22.7 Collecte terrain

Préparer un script ADB collectant :

```text
logcat filtré Marsel
dumpsys wifi
dumpsys connectivity
dumpsys activity services
dumpsys deviceidle
état batterie
version Android
constructeur et modèle
```

---

# 23. Cibles de performance initiales

Ces valeurs constituent des cibles de recette à confirmer sur appareils réels.

## 23.1 Diffusion

```text
Détection un saut, P50 : ≤ 10 secondes
Détection un saut, P95 : ≤ 30 secondes
Trois sauts contrôlés, P95 : ≤ 60 secondes
```

## 23.2 Fiabilité

```text
0 SMS dupliqué sur 1 000 scénarios automatisés/simulés
0 réapparition d’alerte résolue
0 perte de message validé lors d’un crash contrôlé
100 % des messages falsifiés rejetés
100 % des trames hors limite rejetées
```

## 23.3 Batterie

Mesurer séparément :

```text
mode arrêté
mode relais basse consommation
urgence locale active
urgence distante active
audio actif
GPS actif
```

Les résultats doivent être consignés par appareil.

## 23.4 Stabilité

Aucun crash ne doit être observé pendant :

* quatre heures de test MRN continu ;
* cinquante cycles activation/résolution ;
* vingt changements de groupe Wi-Fi Direct ;
* dix destructions/recréations du processus.

---

# 24. Lots de réalisation

## Lot 0 — sécurisation immédiate

* désactiver le relais SMS ouvert ;
* supprimer les contacts du TXT DNS-SD ;
* supprimer le port 8888 ;
* limiter le serveur 8890 ;
* rendre les coordonnées optionnelles ;
* séparer `SMS_REQUEST` de `ALERT` ;
* durcir le manifeste ;
* retirer les secrets potentiels de `config.js` ;
* renforcer la CI avec `assembleDebug` et `lintDebug`.

## Lot 1 — persistance et architecture

* introduire Room ;
* créer les entités et DAO ;
* déplacer les files natives ;
* créer la machine à états ;
* séparer `MainActivity` ;
* créer les repositories ;
* réduire le bridge JavaScript.

## Lot 2 — ForegroundService

* créer `MrnForegroundService` ;
* déplacer le Wi-Fi Direct ;
* déplacer DNS-SD ;
* déplacer les sockets ;
* déplacer le suivi GPS d’urgence ;
* créer la notification persistante ;
* reprendre l’état après destruction.

## Lot 3 — protocole MRN v2

* enveloppe versionnée ;
* validation stricte ;
* expiration ;
* séquences ;
* signatures ;
* identités locales ;
* synchronisation différentielle ;
* compatibilité temporaire v1.

## Lot 4 — délégation SMS transactionnelle

* `SMS_REQUEST` ;
* `SMS_CLAIM` ;
* bail ;
* élection ;
* suivi par destinataire ;
* résultat multipart ;
* reprise après réseau ;
* limitation d’abus ;
* consentement relais.

## Lot 5 — chiffrement et confiance

* Android Keystore ;
* sessions authentifiées ;
* chiffrement des bundles ;
* appairage ;
* certificats de relais ;
* pseudonymisation ;
* rotation d’identifiants.

## Lot 6 — sécurisation WebView et stockage

* `WebViewAssetLoader` ;
* CSP ;
* blocage des navigations ;
* stockage Room chiffré ;
* migration localStorage ;
* authentification locale ;
* désactivation des sauvegardes.

## Lot 7 — validation terrain

* APK de test ;
* matrice multi-appareils ;
* scénarios Wi-Fi Direct ;
* tests Doze ;
* tests SMS ;
* analyse batterie ;
* correction des anomalies ;
* rapport de recette.

---

# 25. Ordre de commits recommandé

Les commits doivent rester indépendants et réversibles.

Exemple :

```text
ci: build and archive debug apk
security: harden manifest and webview
protocol: allow packets without location
protocol: separate sms requests from alerts
transport: remove legacy relay server
transport: bound socket resources
data: add room message persistence
service: move mrn lifecycle to foreground service
sms: add per-recipient multipart tracking
sms: implement relay claim leases
security: sign mrn messages
security: encrypt trusted relay payloads
test: add protocol adversarial coverage
test: add multi-device test harness
```

Éviter un commit unique modifiant simultanément tout le projet.

---

# 26. Critères d’acceptation principaux

## CA-01 — alerte sans GPS

**Étant donné** qu’aucune position n’est disponible
**Quand** l’utilisateur déclenche Marsel
**Alors** l’alerte est persistée et diffusée sans coordonnées
**Et** aucun crash ou rejet de protocole ne se produit
**Et** une position ultérieure génère un message `POSITION`.

## CA-02 — fonctionnement arrière-plan

**Étant donné** une alerte active
**Quand** l’application passe en arrière-plan et l’écran s’éteint
**Alors** le service reste actif
**Et** le MRN continue de découvrir, recevoir et transmettre
**Et** la notification persistante reste affichée.

## CA-03 — reprise après destruction

**Étant donné** une alerte persistée
**Quand** le processus Android est détruit puis relancé
**Alors** l’état est restauré depuis Room
**Et** les messages non terminés sont repris
**Et** aucun SMS déjà confirmé n’est renvoyé.

## CA-04 — résolution définitive

**Étant donné** une urgence résolue
**Quand** un ancien ALERT ou POSITION est rejoué
**Alors** il est ignoré
**Et** aucun marqueur ou notification ne réapparaît.

## CA-05 — absence de double SMS

**Étant donné** deux relais capables d’envoyer
**Quand** ils reçoivent la même demande
**Alors** un seul obtient le claim
**Et** un seul envoie les SMS
**Et** l’autre abandonne.

## CA-06 — résultat par destinataire

**Étant donné** trois contacts
**Quand** deux envois réussissent et un échoue
**Alors** le résultat global est `PARTIAL_SUCCESS`
**Et** chaque destinataire possède son résultat
**Et** l’émetteur reçoit une information exacte.

## CA-07 — retour réseau

**Étant donné** une demande reçue hors réseau
**Quand** le réseau cellulaire revient avant expiration
**Alors** la demande est réévaluée
**Et** elle peut être revendiquée
**Et** la déduplication ne bloque pas l’effet.

## CA-08 — falsification

**Étant donné** un message dont la signature ou le hash est invalide
**Quand** il est reçu
**Alors** il est rejeté avant affichage, relais ou effet SMS
**Et** un événement anonymisé est écrit dans les diagnostics.

## CA-09 — confidentialité DNS-SD

**Étant donné** un scan DNS-SD effectué par un appareil tiers
**Quand** il lit les TXT Marsel
**Alors** il ne trouve ni numéro, ni nom réel, ni position exacte, ni contenu SMS.

## CA-10 — socket hostile

**Étant donné** un client envoyant une trame surdimensionnée ou incomplète
**Quand** il se connecte au port relais
**Alors** la connexion est fermée rapidement
**Et** l’application reste disponible
**Et** la mémoire n’augmente pas de manière non bornée.

## CA-11 — CI

**Étant donné** un push sur `test/experimentation`
**Quand** GitHub Actions démarre
**Alors** les tests, le lint et la compilation sont exécutés
**Et** un APK debug est disponible comme artefact.

---

# 27. Livrables attendus du développeur

Le développeur doit fournir :

1. le code corrigé sur `test/experimentation` ;
2. un historique de commits structuré ;
3. le schéma de l’architecture cible ;
4. la documentation du protocole MRN v2 ;
5. la documentation des machines à états ;
6. la base Room et ses migrations ;
7. les tests unitaires ;
8. les tests instrumentés ;
9. le workflow GitHub Actions ;
10. un APK debug généré par CI ;
11. un rapport Android Lint ;
12. un rapport d’analyse statique ;
13. une matrice de tests physiques ;
14. les scripts ADB ;
15. un rapport de consommation batterie ;
16. un rapport des anomalies restantes ;
17. une liste explicite des fonctionnalités non encore validées ;
18. une documentation de reprise et de diagnostic.

---

# 28. Définition de terminé

Le chantier ne peut pas être considéré comme terminé uniquement parce que l’APK compile.

La version est considérée comme candidate à la production lorsque :

* aucun P0 du présent document ne reste ouvert ;
* la CI est entièrement verte ;
* l’APK est généré automatiquement ;
* les messages sont signés ;
* les données sensibles ne circulent plus en clair ;
* le relais SMS ouvert est supprimé ou sécurisé ;
* le ForegroundService fonctionne ;
* Room constitue la source de vérité ;
* les alertes sans GPS fonctionnent ;
* la reprise après destruction fonctionne ;
* les SMS sont suivis par destinataire ;
* aucun doublon n’apparaît dans la campagne de recette ;
* les sockets sont durcis ;
* la WebView est sécurisée ;
* aucun secret n’est présent dans l’APK ;
* les tests sur plusieurs téléphones sont validés ;
* l’impact batterie est documenté ;
* les exigences Google Play relatives aux permissions sensibles ont été examinées ;
* les limites connues sont documentées.

La publication publique reste soumise à une validation séparée :

* juridique ;
* confidentialité ;
* Google Play ;
* opérateurs SMS ;
* sécurité ;
* expérience utilisateur ;
* conditions générales du service Marsel.
