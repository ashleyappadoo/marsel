/* =========================================================
   MARSEL – script.js
   Fully functional implementation – no mock data.
   ========================================================= */

/* ---------------------------------------------------------
   IN-APP DEBUG LOG
   Captured by the LOG overlay (bottom-right button).
   Also forwarded from Kotlin via window.marselLog().
   --------------------------------------------------------- */
var _marselLogEntries = [];
var _marselLogFilter  = '';
var _marselLogMaxEntries = 600;

function mLog(src, tag, msg) {
    var ts = new Date().toLocaleTimeString('fr-FR', {hour:'2-digit',minute:'2-digit',second:'2-digit'});
    var ms = ('000' + (Date.now() % 1000)).slice(-3);
    var entry = { ts: ts + '.' + ms, src: src, tag: tag, msg: String(msg) };
    _marselLogEntries.push(entry);
    if (_marselLogEntries.length > _marselLogMaxEntries) _marselLogEntries.shift();
    _marselLogRender();
}

/* Called by Kotlin: window.marselLog('K', 'DNS-SD', 'msg') */
window.marselLog = function(src, tag, msg) { mLog(src, tag, msg); };

function _marselLogRender() {
    var el = document.getElementById('debug-log-content');
    if (!el) return;
    var f = _marselLogFilter;
    var lines = _marselLogEntries
        .filter(function(e) { return !f || e.tag.indexOf(f) !== -1; })
        .map(function(e) {
            var color = e.src === 'K' ? '#7ec8e3'
                      : e.tag === 'RELAY' ? '#00ff88'
                      : e.tag === 'GPS'   ? '#f0c040'
                      : e.tag === 'NET'   ? '#ff8c69'
                      : '#cccccc';
            return '<span style="color:#666">' + e.ts + '</span> '
                 + '<span style="color:' + color + '">[' + e.tag + ']</span> '
                 + escapeHtml(e.msg);
        });
    el.innerHTML = lines.join('\n');
    el.scrollTop = el.scrollHeight;
    var cnt = document.getElementById('debug-log-count');
    if (cnt) cnt.textContent = _marselLogEntries.length + ' lignes';
}

function marselLogFilter(tag) {
    _marselLogFilter = tag;
    document.querySelectorAll('.log-chip').forEach(function(btn) {
        btn.classList.toggle('active', btn.getAttribute('onclick').indexOf("'" + tag + "'") !== -1 || (tag === '' && btn.id === 'fltr-all'));
    });
    _marselLogRender();
}

function marselCopyLog() {
    var lines = _marselLogEntries.map(function(e) {
        return '[' + e.ts + '] [' + e.src + '][' + e.tag + '] ' + e.msg;
    }).join('\n');
    if (navigator.clipboard) {
        navigator.clipboard.writeText(lines).then(function() { showToast('Logs copiés ✓'); });
    } else {
        var ta = document.createElement('textarea');
        ta.value = lines;
        ta.style.position = 'fixed'; ta.style.top = '-9999px';
        document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); showToast('Logs copiés ✓'); } catch(e) { showToast('Erreur copie'); }
        document.body.removeChild(ta);
    }
}

function marselClearLog() {
    _marselLogEntries = [];
    _marselLogRender();
}

/* ---------------------------------------------------------
   GLOBAL STATE OBJECT
   --------------------------------------------------------- */
var MARSEL = {
    // User state
    currentUser: null,
    // Position : RIEN par défaut. On n'utilise QUE de vraies coordonnées GPS.
    // Reste null tant qu'aucun vrai fix (réel) n'est arrivé — jamais de valeur
    // par défaut / factice qui masquerait l'absence de position.
    currentLat: null,
    currentLng: null,
    locationWatchId: null,

    // Emergency state
    emergencyActive: false,
    emergencyId: null,
    emergencyHoldTimer: null,
    emergencyRingInterval: null,
    emergencyRingProgress: 0,

    // Map state
    mapInitialized: false,
    leafletMap: null,
    userMarker: null,
    incidentMarkers: {},    // key: emergencyId → L.marker
    friendMarkers: {},      // key: userId → L.marker
    safePlaceMarkers: [],

    // Network & relay state
    networkType: 'NONE',
    p2pPeers: [],
    p2pConnected: false,
    relayQueue: [],
    processedMessageIds: {},   // id → true  (Set replacement for ES5)
    resolvedEmergencies: {},   // emergencyId → true — plus jamais de marqueur/notif pour elles

    // Mode
    mode: 'autonome',          // 'autonome' | 'assistance'

    // Navigation
    screenHistory: [],

    // IndexedDB handle
    db: null,

    // GPS tracking during emergency
    trackingInterval: null,    // setInterval handle for continuous position broadcast
    firstGpsFix: false,        // true dès qu'un VRAI fix GPS est reçu (jamais avant)

    // GPS-QUALITY (terrain) : filtre des fix réseau grossiers + recentrage
    lastFixAcc: null,          // précision (m) du dernier fix ACCEPTÉ
    lastFixTs: 0,              // timestamp du dernier fix accepté
    mapCenteredAcc: null,      // précision du fix sur lequel la carte a été centrée

    // Timer 20 minutes (Section 4)
    timeoutTimer: null         // setTimeout handle for the 20-min still-active alert
};

/* Durée avant relance SMS "alerte toujours en cours" (20 min) */
var EMERGENCY_TIMEOUT_MS = 20 * 60 * 1000;

/* Ring circumference for r=78 */
var RING_CIRCUMFERENCE = 2 * Math.PI * 78; // ≈ 490

/* ---------------------------------------------------------
   STOCKAGE CHIFFRÉ (chantier confidentialité, TODO.md §1.4)
   --------------------------------------------------------- */
/* marsel_user, marsel_contacts, marsel_profile et marsel_emergency
   contiennent des données sensibles (pseudo, numéros de proches, position,
   historique d'alertes) — elles ne doivent plus jamais toucher le
   localStorage en clair. secureSet/secureGet/secureRemove passent par le
   pont natif (AES-GCM, clé Android Keystore, jamais exportée) ; repli sur
   localStorage en clair uniquement si le pont natif est indisponible
   (aperçu navigateur hors app), pour ne pas casser le développement. */
function secureSet(key, value) {
    try {
        if (window.AndroidBridge && typeof AndroidBridge.secureStore === 'function') {
            AndroidBridge.secureStore(key, value);
            return;
        }
    } catch (e) {}
    try { localStorage.setItem(key, value); } catch (e) {}
}
function secureGet(key) {
    try {
        if (window.AndroidBridge && typeof AndroidBridge.secureRetrieve === 'function') {
            var v = AndroidBridge.secureRetrieve(key);
            return (v === null || v === undefined || v === '') ? null : v;
        }
    } catch (e) {}
    try { return localStorage.getItem(key); } catch (e) { return null; }
}
function secureRemove(key) {
    try {
        if (window.AndroidBridge && typeof AndroidBridge.secureRemove === 'function') {
            AndroidBridge.secureRemove(key);
            return;
        }
    } catch (e) {}
    try { localStorage.removeItem(key); } catch (e) {}
}

/* ---------------------------------------------------------
   LOCAL DATABASE (IndexedDB)
   --------------------------------------------------------- */
function initDB() {
    return new Promise(function (resolve, reject) {
        if (!window.indexedDB) {
            console.warn('IndexedDB not available – offline store disabled');
            resolve(null);
            return;
        }
        var request = indexedDB.open('MarselDB', 2);

        request.onupgradeneeded = function (e) {
            var db = e.target.result;

            if (!db.objectStoreNames.contains('safe_places')) {
                var sp = db.createObjectStore('safe_places', { keyPath: 'id', autoIncrement: true });
                sp.createIndex('type', 'type', { unique: false });
            }

            if (!db.objectStoreNames.contains('emergency_events')) {
                var ee = db.createObjectStore('emergency_events', { keyPath: 'id' });
                ee.createIndex('timestamp', 'timestamp', { unique: false });
                ee.createIndex('status', 'status', { unique: false });
            }

            if (!db.objectStoreNames.contains('relay_queue')) {
                db.createObjectStore('relay_queue', { keyPath: 'id' });
            }

            if (!db.objectStoreNames.contains('profile')) {
                db.createObjectStore('profile', { keyPath: 'key' });
            }
        };

        request.onsuccess = function (e) {
            MARSEL.db = e.target.result;
            // Pas de seed factice : les lieux sûrs réels sont récupérés autour
            // de la position exacte au premier fix GPS (refreshRealSafePlaces).
            resolve(MARSEL.db);
        };

        request.onerror = function () {
            console.warn('DB open error:', request.error);
            reject(request.error);
        };
    });
}

function dbPut(storeName, data) {
    return new Promise(function (resolve, reject) {
        if (!MARSEL.db) { resolve(null); return; }
        try {
            var tx = MARSEL.db.transaction(storeName, 'readwrite');
            var req = tx.objectStore(storeName).put(data);
            req.onsuccess = function () { resolve(req.result); };
            req.onerror = function () { reject(req.error); };
        } catch (e) { reject(e); }
    });
}

function dbGetAll(storeName) {
    return new Promise(function (resolve, reject) {
        if (!MARSEL.db) { resolve([]); return; }
        try {
            var tx = MARSEL.db.transaction(storeName, 'readonly');
            var req = tx.objectStore(storeName).getAll();
            req.onsuccess = function () { resolve(req.result || []); };
            req.onerror = function () { reject(req.error); };
        } catch (e) { resolve([]); }
    });
}

function dbGet(storeName, key) {
    return new Promise(function (resolve, reject) {
        if (!MARSEL.db) { resolve(null); return; }
        try {
            var tx = MARSEL.db.transaction(storeName, 'readonly');
            var req = tx.objectStore(storeName).get(key);
            req.onsuccess = function () { resolve(req.result || null); };
            req.onerror = function () { reject(req.error); };
        } catch (e) { resolve(null); }
    });
}

function dbDelete(storeName, key) {
    return new Promise(function (resolve) {
        if (!MARSEL.db) { resolve(); return; }
        try {
            var tx = MARSEL.db.transaction(storeName, 'readwrite');
            tx.objectStore(storeName).delete(key);
            tx.oncomplete = resolve;
            tx.onerror = resolve; // don't fail hard
        } catch (e) { resolve(); }
    });
}

/* ---------------------------------------------------------
   SAFE PLACES — source : assets/safeplace.csv (dans le git)
   Format : nom_emplacement, lat, long (avec ligne d'en-tête).
   Les lieux s'affichent SELON NOTRE POSITION RÉELLE : uniquement
   ceux dans SAFE_PLACES_RADIUS_M autour du vrai fix GPS.
   --------------------------------------------------------- */
var _safePlacesAll = null;      // contenu parsé du CSV (cache mémoire)
var _spLastRenderPos = null;    // dernière position de rendu (re-filtre si >500m)

function loadSafePlacesCsv() {
    if (_safePlacesAll !== null) return _safePlacesAll;
    var text = '';
    if (window.AndroidBridge && typeof AndroidBridge.getSafePlacesCsv === 'function') {
        try { text = AndroidBridge.getSafePlacesCsv() || ''; } catch (e) {}
    }
    _safePlacesAll = [];
    if (!text) { mLog('J', 'NET', 'safeplace.csv introuvable ou vide'); return _safePlacesAll; }

    var lines = text.split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (!line) continue;
        // Séparateur : virgule, ou point-virgule si pas de virgule (export Excel FR)
        var sep = (line.indexOf(',') === -1 && line.indexOf(';') !== -1) ? ';' : ',';
        var parts = line.split(sep);
        if (parts.length < 3) continue;
        // Format nom_emplacement, lat, long : lat/long lues depuis la DROITE,
        // le nom peut donc contenir des virgules.
        var lng = parseFloat(parts[parts.length - 1]);
        var lat = parseFloat(parts[parts.length - 2]);
        var name = parts.slice(0, parts.length - 2).join(sep).trim();
        if (!isFinite(lat) || !isFinite(lng)) continue; // saute l'en-tête / lignes invalides
        if (!name) continue;
        _safePlacesAll.push({ id: 'csv-' + i, name: name, lat: lat, lng: lng, type: 'safe' });
    }
    mLog('J', 'NET', 'safeplace.csv chargé : ' + _safePlacesAll.length + ' lieu(x)');
    return _safePlacesAll;
}

/* Rafraîchit l'affichage des lieux sûrs autour de la position réelle donnée.
   Idempotent : ne re-rend que si on a bougé de plus de 500 m. */
function refreshRealSafePlaces(lat, lng) {
    if (!isFinite(lat) || !isFinite(lng)) return;
    if (_spLastRenderPos && distanceMeters(lat, lng, _spLastRenderPos.lat, _spLastRenderPos.lng) < 500) return;
    _spLastRenderPos = { lat: lat, lng: lng };
    renderSafePlaces(lat, lng);
}

function renderSafePlaces(lat, lng) {
    if (!MARSEL.leafletMap) return;
    var all = loadSafePlacesCsv();
    var radius = (window.MARSEL_CONFIG && MARSEL_CONFIG.SAFE_PLACES_RADIUS_M) || 2500;

    // Retirer les marqueurs précédents avant de re-filtrer
    MARSEL.safePlaceMarkers.forEach(function (m) {
        try { MARSEL.leafletMap.removeLayer(m); } catch (e) {}
    });
    MARSEL.safePlaceMarkers = [];

    var shown = 0;
    all.forEach(function (place) {
        if (distanceMeters(lat, lng, place.lat, place.lng) > radius) return;
        var icon = createMapMarkerIcon('#4CAF50', 'safe');
        var marker = L.marker([place.lat, place.lng], { icon: icon })
            .addTo(MARSEL.leafletMap)
            .bindPopup('<b>' + escapeHtml(place.name) + '</b>');
        MARSEL.safePlaceMarkers.push(marker);
        shown++;
    });
    mLog('J', 'NET', 'safe places affichés : ' + shown + '/' + all.length + ' (rayon ' + radius + 'm)');
}

/* Distance approximative en mètres entre deux points (équirectangulaire). */
function distanceMeters(la1, ln1, la2, ln2) {
    var R = 6371000, rad = Math.PI / 180;
    var x = (ln2 - ln1) * rad * Math.cos((la1 + la2) / 2 * rad);
    var y = (la2 - la1) * rad;
    return Math.sqrt(x * x + y * y) * R;
}

// Marque comme RESOLVED les urgences de plus de 2h pour éviter
// que les marqueurs des sessions précédentes réapparaissent au démarrage.
function cleanupOldEmergencyEvents() {
    var cutoff = Date.now() - 2 * 60 * 60 * 1000; // 2h
    dbGetAll('emergency_events').then(function (events) {
        events.forEach(function (ev) {
            if (ev.status !== 'RESOLVED' && ev.timestamp && ev.timestamp < cutoff) {
                dbPut('emergency_events', { id: ev.id, status: 'RESOLVED', resolvedAt: Date.now() }).catch(function () {});
            }
        });
    }).catch(function () {});
}

/* ---------------------------------------------------------
   GPS / LOCATION
   --------------------------------------------------------- */
function startRealGPS() {
    if (!navigator.geolocation) {
        tryAndroidLocation();
        return;
    }

    // Immediate fix
    navigator.geolocation.getCurrentPosition(
        function (pos) {
            onLocationUpdate(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy);
        },
        function (err) {
            console.warn('GPS immediate fix failed:', err.message);
            tryAndroidLocation();
        },
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 }
    );

    // Continuous watch
    MARSEL.locationWatchId = navigator.geolocation.watchPosition(
        function (pos) {
            onLocationUpdate(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy);
        },
        function (err) {
            console.warn('GPS watch error:', err.message);
        },
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 10000 }
    );

    // Also start Android location (parallel – more battery efficient on device)
    if (window.AndroidBridge && typeof AndroidBridge.startLocationUpdates === 'function') {
        try { AndroidBridge.startLocationUpdates(); } catch (e) {}
    }
}

function tryAndroidLocation() {
    if (window.AndroidBridge && typeof AndroidBridge.getLocation === 'function') {
        try {
            var locJson = AndroidBridge.getLocation();
            if (locJson) {
                var loc = JSON.parse(locJson);
                if (loc && loc.lat && loc.lng) {
                    onLocationUpdate(parseFloat(loc.lat), parseFloat(loc.lng), loc.accuracy || 0);
                }
            }
        } catch (e) {}
    }
}

function stopGPS() {
    if (MARSEL.locationWatchId !== null && navigator.geolocation) {
        navigator.geolocation.clearWatch(MARSEL.locationWatchId);
        MARSEL.locationWatchId = null;
    }
    if (window.AndroidBridge && typeof AndroidBridge.stopLocationUpdates === 'function') {
        try { AndroidBridge.stopLocationUpdates(); } catch (e) {}
    }
}

/* Retourne une VRAIE position {lat,lng} ou null — jamais de valeur factice.
   1) le fix courant s'il est réel ; 2) sinon le dernier fix réel connu d'Android
   (getLocation), qu'on adopte alors comme position courante. */
function getRealPosition() {
    if (MARSEL.firstGpsFix && isFinite(MARSEL.currentLat) && isFinite(MARSEL.currentLng)) {
        return { lat: MARSEL.currentLat, lng: MARSEL.currentLng };
    }
    if (window.AndroidBridge && typeof AndroidBridge.getLocation === 'function') {
        try {
            var raw = AndroidBridge.getLocation();
            if (raw) {
                var loc = JSON.parse(raw);
                var la = parseFloat(loc && loc.lat), ln = parseFloat(loc && loc.lng);
                if (isFinite(la) && isFinite(ln)) {
                    MARSEL.currentLat = la;
                    MARSEL.currentLng = ln;
                    MARSEL.firstGpsFix = true;
                    return { lat: la, lng: ln };
                }
            }
        } catch (e) {}
    }
    return null;
}

/* true si on dispose d'une vraie position exploitable. */
function hasRealPosition() {
    return getRealPosition() !== null;
}

/* Called by Android native code AND by the browser geolocation callback.
   C'est le SEUL endroit qui écrit MARSEL.currentLat/Lng — et uniquement avec
   de vraies coordonnées GPS. */
window.onLocationUpdate = function (lat, lng, accuracy) {
    var fLat = parseFloat(lat);
    var fLng = parseFloat(lng);
    // Rejeter uniquement les valeurs non numériques (0,0 est une coordonnée valide)
    if (!isFinite(fLat) || !isFinite(fLng)) { mLog('J','GPS','invalid coords: ' + lat + ',' + lng); return; }
    var fAcc = parseFloat(accuracy);
    if (!isFinite(fAcc) || fAcc <= 0) fAcc = 1000; // précision inconnue = grossière
    mLog('J', 'GPS', 'fix lat=' + fLat.toFixed(5) + ' lng=' + fLng.toFixed(5) + ' acc=' + Math.round(fAcc));

    // GPS-QUALITY (terrain) : les fix « network » (cellule/WiFi, 400-800 m)
    // arrivent APRÈS les fix GPS précis et écrasaient la vraie position — la
    // carte semblait « ne pas recentrer sur nous ». On rejette un fix grossier
    // (>100 m) si on a accepté un fix précis (<50 m) il y a moins de 30 s.
    // Jamais de rejet du premier fix : une position grossière vaut mieux
    // qu'aucune position (elle sera remplacée dès qu'un fix précis arrive).
    var nowTs = Date.now();
    if (MARSEL.firstGpsFix && fAcc > 100 &&
        MARSEL.lastFixAcc !== null && MARSEL.lastFixAcc < 50 &&
        (nowTs - MARSEL.lastFixTs) < 30000) {
        mLog('J', 'GPS', 'fix grossier rejeté (acc=' + Math.round(fAcc) +
            'm, précis récent acc=' + Math.round(MARSEL.lastFixAcc) + 'm)');
        return;
    }
    MARSEL.lastFixAcc = fAcc;
    MARSEL.lastFixTs = nowTs;

    var wasFirst = !MARSEL.firstGpsFix;
    MARSEL.currentLat = fLat;
    MARSEL.currentLng = fLng;
    // Vrai fix reçu : marquer IMMÉDIATEMENT, indépendamment de l'état de la carte.
    // (Avant, ce flag n'était posé que si le marqueur existait déjà → un vrai fix
    //  arrivé avant l'init de la carte n'était jamais reconnu.)
    MARSEL.firstGpsFix = true;

    // Créer / déplacer le marqueur utilisateur — jamais posé tant qu'on n'a pas
    // de vraie position (donc aucun marqueur « fantôme » à Paris).
    if (MARSEL.leafletMap) {
        if (!MARSEL.userMarker) {
            var uicon = L.divIcon({
                html: '<div class="user-location-marker"><div class="user-pulse"></div></div>',
                iconSize: [20, 20], iconAnchor: [10, 10], className: ''
            });
            MARSEL.userMarker = L.marker([fLat, fLng], { icon: uicon })
                .addTo(MARSEL.leafletMap).bindPopup('<b>Ma position</b>');
        } else {
            MARSEL.userMarker.setLatLng([fLat, fLng]);
        }
        if (wasFirst) {
            MARSEL.leafletMap.setView([fLat, fLng], 15);
            MARSEL.mapCenteredAcc = fAcc;
        } else if (MARSEL.mapCenteredAcc !== null &&
                   MARSEL.mapCenteredAcc > 100 && fAcc < 20) {
            // RECENTRAGE INTELLIGENT : la carte avait été centrée sur un fix
            // grossier (réseau) et un fix GPS précis vient d'arriver — la vraie
            // position peut être à des centaines de mètres du centre affiché.
            MARSEL.leafletMap.setView([fLat, fLng], 15);
            MARSEL.mapCenteredAcc = fAcc;
            mLog('J', 'MAP', 'recentrage auto sur fix précis (acc=' + Math.round(fAcc) + 'm)');
        }
        // Lieux sûrs du CSV selon la position réelle — appelé à chaque fix,
        // le garde interne (>500 m) évite tout re-rendu inutile ; les lieux
        // suivent donc l'utilisateur quand il se déplace.
        refreshRealSafePlaces(fLat, fLng);
    }

    // Si urgence active : mettre à jour la position locale uniquement.
    // La diffusion relay est gérée par startEmergencyTracking (toutes les 10s).
    // NE PAS appeler sendPositionUpdate ici : ça tirerait sendEmergencyViaRelay
    // à chaque fix GPS (~1 Hz), saturant complètement le stack WiFi P2P.
    if (MARSEL.emergencyActive && MARSEL.emergencyId) {
        var saved = null;
        try { saved = JSON.parse(secureGet('marsel_emergency') || 'null'); } catch (e) {}
        if (saved) {
            var hadNoPosition = !isFinite(saved.lat) || !isFinite(saved.lng);
            saved.lat = fLat;
            saved.lng = fLng;
            secureSet('marsel_emergency', JSON.stringify(saved));
            // L'alerte avait été déclenchée AVANT d'avoir un fix : maintenant qu'on
            // a la vraie position, on crée le marqueur d'incident et on la diffuse
            // immédiatement (une seule fois) au réseau + backend.
            if (hadNoPosition) {
                addIncidentMarker(saved);
                if (MARSEL.leafletMap) MARSEL.leafletMap.setView([fLat, fLng], 15);
                sendPositionUpdate(fLat, fLng);
            }
        }
    }
};

/* Demande un fix GPS réel IMMÉDIAT (haute précision), navigateur + Android.
   Le résultat arrive via onLocationUpdate. Aucune position factice n'est jamais
   fabriquée : si le GPS ne répond pas, il n'y a simplement pas de position. */
function acquireImmediateRealFix() {
    if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
            function (p) { onLocationUpdate(p.coords.latitude, p.coords.longitude, p.coords.accuracy); },
            function () { tryAndroidLocation(); },
            { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
        );
    } else {
        tryAndroidLocation();
    }
    if (window.AndroidBridge && typeof AndroidBridge.startLocationUpdates === 'function') {
        try { AndroidBridge.startLocationUpdates(); } catch (e) {}
    }
}

/* ---------------------------------------------------------
   MAP
   --------------------------------------------------------- */
/* Bouton de recentrage manuel : recentre la carte sur la position réelle
   (fix courant ou dernier fix réel connu). Aucune position factice : sans
   vrai fix, on informe l'utilisateur au lieu d'inventer un centre. */
function recenterMap() {
    var pos = getRealPosition();
    if (pos && MARSEL.leafletMap) {
        MARSEL.leafletMap.setView([pos.lat, pos.lng], 16);
        if (MARSEL.lastFixAcc !== null) MARSEL.mapCenteredAcc = MARSEL.lastFixAcc;
    } else {
        showToast('Position GPS pas encore disponible');
        acquireImmediateRealFix();
    }
}

function initMap() {
    if (MARSEL.mapInitialized) {
        if (MARSEL.leafletMap) {
            setTimeout(function () {
                MARSEL.leafletMap.invalidateSize();
                // Recharge les incidents reçus pendant qu'on était sur un autre écran
                loadIncidentsOnMap();
            }, 200);
        }
        return;
    }
    if (typeof L === 'undefined') { return; }

    var mapEl = document.getElementById('map');
    if (!mapEl) return;

    mapEl.innerHTML = '';
    MARSEL.mapInitialized = true;

    try {
        MARSEL.leafletMap = L.map('map', {
            zoomControl: false,
            attributionControl: false,
            dragging: true,
            touchZoom: true,
            scrollWheelZoom: false
        });
        // Centre initial : la VRAIE position si on en a déjà une (fix courant ou
        // dernier fix réel connu d'Android), sinon vue monde neutre (aucune
        // position factice). onLocationUpdate recentrera au premier vrai fix.
        var realStart = getRealPosition();
        if (realStart) {
            MARSEL.leafletMap.setView([realStart.lat, realStart.lng], 15);
            // Précision inconnue pour un « dernier fix connu » → traiter comme
            // grossier : le premier fix GPS précis recentrera automatiquement.
            MARSEL.mapCenteredAcc = 9999;
        } else {
            MARSEL.leafletMap.setView([20, 0], 2);
        }

        // Tile layer: Mapbox if token, else CartoDB light
        if (window.MARSEL_CONFIG && MARSEL_CONFIG.MAPBOX_TOKEN) {
            L.tileLayer('https://api.mapbox.com/styles/v1/{id}/tiles/{z}/{x}/{y}?access_token={accessToken}', {
                attribution: '© Mapbox',
                id: 'mapbox/light-v11',
                tileSize: 512,
                zoomOffset: -1,
                accessToken: MARSEL_CONFIG.MAPBOX_TOKEN,
                maxZoom: 19
            }).addTo(MARSEL.leafletMap);
        } else {
            L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png', {
                attribution: '© OpenStreetMap contributors',
                subdomains: 'abcd',
                maxZoom: 19
            }).addTo(MARSEL.leafletMap);
        }

        // Marqueur utilisateur : créé UNIQUEMENT si on a une vraie position.
        // Sinon il apparaîtra au premier vrai fix (dans onLocationUpdate).
        if (realStart) {
            var userIcon = L.divIcon({
                html: '<div class="user-location-marker"><div class="user-pulse"></div></div>',
                iconSize: [20, 20],
                iconAnchor: [10, 10],
                className: ''
            });
            MARSEL.userMarker = L.marker([realStart.lat, realStart.lng], { icon: userIcon })
                .addTo(MARSEL.leafletMap)
                .bindPopup('<b>Ma position</b>');
        }

        // Zoom control bottom right
        L.control.zoom({ position: 'bottomright' }).addTo(MARSEL.leafletMap);

        // Load data from DB (lieux sûrs réels déjà en cache + incidents)
        loadSafePlacesOnMap();
        loadIncidentsOnMap();
        // Si on a déjà une vraie position au moment de l'init, rafraîchir les
        // lieux sûrs réels autour (sinon onLocationUpdate le fera au 1er fix).
        if (realStart) refreshRealSafePlaces(realStart.lat, realStart.lng);

        var settings = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
        if (settings.location) {
            loadFriendsOnMap();
        }

        setTimeout(function () {
            if (MARSEL.leafletMap) MARSEL.leafletMap.invalidateSize();
        }, 300);

    } catch (err) {
        console.warn('Map init failed:', err);
        MARSEL.mapInitialized = false;
        var fallback = document.getElementById('map');
        if (fallback) {
            fallback.innerHTML = '<div class="map-placeholder"><svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#9E9E9E" stroke-width="1.5"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg><p>Carte indisponible</p></div>';
        }
    }
}

function loadSafePlacesOnMap() {
    // Source : safeplace.csv, affiché selon la position RÉELLE uniquement.
    // Sans vrai fix GPS, rien n'est affiché — les lieux apparaîtront au
    // premier fix via refreshRealSafePlaces (onLocationUpdate).
    var pos = getRealPosition();
    if (pos) renderSafePlaces(pos.lat, pos.lng);
}

function loadIncidentsOnMap() {
    dbGetAll('emergency_events').then(function (events) {
        if (!MARSEL.leafletMap) return;
        var cutoff = Date.now() - 24 * 60 * 60 * 1000; // last 24h
        events.forEach(function (ev) {
            if (ev.timestamp > cutoff && ev.lat && ev.lng && ev.status !== 'RESOLVED') {
                addIncidentMarker(ev);
            }
        });
    }).catch(function () {});
}

function addIncidentMarker(ev) {
    if (!MARSEL.leafletMap) return;
    if (!ev || !isFinite(ev.lat) || !isFinite(ev.lng)) return; // jamais de marqueur sans vraie position
    if (MARSEL.incidentMarkers[ev.id]) return; // already shown

    var icon = L.divIcon({
        html: '<div style="width:32px;height:32px;border-radius:50%;background:#E84315;display:flex;align-items:center;justify-content:center;border:3px solid white;box-shadow:0 2px 8px rgba(232,67,21,0.6);animation:pulse-emergency 1s infinite;"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><path d="M13 2L3 14h7l-1 8 10-12h-7z"/></svg></div>',
        iconSize: [32, 32],
        iconAnchor: [16, 16],
        className: ''
    });

    var timeStr = '';
    var dateStr = '';
    if (ev.timestamp) {
        var d = new Date(ev.timestamp);
        timeStr = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
        dateStr = d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
    }

    var marker = L.marker([ev.lat, ev.lng], { icon: icon })
        .addTo(MARSEL.leafletMap)
        .bindPopup('<b>🚨 Alerte Marsel</b><br>Utilisateur : ' + escapeHtml(ev.pseudo || '?') + (dateStr ? '<br>Date : ' + dateStr : '') + (timeStr ? '<br>Heure : ' + timeStr : ''));

    MARSEL.incidentMarkers[ev.id] = marker;
}

function createMapMarkerIcon(color, type) {
    var icons = {
        police: '<path d="M12 2L2 7v10l10 5 10-5V7L12 2z" fill="white"/>',
        hopital: '<path d="M12 2v20M2 12h20" stroke="white" stroke-width="3" stroke-linecap="round"/>',
        mairie: '<path d="M3 21h18M3 10h18M3 7l9-5 9 5M8 21v-5h8v5" fill="none" stroke="white" stroke-width="2"/>',
        safe: '<path d="M12 22s-8-4-8-9a8 8 0 0 1 16 0c0 5-8 9-8 9z" fill="none" stroke="white" stroke-width="2"/>'
    };
    var svgPath = icons[type] || icons['safe'];
    return L.divIcon({
        html: '<div style="width:30px;height:30px;border-radius:50%;background:' + color + ';display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 6px rgba(0,0,0,0.3);"><svg width="14" height="14" viewBox="0 0 24 24">' + svgPath + '</svg></div>',
        iconSize: [30, 30],
        iconAnchor: [15, 15],
        className: ''
    });
}

function loadFriendsOnMap() {
    if (!MARSEL.leafletMap) return;

    // Load contacts who have location sharing enabled.
    // Without a real server, we show contacts at a slight offset from user's position
    // so the map reflects "nearby" contacts. When API_URL is configured, fetch real coords.
    var contacts = JSON.parse(secureGet('marsel_contacts') || '[]');
    var settings = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
    if (!settings.location) return;

    contacts.forEach(function (contact, idx) {
        if (!contact.nom && !contact.mobile) return;

        var userId = 'contact-' + idx;
        if (MARSEL.friendMarkers[userId]) return; // already shown

        // If API is configured, try to fetch real position
        if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL && MARSEL_CONFIG.API_KEY) {
            var userData = JSON.parse(secureGet('marsel_user') || '{}');
            fetch(MARSEL_CONFIG.API_URL + '/friends/location?contactEmail=' + encodeURIComponent(contact.email || ''), {
                headers: { 'Authorization': 'Bearer ' + (userData.token || MARSEL_CONFIG.API_KEY) }
            }).then(function (r) {
                if (r.ok) return r.json();
            }).then(function (data) {
                if (data && data.lat && data.lng) {
                    placeFriendMarker(userId, data.lat, data.lng, contact.nom || contact.email);
                }
            }).catch(function () {});
        }
    });
}

function placeFriendMarker(userId, lat, lng, name) {
    if (!MARSEL.leafletMap) return;
    if (MARSEL.friendMarkers[userId]) return;

    var icon = L.divIcon({
        html: '<div style="width:28px;height:28px;border-radius:50%;background:#FF7043;display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 6px rgba(255,112,67,0.5);"><svg width="14" height="14" viewBox="0 0 24 24" fill="white"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg></div>',
        iconSize: [28, 28],
        iconAnchor: [14, 14],
        className: ''
    });

    var marker = L.marker([lat, lng], { icon: icon })
        .addTo(MARSEL.leafletMap)
        .bindPopup('<b>' + escapeHtml(name || 'Proche') + '</b>');

    MARSEL.friendMarkers[userId] = marker;
}

/* ---------------------------------------------------------
   NETWORK STATUS
   --------------------------------------------------------- */
function updateNetworkStatus() {
    var type = 'NONE';

    if (window.AndroidBridge && typeof AndroidBridge.getNetworkType === 'function') {
        try { type = AndroidBridge.getNetworkType() || 'NONE'; } catch (e) {}
    } else if (navigator.onLine) {
        var conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
        if (conn && conn.type) {
            if (conn.type === 'wifi') type = 'WIFI';
            else if (conn.type === 'cellular') type = 'MOBILE';
            else type = 'WIFI';
        } else {
            type = 'WIFI';
        }
    }

    if (type !== MARSEL.networkType) mLog('J', 'NET', 'network changed → ' + type);
    MARSEL.networkType = type;
    return type;
}

function updateNetworkBadge(override) {
    var badge = document.getElementById('network-badge');
    if (!badge) return;

    var label;
    var bgColor;

    if (override) {
        label = override;
        bgColor = '#FF9800';
    } else {
        var t = updateNetworkStatus();
        if (t === 'WIFI') {
            label = '📶 WiFi';
            bgColor = '#4CAF50';
        } else if (t === 'MOBILE') {
            label = '📱 Mobile';
            bgColor = '#4CAF50';
        } else {
            label = '🔁 Marsel Relay Network';
            bgColor = '#FF5722';
        }
    }

    badge.textContent = label;
    badge.style.background = bgColor;
}

/* ---------------------------------------------------------
   MODE TOGGLE
   --------------------------------------------------------- */
function setMode(mode) {
    MARSEL.mode = mode;
    localStorage.setItem('marsel_mode', mode);

    var modeLabel = document.getElementById('mode-label');
    var modeBtn = document.getElementById('mode-toggle-btn');

    if (modeLabel) {
        modeLabel.textContent = mode === 'autonome' ? 'Autonome' : 'Assistance';
    }
    if (modeBtn) {
        if (mode === 'assistance') {
            modeBtn.classList.add('assistance');
        } else {
            modeBtn.classList.remove('assistance');
        }
    }

    // Show chat FAB in assistance mode always; in autonome only when emergency active
    var chatFab = document.getElementById('chat-fab');
    if (chatFab) {
        if (mode === 'assistance' || MARSEL.emergencyActive) {
            chatFab.style.display = 'flex';
            chatFab.classList.add('visible');
        } else {
            chatFab.style.display = 'none';
            chatFab.classList.remove('visible');
        }
    }
}

function toggleMode() {
    var newMode = MARSEL.mode === 'autonome' ? 'assistance' : 'autonome';
    setMode(newMode);
    showToast('Mode ' + (newMode === 'autonome' ? 'Autonome' : 'Assistance') + ' activé');
}

/* ---------------------------------------------------------
   EMERGENCY SYSTEM
   --------------------------------------------------------- */
function startEmergencyHold(e) {
    e.preventDefault();

    if (MARSEL.emergencyActive) {
        deactivateEmergency();
        return;
    }

    var startTime = Date.now();
    var fillEl = document.getElementById('progress-ring-fill');
    if (fillEl) {
        fillEl.style.transition = 'stroke-dashoffset 0.05s linear';
        fillEl.style.strokeDashoffset = RING_CIRCUMFERENCE;
    }

    // Initial haptic
    if (window.AndroidBridge && typeof AndroidBridge.vibrate === 'function') {
        try { AndroidBridge.vibrate(50); } catch (e) {}
    }

    MARSEL.emergencyRingInterval = setInterval(function () {
        var elapsed = Date.now() - startTime;
        var fraction = Math.min(elapsed / ((window.MARSEL_CONFIG && MARSEL_CONFIG.EMERGENCY_HOLD_DURATION) || 5000), 1);

        if (fillEl) {
            fillEl.style.strokeDashoffset = RING_CIRCUMFERENCE * (1 - fraction);
        }

        if (fraction >= 1) {
            clearInterval(MARSEL.emergencyRingInterval);
            MARSEL.emergencyRingInterval = null;
            activateEmergency();
        }
    }, 50);
}

function cancelEmergencyHold(e) {
    if (MARSEL.emergencyActive) return;

    if (MARSEL.emergencyRingInterval) {
        clearInterval(MARSEL.emergencyRingInterval);
        MARSEL.emergencyRingInterval = null;
    }

    var fillEl = document.getElementById('progress-ring-fill');
    if (fillEl) {
        fillEl.style.transition = 'stroke-dashoffset 0.3s ease';
        fillEl.style.strokeDashoffset = RING_CIRCUMFERENCE;
        setTimeout(function () {
            fillEl.style.transition = 'stroke-dashoffset 0.05s linear';
        }, 300);
    }
}

function activateEmergency() {
    MARSEL.emergencyActive = true;
    MARSEL.emergencyId = generateEmergencyId();

    updateEmergencyUI();

    // Shield features
    var settings = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
    if (window.AndroidBridge) {
        if (settings.flash && typeof AndroidBridge.activateFlash === 'function') {
            try { AndroidBridge.activateFlash(); } catch (e) {}
        }
        if (settings.audio && typeof AndroidBridge.startAudioRecord === 'function') {
            try { AndroidBridge.startAudioRecord(); } catch (e) {}
        }
        if (typeof AndroidBridge.vibrate === 'function') {
            try { AndroidBridge.vibrate(800); } catch (e) {}
        }
    }

    // Build emergency payload
    var contacts = JSON.parse(secureGet('marsel_contacts') || '[]')
        .filter(function (c) { return c.nom || c.mobile; });
    var userData = JSON.parse(secureGet('marsel_user') || '{}');
    var profileData = JSON.parse(secureGet('marsel_profile') || '{}');

    // Position RÉELLE uniquement : fix courant, sinon dernier fix réel Android.
    // Jamais de coordonnées par défaut. Si aucune position réelle n'est encore
    // disponible, l'alerte part quand même (mieux vaut une alerte sans position
    // exacte que pas d'alerte), lat/lng restent null → « position non disponible »,
    // et le tracking diffusera la vraie position dès le premier fix (ci-dessous).
    var pos = getRealPosition();
    if (!pos) {
        mLog('J', 'GPS', 'Alerte sans fix GPS encore — acquisition en cours, position réelle diffusée dès réception');
        acquireImmediateRealFix();
    }

    var emergencyData = {
        type: 'MARSEL_EMERGENCY',
        version: 1,
        id: MARSEL.emergencyId,
        messageId: MARSEL.emergencyId,
        userId: userData.userId || userData.email || 'unknown',
        pseudo: profileData.pseudo || userData.pseudo || userData.email || 'Utilisateur',
        lat: pos ? pos.lat : null,
        lng: pos ? pos.lng : null,
        timestamp: Date.now(),
        contacts: contacts,
        mode: MARSEL.mode,
        hopCount: 0,
        maxHops: (window.MARSEL_CONFIG && MARSEL_CONFIG.RELAY_HOP_LIMIT) || 5,
        status: 'ACTIVE',
        originNetwork: MARSEL.networkType
    };

    // Persist locally
    dbPut('emergency_events', emergencyData).catch(function () {});
    secureSet('marsel_emergency', JSON.stringify(emergencyData));

    // Show own incident on map (uniquement si vraie position)
    if (pos) addIncidentMarker(emergencyData);

    // Route it
    routeEmergency(emergencyData);

    // Démarrer le tracking GPS continu (position toutes les 10s)
    startEmergencyTracking(emergencyData);

    // Armer le timer 20 min (Section 4)
    armEmergencyTimeout(emergencyData);

    showToast('🚨 Alerte envoyée !');
}

function deactivateEmergency() {
    // Stopper le tracking GPS continu
    stopEmergencyTracking();
    // Annuler le timer 20 min (Section 4)
    cancelEmergencyTimeout();

    MARSEL.emergencyActive = false;

    // Stop shield
    if (window.AndroidBridge) {
        if (typeof AndroidBridge.stopFlash === 'function') {
            try { AndroidBridge.stopFlash(); } catch (e) {}
        }
        if (typeof AndroidBridge.stopAudioRecord === 'function') {
            try { AndroidBridge.stopAudioRecord(); } catch (e) {}
        }
    }

    // Envoyer notification de fin d'alerte aux proches et appareils voisins
    var savedEmergency = null;
    try { savedEmergency = JSON.parse(secureGet('marsel_emergency') || 'null'); } catch (e) {}

    if (savedEmergency) {
        var pseudo = savedEmergency.pseudo || 'Utilisateur';

        // Dernière position RÉELLE connue : fix courant sinon dernière position
        // réelle de l'alerte. Jamais de « null,null » ni de valeur par défaut.
        var finPos = getRealPosition();
        if (!finPos && isFinite(savedEmergency.lat) && isFinite(savedEmergency.lng)) {
            finPos = { lat: savedEmergency.lat, lng: savedEmergency.lng };
        }
        var lastPosLine = finPos
            ? '\nDernière position connue : https://maps.google.com/?q=' + finPos.lat + ',' + finPos.lng
            : '';

        // Enregistrement audio actif ? (shield audio) → mention dans le SMS de fin
        var settingsFin = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
        var hasAudio = !!settingsFin.audio;
        var audioMention = hasAudio ? '\nUn enregistrement audio de l\'alerte est disponible.' : '';
        var finMsg = '✅ FIN D\'ALERTE MARSEL\n' + pseudo + ' est en sécurité.' + lastPosLine + audioMention;

        // ÉTAPE D : SMS de fin via la MÊME cascade que l'étape A
        // (mobile → wifi → paquet MRN MARSEL_RESOLVED_SMS_REQUEST relayé).
        sendCascadeSms('RESOLVED', savedEmergency, finMsg, hasAudio);

        // Paquet de résolution vers les appareils voisins via relay.
        // Position réelle uniquement (fix courant ou dernière position réelle).
        var resolvedPacket = {
            type: 'MARSEL_EMERGENCY_RESOLVED',
            version: 1,
            messageId: MARSEL.emergencyId + '_resolved_' + Date.now(),
            emergencyId: MARSEL.emergencyId,
            userId: savedEmergency.userId,
            pseudo: pseudo,
            lat: finPos ? finPos.lat : null,
            lng: finPos ? finPos.lng : null,
            timestamp: Date.now(),
            contacts: savedEmergency.contacts || [],
            hopCount: 0,
            maxHops: 5
        };
        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
            try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(resolvedPacket)); } catch (e) {}
        }
    }

    // Mark resolved in DB
    if (MARSEL.emergencyId) {
        var resolvedId = MARSEL.emergencyId;
        MARSEL.resolvedEmergencies[resolvedId] = true;
        dbPut('emergency_events', {
            id: resolvedId,
            status: 'RESOLVED',
            resolvedAt: Date.now()
        }).catch(function () {});

        // Remove from map
        if (MARSEL.incidentMarkers[resolvedId]) {
            try { MARSEL.leafletMap.removeLayer(MARSEL.incidentMarkers[resolvedId]); } catch (e) {}
            delete MARSEL.incidentMarkers[resolvedId];
        }

        // BUG-2 : purger la file relay JS de TOUT paquet de cette urgence —
        // sinon ils seraient re-poussés plus tard (marqueurs/SMS fantômes).
        MARSEL.relayQueue = MARSEL.relayQueue.filter(function (p) {
            var pid = (p && (p.emergencyId || p.id || p.messageId)) || '';
            return String(pid).indexOf(resolvedId) === -1;
        });
        dbDelete('relay_queue', resolvedId).catch(function () {});
    }

    secureRemove('marsel_emergency');
    MARSEL.emergencyId = null;

    // Reset ring
    var fillEl = document.getElementById('progress-ring-fill');
    if (fillEl) {
        fillEl.style.transition = 'stroke-dashoffset 0.3s ease';
        fillEl.style.strokeDashoffset = RING_CIRCUMFERENCE;
        setTimeout(function () { fillEl.style.transition = 'stroke-dashoffset 0.05s linear'; }, 300);
    }

    updateEmergencyUI();
    showToast('✅ Alerte désactivée – SMS de fin envoyé aux proches');
}

function updateEmergencyUI() {
    var btn = document.getElementById('emergency-btn');
    var inner = document.getElementById('emergency-btn-inner');
    var label = document.getElementById('emergency-label');
    var chatFab = document.getElementById('chat-fab');

    if (!btn) return;

    if (MARSEL.emergencyActive) {
        btn.classList.add('active');
        if (label) {
            label.textContent = 'Alerte en cours – Appuyer pour annuler';
            label.classList.add('visible');
        }
        if (chatFab) {
            chatFab.style.display = 'flex';
            chatFab.classList.add('visible');
        }
        if (inner) {
            inner.innerHTML = [
                '<svg width="70" height="70" viewBox="0 0 70 70" fill="#E84315">',
                    '<path d="M42 5L18 38h20l-8 27 28-35H38z"/>',
                '</svg>'
            ].join('');
        }
    } else {
        btn.classList.remove('active');
        if (label) {
            label.textContent = 'Activation Marsel';
            label.classList.remove('visible');
        }
        // Chat FAB: visible only in assistance mode
        if (chatFab) {
            if (MARSEL.mode === 'assistance') {
                chatFab.style.display = 'flex';
                chatFab.classList.add('visible');
            } else {
                chatFab.style.display = 'none';
                chatFab.classList.remove('visible');
            }
        }
        if (inner) {
            inner.innerHTML = [
                '<div class="emergency-s-wrapper">',
                    '<span class="emergency-s">S</span>',
                    '<span class="emergency-bolt-dot">',
                        '<svg width="22" height="22" viewBox="0 0 22 22">',
                            '<circle cx="11" cy="11" r="11" fill="#E84315"/>',
                            '<path d="M12.5 3.5L7.5 12h5l-2.5 6.5 8-9.5H13z" fill="white"/>',
                        '</svg>',
                    '</span>',
                '</div>'
            ].join('');
        }
    }
}

/* ---------------------------------------------------------
   EMERGENCY ROUTING — cascade réseau (Section 1)
   --------------------------------------------------------- */

// Qualité réseau VALIDÉE via le bridge natif (F1). Fallback navigator si
// le bridge est absent (test navigateur).
function getNetworkQuality() {
    if (window.AndroidBridge && typeof AndroidBridge.detectNetworkQuality === 'function') {
        try { return AndroidBridge.detectNetworkQuality() || 'NONE'; } catch (e) {}
    }
    return navigator.onLine ? 'WIFI_STABLE' : 'NONE';
}

// Capacité SMS réelle (SIM prête + réseau cellulaire enregistré). Le réseau
// data ne dit RIEN de la capacité SMS : un téléphone en WiFi sans SIM (ou avec
// SIM mais en zone blanche) doit déléguer ses SMS au réseau Marsel.
function deviceCanSendSms() {
    if (window.AndroidBridge && typeof AndroidBridge.canSendSms === 'function') {
        try { return !!AndroidBridge.canSendSms(); } catch (e) {}
    }
    return false;
}

/* ---------------------------------------------------------
   ACK RELAIS SMS — boucle fermée émetteur ↔ relais
   Quand les SMS sont délégués au réseau Marsel, le relais qui les envoie
   RÉELLEMENT (accusé système Android) diffuse un ACK qui remonte jusqu'à
   nous (max 5 sauts). Sans ACK après SMS_ACK_TIMEOUT_MS (2 min) :
   notification « Impossible d'envoyer les SMS ». Le paquet reste en
   diffusion : si un relais apparaît plus tard, le succès est notifié.
   --------------------------------------------------------- */
var _smsAckTimers = {};   // id attendu → handle setTimeout
var _smsAckDone = {};     // id attendu → true (ACK reçu)
var _cascadeCtx = {};     // trackId d'envoi direct → contexte pour bascule MRN

function armSmsAckWait(expectedId, label) {
    if (!expectedId || _smsAckDone[expectedId] || _smsAckTimers[expectedId]) return;
    var timeoutMs = (window.MARSEL_CONFIG && MARSEL_CONFIG.SMS_ACK_TIMEOUT_MS) || 120000;
    mLog('J', 'SMS', 'attente ACK relais pour ' + expectedId + ' (' + Math.round(timeoutMs / 1000) + 's max)');
    _smsAckTimers[expectedId] = setTimeout(function () {
        _smsAckTimers[expectedId] = null;
        if (_smsAckDone[expectedId]) return;
        mLog('J', 'SMS', 'AUCUN ACK pour ' + expectedId + ' — échec notifié à l\'utilisateur');
        if (window.AndroidBridge && typeof AndroidBridge.showNotification === 'function') {
            try {
                AndroidBridge.showNotification('⚠️ SMS non envoyés',
                    'Impossible d\'envoyer les SMS ' + label + ' : aucun téléphone relais avec réseau à portée. L\'alerte continue de chercher un relais.');
            } catch (e) {}
        }
        showToast('⚠️ Impossible d\'envoyer les SMS — aucun relais avec réseau à portée');
    }, timeoutMs);
}

/* Appelé par Android quand un ACK relais arrive (les SMS ont réellement été
   envoyés quelque part dans le maillage pour la demande ackedId). */
window.onSmsRelayAck = function (ackedId) {
    mLog('J', 'SMS', 'ACK relais reçu pour ' + ackedId);
    if (_smsAckDone[ackedId]) return;
    // Ne notifier que si c'est NOTRE demande (timer armé, ou id de notre urgence)
    var ours = false;
    if (_smsAckTimers[ackedId]) {
        clearTimeout(_smsAckTimers[ackedId]);
        _smsAckTimers[ackedId] = null;
        ours = true;
    }
    if (MARSEL.emergencyId && ackedId.indexOf(MARSEL.emergencyId) === 0) ours = true;
    if (!ours) return;
    _smsAckDone[ackedId] = true;
    if (window.AndroidBridge && typeof AndroidBridge.showNotification === 'function') {
        try { AndroidBridge.showNotification('✅ SMS envoyés', 'Tes proches ont été prévenus par SMS via le réseau Marsel.'); } catch (e) {}
    }
    showToast('✅ SMS envoyés à tes proches via le réseau Marsel');
};

/* Appelé par Android avec le VERDICT système d'un envoi SMS local suivi.
   Échec réel (radio, zone blanche apparue entre le check et l'envoi) →
   bascule automatique sur le réseau Marsel. */
window.onSmsSendResult = function (trackId, ok, code) {
    mLog('J', 'SMS', 'verdict envoi ' + trackId + ' ok=' + ok + ' code=' + code);
    if (ok) {
        delete _cascadeCtx[trackId];
        return;
    }
    // Échec d'un SMS de fin d'alerte / relance 20 min envoyé en direct
    if (_cascadeCtx[trackId]) {
        var ctx = _cascadeCtx[trackId];
        delete _cascadeCtx[trackId];
        mLog('J', 'SMS', 'échec SMS direct ' + ctx.kind + ' — bascule sur le réseau Marsel');
        showToast('🔁 SMS non parti — tentative via le réseau Marsel');
        sendCascadeViaMrn(ctx.kind, ctx.savedEmergency, ctx.hasAudio);
        return;
    }
    // Échec du SMS d'alerte initial (trackId = messageId de l'urgence active)
    var saved = null;
    try { saved = JSON.parse(secureGet('marsel_emergency') || 'null'); } catch (e) {}
    if (saved && (saved.messageId === trackId || saved.id === trackId)) {
        saved.relaySms = '1';
        saved.smsSent = false;
        if (!saved.type) saved.type = 'MARSEL_EMERGENCY';
        secureSet('marsel_emergency', JSON.stringify(saved));
        mLog('J', 'SMS', 'échec SMS d\'alerte — relaySms=1, re-diffusion MRN');
        showToast('🔁 SMS non parti — délégué au réseau Marsel');
        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
            try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(saved)); } catch (e) {}
        }
        armSmsAckWait(trackId, 'd\'alerte');
    }
};

function routeEmergency(emergencyData) {
    var quality = getNetworkQuality();
    var canSms = deviceCanSendSms();
    updateNetworkStatus();
    mLog('J', 'NET', 'routeEmergency quality=' + quality + ' canSms=' + canSms);

    // AUDIT-FIX 2 + TEST-FIX : relaySms indique si un relais doit envoyer les SMS
    // à ma place. Vrai si je suis hors réseau validé OU si je ne peux pas envoyer
    // de SMS moi-même (PAS DE SIM — cas terrain : WiFi sans SIM).
    // Doit être positionné AVANT la diffusion MRN (ÉTAPE B) pour voyager dans le TXT.
    emergencyData.relaySms = (quality === 'NONE' || !canSms) ? '1' : '0';
    // AUDIT-FIX 3 : persister relaySms pour qu'un redémarrage de l'app préserve
    // le flag lors de la re-diffusion de l'alerte.
    try {
        var savedRoute = JSON.parse(secureGet('marsel_emergency') || 'null');
        if (savedRoute) { savedRoute.relaySms = emergencyData.relaySms; secureSet('marsel_emergency', JSON.stringify(savedRoute)); }
    } catch (e) {}

    // ── ÉTAPE B (TOUJOURS) : diffusion MRN ──
    // Notification système + marqueur GPS chez les téléphones Marsel voisins,
    // quel que soit l'état réseau. Le paquet transporte les numéros des proches
    // (champ "c" du TXT record) pour la chaîne relay hors-ligne.
    sendEmergencyViaRelayNetwork(emergencyData);

    // ── ÉTAPE A : alerte aux proches selon la cascade ──
    if (!canSms) {
        // TEST-FIX : pas de SIM — impossible d'envoyer les SMS nous-mêmes, même
        // avec du réseau. Les numéros voyagent dans le paquet MRN (relaySms=1) :
        // un relais avec SIM les enverra à notre place. API backend si dispo.
        emergencyData.smsSent = false;
        if (quality !== 'NONE' && window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL) sendToAPI(emergencyData);
        mLog('J', 'NET', 'Pas de SIM/réseau cellulaire — SMS délégués au relais MRN (relaySms=1)');
        showToast('🔁 Pas de SIM : SMS aux proches délégués au réseau Marsel');
        try {
            var savedNoSim = JSON.parse(secureGet('marsel_emergency') || 'null');
            if (savedNoSim) { savedNoSim.smsSent = false; secureSet('marsel_emergency', JSON.stringify(savedNoSim)); }
        } catch (e) {}
        // ACK attendu du relais qui enverra réellement ; sans ACK sous 2 min →
        // notification « Impossible d'envoyer les SMS »
        armSmsAckWait(emergencyData.messageId || emergencyData.id, 'd\'alerte');
    } else if (quality === 'MOBILE_STABLE') {
        // 3G/4G/5G validé → SMS immédiat
        sendEmergencyViaInternet(emergencyData);
        emergencyData.smsSent = true;
    } else if (quality === 'WIFI_STABLE') {
        // WiFi validé → API backend si configurée + tenter le SMS (la baseband
        // peut passer même sans data mobile)
        if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL) sendToAPI(emergencyData);
        sendEmergencyViaInternet(emergencyData);
        emergencyData.smsSent = true;
    } else {
        // NONE → aucun réseau validé : le SMS est délégué au réseau Marsel.
        // Le paquet se propage de téléphone en téléphone jusqu'à un relais
        // ayant du réseau qui enverra le SMS aux proches À LA PLACE de l'émetteur.
        emergencyData.smsSent = false;
        mLog('J', 'NET', 'Hors réseau validé — SMS délégué au relais MRN');
        showToast('🔁 Hors réseau : alerte transmise via le réseau Marsel');
        // Persister le flag pour éviter un double-envoi au flush (F2)
        try {
            var saved = JSON.parse(secureGet('marsel_emergency') || 'null');
            if (saved) { saved.smsSent = false; secureSet('marsel_emergency', JSON.stringify(saved)); }
        } catch (e) {}
        // ACK attendu du relais qui enverra réellement ; sans ACK sous 2 min →
        // notification « Impossible d'envoyer les SMS »
        armSmsAckWait(emergencyData.messageId || emergencyData.id, 'd\'alerte');
    }
}

// Cascade SMS pour les messages de fin/timeout (Section 1 ÉTAPE D + Section 4).
// kind = 'RESOLVED' | 'TIMEOUT'. Réseau validé → SMS direct via SmsManager ;
// sinon paquet MRN (F/T) relayé jusqu'à un téléphone connecté, dédup smsHandled
// gérée côté Kotlin. Le fichier audio ne transite JAMAIS par le MRN (flag "audio"
// seulement, pour que le SMS relayé mentionne l'enregistrement).
function sendCascadeSms(kind, savedEmergency, message, hasAudio) {
    var quality = getNetworkQuality();
    var canSms = deviceCanSendSms();
    var contacts = (savedEmergency.contacts || []).filter(function (c) { return c.mobile; });

    // TEST-FIX : le SMS direct exige réseau validé ET SIM+réseau cellulaire.
    // Sans ça (WiFi seul, zone blanche), délégation au réseau Marsel (F/T).
    if ((quality === 'MOBILE_STABLE' || quality === 'WIFI_STABLE') && canSms) {
        // Le premier envoi est SUIVI : si l'accusé système signale un échec réel
        // (le réseau cellulaire a disparu entre le check et l'envoi), le verdict
        // revient dans onSmsSendResult qui bascule sur le réseau Marsel.
        var trackId = 'cascade_' + kind + '_' + (savedEmergency.id || savedEmergency.emergencyId || 'x') + '_' + Date.now();
        _cascadeCtx[trackId] = { kind: kind, savedEmergency: savedEmergency, hasAudio: hasAudio };
        contacts.forEach(function (c, idx) {
            if (idx === 0 && window.AndroidBridge && typeof AndroidBridge.sendEmergencySMSTracked === 'function') {
                try { AndroidBridge.sendEmergencySMSTracked(c.mobile, message, trackId); } catch (e) {}
            } else if (window.AndroidBridge && typeof AndroidBridge.sendEmergencySMS === 'function') {
                try { AndroidBridge.sendEmergencySMS(c.mobile, message); } catch (e) {}
            }
            // 5b : à la fin d'alerte, si audio + 4G validée → MMS du dernier
            // enregistrement (best-effort, le SMS texte est déjà parti).
            if (kind === 'RESOLVED' && hasAudio && quality === 'MOBILE_STABLE'
                    && window.AndroidBridge && typeof AndroidBridge.sendRecordingMms === 'function') {
                try { AndroidBridge.sendRecordingMms(c.mobile); } catch (e) {}
            }
        });
        mLog('J', 'NET', kind + ' SMS direct (' + quality + ') → ' + contacts.length + ' proche(s)');
    } else {
        sendCascadeViaMrn(kind, savedEmergency, hasAudio);
    }
}

// Chemin MRN de la cascade fin d'alerte / relance 20 min : paquet F ou T
// relayé de téléphone en téléphone (max 5 sauts) jusqu'à un appareil avec
// SIM + réseau qui enverra les SMS. ACK attendu, sinon notification d'échec.
function sendCascadeViaMrn(kind, savedEmergency, hasAudio) {
    // Position réelle uniquement pour le paquet relayé (jamais de défaut)
    var scPos = getRealPosition();
    if (!scPos && isFinite(savedEmergency.lat) && isFinite(savedEmergency.lng)) {
        scPos = { lat: savedEmergency.lat, lng: savedEmergency.lng };
    }
    var type = (kind === 'TIMEOUT') ? 'MARSEL_TIMEOUT_SMS_REQUEST' : 'MARSEL_RESOLVED_SMS_REQUEST';
    var tag = (kind === 'TIMEOUT') ? 'timeout' : 'ressms';
    var eId = savedEmergency.id || savedEmergency.emergencyId || MARSEL.emergencyId;
    var reqPacket = {
        type: type,
        version: 1,
        messageId: eId + '_' + tag + '_' + Date.now(),
        emergencyId: eId,
        userId: savedEmergency.userId,
        pseudo: savedEmergency.pseudo || 'Utilisateur',
        lat: scPos ? scPos.lat : null,
        lng: scPos ? scPos.lng : null,
        timestamp: Date.now(),
        contacts: savedEmergency.contacts || [],
        audio: hasAudio ? '1' : '0',
        hopCount: 0,
        maxHops: (window.MARSEL_CONFIG && MARSEL_CONFIG.RELAY_HOP_LIMIT) || 5
    };
    if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
        try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(reqPacket)); } catch (e) {}
    }
    mLog('J', 'NET', kind + ' SMS délégué au MRN (' + type + ')');
    armSmsAckWait(reqPacket.messageId, kind === 'TIMEOUT' ? 'de relance' : 'de fin d\'alerte');
}

function sendEmergencyViaInternet(emergencyData) {
    var contacts = emergencyData.contacts || [];
    var contactsWithMobile = contacts.filter(function (c) { return c.mobile; });

    // Warn user if no contacts have phone numbers
    if (contactsWithMobile.length === 0) {
        showToast('⚠️ Aucun contact avec numéro – ajoutez des contacts pour les SMS');
    }

    // Check SMS permission
    if (window.AndroidBridge && typeof AndroidBridge.hasSMSPermission === 'function') {
        try {
            if (!AndroidBridge.hasSMSPermission()) {
                showToast('⚠️ Permission SMS non accordée – activez-la dans les paramètres');
            }
        } catch (e) {}
    }

    // Send SMS via Android SmsManager.
    // Le PREMIER envoi est SUIVI (trackId = messageId de l'urgence) : si
    // l'accusé système signale un échec réel (zone blanche apparue après le
    // check), onSmsSendResult bascule automatiquement sur le réseau Marsel.
    var trackArmed = false;
    contacts.forEach(function (contact) {
        if (!contact.mobile) return;

        // Validate coordinates before building SMS link
        var lat = parseFloat(emergencyData.lat);
        var lng = parseFloat(emergencyData.lng);
        var mapsLink;
        if (isNaN(lat) || isNaN(lng)) {
            mapsLink = 'Position non disponible';
        } else {
            mapsLink = 'https://maps.google.com/?q=' + lat.toFixed(6) + ',' + lng.toFixed(6);
        }
        var msg = '🚨 ALERTE MARSEL\n' +
            (emergencyData.pseudo || 'Un utilisateur') + ' a déclenché une alerte.\n' +
            'Position : ' + mapsLink + '\n' +
            'Heure : ' + new Date(emergencyData.timestamp).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });

        var trackId = emergencyData.messageId || emergencyData.id || null;
        if (!trackArmed && trackId && window.AndroidBridge && typeof AndroidBridge.sendEmergencySMSTracked === 'function') {
            trackArmed = true;
            try {
                AndroidBridge.sendEmergencySMSTracked(contact.mobile, msg, trackId);
                showToast('SMS envoyé à ' + escapeHtml(contact.nom || contact.mobile));
            } catch (ex) {
                console.error('SMS tracked via Android failed:', ex);
                sendViaSMSGateway(emergencyData);
            }
        } else if (window.AndroidBridge && typeof AndroidBridge.sendEmergencySMS === 'function') {
            try {
                AndroidBridge.sendEmergencySMS(contact.mobile, msg);
                showToast('SMS envoyé à ' + escapeHtml(contact.nom || contact.mobile));
            } catch (ex) {
                console.error('SMS via Android failed:', ex);
                // Fallback to SMS gateway
                sendViaSMSGateway(emergencyData);
            }
        } else if (window.MARSEL_CONFIG && MARSEL_CONFIG.SMS_GATEWAY_URL) {
            sendViaSMSGateway(emergencyData);
        }
    });

    // Cloud API if configured
    if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL) {
        sendToAPI(emergencyData);
    }
}

function sendEmergencyViaRelayNetwork(emergencyData) {
    mLog('J', 'RELAY', 'sendViaRelay type=' + emergencyData.type + ' id=' + (emergencyData.messageId || '?'));
    if (!MARSEL.p2pConnected && typeof AndroidBridge !== 'undefined') {
        showToast('🔁 Recherche téléphones Marsel voisins…');
    }

    // Store in relay queue DB
    dbPut('relay_queue', {
        id: emergencyData.messageId,
        data: emergencyData,
        addedAt: Date.now()
    }).catch(function () {});

    MARSEL.relayQueue.push(emergencyData);

    updateNetworkBadge('🔁 Marsel Relay Network');

    // Trigger Android WiFi Direct
    if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
        try {
            AndroidBridge.sendEmergencyViaRelay(JSON.stringify(emergencyData));
        } catch (ex) {
            console.error('Relay start failed:', ex);
        }
    }
}

function sendToAPI(emergencyData) {
    if (!window.MARSEL_CONFIG || !MARSEL_CONFIG.API_URL) return;
    var userData = JSON.parse(secureGet('marsel_user') || '{}');
    var token = userData.token || MARSEL_CONFIG.API_KEY;

    fetch(MARSEL_CONFIG.API_URL + '/emergency', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + token
        },
        body: JSON.stringify(emergencyData)
    }).then(function (r) {
        if (r.ok) { console.log('API emergency sent'); }
        else { console.warn('API returned', r.status); }
    }).catch(function (e) {
        console.warn('API send failed:', e);
    });
}

function sendViaSMSGateway(emergencyData) {
    if (!window.MARSEL_CONFIG || !MARSEL_CONFIG.SMS_GATEWAY_URL) return;

    var contacts = emergencyData.contacts || [];
    contacts.forEach(function (c) {
        if (!c.mobile) return;

        var text = '🚨 ALERTE MARSEL : ' +
            (emergencyData.pseudo || 'Utilisateur') +
            ' – https://maps.google.com/?q=' +
            parseFloat(emergencyData.lat).toFixed(6) + ',' +
            parseFloat(emergencyData.lng).toFixed(6);

        var body = new URLSearchParams();
        body.append('From', MARSEL_CONFIG.SMS_FROM_NUMBER || '');
        body.append('To', c.mobile);
        body.append('Body', text);

        fetch(MARSEL_CONFIG.SMS_GATEWAY_URL, {
            method: 'POST',
            headers: {
                'Authorization': 'Basic ' + btoa(
                    (MARSEL_CONFIG.SMS_GATEWAY_SID || '') + ':' +
                    (MARSEL_CONFIG.SMS_GATEWAY_TOKEN || '')
                ),
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: body.toString()
        }).then(function (r) {
            if (r.ok) { showToast('SMS gateway : envoyé à ' + escapeHtml(c.nom || c.mobile)); }
            else { console.warn('SMS gateway error', r.status); }
        }).catch(function (e) {
            console.warn('SMS gateway request failed:', e);
        });
    });
}

function generateEmergencyId() {
    return 'emg-' + Date.now().toString(36) + '-' + Math.random().toString(36).substr(2, 8);
}

/* ---------------------------------------------------------
   WIFI DIRECT / RELAY CALLBACKS (called by Android)
   --------------------------------------------------------- */

/* Called by Android when WiFi Direct peers are discovered */
window.onPeersDiscovered = function (peers) {
    if (typeof peers === 'string') {
        try { peers = JSON.parse(peers); } catch (e) { peers = []; }
    }
    MARSEL.p2pPeers = peers || [];
    var count = MARSEL.p2pPeers.length;
    updateNetworkBadge('🔁 Marsel Relay Network (' + count + ')');
    if (count > 0) {
        showToast('🔁 Marsel Relay Network : ' + count + ' appareil(s) détecté(s)');
    } else {
        showToast('🔍 Recherche d\'appareils Marsel…');
    }
};

/* PERM-FIX : appelé par Android quand le MRN est bloqué par une permission
   manquante (« Appareils à proximité »). On informe l'utilisateur et on
   redemande la permission UNE fois automatiquement (l'onboarding ne repasse
   plus après le premier lancement). reason vide = débloqué. */
var _p2pBlockedPromptDone = false;
window.onP2PBlocked = function (reason) {
    if (!reason) {
        mLog('J', 'NET', 'MRN débloqué — permission OK');
        updateNetworkBadge();
        return;
    }
    mLog('J', 'NET', 'MRN BLOQUÉ : ' + reason);
    updateNetworkBadge('⚠️ Permission proximité requise');
    if (!_p2pBlockedPromptDone) {
        _p2pBlockedPromptDone = true;
        showToast('⚠️ Le réseau Marsel a besoin de la permission « Appareils à proximité »');
        if (window.AndroidBridge && typeof AndroidBridge.requestPermissionGroup === 'function') {
            try { AndroidBridge.requestPermissionGroup('nearby'); } catch (e) {}
        }
    }
};

/* Appelé par Android quand l'état de l'enregistrement audio change (Section 5). */
window.onRecordingStateChanged = function (recording, reason) {
    mLog('J', 'NET', 'audio recording=' + recording + ' reason=' + reason);
    if (!recording && reason === 'permission_refusee') {
        showToast('🎙️ Micro non autorisé — enregistrement de protection indisponible');
    }
};

/* Called by Android when the WiFi P2P radio state changes. */
window.onP2PStateChanged = function (enabled) {
    mLog('J', 'NET', 'P2P state changed: ' + (enabled ? 'ENABLED' : 'DISABLED'));
    if (!enabled) {
        updateNetworkBadge('⚠️ WiFi désactivé – relay indisponible');
        showToast('⚠️ Activez le WiFi (même sans internet) pour alerter les Marsel à proximité');
    } else {
        updateNetworkBadge();
    }
};

/* Called by Android when P2P connection established */
window.onP2PConnected = function (info) {
    MARSEL.p2pConnected = true;
    mLog('J', 'RELAY', 'P2P connected isOwner=' + (info && info.isOwner) + ' addr=' + (info && info.address));
    updateNetworkBadge('🔁 Marsel Relay Network ✓');

    // BUG-2 (terrain) : le flush de MARSEL.relayQueue re-poussait ici CHAQUE
    // vieille urgence (y compris résolues) à CHAQUE connexion P2P → services
    // marsel-alert-* fantômes, marqueurs qui s'accumulent, re-notifications et
    // re-SMS chez les voisins. SUPPRIMÉ : le service DNS-SD de l'alerte active
    // émet déjà en continu, et la file socket native (pendingRelayMessages)
    // gère la livraison aux pairs qui se connectent.
};

/* Called by Android when a relay message arrives from another device */
window.onRelayMessageReceived = function (jsonStr) {
    var data;
    try { data = JSON.parse(jsonStr); } catch (e) {
        mLog('J', 'RELAY', 'JSON parse error: ' + e);
        return;
    }
    if (!data || !data.type) { mLog('J', 'RELAY', 'no type field, drop'); return; }

    mLog('J', 'RELAY', 'JS RX type=' + data.type + ' pseudo=' + (data.pseudo||'?') + ' id=' + (data.messageId||'?'));

    // ── Mise à jour de position GPS (tracking continu) ──
    if (data.type === 'MARSEL_POSITION_UPDATE') {
        handlePositionUpdate(data);
        return;
    }

    // ── Fin d'alerte ──
    if (data.type === 'MARSEL_EMERGENCY_RESOLVED') {
        handleEmergencyResolved(data);
        return;
    }

    // ── Nouvelle alerte ──
    if (data.type !== 'MARSEL_EMERGENCY') return;

    var msgId = data.messageId || data.id;
    if (!msgId) return;
    if (MARSEL.processedMessageIds[msgId]) return;
    MARSEL.processedMessageIds[msgId] = true;

    // BUG-2/6 : urgence déjà résolue (fin d'alerte reçue ou émise) — aucun
    // marqueur ni notification, même si un vieux paquet E traîne encore.
    var eKey = data.emergencyId || data.id || msgId;
    if (MARSEL.resolvedEmergencies[eKey]) {
        mLog('J', 'RELAY', 'E ignorée (urgence résolue) : ' + eKey);
        return;
    }

    var record = {
        id: msgId,
        userId: data.userId,
        pseudo: data.pseudo,
        lat: data.lat,
        lng: data.lng,
        timestamp: data.timestamp || Date.now(),
        contacts: data.contacts || [],
        status: 'RECEIVED_RELAY',
        hopCount: (data.hopCount || 0) + 1
    };
    dbPut('emergency_events', record).catch(function () {});

    // AUDIT-FIX 4 : la notification système est déjà émise côté Kotlin
    // (processRelayMessage → showNotificationDirect) AVANT l'appel à ce handler.
    // Ne pas la ré-émettre ici (même canal/ID) pour éviter double vibration.
    showToast('🚨 Alerte reçue de ' + escapeHtml(data.pseudo || 'un utilisateur'));

    // Afficher sur la carte — naviguer vers home si nécessaire
    var validCoords = data.lat && data.lng && !isNaN(parseFloat(data.lat)) && !isNaN(parseFloat(data.lng));
    if (!MARSEL.mapInitialized) {
        // Map pas encore initialisée : aller sur home pour l'initialiser
        showScreen('screen-home');
        setTimeout(function () {
            if (MARSEL.leafletMap && validCoords) {
                addIncidentMarker(record);
                MARSEL.leafletMap.setView([parseFloat(data.lat), parseFloat(data.lng)], 15);
            }
        }, 700);
    } else if (MARSEL.leafletMap && validCoords) {
        addIncidentMarker(record);
        MARSEL.leafletMap.setView([parseFloat(data.lat), parseFloat(data.lng)], 15);
    }

    // C4 : le hop-forwarding est la propriété EXCLUSIVE du Kotlin
    // (processRelayMessage → registerRelayedService). Le JS ne fait QUE
    // l'affichage. Re-propager ici créait un double envoi (Kotlin + JS)
    // avec des hopCount divergents et du churn de services DNS-SD.
};

/* ── Tracking GPS continu pendant l'urgence ── */

function startEmergencyTracking(emergencyData) {
    stopEmergencyTracking(); // reset si déjà actif
    // Première position après 2s : envoyée immédiatement, elle partait en
    // COURSE avec l'enregistrement du service d'alerte côté natif (threads
    // séparés) et était jetée (« POSITION: no active emergency, skip »,
    // vu en test terrain). Puis toutes les 10s.
    setTimeout(function () {
        if (MARSEL.emergencyActive) sendPositionUpdate(MARSEL.currentLat, MARSEL.currentLng);
    }, 2000);
    MARSEL.trackingInterval = setInterval(function () {
        if (!MARSEL.emergencyActive) { stopEmergencyTracking(); return; }
        sendPositionUpdate(MARSEL.currentLat, MARSEL.currentLng);
    }, 10000);
}

function stopEmergencyTracking() {
    if (MARSEL.trackingInterval) {
        clearInterval(MARSEL.trackingInterval);
        MARSEL.trackingInterval = null;
    }
}

/* ── Timer 20 minutes (Section 4) ── */

// Arme le timer sur le temps RESTANT calculé depuis le timestamp de
// déclenchement (persisté dans marsel_emergency). Au restart de l'app pendant
// une alerte active, le temps déjà écoulé est déduit ; si les 20 min sont
// dépassées, l'échéance se déclenche immédiatement.
function armEmergencyTimeout(emergencyData) {
    cancelEmergencyTimeout();
    var startTs = (emergencyData && emergencyData.timestamp) || Date.now();
    var remaining = startTs + EMERGENCY_TIMEOUT_MS - Date.now();
    if (remaining < 0) remaining = 0;
    mLog('J', 'NET', 'Timer 20min armé, reste ' + Math.round(remaining / 1000) + 's');
    MARSEL.timeoutTimer = setTimeout(triggerEmergencyTimeout, remaining);
}

function cancelEmergencyTimeout() {
    if (MARSEL.timeoutTimer) {
        clearTimeout(MARSEL.timeoutTimer);
        MARSEL.timeoutTimer = null;
    }
}

// Échéance atteinte : alerte toujours active après 20 min sans fin d'alerte.
// SMS "toujours en cours" en cascade (mobile → wifi → paquet MRN T relayé).
function triggerEmergencyTimeout() {
    MARSEL.timeoutTimer = null;
    if (!MARSEL.emergencyActive) return;

    var savedEmergency = null;
    try { savedEmergency = JSON.parse(secureGet('marsel_emergency') || 'null'); } catch (e) {}
    if (!savedEmergency) return;

    var pseudo = savedEmergency.pseudo || 'Utilisateur';
    // Position réelle uniquement (fix courant ou dernière position réelle de l'alerte)
    var toPos = getRealPosition();
    if (!toPos && isFinite(savedEmergency.lat) && isFinite(savedEmergency.lng)) {
        toPos = { lat: savedEmergency.lat, lng: savedEmergency.lng };
    }
    var posLine = toPos ? '\nPosition : https://maps.google.com/?q=' + toPos.lat + ',' + toPos.lng : '';
    var settings = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
    var hasAudio = !!settings.audio;
    var audioMention = hasAudio ? '\nUn enregistrement audio est en cours.' : '';
    var msg = '⚠️ ALERTE MARSEL TOUJOURS EN COURS depuis 20 min. ' +
        pseudo + ' n\'a pas désactivé son alerte.' + posLine + audioMention;

    mLog('J', 'NET', 'Timer 20min ÉCHU — envoi SMS "toujours en cours"');
    sendCascadeSms('TIMEOUT', savedEmergency, msg, hasAudio);
    showToast('⚠️ Alerte toujours active depuis 20 min — proches renotifiés');
}

function sendPositionUpdate(lat, lng) {
    var fLat = parseFloat(lat);
    var fLng = parseFloat(lng);
    if (isNaN(fLat) || isNaN(fLng)) return; // skip if GPS not yet fixed

    var savedEmergency = null;
    try { savedEmergency = JSON.parse(secureGet('marsel_emergency') || 'null'); } catch (e) {}
    if (!savedEmergency || !MARSEL.emergencyId) return;

    var updatePacket = {
        type: 'MARSEL_POSITION_UPDATE',
        version: 1,
        messageId: MARSEL.emergencyId + '_pos_' + Date.now(),
        emergencyId: MARSEL.emergencyId,
        userId: savedEmergency.userId,
        pseudo: savedEmergency.pseudo,
        lat: fLat,
        lng: fLng,
        timestamp: Date.now(),
        contacts: savedEmergency.contacts || [],
        hopCount: 0,
        maxHops: (window.MARSEL_CONFIG && MARSEL_CONFIG.RELAY_HOP_LIMIT) || 5
    };

    // Mettre à jour DB locale
    dbPut('emergency_events', Object.assign({}, savedEmergency, {
        lat: fLat, lng: fLng, lastPositionUpdate: Date.now()
    })).catch(function () {});

    // Diffuser via Marsel Relay Network (WiFi Direct)
    if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
        try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(updatePacket)); } catch (e) {}
    }

    // ÉTAPE C : tracking temps réel proches/sécurité via backend.
    // Si l'émetteur a (ou retrouve) du réseau VALIDÉ pendant l'alerte, pousser
    // la position au backend à chaque cycle (10s). Vérifié à chaque appel.
    var quality = getNetworkQuality();
    if ((quality === 'WIFI_STABLE' || quality === 'MOBILE_STABLE')
            && window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL && MARSEL_CONFIG.API_KEY) {
        fetch(MARSEL_CONFIG.API_URL + '/emergency/' + MARSEL.emergencyId + '/position', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + MARSEL_CONFIG.API_KEY },
            body: JSON.stringify({ lat: lat, lng: lng, timestamp: Date.now() })
        }).catch(function () {});
    }
}

/* Mise à jour de position d'un autre utilisateur reçue via relay */
function handlePositionUpdate(data) {
    if (!data.emergencyId) return;
    // BUG-6 : une position périmée arrivant APRÈS la fin d'alerte recréait le
    // marqueur (tag fantôme). Urgence résolue → ignorer définitivement.
    if (MARSEL.resolvedEmergencies[data.emergencyId]) return;
    var fLat = parseFloat(data.lat);
    var fLng = parseFloat(data.lng);
    if (isNaN(fLat) || isNaN(fLng)) { mLog('J','RELAY','POS_UPDATE bad coords'); return; }
    mLog('J','RELAY','POS_UPDATE eId=' + data.emergencyId.slice(-8) + ' lat=' + fLat.toFixed(4) + ' lng=' + fLng.toFixed(4));

    var eId = data.emergencyId;
    var _d = new Date(data.timestamp || Date.now());
    var timeStr = _d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    var dateStr = _d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });

    // Mettre à jour le marqueur existant
    if (MARSEL.incidentMarkers[eId] && MARSEL.leafletMap) {
        MARSEL.incidentMarkers[eId].setLatLng([fLat, fLng]);
        MARSEL.incidentMarkers[eId].setPopupContent(
            '<b>🚨 ' + escapeHtml(data.pseudo || 'Utilisateur') + '</b><br>' +
            'Tracking actif – ' + dateStr + ' ' + timeStr
        );
    } else if (MARSEL.leafletMap) {
        // Première réception pour cet emergency : créer le marqueur
        addIncidentMarker({ id: eId, pseudo: data.pseudo, lat: fLat, lng: fLng, timestamp: data.timestamp });
    }

    // Sauvegarder la position mise à jour en DB
    dbPut('emergency_events', {
        id: eId,
        pseudo: data.pseudo,
        userId: data.userId,
        lat: fLat,
        lng: fLng,
        timestamp: data.timestamp,
        status: 'ACTIVE_TRACKING'
    }).catch(function () {});

    // C4 : pas de re-propagation JS. Les positions relayées ne font qu'un seul
    // hop DNS-SD côté Kotlin ; re-diffuser le tracking de proche en proche
    // saturerait le stack WiFi P2P.
}

/* Fin d'alerte reçue via relay d'un autre utilisateur */
function handleEmergencyResolved(data) {
    var eId = data.emergencyId;
    if (!eId) return;

    // BUG-2/6 : mémoriser la résolution — tout paquet E/P retardataire de
    // cette urgence sera ignoré (plus de marqueur fantôme qui revient).
    MARSEL.resolvedEmergencies[eId] = true;

    // Supprimer le marqueur de la carte
    if (MARSEL.incidentMarkers[eId] && MARSEL.leafletMap) {
        try { MARSEL.leafletMap.removeLayer(MARSEL.incidentMarkers[eId]); } catch (e) {}
        delete MARSEL.incidentMarkers[eId];
    }

    // Mettre à jour la DB
    dbPut('emergency_events', { id: eId, status: 'RESOLVED', resolvedAt: data.timestamp || Date.now() }).catch(function () {});

    // Notification locale
    if (window.AndroidBridge && typeof AndroidBridge.showNotification === 'function') {
        try { AndroidBridge.showNotification('✅ Alerte Marsel résolue', (data.pseudo || 'Utilisateur') + ' est en sécurité'); } catch (e) {}
    }
    showToast('✅ ' + escapeHtml(data.pseudo || 'Utilisateur') + ' est en sécurité');

    // C4 : la re-propagation de la résolution est gérée par le Kotlin
    // (registerRelayedService sur réception d'un R). Le JS ne fait qu'afficher.
}

/* Legacy WiFi message handler (port 8888 direct send) */
window.recevoirWifiMessage = function (messageJson) {
    try {
        var data = JSON.parse(messageJson);
        if (data && data.type === 'MARSEL_EMERGENCY') {
            window.onRelayMessageReceived(messageJson);
        } else {
            var text = (data && data.message) ? data.message : messageJson;
            addChatMessage('received', text);
        }
    } catch (e) {
        addChatMessage('received', messageJson);
    }
};

/* ---------------------------------------------------------
   CHAT / MESSAGING
   --------------------------------------------------------- */
var chatMessages = [];

function addChatMessage(type, text, timestamp) {
    chatMessages.push({ type: type, text: text, timestamp: timestamp || Date.now() });
    renderChatMessages();
}

function renderChatMessages() {
    var area = document.getElementById('chat-area');
    if (!area) return;

    area.innerHTML = '';

    chatMessages.forEach(function (msg) {
        var bubble = document.createElement('div');
        bubble.className = 'chat-bubble ' + (msg.type || 'received');
        bubble.textContent = msg.text;

        var time = document.createElement('div');
        time.className = 'chat-time';
        time.textContent = new Date(msg.timestamp || Date.now()).toLocaleTimeString('fr-FR', {
            hour: '2-digit', minute: '2-digit'
        });
        bubble.appendChild(time);

        area.appendChild(bubble);
    });

    area.scrollTop = area.scrollHeight;
}

function sendChatMessage() {
    var input = document.getElementById('chat-input');
    if (!input || !input.value.trim()) return;

    var text = input.value.trim();
    input.value = '';

    addChatMessage('sent', text);

    // Send via Android WiFi Direct
    if (window.AndroidWifiDirect && typeof AndroidWifiDirect.sendWifiMessage === 'function') {
        try {
            AndroidWifiDirect.sendWifiMessage(JSON.stringify({
                type: 'CHAT',
                message: text,
                timestamp: Date.now()
            }));
        } catch (e) {}
    }
    // Also try via AndroidBridge
    if (window.AndroidBridge && typeof AndroidBridge.sendWifiMessage === 'function') {
        try {
            AndroidBridge.sendWifiMessage(JSON.stringify({
                type: 'CHAT',
                message: text,
                timestamp: Date.now()
            }));
        } catch (e) {}
    }

    // Cloud API if configured and online
    if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL &&
        (MARSEL.networkType === 'WIFI' || MARSEL.networkType === 'MOBILE')) {
        var userData = JSON.parse(secureGet('marsel_user') || '{}');
        fetch(MARSEL_CONFIG.API_URL + '/messages', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + (userData.token || MARSEL_CONFIG.API_KEY || '')
            },
            body: JSON.stringify({
                text: text,
                userId: userData.userId || userData.email,
                timestamp: Date.now()
            })
        }).catch(function () {});
    }
}

/* ---------------------------------------------------------
   AUTH — authentification locale réelle (TODO.md §1)
   Aucun compte serveur, aucune connexion Google/Facebook/Apple : le
   mot de passe/code est haché et vérifié EXCLUSIVEMENT sur l'appareil
   (AndroidBridge.setLocalSecret/verifyLocalSecret, PBKDF2 côté natif,
   jamais transmis nulle part). Les onglets signin/signup ne sont plus un
   choix libre de l'utilisateur : l'écran affiche automatiquement la
   création d'accès (aucun secret local existant) ou le déverrouillage
   (secret déjà défini), déterminé par initAuthScreen().
   --------------------------------------------------------- */
function switchAuthTab(tab) {
    var formSignin = document.getElementById('form-signin');
    var formSignup = document.getElementById('form-signup');
    if (tab === 'signin') {
        if (formSignin) formSignin.classList.add('active');
        if (formSignup) formSignup.classList.remove('active');
    } else {
        if (formSignup) formSignup.classList.add('active');
        if (formSignin) formSignin.classList.remove('active');
    }
}

/* Appelé à chaque affichage de screen-auth (verrouillage systématique —
   TODO.md §1.3) : bascule entre « créer un accès local » (premier
   lancement / aucun secret défini) et « déverrouiller » (secret déjà
   défini, à re-saisir à CHAQUE ouverture de l'app, pas juste une fois). */
function initAuthScreen() {
    var hasAuth = false;
    try { hasAuth = !!(window.AndroidBridge && AndroidBridge.hasLocalAuth && AndroidBridge.hasLocalAuth()); } catch (e) {}

    switchAuthTab(hasAuth ? 'signin' : 'signup');

    var bioBtn = document.getElementById('biometric-unlock-btn');
    if (bioBtn) {
        var bioAvail = false;
        try { bioAvail = hasAuth && !!(window.AndroidBridge && AndroidBridge.isBiometricAvailable && AndroidBridge.isBiometricAvailable()); } catch (e) {}
        bioBtn.style.display = bioAvail ? 'flex' : 'none';
    }

    var pinField = document.getElementById('signin-password');
    if (pinField) pinField.value = '';
}

function togglePassword(inputId, eyeEl) {
    var input = document.getElementById(inputId);
    if (!input) return;
    if (input.type === 'password') {
        input.type = 'text';
        if (eyeEl) eyeEl.style.opacity = '0.45';
    } else {
        input.type = 'password';
        if (eyeEl) eyeEl.style.opacity = '1';
    }
}

function generateUserId() {
    return 'MRS-' + Date.now().toString(36).toUpperCase() + '-' + Math.random().toString(36).substr(2, 5).toUpperCase();
}

/* Entrée commune une fois le verrou (mot de passe OU biométrie) validé. */
function unlockIntoApp() {
    var userData = null;
    try { userData = JSON.parse(secureGet('marsel_user') || 'null'); } catch (e) {}
    MARSEL.currentUser = userData;
    MARSEL.screenHistory = [];
    showScreen('screen-home');
    startRealGPS();
}

/* Déverrouillage (secret local déjà créé) — PLUS de compte email/mot de
   passe distant : le mot de passe ne sert qu'à débloquer les données déjà
   présentes sur l'appareil. */
function doLogin() {
    var password = ((document.getElementById('signin-password') || {}).value || '').trim();
    if (!password) {
        showToast('Veuillez saisir votre mot de passe');
        return;
    }
    var ok = false;
    try { ok = !!(window.AndroidBridge && AndroidBridge.verifyLocalSecret && AndroidBridge.verifyLocalSecret(password)); } catch (e) {}
    if (!ok) {
        showToast('Mot de passe incorrect');
        return;
    }
    unlockIntoApp();
}

/* Biométrie : complément du mot de passe, jamais un remplacement — le
   champ mot de passe reste toujours disponible en repli (TODO.md §1.2). */
function requestBiometricUnlock() {
    try {
        if (window.AndroidBridge && AndroidBridge.showBiometricPrompt) AndroidBridge.showBiometricPrompt();
    } catch (e) {}
}
window.onBiometricResult = function (success) {
    if (success) {
        unlockIntoApp();
    } else {
        showToast('Authentification biométrique indisponible — utilisez votre mot de passe');
    }
};

/* Création de l'accès local (premier lancement, ou après un factory
   reset) : choix d'un pseudo + d'un mot de passe/code haché sur
   l'appareil. Aucune donnée n'est envoyée à un serveur. */
function doRegister() {
    var pseudo = ((document.getElementById('signup-pseudo') || {}).value || '').trim();
    var password = ((document.getElementById('signup-password') || {}).value || '').trim();
    var password2 = ((document.getElementById('signup-password2') || {}).value || '').trim();
    var agreed = (document.getElementById('agree-tnc') || {}).checked;

    if (!pseudo || !password) {
        showToast('Veuillez remplir tous les champs');
        return;
    }
    if (password.length < 4) {
        showToast('Le mot de passe doit contenir au moins 4 caractères');
        return;
    }
    if (password !== password2) {
        showToast('Les mots de passe ne correspondent pas');
        return;
    }
    if (!agreed) {
        showToast('Veuillez accepter les conditions d\'utilisation');
        return;
    }

    var created = false;
    try { created = !!(window.AndroidBridge && AndroidBridge.setLocalSecret && AndroidBridge.setLocalSecret(password)); } catch (e) {}
    if (!created) {
        showToast('Impossible de créer l\'accès local sur cet appareil');
        return;
    }

    // Préserve un userId existant (ex. après un changement de mot de
    // passe) pour que l'identité de relais MRN reste stable.
    var existingUser = null;
    try { existingUser = JSON.parse(secureGet('marsel_user') || 'null'); } catch (e) {}
    var userId = (existingUser && existingUser.userId) ? existingUser.userId : generateUserId();

    var userData = { pseudo: pseudo, loggedIn: true, userId: userId };
    secureSet('marsel_user', JSON.stringify(userData));

    unlockIntoApp();
}

function doLogout() {
    MARSEL.emergencyActive = false;
    MARSEL.emergencyId = null;
    MARSEL.currentUser = null;
    secureRemove('marsel_emergency');
    stopGPS();
    MARSEL.screenHistory = [];
    initAuthScreen();
    showScreen('screen-auth');
}

/* ---------------------------------------------------------
   PROFILE
   --------------------------------------------------------- */
function updateProfileMenu() {
    var userData = JSON.parse(secureGet('marsel_user') || '{}');
    var profileData = JSON.parse(secureGet('marsel_profile') || '{}');
    var pseudo = profileData.pseudo || userData.pseudo || 'Utilisateur';
    var email = profileData.email || userData.email || '';

    var pmPseudo = document.getElementById('pm-pseudo');
    var pmEmail = document.getElementById('pm-email');
    if (pmPseudo) pmPseudo.textContent = pseudo;
    if (pmEmail) pmEmail.textContent = email;
}

function loadProfileForm() {
    var userData = JSON.parse(secureGet('marsel_user') || '{}');
    var profileData = JSON.parse(secureGet('marsel_profile') || '{}');

    var idEl = document.getElementById('profile-id');
    if (idEl) idEl.value = userData.userId || '—';

    var el;
    el = document.getElementById('profile-pseudo');
    if (el) el.value = profileData.pseudo || userData.pseudo || '';

    el = document.getElementById('profile-mobile');
    if (el) el.value = profileData.mobile || '';

    el = document.getElementById('profile-email');
    if (el) el.value = profileData.email || userData.email || '';
}

function saveProfile() {
    var pseudo = ((document.getElementById('profile-pseudo') || {}).value || '').trim();
    var mobile = ((document.getElementById('profile-mobile') || {}).value || '').trim();
    var email = ((document.getElementById('profile-email') || {}).value || '').trim();

    var profileData = { pseudo: pseudo, mobile: mobile, email: email };
    secureSet('marsel_profile', JSON.stringify(profileData));

    // Sync user object
    var userData = JSON.parse(secureGet('marsel_user') || '{}');
    if (pseudo) userData.pseudo = pseudo;
    if (email) userData.email = email;
    secureSet('marsel_user', JSON.stringify(userData));
    MARSEL.currentUser = userData;

    // Push to API if configured
    if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL && userData.token) {
        fetch(MARSEL_CONFIG.API_URL + '/profile', {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + userData.token
            },
            body: JSON.stringify({ pseudo: pseudo, mobile: mobile, email: email })
        }).catch(function () {});
    }

    showToast('Profil enregistré !');
    goBack();
}

/* ---------------------------------------------------------
   SETTINGS
   --------------------------------------------------------- */
function loadSettings() {
    var s = JSON.parse(localStorage.getItem('marsel_settings') || '{}');

    var el;
    el = document.getElementById('toggle-location');
    if (el) el.checked = !!s.location;

    el = document.getElementById('toggle-flash');
    if (el) el.checked = !!s.flash;

    el = document.getElementById('toggle-audio');
    if (el) el.checked = !!s.audio;
}

function saveSetting(key, value) {
    var s = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
    s[key] = value;
    localStorage.setItem('marsel_settings', JSON.stringify(s));

    // Side effects
    if (key === 'location') {
        if (value) {
            startRealGPS();
            loadFriendsOnMap();
        } else {
            stopGPS();
        }
    }
}

/* ---------------------------------------------------------
   CONTACTS
   --------------------------------------------------------- */
function renderContacts() {
    var contacts = JSON.parse(secureGet('marsel_contacts') || '[]');
    while (contacts.length < 5) contacts.push({ nom: '', mobile: '', email: '', pseudo: '' });

    var list = document.getElementById('contacts-list');
    if (!list) return;
    list.innerHTML = '';

    for (var i = 0; i < 5; i++) {
        var c = contacts[i];
        var slot = document.createElement('div');
        slot.className = 'contact-slot';
        slot.setAttribute('data-index', i);

        var displayName = c.nom ? c.nom : 'Proche ' + (i + 1);
        var subText = c.mobile || c.email || 'Non renseigné';

        slot.innerHTML = [
            '<div class="contact-slot-icon">',
                '<svg width="22" height="22" viewBox="0 0 24 24" fill="white">',
                    '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>',
                    '<circle cx="12" cy="7" r="4"/>',
                    c.nom ? '' : '<path d="M19 8v6M22 11h-6" stroke="white" stroke-width="2" stroke-linecap="round"/>',
                '</svg>',
            '</div>',
            '<div class="contact-slot-info">',
                '<div class="contact-slot-name">' + escapeHtml(displayName) + '</div>',
                '<div style="font-size:12px;color:#9E9E9E;margin-top:2px;">' + escapeHtml(subText) + '</div>',
            '</div>',
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#E84315" stroke-width="2.5" stroke-linecap="round"><polyline points="9 18 15 12 9 6"/></svg>'
        ].join('');

        slot.onclick = (function (idx) {
            return function () { openContactDetail(idx); };
        })(i);

        list.appendChild(slot);
    }
}

function openContactDetail(idx) {
    var contacts = JSON.parse(secureGet('marsel_contacts') || '[]');
    while (contacts.length < 5) contacts.push({ nom: '', mobile: '', email: '', pseudo: '' });
    var c = contacts[idx] || {};

    var idxInput = document.getElementById('contact-slot-index');
    if (idxInput) idxInput.value = idx;

    var el;
    el = document.getElementById('contact-nom');
    if (el) el.value = c.nom || '';

    el = document.getElementById('contact-mobile');
    if (el) el.value = c.mobile || '';

    el = document.getElementById('contact-email');
    if (el) el.value = c.email || '';

    el = document.getElementById('contact-pseudo');
    if (el) {
        var userData = JSON.parse(secureGet('marsel_user') || '{}');
        el.value = c.pseudo || userData.pseudo || 'Marsel';
    }

    showScreen('screen-contact-detail');
}

function saveContact() {
    var idx = parseInt(((document.getElementById('contact-slot-index') || {}).value || '0'), 10);
    var nom = ((document.getElementById('contact-nom') || {}).value || '').trim();
    var mobile = ((document.getElementById('contact-mobile') || {}).value || '').trim();
    var email = ((document.getElementById('contact-email') || {}).value || '').trim();
    var pseudo = ((document.getElementById('contact-pseudo') || {}).value || '').trim();

    var contacts = JSON.parse(secureGet('marsel_contacts') || '[]');
    while (contacts.length < 5) contacts.push({ nom: '', mobile: '', email: '', pseudo: '' });

    contacts[idx] = { nom: nom, mobile: mobile, email: email, pseudo: pseudo };
    secureSet('marsel_contacts', JSON.stringify(contacts));

    showToast('Contact enregistré !');
    goBack();
}

/* ---------------------------------------------------------
   SUBSCRIPTION
   --------------------------------------------------------- */
var selectedPlan = 0;

function selectPlan(planIdx) {
    selectedPlan = planIdx;
    localStorage.setItem('marsel_plan', String(planIdx));
    updatePlanSelection();
}

function updatePlanSelection() {
    selectedPlan = parseInt(localStorage.getItem('marsel_plan') || '0', 10);

    var cards = document.querySelectorAll('.plan-card');
    var dots = document.querySelectorAll('.plan-dot');

    for (var i = 0; i < cards.length; i++) {
        // Don't touch the always-orange card (index 1)
        if (i !== 1) {
            if (i === selectedPlan) {
                cards[i].classList.add('selected');
            } else {
                cards[i].classList.remove('selected');
            }
        }
    }

    for (var j = 0; j < dots.length; j++) {
        if (j === selectedPlan) {
            dots[j].classList.add('active');
        } else {
            dots[j].classList.remove('active');
        }
    }

    // Attach scroll listener once
    var wrapper = document.querySelector('.plan-scroll-wrapper');
    if (wrapper && !wrapper._scrollBound) {
        wrapper.addEventListener('scroll', onPlanScroll, { passive: true });
        wrapper._scrollBound = true;
    }
}

function onPlanScroll(e) {
    var wrapper = e.target;
    var scrollLeft = wrapper.scrollLeft;
    var cardWidth = 214; // 200px card + 14px gap
    var idx = Math.round(scrollLeft / cardWidth);
    idx = Math.max(0, Math.min(2, idx));

    var dots = document.querySelectorAll('.plan-dot');
    for (var i = 0; i < dots.length; i++) {
        if (i === idx) dots[i].classList.add('active');
        else dots[i].classList.remove('active');
    }
}

/* ---------------------------------------------------------
   NAVIGATION
   --------------------------------------------------------- */
function showScreen(id, isBack) {
    var currentActive = document.querySelector('.screen.active');
    var next = document.getElementById(id);
    if (!next) return;

    if (currentActive && currentActive !== next) {
        currentActive.classList.remove('active');
    }

    next.classList.remove('slide-in', 'slide-back-in');
    next.classList.add('active');

    void next.offsetWidth; // force reflow for animation
    if (isBack) {
        next.classList.add('slide-back-in');
    } else {
        next.classList.add('slide-in');
    }

    setTimeout(function () {
        next.classList.remove('slide-in', 'slide-back-in');
    }, 300);

    if (!isBack) {
        MARSEL.screenHistory.push(id);
    }

    // Screen-specific initialisation
    if (id === 'screen-home') {
        updateNetworkBadge();
        setMode(MARSEL.mode); // re-apply mode UI
        setTimeout(function () { initMap(); }, 200);
        updateEmergencyUI();
        updateProfileMenu();
        // Onboarding permissions au premier accès à l'accueil (Section 6)
        setTimeout(maybeStartOnboarding, 600);
    }
    if (id === 'screen-profile-menu') {
        updateProfileMenu();
    }
    if (id === 'screen-my-profile') {
        loadProfileForm();
    }
    if (id === 'screen-settings') {
        loadSettings();
    }
    if (id === 'screen-contacts') {
        renderContacts();
    }
    if (id === 'screen-messaging') {
        renderChatMessages();
    }
    if (id === 'screen-subscription') {
        updatePlanSelection();
    }
}

function goBack() {
    if (MARSEL.screenHistory.length <= 1) {
        if (!MARSEL.screenHistory.length || MARSEL.screenHistory[0] !== 'screen-home') {
            showScreen('screen-home', true);
            MARSEL.screenHistory = ['screen-home'];
        }
        return;
    }

    MARSEL.screenHistory.pop();
    var prev = MARSEL.screenHistory[MARSEL.screenHistory.length - 1];

    var currentActive = document.querySelector('.screen.active');
    if (currentActive) currentActive.classList.remove('active');

    var prevScreen = document.getElementById(prev);
    if (!prevScreen) return;

    prevScreen.classList.remove('slide-in', 'slide-back-in');
    prevScreen.classList.add('active');
    void prevScreen.offsetWidth;
    prevScreen.classList.add('slide-back-in');
    setTimeout(function () { prevScreen.classList.remove('slide-back-in'); }, 300);

    // Screen-specific refresh on back
    if (prev === 'screen-home') {
        updateNetworkBadge();
        updateEmergencyUI();
        updateProfileMenu();
        setMode(MARSEL.mode);
    }
    if (prev === 'screen-profile-menu') {
        updateProfileMenu();
    }
    if (prev === 'screen-contacts') {
        renderContacts();
    }
    if (prev === 'screen-messaging') {
        renderChatMessages();
    }
}

/* ---------------------------------------------------------
   TOAST NOTIFICATION
   --------------------------------------------------------- */
function showToast(msg) {
    var existing = document.getElementById('marsel-toast');
    if (existing) existing.remove();

    var toast = document.createElement('div');
    toast.id = 'marsel-toast';
    toast.textContent = msg;
    toast.style.cssText = [
        'position:fixed;bottom:80px;left:50%;transform:translateX(-50%);',
        'background:#1A35C8;color:white;padding:12px 24px;border-radius:24px;',
        'font-family:Nunito,sans-serif;font-size:14px;font-weight:700;',
        'z-index:9999;box-shadow:0 4px 16px rgba(0,0,0,0.2);',
        'animation:toastIn 0.3s ease;max-width:80vw;text-align:center;',
        'pointer-events:none;'
    ].join('');

    if (!document.getElementById('toast-style')) {
        var style = document.createElement('style');
        style.id = 'toast-style';
        style.textContent = '@keyframes toastIn{from{opacity:0;transform:translateX(-50%) translateY(12px)}to{opacity:1;transform:translateX(-50%) translateY(0)}}';
        document.head.appendChild(style);
    }

    document.body.appendChild(toast);

    setTimeout(function () {
        toast.style.opacity = '0';
        toast.style.transition = 'opacity 0.3s';
        setTimeout(function () { if (toast.parentNode) toast.remove(); }, 300);
    }, 2500);
}

/* ---------------------------------------------------------
   UTILITY
   --------------------------------------------------------- */
function escapeHtml(str) {
    return String(str || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/* ---------------------------------------------------------
   ONBOARDING PERMISSIONS (Section 6)
   Flow séquencé : explique PUIS demande chaque groupe, dans l'ordre.
   La position en arrière-plan est demandée SEULE et en dernier (G1).
   Un refus ne bloque pas l'app : on affiche l'impact fonctionnel.
   --------------------------------------------------------- */
var MARSEL_PERM_GROUPS = [
    { key: 'location',            icon: '📍', title: 'Position',
      desc: 'Pour partager ta position en cas d\'alerte',
      impact: 'Sans position, tes proches ne sauront pas où te trouver.' },
    { key: 'nearby',              icon: '📶', title: 'Appareils à proximité',
      desc: 'Pour le réseau Marsel entre téléphones',
      impact: 'Sans ça, l\'alerte ne peut pas transiter par les téléphones voisins hors réseau.' },
    { key: 'sms',                 icon: '✉️', title: 'SMS',
      desc: 'Pour alerter tes proches même sans internet',
      impact: 'Sans SMS, tes proches ne seront pas alertés hors internet.' },
    { key: 'notifications',       icon: '🔔', title: 'Notifications',
      desc: 'Pour être prévenu·e d\'une alerte à proximité',
      impact: 'Sans notifications, tu ne verras pas les alertes des personnes autour de toi.' },
    { key: 'microphone',          icon: '🎙️', title: 'Microphone',
      desc: 'Pour l\'enregistrement de protection (shield)',
      impact: 'Sans micro, l\'enregistrement de protection est indisponible.' },
    { key: 'camera',              icon: '💡', title: 'Caméra',
      desc: 'Pour le flash SOS',
      impact: 'Sans caméra, le flash SOS est indisponible.' },
    { key: 'background_location', icon: '🗺️', title: 'Position en arrière-plan',
      desc: 'Pour continuer à partager ta position même écran éteint',
      impact: 'Sans ça, le partage de position s\'arrête quand l\'écran s\'éteint.', last: true }
];
var _onbIndex = 0;
var _onbStarted = false;

function maybeStartOnboarding() {
    if (_onbStarted) return;
    if (localStorage.getItem('marsel_onboarded') === '1') return;
    if (!window.AndroidBridge || typeof AndroidBridge.requestPermissionGroup !== 'function') {
        // Pas de bridge natif (test navigateur) : rien à demander
        localStorage.setItem('marsel_onboarded', '1');
        return;
    }
    _onbStarted = true;
    _onbIndex = 0;
    _renderOnboarding();
}

function _onbEnsureOverlay() {
    var ov = document.getElementById('marsel-onboarding');
    if (ov) return ov;
    ov = document.createElement('div');
    ov.id = 'marsel-onboarding';
    ov.style.cssText = [
        'position:fixed;inset:0;z-index:10000;display:flex;flex-direction:column;',
        'align-items:center;justify-content:center;padding:32px 24px;text-align:center;',
        'background:#1A35C8;color:white;font-family:Nunito,sans-serif;'
    ].join('');
    document.body.appendChild(ov);
    return ov;
}

function _renderOnboarding() {
    // Sauter la position en arrière-plan si la localisation n'a pas été accordée (G1)
    var g = MARSEL_PERM_GROUPS[_onbIndex];
    if (g && g.key === 'background_location' &&
        window.AndroidBridge && typeof AndroidBridge.hasPermissionGroup === 'function') {
        try {
            if (!AndroidBridge.hasPermissionGroup('location')) { _onbNext(); return; }
        } catch (e) {}
    }
    if (_onbIndex >= MARSEL_PERM_GROUPS.length) { _finishOnboarding(); return; }
    g = MARSEL_PERM_GROUPS[_onbIndex];
    var ov = _onbEnsureOverlay();
    var step = (_onbIndex + 1) + ' / ' + MARSEL_PERM_GROUPS.length;
    ov.innerHTML = [
        '<div style="font-size:13px;opacity:0.7;margin-bottom:24px;">' + step + '</div>',
        '<div style="font-size:64px;margin-bottom:16px;">' + g.icon + '</div>',
        '<div style="font-size:24px;font-weight:800;margin-bottom:12px;">' + escapeHtml(g.title) + '</div>',
        '<div style="font-size:16px;opacity:0.9;max-width:320px;margin-bottom:28px;line-height:1.4;">' + escapeHtml(g.desc) + '</div>',
        '<button id="onb-allow" style="background:white;color:#1A35C8;border:none;border-radius:24px;padding:14px 40px;font-size:16px;font-weight:800;font-family:Nunito,sans-serif;cursor:pointer;margin-bottom:14px;">Autoriser</button>',
        '<button id="onb-skip" style="background:transparent;color:white;border:none;font-size:14px;opacity:0.75;cursor:pointer;font-family:Nunito,sans-serif;text-decoration:underline;">Plus tard</button>',
        '<div id="onb-impact" style="font-size:13px;opacity:0;margin-top:20px;max-width:300px;line-height:1.4;color:#FFD9CC;"></div>'
    ].join('');
    document.getElementById('onb-allow').onclick = _onbRequest;
    document.getElementById('onb-skip').onclick = function () {
        var imp = document.getElementById('onb-impact');
        if (imp) { imp.textContent = g.impact; imp.style.opacity = '1'; }
        setTimeout(_onbNext, 900);
    };
}

function _onbRequest() {
    var g = MARSEL_PERM_GROUPS[_onbIndex];
    if (window.AndroidBridge && typeof AndroidBridge.requestPermissionGroup === 'function') {
        try { AndroidBridge.requestPermissionGroup(g.key); } catch (e) { _onbNext(); }
    } else {
        _onbNext();
    }
}

// Callback natif (Section 6)
window.onPermissionResult = function (group, granted, permanentlyDenied) {
    mLog('J', 'NET', 'perm ' + group + ' granted=' + granted + ' permDenied=' + permanentlyDenied);
    if (!_onbStarted) {
        // PERM-FIX : re-demande hors onboarding (permission proximité pour le MRN)
        if (group === 'nearby') {
            if (granted) {
                showToast('✅ Réseau Marsel activé');
                updateNetworkBadge();
            } else if (permanentlyDenied) {
                showToast('⚠️ Active « Appareils à proximité » dans les réglages pour le réseau Marsel');
                if (window.AndroidBridge && typeof AndroidBridge.openAppSettings === 'function') {
                    try { AndroidBridge.openAppSettings(); } catch (e) {}
                }
            } else {
                showToast('⚠️ Sans cette permission, le réseau Marsel reste inactif');
            }
        }
        return;
    }
    var g = MARSEL_PERM_GROUPS[_onbIndex];
    if (!g || g.key !== group) return;
    if (!granted && permanentlyDenied) {
        // Refus définitif : proposer l'ouverture des réglages
        var ov = _onbEnsureOverlay();
        var imp = document.getElementById('onb-impact');
        if (imp) { imp.textContent = g.impact + ' Tu peux l\'activer dans les réglages.'; imp.style.opacity = '1'; }
        var allow = document.getElementById('onb-allow');
        if (allow) {
            allow.textContent = 'Ouvrir les réglages';
            allow.onclick = function () {
                if (window.AndroidBridge && typeof AndroidBridge.openAppSettings === 'function') {
                    try { AndroidBridge.openAppSettings(); } catch (e) {}
                }
                setTimeout(_onbNext, 400);
            };
        }
        return;
    }
    _onbNext();
};

function _onbNext() {
    _onbIndex++;
    _renderOnboarding();
}

function _finishOnboarding() {
    localStorage.setItem('marsel_onboarded', '1');
    var ov = document.getElementById('marsel-onboarding');
    if (ov && ov.parentNode) ov.parentNode.removeChild(ov);
    showToast('Configuration terminée ✓');
}

/* ---------------------------------------------------------
   RELAY QUEUE FLUSH (periodic)
   --------------------------------------------------------- */
function flushRelayQueueIfOnline() {
    var quality = getNetworkQuality();
    if (quality === 'NONE') return;
    // TEST-FIX : sans SIM, aucun SMS ne peut partir d'ici — les paquets MRN
    // (relaySms=1) sont déjà en diffusion, un relais avec SIM s'en charge.
    if (!deviceCanSendSms()) return;
    if (!MARSEL.relayQueue.length) return;

    var queue = MARSEL.relayQueue.slice();
    MARSEL.relayQueue = [];

    queue.forEach(function (data) {
        // F2 : ne (re)envoyer le SMS que si l'alerte n'a PAS déjà été SMS-ée.
        // Sans ce garde, une urgence déclenchée avec réseau était re-SMS-ée à
        // chaque event 'online' / cycle 30s → salves de SMS dupliqués.
        if (data && data.smsSent === true) return;
        sendEmergencyViaInternet(data);
        data.smsSent = true;
    });

    // Clear DB queue
    dbGetAll('relay_queue').then(function (items) {
        items.forEach(function (item) { dbDelete('relay_queue', item.id); });
    }).catch(function () {});
}

/* ---------------------------------------------------------
   INIT SEQUENCE
   --------------------------------------------------------- */
document.addEventListener('DOMContentLoaded', function () {

    // 1. Initialize IndexedDB
    initDB().then(function () {
        console.log('MarselDB ready');
        cleanupOldEmergencyEvents();
    }).catch(function (e) {
        console.warn('DB init failed:', e);
    });

    // 2. Load persisted mode
    MARSEL.mode = localStorage.getItem('marsel_mode') || 'autonome';

    // 3. Check if emergency was active when app was killed
    var savedEmergency = null;
    try { savedEmergency = JSON.parse(secureGet('marsel_emergency') || 'null'); } catch (e) {}
    if (savedEmergency && savedEmergency.id) {
        MARSEL.emergencyActive = true;
        MARSEL.emergencyId = savedEmergency.id;
        // AUDIT-FIX 3 : re-diffuser l'ALERTE (E) après un redémarrage, sinon le
        // service DNS-SD marsel-alert n'est jamais ré-enregistré et les nouveaux
        // téléphones à proximité ne reçoivent plus la notification (seules les
        // mises à jour de position continueraient). Ne renvoie PAS de SMS.
        if (!savedEmergency.type) savedEmergency.type = 'MARSEL_EMERGENCY';
        if (typeof savedEmergency.relaySms === 'undefined') savedEmergency.relaySms = '0';
        sendEmergencyViaRelayNetwork(savedEmergency);
        // Reprendre le tracking et RÉARMER le timer 20 min sur le temps restant
        // (Section 4 — persistance). Si les 20 min sont dépassées, il se
        // déclenche immédiatement via armEmergencyTimeout (remaining=0).
        startEmergencyTracking(savedEmergency);
        armEmergencyTimeout(savedEmergency);
    }

    // 4. After splash delay, ALWAYS route through the lock screen (TODO.md
    // §1.3) : même si un secret local existe déjà, il doit être re-saisi
    // (ou déverrouillé par biométrie) à CHAQUE ouverture de l'app — utile
    // notamment si le téléphone change de main. initAuthScreen() choisit
    // automatiquement « créer un accès » vs « déverrouiller ».
    setTimeout(function () {
        MARSEL.screenHistory = [];
        initAuthScreen();
        showScreen('screen-auth');
    }, 2500);

    // 5. Online / offline events
    window.addEventListener('online', function () {
        updateNetworkStatus();
        updateNetworkBadge();
        flushRelayQueueIfOnline();
    });
    window.addEventListener('offline', function () {
        MARSEL.networkType = 'NONE';
        updateNetworkBadge();
    });

    // 6. Android back button – dummy history entry
    window.history.pushState({ marsel: true }, '');
    window.addEventListener('popstate', function () { goBack(); });

    // 7. Periodic tasks every 30 seconds
    setInterval(function () {
        updateNetworkStatus();
        updateNetworkBadge();
        flushRelayQueueIfOnline();
    }, 30000);
});
