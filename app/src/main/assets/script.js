/* =========================================================
   MARSEL – script.js
   Fully functional implementation – no mock data.
   ========================================================= */

/* ---------------------------------------------------------
   GLOBAL STATE OBJECT
   --------------------------------------------------------- */
var MARSEL = {
    // User state
    currentUser: null,
    currentLat: 48.8566,   // default Paris – updated by GPS
    currentLng: 2.3522,
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

    // Mode
    mode: 'autonome',          // 'autonome' | 'assistance'

    // Navigation
    screenHistory: [],

    // IndexedDB handle
    db: null,

    // GPS tracking during emergency
    trackingInterval: null,    // setInterval handle for continuous position broadcast
    firstGpsFix: false         // true once real GPS replaces Paris default
};

/* Ring circumference for r=78 */
var RING_CIRCUMFERENCE = 2 * Math.PI * 78; // ≈ 490

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
            seedSafePlaces();
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

function seedSafePlaces() {
    if (!MARSEL.db) return;
    try {
        var tx = MARSEL.db.transaction('safe_places', 'readonly');
        var req = tx.objectStore('safe_places').count();
        req.onsuccess = function () {
            if (req.result === 0) {
                var places = (window.MARSEL_CONFIG && MARSEL_CONFIG.SAFE_PLACES_SEED) || [];
                places.forEach(function (place) {
                    dbPut('safe_places', place).catch(function () {});
                });
            }
        };
    } catch (e) {}
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

/* Called by Android native code AND by the browser geolocation callback */
window.onLocationUpdate = function (lat, lng, accuracy) {
    var fLat = parseFloat(lat);
    var fLng = parseFloat(lng);
    if (!fLat || !fLng) return;

    var wasDefault = !MARSEL.firstGpsFix;
    MARSEL.currentLat = fLat;
    MARSEL.currentLng = fLng;

    // Update user marker on map
    if (MARSEL.userMarker && MARSEL.leafletMap) {
        MARSEL.userMarker.setLatLng([fLat, fLng]);
        // Premier vrai fix GPS : recentrer la carte sur la position réelle
        if (wasDefault) {
            MARSEL.firstGpsFix = true;
            MARSEL.leafletMap.setView([fLat, fLng], 15);
        }
    }

    // Si urgence active : mettre à jour la position locale et la diffuser
    if (MARSEL.emergencyActive && MARSEL.emergencyId) {
        var saved = null;
        try { saved = JSON.parse(localStorage.getItem('marsel_emergency') || 'null'); } catch (e) {}
        if (saved) {
            saved.lat = fLat;
            saved.lng = fLng;
            localStorage.setItem('marsel_emergency', JSON.stringify(saved));
        }
        // Diffuser immédiatement la nouvelle position (le tracking interval s'en occupe toutes les 10s)
        sendPositionUpdate(fLat, fLng);
    }
};

/* ---------------------------------------------------------
   MAP
   --------------------------------------------------------- */
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
        }).setView([MARSEL.currentLat, MARSEL.currentLng], 14);

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

        // User marker with pulse animation
        var userIcon = L.divIcon({
            html: '<div class="user-location-marker"><div class="user-pulse"></div></div>',
            iconSize: [20, 20],
            iconAnchor: [10, 10],
            className: ''
        });
        MARSEL.userMarker = L.marker([MARSEL.currentLat, MARSEL.currentLng], { icon: userIcon })
            .addTo(MARSEL.leafletMap)
            .bindPopup('<b>Ma position</b>');

        // Zoom control bottom right
        L.control.zoom({ position: 'bottomright' }).addTo(MARSEL.leafletMap);

        // Load data from DB
        loadSafePlacesOnMap();
        loadIncidentsOnMap();

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
    dbGetAll('safe_places').then(function (places) {
        if (!MARSEL.leafletMap) return;
        places.forEach(function (place) {
            var color = '#4CAF50';
            if (place.type === 'police') color = '#1A35C8';
            else if (place.type === 'hopital') color = '#E84315';
            else if (place.type === 'mairie') color = '#9C27B0';

            var icon = createMapMarkerIcon(color, place.type);
            var marker = L.marker([place.lat, place.lng], { icon: icon })
                .addTo(MARSEL.leafletMap)
                .bindPopup('<b>' + escapeHtml(place.name) + '</b><br>📞 ' + escapeHtml(place.phone || ''));
            MARSEL.safePlaceMarkers.push(marker);
        });
    }).catch(function () {});
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
    if (MARSEL.incidentMarkers[ev.id]) return; // already shown

    var icon = L.divIcon({
        html: '<div style="width:32px;height:32px;border-radius:50%;background:#E84315;display:flex;align-items:center;justify-content:center;border:3px solid white;box-shadow:0 2px 8px rgba(232,67,21,0.6);animation:pulse-emergency 1s infinite;"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><path d="M13 2L3 14h7l-1 8 10-12h-7z"/></svg></div>',
        iconSize: [32, 32],
        iconAnchor: [16, 16],
        className: ''
    });

    var timeStr = '';
    if (ev.timestamp) {
        timeStr = new Date(ev.timestamp).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    }

    var marker = L.marker([ev.lat, ev.lng], { icon: icon })
        .addTo(MARSEL.leafletMap)
        .bindPopup('<b>🚨 Alerte Marsel</b><br>Utilisateur : ' + escapeHtml(ev.pseudo || '?') + (timeStr ? '<br>Heure : ' + timeStr : ''));

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
    var contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]');
    var settings = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
    if (!settings.location) return;

    contacts.forEach(function (contact, idx) {
        if (!contact.nom && !contact.mobile) return;

        var userId = 'contact-' + idx;
        if (MARSEL.friendMarkers[userId]) return; // already shown

        // If API is configured, try to fetch real position
        if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL && MARSEL_CONFIG.API_KEY) {
            var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
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
    var contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]')
        .filter(function (c) { return c.nom || c.mobile; });
    var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
    var profileData = JSON.parse(localStorage.getItem('marsel_profile') || '{}');

    var emergencyData = {
        type: 'MARSEL_EMERGENCY',
        version: 1,
        id: MARSEL.emergencyId,
        messageId: MARSEL.emergencyId,
        userId: userData.userId || userData.email || 'unknown',
        pseudo: profileData.pseudo || userData.pseudo || userData.email || 'Utilisateur',
        lat: MARSEL.currentLat,
        lng: MARSEL.currentLng,
        timestamp: Date.now(),
        contacts: contacts,
        mode: MARSEL.mode,
        hopCount: 0,
        maxHops: (window.MARSEL_CONFIG && MARSEL_CONFIG.RELAY_HOP_LIMIT) || 10,
        status: 'ACTIVE',
        originNetwork: MARSEL.networkType
    };

    // Persist locally
    dbPut('emergency_events', emergencyData).catch(function () {});
    localStorage.setItem('marsel_emergency', JSON.stringify(emergencyData));

    // Show own incident on map
    addIncidentMarker(emergencyData);

    // Route it
    routeEmergency(emergencyData);

    // Démarrer le tracking GPS continu (position toutes les 10s)
    startEmergencyTracking(emergencyData);

    showToast('🚨 Alerte envoyée !');
}

function deactivateEmergency() {
    // Stopper le tracking GPS continu
    stopEmergencyTracking();

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
    try { savedEmergency = JSON.parse(localStorage.getItem('marsel_emergency') || 'null'); } catch (e) {}

    if (savedEmergency) {
        var pseudo = savedEmergency.pseudo || 'Utilisateur';
        var lastPos = 'https://maps.google.com/?q=' + MARSEL.currentLat + ',' + MARSEL.currentLng;
        var finMsg = '✅ FIN D\'ALERTE MARSEL\n' + pseudo + ' est en sécurité.\nDernière position connue : ' + lastPos;

        // SMS de fin aux proches — SmsManager utilise le réseau cellulaire (pas internet)
        var contacts = savedEmergency.contacts || [];
        contacts.forEach(function (c) {
            if (c.mobile && window.AndroidBridge && typeof AndroidBridge.sendEmergencySMS === 'function') {
                try { AndroidBridge.sendEmergencySMS(c.mobile, finMsg); } catch (e) {}
            }
        });

        // Paquet de résolution vers les appareils voisins via relay
        var resolvedPacket = {
            type: 'MARSEL_EMERGENCY_RESOLVED',
            version: 1,
            messageId: MARSEL.emergencyId + '_resolved_' + Date.now(),
            emergencyId: MARSEL.emergencyId,
            userId: savedEmergency.userId,
            pseudo: pseudo,
            lat: MARSEL.currentLat,
            lng: MARSEL.currentLng,
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
        dbPut('emergency_events', {
            id: MARSEL.emergencyId,
            status: 'RESOLVED',
            resolvedAt: Date.now()
        }).catch(function () {});

        // Remove from map
        if (MARSEL.incidentMarkers[MARSEL.emergencyId]) {
            try { MARSEL.leafletMap.removeLayer(MARSEL.incidentMarkers[MARSEL.emergencyId]); } catch (e) {}
            delete MARSEL.incidentMarkers[MARSEL.emergencyId];
        }
    }

    localStorage.removeItem('marsel_emergency');
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
   EMERGENCY ROUTING
   --------------------------------------------------------- */
function routeEmergency(emergencyData) {
    updateNetworkStatus();

    // Always relay via WiFi Direct so nearby Marsel phones get a notification + map marker.
    // Relay works independently of internet: it uses WiFi P2P (no data connection needed).
    sendEmergencyViaRelayNetwork(emergencyData);

    // Always attempt SMS to contacts: SmsManager uses the cellular baseband (2G/3G/4G
    // signalling), it does NOT require a mobile-data or WiFi internet connection.
    // Also calls the cloud API if API_URL is configured and we are online.
    sendEmergencyViaInternet(emergencyData);
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

    // Send SMS via Android SmsManager
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

        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencySMS === 'function') {
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
    if (!MARSEL.p2pConnected && typeof AndroidBridge !== 'undefined') {
        // Give feedback: relay needs WiFi radio on even without internet
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
    var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
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

/* Called by Android when P2P connection established */
window.onP2PConnected = function (info) {
    MARSEL.p2pConnected = true;
    updateNetworkBadge('🔁 Marsel Relay Network ✓');

    // Flush relay queue now that we have a peer
    MARSEL.relayQueue.forEach(function (emergencyData) {
        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
            try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(emergencyData)); } catch (e) {}
        }
    });
};

/* Called by Android when a relay message arrives from another device */
window.onRelayMessageReceived = function (jsonStr) {
    var data;
    try { data = JSON.parse(jsonStr); } catch (e) { return; }
    if (!data || !data.type) return;

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

    // Notification système (fonctionne même si l'app est en arrière-plan)
    if (window.AndroidBridge && typeof AndroidBridge.showNotification === 'function') {
        try { AndroidBridge.showNotification('🚨 Alerte Marsel', (data.pseudo || 'Utilisateur') + ' a déclenché une alerte à proximité'); } catch (e) {}
    }
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

    // Relay further to phones out of direct range (hop forwarding).
    // Never re-send via internet here: the sender's phone already sent SMS to contacts,
    // and the Kotlin layer (forwardEmergencyToContacts) already handles any SMS forwarding
    // needed from this device. Sending via internet here would double-SMS the contacts.
    var maxHops = (window.MARSEL_CONFIG && MARSEL_CONFIG.RELAY_HOP_LIMIT) || 10;
    if ((data.hopCount || 0) < maxHops) {
        data.hopCount = (data.hopCount || 0) + 1;
        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
            try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(data)); } catch (e) {}
        }
    }
};

/* ── Tracking GPS continu pendant l'urgence ── */

function startEmergencyTracking(emergencyData) {
    stopEmergencyTracking(); // reset si déjà actif
    // Envoyer la position immédiatement, puis toutes les 10s
    sendPositionUpdate(MARSEL.currentLat, MARSEL.currentLng);
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

function sendPositionUpdate(lat, lng) {
    var fLat = parseFloat(lat);
    var fLng = parseFloat(lng);
    if (isNaN(fLat) || isNaN(fLng)) return; // skip if GPS not yet fixed

    var savedEmergency = null;
    try { savedEmergency = JSON.parse(localStorage.getItem('marsel_emergency') || 'null'); } catch (e) {}
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
        maxHops: 10
    };

    // Mettre à jour DB locale
    dbPut('emergency_events', Object.assign({}, savedEmergency, {
        lat: fLat, lng: fLng, lastPositionUpdate: Date.now()
    })).catch(function () {});

    // Diffuser via Marsel Relay Network (WiFi Direct)
    if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
        try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(updatePacket)); } catch (e) {}
    }

    // Si internet dispo : mettre à jour via API
    if ((MARSEL.networkType === 'WIFI' || MARSEL.networkType === 'MOBILE')
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
    var fLat = parseFloat(data.lat);
    var fLng = parseFloat(data.lng);
    if (isNaN(fLat) || isNaN(fLng)) return;

    var eId = data.emergencyId;
    var timeStr = new Date(data.timestamp || Date.now()).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    // Mettre à jour le marqueur existant
    if (MARSEL.incidentMarkers[eId] && MARSEL.leafletMap) {
        MARSEL.incidentMarkers[eId].setLatLng([fLat, fLng]);
        MARSEL.incidentMarkers[eId].setPopupContent(
            '<b>🚨 ' + escapeHtml(data.pseudo || 'Utilisateur') + '</b><br>' +
            'Tracking actif – mis à jour ' + timeStr
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

    // Relayer la mise à jour aux autres pairs si nécessaire
    if ((data.hopCount || 0) < (data.maxHops || 10)) {
        data.hopCount = (data.hopCount || 0) + 1;
        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
            try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(data)); } catch (e) {}
        }
    }
}

/* Fin d'alerte reçue via relay d'un autre utilisateur */
function handleEmergencyResolved(data) {
    var eId = data.emergencyId;
    if (!eId) return;

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

    // Relayer la résolution aux autres pairs
    if ((data.hopCount || 0) < (data.maxHops || 5)) {
        data.hopCount = (data.hopCount || 0) + 1;
        if (window.AndroidBridge && typeof AndroidBridge.sendEmergencyViaRelay === 'function') {
            try { AndroidBridge.sendEmergencyViaRelay(JSON.stringify(data)); } catch (e) {}
        }
    }
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
        var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
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
   AUTH
   --------------------------------------------------------- */
function switchAuthTab(tab) {
    var signinTab = document.getElementById('tab-signin');
    var signupTab = document.getElementById('tab-signup');
    var formSignin = document.getElementById('form-signin');
    var formSignup = document.getElementById('form-signup');

    if (tab === 'signin') {
        if (signinTab) signinTab.classList.add('active');
        if (signupTab) signupTab.classList.remove('active');
        if (formSignin) formSignin.classList.add('active');
        if (formSignup) formSignup.classList.remove('active');
    } else {
        if (signupTab) signupTab.classList.add('active');
        if (signinTab) signinTab.classList.remove('active');
        if (formSignup) formSignup.classList.add('active');
        if (formSignin) formSignin.classList.remove('active');
    }
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

function doLogin() {
    var email = ((document.getElementById('signin-email') || {}).value || '').trim();
    var password = ((document.getElementById('signin-password') || {}).value || '').trim();

    if (!email || !password) {
        showToast('Veuillez remplir tous les champs');
        return;
    }

    // Preserve existing userId across logins so relay identity stays stable
    var existingUser = null;
    try { existingUser = JSON.parse(localStorage.getItem('marsel_user') || 'null'); } catch (e) {}
    var userId = (existingUser && existingUser.userId) ? existingUser.userId : generateUserId();

    var userData = {
        email: email,
        pseudo: email.split('@')[0] || 'User',
        loggedIn: true,
        userId: userId
    };
    localStorage.setItem('marsel_user', JSON.stringify(userData));

    MARSEL.currentUser = userData;

    // Try API login if configured (non-blocking)
    if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL) {
        fetch(MARSEL_CONFIG.API_URL + '/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: email, password: password })
        }).then(function (r) {
            if (r.ok) return r.json();
        }).then(function (data) {
            if (data && data.token) {
                userData.token = data.token;
                if (data.userId) userData.userId = data.userId;
                localStorage.setItem('marsel_user', JSON.stringify(userData));
                MARSEL.currentUser = userData;
            }
        }).catch(function () {});
    }

    MARSEL.screenHistory = [];
    showScreen('screen-home');
    startRealGPS();
}

function doRegister() {
    var email = ((document.getElementById('signup-email') || {}).value || '').trim();
    var password = ((document.getElementById('signup-password') || {}).value || '').trim();
    var agreed = (document.getElementById('agree-tnc') || {}).checked;

    if (!email || !password) {
        showToast('Veuillez remplir tous les champs');
        return;
    }
    if (!agreed) {
        showToast('Veuillez accepter les conditions d\'utilisation');
        return;
    }

    var userId = generateUserId();
    var userData = {
        email: email,
        pseudo: email.split('@')[0] || 'User',
        loggedIn: true,
        userId: userId
    };
    localStorage.setItem('marsel_user', JSON.stringify(userData));
    MARSEL.currentUser = userData;

    // Try API registration if configured (non-blocking)
    if (window.MARSEL_CONFIG && MARSEL_CONFIG.API_URL) {
        fetch(MARSEL_CONFIG.API_URL + '/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: email, password: password, userId: userId })
        }).then(function (r) {
            if (r.ok) return r.json();
        }).then(function (data) {
            if (data && data.token) {
                userData.token = data.token;
                localStorage.setItem('marsel_user', JSON.stringify(userData));
                MARSEL.currentUser = userData;
            }
        }).catch(function () {});
    }

    MARSEL.screenHistory = [];
    showScreen('screen-home');
    startRealGPS();
}

function doLogout() {
    MARSEL.emergencyActive = false;
    MARSEL.emergencyId = null;
    MARSEL.currentUser = null;
    localStorage.removeItem('marsel_emergency');
    stopGPS();
    MARSEL.screenHistory = [];
    showScreen('screen-auth');
}

/* ---------------------------------------------------------
   PROFILE
   --------------------------------------------------------- */
function updateProfileMenu() {
    var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
    var profileData = JSON.parse(localStorage.getItem('marsel_profile') || '{}');
    var pseudo = profileData.pseudo || userData.pseudo || 'Utilisateur';
    var email = profileData.email || userData.email || '';

    var pmPseudo = document.getElementById('pm-pseudo');
    var pmEmail = document.getElementById('pm-email');
    if (pmPseudo) pmPseudo.textContent = pseudo;
    if (pmEmail) pmEmail.textContent = email;
}

function loadProfileForm() {
    var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
    var profileData = JSON.parse(localStorage.getItem('marsel_profile') || '{}');

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
    localStorage.setItem('marsel_profile', JSON.stringify(profileData));

    // Sync user object
    var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
    if (pseudo) userData.pseudo = pseudo;
    if (email) userData.email = email;
    localStorage.setItem('marsel_user', JSON.stringify(userData));
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
    var contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]');
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
    var contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]');
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
        var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
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

    var contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]');
    while (contacts.length < 5) contacts.push({ nom: '', mobile: '', email: '', pseudo: '' });

    contacts[idx] = { nom: nom, mobile: mobile, email: email, pseudo: pseudo };
    localStorage.setItem('marsel_contacts', JSON.stringify(contacts));

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
   RELAY QUEUE FLUSH (periodic)
   --------------------------------------------------------- */
function flushRelayQueueIfOnline() {
    if (MARSEL.networkType !== 'WIFI' && MARSEL.networkType !== 'MOBILE') return;
    if (!MARSEL.relayQueue.length) return;

    var queue = MARSEL.relayQueue.slice();
    MARSEL.relayQueue = [];

    queue.forEach(function (data) {
        sendEmergencyViaInternet(data);
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
    }).catch(function (e) {
        console.warn('DB init failed:', e);
    });

    // 2. Load persisted mode
    MARSEL.mode = localStorage.getItem('marsel_mode') || 'autonome';

    // 3. Check if emergency was active when app was killed
    var savedEmergency = null;
    try { savedEmergency = JSON.parse(localStorage.getItem('marsel_emergency') || 'null'); } catch (e) {}
    if (savedEmergency && savedEmergency.id) {
        MARSEL.emergencyActive = true;
        MARSEL.emergencyId = savedEmergency.id;
    }

    // 4. After splash delay, check auth and route to correct screen
    setTimeout(function () {
        var userData = null;
        try { userData = JSON.parse(localStorage.getItem('marsel_user') || 'null'); } catch (e) {}

        if (userData && userData.loggedIn) {
            MARSEL.currentUser = userData;
            MARSEL.screenHistory = [];
            showScreen('screen-home');
            startRealGPS();
        } else {
            MARSEL.screenHistory = [];
            showScreen('screen-auth');
        }
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
