/**
 * Marsel — réception du Feedback bêta (Google Apps Script).
 *
 * Reçoit le formulaire Feedback envoyé par l'application (nom, prénom, e-mail,
 * avis + journaux techniques) et l'envoie par Gmail à RECIPIENT, avec les
 * journaux en pièce jointe. Le testeur n'a rien d'autre à faire qu'appuyer sur
 * « Envoyer » dans l'application.
 *
 * INSTALLATION (une seule fois, connecté au compte Google d'ONA) :
 *  1. https://script.google.com → « Nouveau projet », nommé « Marsel Feedback ».
 *  2. Remplacer tout le contenu de Code.gs par ce fichier, puis Enregistrer.
 *  3. « Déployer » → « Nouveau déploiement » → type « Application Web » :
 *       - Exécuter en tant que : « Moi »
 *       - Qui a accès : « Tout le monde »
 *     → « Déployer », autoriser l'accès Gmail demandé par Google.
 *  4. Copier l'URL de l'application Web (elle se termine par /exec) et la
 *     coller dans app/src/main/assets/config.js → FEEDBACK_ENDPOINT_URL.
 *  5. TOKEN ci-dessous doit rester IDENTIQUE à FEEDBACK_TOKEN de config.js.
 *
 * Après toute modification de ce script : « Déployer » → « Gérer les
 * déploiements » → modifier → « Nouvelle version » (l'URL reste la même).
 *
 * Quotas Google (compte gratuit) : 100 destinataires d'e-mail par jour.
 */

const RECIPIENT = 'ona.action@gmail.com';
const TOKEN = 'mrsl-fb-9982b100b6844288b9a434f6';
const MAX_PER_HOUR = 30;         // anti-abus : envois acceptés par heure
const MAX_PER_DAY = 80;          // < quota Gmail gratuit (100 destinataires/jour)
const MAX_LOG_CHARS = 2000000;   // ~2 Mo de journaux au maximum

function doPost(e) {
  try {
    const data = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (data.token !== TOKEN) return reply_({ ok: false, error: 'token' });

    // Limite de débit (le jeton est lisible dans l'APK) : fenêtre FIXE par
    // heure, compteur protégé par un verrou contre les envois simultanés.
    const lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      const cache = CacheService.getScriptCache();
      const now = new Date();
      const hourKey = 'feedback-h-' + Utilities.formatDate(now, 'UTC', 'yyyyMMddHH');
      const dayKey = 'feedback-d-' + Utilities.formatDate(now, 'UTC', 'yyyyMMdd');
      const hourCount = Number(cache.get(hourKey) || 0);
      const dayCount = Number(cache.get(dayKey) || 0);
      // Plafond journalier sous le quota Gmail : un abus ne peut pas épuiser
      // le quota au détriment des vrais testeurs (qui basculent alors sur
      // leur appli mail).
      if (hourCount >= MAX_PER_HOUR || dayCount >= MAX_PER_DAY) {
        return reply_({ ok: false, error: 'rate' });
      }
      cache.put(hourKey, String(hourCount + 1), 3600);
      cache.put(dayKey, String(dayCount + 1), 86400);
    } finally {
      lock.releaseLock();
    }

    const nom = clip_(data.nom, 100);
    const prenom = clip_(data.prenom, 100);
    const email = clip_(data.email, 200);
    const avis = clip_(data.avis, 20000);
    const logs = clip_(data.logs, MAX_LOG_CHARS);

    // Même objet que l'envoi par application mail : Marsel Feedback_date_Nom,
    // avec la date du téléphone (même fuseau horaire que ce chemin-là).
    const date = /^\d{2}-\d{2}-\d{4}$/.test(String(data.date))
      ? String(data.date)
      : Utilities.formatDate(new Date(), 'Europe/Paris', 'dd-MM-yyyy');
    const subject = 'Marsel Feedback_' + date + '_' + nom;
    const body = [
      'Nom : ' + nom,
      'Prénom : ' + prenom,
      'Email : ' + email,
      '',
      'Avis :',
      avis,
      '',
      '— Technique —',
      'Version Marsel : ' + clip_(data.appVersion, 30),
      'Android : ' + clip_(data.android, 30),
      'Appareil : ' + clip_(data.device, 80)
    ].join('\n');

    const fileName = 'marsel_logs_' + Utilities.formatDate(new Date(), 'Europe/Paris', 'yyyyMMdd_HHmmss') + '.txt';
    const options = {
      name: 'Marsel Feedback',
      attachments: [Utilities.newBlob(logs || '(aucun journal)', 'text/plain', fileName)]
    };
    // « Répondre » écrit directement au testeur.
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) options.replyTo = email;

    MailApp.sendEmail(RECIPIENT, subject, body, options);
    return reply_({ ok: true });
  } catch (err) {
    return reply_({ ok: false, error: String(err) });
  }
}

function clip_(value, max) {
  return String(value == null ? '' : value).slice(0, max);
}

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
