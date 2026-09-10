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

### 1. Vraie authentification locale (sans Google/Facebook/OAuth tiers) — ✅ fait

**Constat (historique) :** `doLogin()`/`doRegister()` (`script.js`) acceptaient
n'importe quel couple email/mot de passe car `API_URL` est vide — ce n'était
pas de l'authentification, juste une identité locale (`userId` généré côté
client) stockée en clair dans `localStorage['marsel_user']`. Aucun verrou
n'existait avant d'accéder à l'historique d'alertes, aux contacts, à la
position passée.

**Fait :**
1. ✅ Écran de connexion refondu (`app.html`/`script.js`) : plus d'email/mot
   de passe distant, plus d'onglets manuels — création d'un accès local
   (pseudo + mot de passe/code, ≥4 caractères) ou déverrouillage, choisi
   automatiquement par `initAuthScreen()` selon qu'un secret existe déjà.
   Vérification **exclusivement sur l'appareil** via `SecureStore` (nouveau
   fichier `SecureStore.kt`) : hachage PBKDF2WithHmacSHA256 (120 000
   itérations, sel aléatoire par appareil), comparaison à temps constant,
   jamais de compte serveur ni de connexion tierce.
2. ✅ Biométrie (`BiometricPrompt`, androidx.biometric) en complément du mot
   de passe — bouton affiché seulement si `BiometricManager` confirme un
   moyen fort enrôlé, le champ mot de passe reste toujours utilisable en
   repli. `MainActivity` passe de `ComponentActivity` à `FragmentActivity`
   (superset requis par l'API biométrique).
3. ✅ Verrouillage systématique : l'app route **toujours** par l'écran
   d'authentification à chaque lancement (plus de bypass direct vers
   `screen-home` même si `marsel_user.loggedIn` était vrai) — important
   dans le scénario où quelqu'un d'autre a un accès physique au téléphone.
4. ⚠️ **Partiellement fait** — chiffrement au repos (AES/256-GCM, clé
   Android Keystore non exportable) appliqué à `marsel_user`,
   `marsel_contacts`, `marsel_profile`, `marsel_emergency` (migrés de
   `localStorage` vers un pont natif `secureStore`/`secureGet`/`secureRemove`).
   **Reste à faire :** l'historique IndexedDB `emergency_events` n'est PAS
   chiffré — refactor plus invasif (valeurs indexées, pas un simple
   couple clé/valeur), volontairement laissé de côté cette passe pour ne
   pas le faire à l'aveugle.
5. ✅ `android:allowBackup` passé à `false` (neutralisation complète, plus
   simple et plus sûr qu'une liste d'exclusion).
6. ✅ Boutons Google/Facebook/Apple retirés de `app.html` (et CSS mort
   associé nettoyé : `.auth-tabs`, `.auth-or`, `.social-row`, `.social-btn`,
   `.auth-row`, `.forgot-link`).

### 2. Anonymisation de l'affichage lors d'un appel d'urgence — ✅ fait (avec une limite documentée)

**Constat (historique) :** le pseudo réel de l'émetteur circulait en clair
dans les paquets MRN (TXT `"p"`) et était affiché tel quel côté réception
(notification, marqueur carte, popup) — visible par tout relais et par le
destinataire.

**Règle validée avec l'utilisateur :** le **vrai nom de l'émetteur ne doit
être communiqué qu'à ses proches** (la liste de contacts d'urgence qu'il a
lui-même renseignée). Tout le reste du réseau — relais, autres utilisateurs
Marsel à proximité, simples spectateurs de l'alerte — ne doit voir qu'un
identifiant anonymisé, jamais le nom réel.

**Fait :**
1. ✅ `MarselProtocol.deriveAlertAlias(emergencyId)` (nouveau) dérive un
   identifiant pseudonymisé déterministe (`"Alerte Marsel #XXXXXX"`, hex
   SHA-256 tronqué) — chaque appareil du réseau le recalcule indépendamment
   à partir de l'`emergencyId` déjà présent dans tout paquet, sans champ
   supplémentaire à faire circuler.
2. ✅ Le TXT DNS-SD (`buildTxtRecord`) ne porte plus le vrai pseudo pour les
   paquets de télémétrie publique (E/P/R/S) — la clé `"p"` est dérivée de
   l'`emergencyId`. Idem côté socket : `sendEmergencyViaRelay` (nouveau
   choke point `MarselProtocol.anonymizePseudoForTransit`) anonymise avant
   toute diffusion, DNS-SD et socket confondus.
3. Le nom réel reste disponible **pour les proches uniquement** :
   - ✅ dans le corps du SMS envoyé à SES PROPRES contacts — inchangé,
     toujours correct ;
   - ⚠️ **Exception voulue et documentée**, pas la reconnaissance « proche »
     initialement envisagée : les paquets `F`/`T` (SMS_REQUEST) et tout
     paquet marqué `relaySms="1"` conservent le vrai pseudo **jusqu'au
     relais qui compose le SMS** (`forwardEmergencyToContacts`,
     `handleSmsRequestPacket`) — c'est le mécanisme déjà existant qui
     délègue l'envoi SMS à un inconnu à proximité, il a structurellement
     besoin du vrai nom. Cette exception est bornée : la couche JS/carte ne
     voit jamais ce vrai nom même dans ce cas (`anonymizePseudoForDisplay`,
     appliqué avant tout `window.onRelayMessageReceived`), et la
     notification système « alerte à proximité » utilise toujours l'alias.
   - ❌ **Toujours pas résolu** : un proche qui est lui-même utilisateur
     Marsel et reçoit l'alerte via le MRN (pas par SMS) verra l'alias comme
     n'importe quel tiers — aucun mécanisme « je suis un proche déclaré »
     n'a été conçu (dépend de l'appairage évoqué pour le chat, étude
     abandonnée pour l'instant). Seul le canal SMS donne le vrai nom.
4. ✅ Carte (`addIncidentMarker`) et notifications de réception
   (`onRelayMessageReceived`, notification système) n'affichent plus que
   l'alias — géré automatiquement puisque l'anonymisation a lieu à la
   source, avant toute diffusion/affichage.

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

## Fiabilité du relais SMS — éviter les envois en double (élection d'un seul relais) — ✅ fait

**Constat (audit du 09/09/2026, suite à une question utilisateur) :** la
demande de relais SMS (paquets `F`/`T`, ou `E` avec `relaySms="1"`) est
diffusée en *flood* — tous les téléphones à portée/dans les 5 sauts la
reçoivent, il n'y a pas de relais « élu » à l'avance. Chaque relais ne
vérifie que son **propre** historique local (`smsInFlight` en RAM +
`dedupLedger` persisté, kind `"sms"`) avant d'envoyer.

**Trou confirmé dans le code** : quand un relais reçoit l'accusé
`SMS_ACK` diffusé par un AUTRE relais qui a déjà envoyé le SMS
(`processRelayMessage`, branche `TYPE_SMS_ACK`), il le retransmet plus
loin dans le maillage (pour qu'il remonte jusqu'à l'émetteur) mais **ne
marque jamais son propre `dedupLedger("sms", ...)`** avant de retourner.
Résultat : si deux téléphones-passerelles ont réseau + SIM au même
moment et reçoivent la demande avant que l'un des deux ait fini d'agir,
**le même SMS peut partir en double** (voire plus), depuis des numéros
différents, vers les mêmes contacts — ce n'est pas qu'un cas limite
théorique, c'est une vraie course actuellement non protégée.

**Fait :**
1. ✅ **Correctif rapide** : `processRelayMessage` (branche `TYPE_SMS_ACK`,
   `MainActivity.kt`) appelle désormais `dedupLedger.checkAndMark("sms",
   ackedId)` dès réception de l'accusé, même sur un relais qui n'a pas
   lui-même envoyé le SMS — un relais qui reçoit la demande APRÈS avoir vu
   passer cet accusé ne tente plus d'envoyer.
2. ✅ **Élection d'un seul relais avant envoi** : nouvelles
   `electionDelayMs()`/`scheduleElectedSmsSend()`. Chaque relais candidat
   calcule un délai (réseau WiFi < réseau mobile < défaut, batterie plus
   haute = délai plus court, + un jitter aléatoire 0-700 ms), puis attend
   ce délai avant d'envoyer réellement — et revérifie `dedupLedger` juste
   avant : si un ACK est arrivé entre-temps (point 1), il abandonne
   proprement (libère `smsInFlight`/`pendingRelaySends`, y compris en cas
   d'interruption du thread d'attente). Branché sur les deux points
   d'envoi existants (`handleSmsRequestPacket` pour F/T,
   `forwardEmergencyToContacts` pour E+`relaySms="1"`).
   ⚠️ **Limite assumée, comme prévu** : reste probabiliste sur un réseau
   best-effort comme WiFi Direct — réduit fortement le risque de doublon,
   ne l'annule pas à 100 % (pas de coordination inter-appareils avant
   l'attente, seulement après). Non vérifiable par test unitaire (logique
   100 % Android : threads, réseau, batterie) — à confirmer sur device réel.

## Import des proches depuis les contacts du téléphone (`Intent.ACTION_PICK`) — ✅ fait

**Constat (demande utilisateur du 09/09/2026) :** aujourd'hui, les 5
contacts de confiance (« proches ») sont saisis entièrement à la main
(nom, mobile, email) — aucun lien avec le carnet d'adresses du téléphone.
C'est d'ailleurs une propriété de confidentialité déjà auditée
positivement : Marsel ne demande **aucune permission `READ_CONTACTS`**
et n'a donc aucun accès à la liste de contacts (cf. chantier
Confidentialité ci-dessus).

**Objectif :** permettre de choisir un proche dans les contacts du
téléphone plutôt que de tout retaper, **sans revenir sur cette propriété
de confidentialité**.

**Fait :**
1. ✅ Sélecteur **système** (`Intent.ACTION_PICK` sur
   `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`, droit sur un
   numéro) plutôt qu'une requête directe au fournisseur de contacts —
   toujours **aucune permission `READ_CONTACTS`** déclarée ni requise.
2. ✅ Nouveau bridge natif `pickContact()` (`MainActivity.kt`) : lance
   l'intent via `ActivityResultContracts.StartActivityForResult`
   (`contactPickerLauncher`, même pattern que les permissions), lit
   nom + numéro depuis l'URI retournée sur un thread dédié (jamais sur
   le thread UI — requête `ContentResolver` bloquante), renvoie le
   résultat à la WebView via `window.onContactPicked`, texte échappé
   avec le helper `escapeJs()` déjà utilisé ailleurs dans le fichier
   (un nom avec apostrophe, ex. « O'Brien », n'est plus corrompu).
3. ✅ Bouton « Importer depuis mes contacts » sur l'écran d'édition d'un
   proche (`app.html`/`script.js`, `pickContactFromPhone()` +
   `window.onContactPicked`) qui préremplit `contact-nom` et
   `contact-mobile` — l'utilisateur garde la main pour corriger/compléter
   avant d'enregistrer (le pseudo affiché à l'émetteur reste un champ
   Marsel distinct, jamais importé).
4. ✅ Stockage inchangé : le proche importé est enregistré comme
   n'importe quel proche saisi à la main (`marsel_contacts`, chiffré au
   repos — cf. chantier Confidentialité §1.4).

## Icône de l'app — ✅ fait (partiellement, limite assumée)

**Constat :** le manifest ne déclarait `android:icon` nulle part — l'app
utilisait l'icône par défaut du template Android Studio (robot vert), y
compris les fichiers `drawable/ic_launcher_background.xml` et
`drawable-v24/ic_launcher_foreground.xml` (toujours le contenu généré par
défaut).

**Fait :**
1. ✅ `AndroidManifest.xml` : ajout de `android:icon="@mipmap/ic_launcher"`
   et `android:roundIcon="@mipmap/ic_launcher_round"` sur `<application>`.
2. ✅ Icône adaptative (API 26+, la grande majorité des appareils actifs)
   redessinée en vecteur pur (`ic_launcher_background.xml` fond blanc,
   `ic_launcher_foreground.xml`) avec la marque Marsel exacte du `mw-dot`
   déjà utilisé dans `app.html` (cercle orange `#E84315` + éclair blanc),
   mise à l'échelle et centrée dans la zone de sécurité 66dp du canevas
   108dp — aucun asset binaire nécessaire, juste le chemin SVG existant.
3. ⚠️ **Limite assumée** : les icônes `.webp` historiques par densité
   (`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.webp`, utilisées en
   repli sur API < 26 ou par un launcher qui ignore les icônes adaptatives)
   n'ont **pas** été régénérées — cet environnement n'a aucun outil de
   rasterisation d'image (ni PIL/Pillow, ni ImageMagick, ni rsvg-convert),
   et je n'ai pas voulu fabriquer des PNG à la main sans pouvoir vérifier
   visuellement le rendu. **Action restante, triviale dans Android
   Studio** : clic droit sur `res` → New → Image Asset → Launcher Icons
   (Adaptive and Legacy) → réutiliser le foreground/background ci-dessus →
   Next → Finish (régénère tous les `.webp` automatiquement).

## MMS audio (5b) — best-effort à fiabiliser

`sendLastRecordingMms` est un envoi best-effort : `SmsManager.sendMultimediaMessage`
dépend de la config MMS de l'opérateur et peut échouer silencieusement. À valider
sur device réel (APN MMS, FileProvider si l'URI MediaStore n'est pas lisible par
l'app MMS). Le SMS texte de fin (avec mention audio) part de toute façon.
