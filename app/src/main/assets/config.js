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

    // === SAFE PLACES DATABASE (local seed) ===
    // These are loaded into the local DB on first run.
    // In production, these come from the Angela database API.
    SAFE_PLACES_SEED: [
        { name: 'Commissariat 1er', lat: 48.8603, lng: 2.3477, phone: '0144418000', type: 'police' },
        { name: 'Hôtel de Ville de Paris', lat: 48.8566, lng: 2.3522, phone: '0142766363', type: 'mairie' },
        { name: 'Hôpital Hôtel-Dieu', lat: 48.8527, lng: 2.3483, phone: '0142348200', type: 'hopital' },
        { name: 'Commissariat 4e', lat: 48.8533, lng: 2.3524, phone: '0144549790', type: 'police' },
        { name: 'Safe Place – Gare de Lyon', lat: 48.8448, lng: 2.3738, phone: '3117', type: 'safe' },
        { name: 'Safe Place – Gare du Nord', lat: 48.8809, lng: 2.3553, phone: '3117', type: 'safe' },
        { name: 'Commissariat 18e', lat: 48.8868, lng: 2.3482, phone: '0153731800', type: 'police' },
        { name: 'Hôpital Lariboisière', lat: 48.8805, lng: 2.3558, phone: '0149956565', type: 'hopital' }
    ]
};
