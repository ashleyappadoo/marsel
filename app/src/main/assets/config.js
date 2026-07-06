/**
 * MARSEL – Configuration File
 * Fill in your API keys below to enable cloud features.
 * Without these keys, the app works in fully offline mode.
 */
var MARSEL_CONFIG = {
    // === BACKEND API (optional) ===
    // Your Marsel backend URL for cloud sync
    API_URL: '',                    // e.g. 'https://api.marsel.io/v1'
    API_KEY: '',                    // Your Marsel API key

    // === SMS GATEWAY (optional) ===
    // If empty, SMS are sent directly via Android SmsManager (requires SIM)
    // Set to use a cloud SMS gateway (e.g. Twilio) as fallback
    SMS_GATEWAY_URL: '',            // e.g. 'https://api.twilio.com/2010-04-01/Accounts/ACXX/Messages'
    SMS_GATEWAY_SID: '',            // Twilio Account SID
    SMS_GATEWAY_TOKEN: '',          // Twilio Auth Token
    SMS_FROM_NUMBER: '',            // e.g. '+33700000000'

    // === PUSH NOTIFICATIONS (optional) ===
    FCM_SERVER_KEY: '',             // Firebase Cloud Messaging server key

    // === MAP (optional) ===
    // Leave empty to use OpenStreetMap/CartoDB (free, no key required)
    MAPBOX_TOKEN: '',               // MapBox public token for better maps

    // === APP SETTINGS ===
    EMERGENCY_RELAY_PORT: 8890,     // WiFi Direct relay port (must match Android)
    RELAY_HOP_LIMIT: 10,            // Max relay hops before dropping
    P2P_DISCOVERY_TIMEOUT: 30000,   // 30s WiFi Direct discovery timeout
    LOCATION_UPDATE_INTERVAL: 10000, // 10s GPS update interval
    EMERGENCY_HOLD_DURATION: 5000,  // 5s hold to trigger emergency

    // === SAFE PLACES ===
    // Source : le fichier assets/safeplace.csv, versionné dans le git.
    // Format : nom_emplacement, lat, long (une ligne par lieu, en-tête inclus).
    // Les lieux s'affichent SELON LA POSITION RÉELLE de l'utilisateur :
    // uniquement ceux situés dans SAFE_PLACES_RADIUS_M autour du vrai fix GPS.
    SAFE_PLACES_RADIUS_M: 2500      // rayon d'affichage autour de la position réelle
};
