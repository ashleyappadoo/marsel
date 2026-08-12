# Marsel — Base de connaissances

Document de suivi : état réel du projet, ce qui est validé fonctionnel, et
journal des travaux de session. Complète `README.md` (référence technique de
l'architecture) et `TODO.md` (chantiers différés) sans les dupliquer —
consulter ces deux fichiers pour le détail.

Dernière mise à jour : session du 2026-08-12, branche `test/experimentation`.

---

## 1. Résumé exécutif

Marsel est une app Android de sécurité personnelle (Kotlin natif + WebView
hybride) construite autour du **Marsel Relay Network (MRN)** : un maillage
WiFi Direct/DNS-SD sans serveur central, capable de relayer une alerte
d'urgence et de faire envoyer un SMS par un téléphone voisin quand
l'émetteur n'a ni réseau ni SIM. Le produit est **offline-first** : aucune
donnée factice ou par défaut nulle part (position, contacts, lieux sûrs),
aucun backend requis pour fonctionner.

Le protocole MRN (`MarselProtocol.kt`) est entièrement testé en JUnit pur
(tests JVM, indépendants d'Android) et la CI GitHub Actions
(`android-ci.yml`, `testDebugUnitTest`) est **verte sur tout l'historique
récent** — c'est le seul point de vérification automatisé disponible dans
cet environnement de développement (pas de SDK Android local, egress
réseau restreint vers les dépôts Gradle/CDN).

---

## 2. Ce qui fonctionne aujourd'hui (validé)

### Confirmé par test terrain (2 téléphones réels, logs analysés en session)

- **Détection d'un pair Marsel voisin** via DNS-SD, avec filtrage strict
  (seuls les appareils qui répondent au service `_marsel._tcp` apparaissent
  — jamais de TV/imprimante/téléphone non-Marsel dans la liste).
- **Cascade réseau intelligente** : l'émetteur choisit SMS direct si SIM +
  réseau cellulaire OK, sinon relais MRN vers un voisin qui, lui, a du
  réseau — avec accusé de réception (paquet `SMS_ACK`) qui referme la
  boucle vers l'émetteur.
- **Limite de 5 sauts** (`MAX_HOPS`) pour borner la propagation et le délai
  avant le verdict « SMS impossible » côté émetteur si personne n'a de
  réseau sur le chemin.
- **Position GPS 100 % réelle** : aucune coordonnée par défaut/Paris/factice
  nulle part ; le marqueur utilisateur n'apparaît qu'au premier vrai fix.
- **Lieux sûrs** chargés depuis `assets/safeplace.csv` (versionné git,
  format `nom_emplacement,lat,long`), affichés selon un rayon réel autour
  de la position actuelle (`SAFE_PLACES_RADIUS_M`), pas de seed fictif.
- **Timer 20 minutes** : relance automatique d'un SMS « alerte toujours en
  cours » si l'urgence n'est pas résolue.
- **Flow de permissions** : onboarding groupe par groupe, détection du
  blocage MRN par permission manquante (`p2pPermissionMissing`), re-prompt
  automatique côté UI, recréation du channel P2P après échecs persistants.
- **Chaîne de mesure de performance** (dernier round de tests) : détection
  d'une alerte voisine passée de 21-24 s à 3,6-4,4 s dans le meilleur cas ;
  boucle complète émission → relais → SMS → accusé ≈ 12-27 s.

### Corrigé cette session (voir §3), pas encore re-testé sur le terrain

- Correction de la troncature d'ID qui cassait la corrélation des accusés
  SMS (fausses notifications d'échec malgré un envoi réussi).
- Bascule socket-first quand un groupe P2P est déjà connecté (mitigation du
  flapping WiFi observé sur Samsung).
- Filtrage des fix GPS grossiers + recentrage intelligent de la carte +
  bouton de recentrage manuel.

### Connu et documenté comme limitation (voir `TODO.md`)

- Un téléphone B en veille écran éteint ne reçoit rien de façon fiable —
  nécessite le chantier ForegroundService (prioritaire, non commencé).
- Le flapping WiFi P2P (DISABLED↔ENABLED toutes les 20-40 s) sur certains
  Samsung est atténué mais pas éliminé.
- Le MMS audio est du best-effort, non validé sur device réel (dépend de
  la config MMS opérateur).

### Non implémenté (assumé hors scope actuel)

Messagerie chiffrée, abonnement/paiement, Bluetooth mesh, boot receiver,
LoRa — écrans ou permissions parfois présents dans le squelette UI mais
sans logique derrière (cf. `README.md` § Fonctionnalités). Le **chat sur le
MRN hors contexte d'urgence** a été étudié cette session (§3) et mis de
côté pour l'instant, pas de trace dans le code.

---

## 3. Travaux réalisés cette session

### 3.1 — Fix 1 : troncature des identifiants (commit `0a4b685`)

`MarselProtocol.buildTxtRecord` tronquait les IDs à 40 caractères ; les IDs
des paquets de relais SMS (`emg-xxx_ressms_<timestamp>`, 42-43 caractères)
étaient donc coupés dans le TXT DNS-SD. L'accusé de réception revenait avec
un ID tronqué, ne correspondait jamais à l'ID attendu par l'émetteur, et
déclenchait une fausse notification « SMS non envoyés » malgré un envoi
réussi. Limite portée à 64 caractères + test de régression JUnit
(`longRequestIds_surviveTxtRoundTripIntact`).

### 3.2 — Fix 2 : socket-first quand un groupe P2P est connecté (`0a4b685`)

Tant qu'une connexion de groupe WiFi Direct tient, le canal fiable devient
le socket TCP plutôt que le DNS-SD :

- nouveau polling socket (10 s) côté client pour tirer les messages en
  attente du Group Owner ;
- DNS-SD mis en retrait pendant la connexion (cycle 20 s au lieu de 8-12 s,
  re-arm complet 60 s au lieu de 15-45 s) pour ne pas aggraver le flapping
  WiFi observé sur Samsung, probablement causé par le scan off-channel
  agressif ;
- retour immédiat au rythme normal à la déconnexion.

Changement de signature signalé (règle du projet) :
`sendRelayToPeer(address, messageJson, attempts: Int = 3)` — paramètre
ajouté avec valeur par défaut, aucun appel existant ne change de
comportement.

### 3.3 — Fix 3 : qualité GPS et recentrage carte (`0a4b685`)

- Rejet d'un fix réseau grossier (>100 m) si un fix précis (<50 m) date de
  moins de 30 s — corrige le cas où un fix cellule/WiFi écrasait la vraie
  position GPS.
- Recentrage automatique de la carte quand un fix précis remplace un fix
  grossier sur lequel elle avait été centrée.
- Bouton de recentrage manuel ajouté sur la carte (`recenterMap()`).

Les trois fixes ont été poussés sur `test/experimentation`, CI verte
confirmée (`testDebugUnitTest`, run associé au commit `0a4b685`).

### 3.4 — Chantier confidentialité/sécurité ajouté au `TODO.md` (`9ee2fa6`, `c5d93c0`)

Suite à une analyse de l'architecture actuelle (auth, stockage local,
diffusion MRN) : l'absence de backend est un vrai atout pour l'anonymat,
mais plusieurs fuites existent en dehors de toute base de données
(stockage local en clair, sauvegarde Android par défaut non exclue,
broadcast DNS-SD/socket en clair). Deux volets ajoutés au TODO :

1. **Authentification locale réelle** (PIN/mot de passe haché + biométrie
   en option), explicitement **sans** Google/Facebook/OAuth tiers, avec
   chiffrement au repos des données sensibles.
2. **Anonymisation de l'affichage** : le vrai nom de l'émetteur d'une
   alerte n'est communiqué qu'à ses **proches** (contacts d'urgence) — via
   SMS, et sur l'app si le proche est lui-même utilisateur Marsel recevant
   par le MRN. Tout autre destinataire (relais, tiers) ne voit qu'un
   identifiant anonymisé. Point ouvert : aucun mécanisme de reconnaissance
   « je suis un proche déclaré » n'est encore spécifié.

Le volet audio (MMS/stockage `Music/`) est explicitement exclu de ce
chantier, traité à part.

### 3.5 — Étude de faisabilité : chat sur le MRN (abandonnée pour l'instant)

Scénario étudié : discuter avec un ou plusieurs proches via le mesh, même
sans appel à l'aide en cours. Conclusion (aucun code produit) : faisable
mais d'une ampleur comparable au MRN actuel dans son ensemble, et
bloquant sur plusieurs points structurants — adressage ciblé (le MRN
actuel ne fait que du flood non ciblé), portée physique du WiFi Direct
(mesh local, pas un réseau étendu), stockage persistant type
store-and-forward (DTN) que l'architecture actuelle ne fait pas,
chiffrement de bout en bout (prérequis bloquant, pas une option), et
dépendance au ForegroundService « veille MRN » non construit. Décision de
l'utilisateur : **abandon pour l'instant**. Le principe d'appairage
proche-à-proche envisagé reste toutefois pertinent pour le point 3.4.2
(reconnaissance des proches) et a été noté comme tel dans le TODO.

### 3.6 — Analyse du `CAHIER_DES_CHARGES_PRODUCTION_MRN.md` (mis de côté)

Fichier déposé directement sur `test/experimentation` par l'utilisateur
(hors session, commit `17a77a9`). Analyse de faisabilité complète produite
(chapitre par chapitre, confrontation au code réel) : le cahier est
cohérent à ~80 % avec les choix produit déjà actés, mais contient des
contradictions structurantes non tranchées — notamment un backend de
certification qui réintroduirait un serveur central (contraire au choix
« aucun backend »), et une exigence de sécuriser le relais SMS tout en le
gardant ouvert à des inconnus (les deux sont mutuellement exclus sans
appairage préalable). **Décision de l'utilisateur : chantier mis de côté**,
à reprendre plus tard. Aucun code modifié suite à cette analyse.

---

## 4. Décisions produit actées avec l'utilisateur

- Toujours développer et pousser sur la branche **`test/experimentation`**
  (jamais sur une autre branche sans autorisation explicite).
- **Aucune donnée factice/par défaut** nulle part dans l'app — position,
  contacts, lieux sûrs : toujours du réel.
- Lieux sûrs pilotés par `assets/safeplace.csv`, versionné git.
- **Ne jamais supprimer une fonction existante sans le signaler
  explicitement** dans le résumé de section/commit.
- ForegroundService : chantier prioritaire mais différé, avec **deux
  rôles** à couvrir (émetteur en alerte + relais toujours actif en
  arrière-plan, opt-in).
- Authentification : **jamais** de connexion Google/Facebook/OAuth tiers —
  authentification locale uniquement.
- Anonymisation : le vrai nom de l'émetteur n'est visible que par ses
  proches, jamais par les relais/tiers du réseau.
- Chat sur le MRN hors urgence : abandonné pour l'instant.
- Cahier des charges de production (2349 lignes) : mis de côté pour
  l'instant, à reprendre plus tard.

---

## 5. Chantiers différés — voir `TODO.md` pour le détail

1. **ForegroundService** (prioritaire) — double rôle émetteur/relais.
2. **Confidentialité & sécurité des données** — auth locale réelle,
   anonymisation de l'affichage, chiffrement au repos, exclusion du
   backup Android, durcissement du transport MRN (à cadrer).
3. **MMS audio** — best-effort à fiabiliser sur device réel.

---

## 6. Contraintes de l'environnement de développement

- Pas de SDK Android ni de Gradle fonctionnel en local (egress réseau
  bloqué vers les dépôts Google/Gradle) — impossible de compiler l'APK ou
  de lancer un test instrumenté depuis ce container.
- **La CI GitHub Actions (`testDebugUnitTest`) est le seul point de
  vérification automatisé** disponible ; tout ce qui touche WiFi
  Direct/DNS-SD réel, l'envoi SMS effectif, la batterie ou le
  comportement multi-appareils physiques reste **non vérifiable** ici et
  dépend des retours de test terrain de l'utilisateur.
- Le protocole pur (`MarselProtocol.kt`) est conçu justement pour rester
  testable en JVM pur, indépendamment d'Android — c'est le socle de
  couverture de tests fiable dont dispose le projet.
