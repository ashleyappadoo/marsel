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

## MMS audio (5b) — best-effort à fiabiliser

`sendLastRecordingMms` est un envoi best-effort : `SmsManager.sendMultimediaMessage`
dépend de la config MMS de l'opérateur et peut échouer silencieusement. À valider
sur device réel (APN MMS, FileProvider si l'URI MediaStore n'est pas lisible par
l'app MMS). Le SMS texte de fin (avec mention audio) part de toute façon.
