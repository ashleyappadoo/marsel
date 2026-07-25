# Marsel — chantiers différés

## ForegroundService d'alerte (A5 / G5) — PRIORITAIRE, chantier séparé

**Limitation actuelle :** l'app ne survit pas de façon fiable en arrière-plan
pendant une alerte.

- `onPause()` désenregistre le `WifiP2pBroadcastReceiver` : en arrière-plan,
  les événements `PEERS_CHANGED` / `CONNECTION_CHANGED` ne sont plus reçus.
  Seuls les listeners DNS-SD et le `rediscoverRunnable` continuent — état
  hybride : le canal socket devient aveugle, seul le broadcast DNS-SD survit.
- Android peut suspendre (doze) ou tuer le process quand l'écran est éteint,
  interrompant la découverte DNS-SD et le tracking GPS.
- Le timer 20 min (Section 4) repose sur un `setTimeout` JS : si le process est
  tué, il est réarmé au prochain lancement sur le temps restant, mais ne se
  déclenche pas tant que l'app n'est pas relancée.

**À faire :**
1. Créer un `ForegroundService` (type `location` + `microphone` API 34+) démarré
   au déclenchement de l'alerte, arrêté à la fin d'alerte.
2. Y déplacer : découverte DNS-SD, ré-enregistrement des services, tracking GPS,
   ré-armement du timer 20 min (via `AlarmManager.setExactAndAllowWhileIdle`
   plutôt qu'un `setTimeout` JS pour survivre au doze).
3. Notification foreground persistante « Alerte Marsel active » avec action
   « Terminer l'alerte ».
4. Conserver le `BroadcastReceiver` enregistré tant que le service tourne
   (ne pas le lier au cycle `onPause`/`onResume` de l'activité).
5. Manifest : déclarer le `<service>` + `FOREGROUND_SERVICE_LOCATION` /
   `FOREGROUND_SERVICE_MICROPHONE` (API 34+). La permission `FOREGROUND_SERVICE`
   est déjà présente.

La permission `RECEIVE_BOOT_COMPLETED` a été retirée (aucun redémarrage au boot
prévu). Si un jour on veut relancer un service après reboot, il faudra la
réintroduire avec un `BroadcastReceiver` `BOOT_COMPLETED`.

### Deux rôles du ForegroundService (décidé avec l'utilisateur)

Le service doit couvrir **deux cas**, pas seulement l'émetteur :

1. **Téléphone ÉMETTEUR (en alerte)** — cf. ci-dessus : garder le MRN, le
   tracking GPS et le timer 20 min vivants écran éteint.

2. **Téléphone RELAIS / à proximité (pas en alerte lui-même)** — le
   **Marsel Relay Network doit rester actif en permanence, app fermée**, pour :
   - **relayer** les paquets (E/P/R/F/T) des alertes voisines même quand
     l'utilisateur n'a pas l'app ouverte ;
   - servir de **point de sortie réseau** : envoyer les SMS d'un appel à l'aide
     **de façon masquée** (aucune UI, aucun historique visible — déjà le cas via
     `sendSMSDirect`, à confirmer app fermée) au nom d'un émetteur hors-réseau ;
   - afficher les **notifications système** des appels à l'aide à proximité
     ET des fins de danger, même app fermée.

   Cela implique un ForegroundService « veille MRN » à **basse conso**, toujours
   actif (ou redémarré au boot → réintroduire `RECEIVE_BOOT_COMPLETED`), qui
   maintient les listeners DNS-SD et le `rediscoverRunnable` en mode veille
   (25 s), et bascule en mode actif (8 s) à la réception d'un paquet.

   ⚠️ Arbitrages à cadrer : impact batterie d'un scan WiFi P2P permanent,
   type de foreground service (`connectedDevice` ?), acceptabilité Play Store
   d'un service always-on, et consentement utilisateur explicite (opt-in) pour
   « prêter » son téléphone comme relais/point de sortie SMS.

## Confidentialité & sécurité des données — chantier différé

Constat de l'audit du 22/07/2026 (auth actuelle + stockage) : l'absence de
backend est un bon point pour l'anonymat, mais plusieurs fuites existent en
dehors de toute base de données et doivent être traitées avant une mise en
production. Périmètre ci-dessous **hors audio** (MMS audio traité séparément
ci-dessous, `Music/` public, non concerné par ce chantier).

### 1. Vraie authentification locale (sans Google/Facebook/OAuth tiers)

**Constat :** `doLogin()`/`doRegister()` (`script.js`) acceptent n'importe quel
couple email/mot de passe car `API_URL` est vide — ce n'est pas de
l'authentification, juste une identité locale (`userId` généré côté client)
stockée en clair dans `localStorage['marsel_user']`. Aucun verrou n'existe
avant d'accéder à l'historique d'alertes, aux contacts, à la position passée.

**À faire :**
1. Remplacer le pseudo-login par une **authentification locale réelle** :
   mot de passe (ou code PIN) **haché** (Argon2id/PBKDF2, jamais stocké en
   clair) et vérifié sur l'appareil — pas de compte serveur, pas de
   connexion tierce (Google/Facebook/Apple explicitement exclus par
   l'utilisateur).
2. Option biométrique (`BiometricPrompt`, empreinte/visage) en complément du
   PIN/mot de passe, pas en remplacement (fallback obligatoire).
3. Verrouiller l'accès à l'app (ou au minimum à l'historique/contacts/carte)
   tant que l'authentification locale n'est pas validée — important dans le
   scénario où quelqu'un d'autre a un accès physique au téléphone.
4. Chiffrer au repos les données sensibles actuellement en clair
   (`marsel_user`, `marsel_contacts`, `marsel_profile`, `marsel_emergency`,
   IndexedDB `emergency_events`) — clé dérivée du secret local, jamais
   envoyée nulle part.
5. Neutraliser `android:allowBackup` (ou fournir un vrai
   `data_extraction_rules.xml`/`backup_rules.xml` excluant ces données) pour
   empêcher leur fuite via la sauvegarde cloud Android par défaut.

### 2. Anonymisation de l'affichage lors d'un appel d'urgence

**Constat :** le pseudo réel de l'émetteur circule en clair dans les paquets
MRN (TXT `"p"`) et est affiché tel quel côté réception (notification,
marqueur carte, popup) — visible par tout relais et par le destinataire.

**Règle validée avec l'utilisateur :** le **vrai nom de l'émetteur ne doit
être communiqué qu'à ses proches** (la liste de contacts d'urgence qu'il a
lui-même renseignée). Tout le reste du réseau — relais, autres utilisateurs
Marsel à proximité, simples spectateurs de l'alerte — ne doit voir qu'un
identifiant anonymisé, jamais le nom réel.

**À faire :**
1. Générer un **identifiant d'alerte pseudonymisé** (ex. dérivé de
   `emergencyId`, distinct du `userId` et du pseudo réel) à afficher côté
   réception (notification, marqueur, popup carte) **à la place du nom/pseudo**
   pour tout destinataire qui n'est pas dans la liste des proches.
2. Le pseudo réel ne doit plus transiter tel quel dans les paquets MRN
   diffusés aux relais/tiers (TXT `"p"`) ; seul l'ID pseudonymisé y circule.
3. Le nom réel reste disponible **pour les proches uniquement**, quel que
   soit le canal :
   - dans le corps du SMS envoyé à SES PROPRES contacts (ceux-ci doivent
     bien identifier qui les appelle à l'aide) — déjà correct aujourd'hui ;
   - **et** si un proche est lui-même utilisateur Marsel et reçoit l'alerte
     directement via le réseau maillé (pas seulement par SMS), il doit voir
     le vrai nom sur son app alors qu'un relais/tiers ne voit que l'ID
     anonymisé pour cette même alerte. Cela suppose un moyen de reconnaître
     côté réception « je suis un proche déclaré de cet émetteur » (ex. via
     l'appairage/clé partagée déjà envisagé pour le chat — cf. étude de
     faisabilité abandonnée pour l'instant, mais le mécanisme d'identité
     proche-à-proche reste pertinent ici) pour lever l'anonymisation
     uniquement pour ce destinataire précis, sans jamais exposer le nom aux
     autres relais qui font transiter le même paquet.
4. Revoir en conséquence l'affichage carte (`addIncidentMarker`) et les
   notifications de réception (`onRelayMessageReceived`) pour n'utiliser que
   l'ID pseudonymisé, sauf résolution positive « proche » comme au point 3.

### 3. Autres fuites identifiées (à cadrer, priorité à discuter)

- Contacts d'urgence (nom + numéro) diffusés en clair dans le TXT DNS-SD
  (`"c"`) et sur les sockets TCP 8888/8890 sans TLS — receivable par tout
  appareil à portée scannant `_marsel._tcp`, pas seulement les relais
  légitimes.
- Aucune authenticité/intégrité des paquets MRN : un tiers peut forger un
  faux `RESOLVED` (étouffer une vraie alerte) ou un faux `ACK` (faire croire
  à tort qu'un SMS est parti). Hors périmètre anonymisation mais à traiter
  dans la foulée (signature/HMAC léger des paquets ?).
- `usesCleartextTraffic="true"` sans `network_security_config.xml` : sans
  conséquence tant qu'aucun `API_URL` n'est activé, mais à durcir le jour où
  un backend optionnel est branché.

## MMS audio (5b) — best-effort à fiabiliser

`sendLastRecordingMms` est un envoi best-effort : `SmsManager.sendMultimediaMessage`
dépend de la config MMS de l'opérateur et peut échouer silencieusement. À valider
sur device réel (APN MMS, FileProvider si l'URI MediaStore n'est pas lisible par
l'app MMS). Le SMS texte de fin (avec mention audio) part de toute façon.
