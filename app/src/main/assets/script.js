/* =========================================================
   MARSEL – script.js
   ========================================================= */

'use strict';

/* ---------------------------------------------------------
   STATE
   --------------------------------------------------------- */
var screenHistory = [];
var emergencyActive = false;
var emergencyHoldTimer = null;
var emergencyRingInterval = null;
var emergencyRingProgress = 0;
var mapInitialized = false;
var leafletMap = null;

var chatMessages = [
  { type: 'received', text: 'Bonjour ! Votre alerte a été reçue. Êtes-vous en sécurité ?' },
  { type: 'sent',     text: 'Non, j\'ai besoin d\'aide. Je suis au parc de la Villette.' },
  { type: 'received', text: 'Des secours ont été alertés. Restez où vous êtes, ils arrivent dans 5 min.' },
  { type: 'sent',     text: 'Merci.' },
];

var contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]');
while (contacts.length < 5) contacts.push({ nom: '', mobile: '', email: '', pseudo: '' });

var settings = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
var currentContactSlot = 0;
var selectedPlan = 0;

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

  // Trigger animation
  void next.offsetWidth; // reflow
  if (isBack) {
    next.classList.add('slide-back-in');
  } else {
    next.classList.add('slide-in');
  }

  setTimeout(function () {
    next.classList.remove('slide-in', 'slide-back-in');
  }, 300);

  // Push to history unless going back
  if (!isBack) {
    screenHistory.push(id);
  }

  // Screen-specific setup
  if (id === 'screen-home') {
    updateNetworkBadge();
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
  if (screenHistory.length <= 1) {
    // Nothing to go back to, go home
    if (screenHistory[0] !== 'screen-home') {
      showScreen('screen-home', true);
      screenHistory = ['screen-home'];
    }
    return;
  }

  // Remove current screen from history
  screenHistory.pop();
  var prev = screenHistory[screenHistory.length - 1];

  var currentActive = document.querySelector('.screen.active');
  if (currentActive) currentActive.classList.remove('active');

  var prevScreen = document.getElementById(prev);
  if (!prevScreen) return;

  prevScreen.classList.remove('slide-in', 'slide-back-in');
  prevScreen.classList.add('active');
  void prevScreen.offsetWidth;
  prevScreen.classList.add('slide-back-in');
  setTimeout(function () { prevScreen.classList.remove('slide-back-in'); }, 300);

  // Screen-specific setup on back
  if (prev === 'screen-home') {
    updateNetworkBadge();
    updateEmergencyUI();
    updateProfileMenu();
  }
  if (prev === 'screen-profile-menu') {
    updateProfileMenu();
  }
  if (prev === 'screen-contacts') {
    renderContacts();
  }
}

/* ---------------------------------------------------------
   ANDROID BACK BUTTON INTERCEPT
   --------------------------------------------------------- */
window.addEventListener('popstate', function (e) {
  goBack();
});

// Push a dummy state so popstate fires on Android back
window.history.pushState({ marsel: true }, '');

/* ---------------------------------------------------------
   AUTH
   --------------------------------------------------------- */
function switchAuthTab(tab) {
  var signinTab  = document.getElementById('tab-signin');
  var signupTab  = document.getElementById('tab-signup');
  var formSignin = document.getElementById('form-signin');
  var formSignup = document.getElementById('form-signup');

  if (tab === 'signin') {
    signinTab.classList.add('active');
    signupTab.classList.remove('active');
    formSignin.classList.add('active');
    formSignup.classList.remove('active');
  } else {
    signupTab.classList.add('active');
    signinTab.classList.remove('active');
    formSignup.classList.add('active');
    formSignin.classList.remove('active');
  }
}

function togglePassword(inputId, eyeEl) {
  var input = document.getElementById(inputId);
  if (!input) return;
  if (input.type === 'password') {
    input.type = 'text';
    eyeEl.style.opacity = '0.5';
  } else {
    input.type = 'password';
    eyeEl.style.opacity = '1';
  }
}

function doLogin() {
  var email    = (document.getElementById('signin-email')    || {}).value || '';
  var password = (document.getElementById('signin-password') || {}).value || '';
  var remember = (document.getElementById('remember-me')     || {}).checked;

  // Basic validation
  if (!email || !password) {
    alert('Veuillez remplir tous les champs.');
    return;
  }

  var userData = {
    email: email,
    pseudo: email.split('@')[0] || 'User',
    loggedIn: true
  };
  localStorage.setItem('marsel_user', JSON.stringify(userData));

  screenHistory = [];
  showScreen('screen-home');
}

function doRegister() {
  var email    = (document.getElementById('signup-email')    || {}).value || '';
  var password = (document.getElementById('signup-password') || {}).value || '';
  var agreed   = (document.getElementById('agree-tnc')       || {}).checked;

  if (!email || !password) {
    alert('Veuillez remplir tous les champs.');
    return;
  }
  if (!agreed) {
    alert('Veuillez accepter les conditions d\'utilisation.');
    return;
  }

  var userData = {
    email: email,
    pseudo: email.split('@')[0] || 'User',
    loggedIn: true
  };
  localStorage.setItem('marsel_user', JSON.stringify(userData));

  screenHistory = [];
  showScreen('screen-home');
}

function doLogout() {
  localStorage.removeItem('marsel_user');
  emergencyActive = false;
  screenHistory = [];
  showScreen('screen-auth');
}

/* ---------------------------------------------------------
   PROFILE
   --------------------------------------------------------- */
function updateProfileMenu() {
  var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
  var pseudo   = userData.pseudo || 'H';
  var email    = userData.email  || '';

  var pmPseudo = document.getElementById('pm-pseudo');
  var pmEmail  = document.getElementById('pm-email');
  if (pmPseudo) pmPseudo.textContent = pseudo.charAt(0).toUpperCase();
  if (pmEmail)  pmEmail.textContent  = email;
}

function loadProfileForm() {
  var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
  var profileData = JSON.parse(localStorage.getItem('marsel_profile') || '{}');

  var el;
  el = document.getElementById('profile-pseudo');
  if (el) el.value = profileData.pseudo || userData.pseudo || '';

  el = document.getElementById('profile-mobile');
  if (el) el.value = profileData.mobile || '';

  el = document.getElementById('profile-email');
  if (el) el.value = profileData.email  || userData.email || '';
}

function saveProfile() {
  var pseudo = (document.getElementById('profile-pseudo') || {}).value || '';
  var mobile = (document.getElementById('profile-mobile') || {}).value || '';
  var email  = (document.getElementById('profile-email')  || {}).value || '';

  var profileData = { pseudo: pseudo, mobile: mobile, email: email };
  localStorage.setItem('marsel_profile', JSON.stringify(profileData));

  // Update user data too
  var userData = JSON.parse(localStorage.getItem('marsel_user') || '{}');
  userData.pseudo = pseudo;
  userData.email  = email;
  localStorage.setItem('marsel_user', JSON.stringify(userData));

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
  settings = s;
}

/* ---------------------------------------------------------
   CONTACTS
   --------------------------------------------------------- */
function renderContacts() {
  contacts = JSON.parse(localStorage.getItem('marsel_contacts') || '[]');
  while (contacts.length < 5) contacts.push({ nom: '', mobile: '', email: '', pseudo: '' });

  var list = document.getElementById('contacts-list');
  if (!list) return;
  list.innerHTML = '';

  for (var i = 0; i < 5; i++) {
    var c = contacts[i];
    var slot = document.createElement('div');
    slot.className = 'contact-slot';
    slot.setAttribute('data-index', i);
    slot.onclick = (function (idx) {
      return function () { openContactDetail(idx); };
    })(i);

    var displayName = c.nom ? 'Nom : ' + c.nom : 'Nom :';

    slot.innerHTML = [
      '<div class="contact-slot-icon">',
        '<svg width="22" height="22" viewBox="0 0 24 24" fill="white">',
          '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>',
          '<circle cx="12" cy="7" r="4"/>',
          '<path d="M19 8v6M22 11h-6" stroke="white" stroke-width="2" stroke-linecap="round"/>',
        '</svg>',
      '</div>',
      '<div class="contact-slot-info">',
        '<div class="contact-slot-name">' + escapeHtml(displayName) + '</div>',
        c.mobile ? '<div style="font-size:12px;color:#9E9E9E;margin-top:2px;">' + escapeHtml(c.mobile) + '</div>' : '',
      '</div>',
      '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#E84315" stroke-width="2.5" stroke-linecap="round"><polyline points="9 18 15 12 9 6"/></svg>'
    ].join('');

    list.appendChild(slot);
  }
}

function openContactDetail(idx) {
  currentContactSlot = idx;
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
    el.value = userData.pseudo || 'Marsel';
  }

  showScreen('screen-contact-detail');
}

function saveContact() {
  var idx    = parseInt((document.getElementById('contact-slot-index') || {}).value || '0', 10);
  var nom    = (document.getElementById('contact-nom')    || {}).value || '';
  var mobile = (document.getElementById('contact-mobile') || {}).value || '';
  var email  = (document.getElementById('contact-email')  || {}).value || '';
  var pseudo = (document.getElementById('contact-pseudo') || {}).value || '';

  contacts[idx] = { nom: nom, mobile: mobile, email: email, pseudo: pseudo };
  localStorage.setItem('marsel_contacts', JSON.stringify(contacts));

  showToast('Contact enregistré !');
  goBack();
}

/* ---------------------------------------------------------
   SUBSCRIPTION
   --------------------------------------------------------- */
function selectPlan(planIdx) {
  selectedPlan = planIdx;
  updatePlanSelection();
}

function updatePlanSelection() {
  var cards = document.querySelectorAll('.plan-card');
  var dots  = document.querySelectorAll('.plan-dot');

  cards.forEach(function (card, i) {
    if (i === selectedPlan && i !== 1) {
      card.classList.add('selected');
    } else if (i !== 1) {
      card.classList.remove('selected');
    }
  });

  dots.forEach(function (dot, i) {
    if (i === selectedPlan) {
      dot.classList.add('active');
    } else {
      dot.classList.remove('active');
    }
  });

  // Sync scroll dots with scroll position
  var wrapper = document.querySelector('.plan-scroll-wrapper');
  if (wrapper) {
    wrapper.addEventListener('scroll', onPlanScroll, { passive: true });
  }
}

function onPlanScroll(e) {
  var wrapper = e.target;
  var scrollLeft = wrapper.scrollLeft;
  var cardWidth  = 214; // 200px + 14px gap
  var idx = Math.round(scrollLeft / cardWidth);
  idx = Math.max(0, Math.min(2, idx));

  var dots = document.querySelectorAll('.plan-dot');
  dots.forEach(function (dot, i) {
    if (i === idx) dot.classList.add('active');
    else dot.classList.remove('active');
  });
}

/* ---------------------------------------------------------
   MAP
   --------------------------------------------------------- */
function initMap() {
  if (mapInitialized) return;
  if (typeof L === 'undefined') {
    // Leaflet not loaded yet, show placeholder
    return;
  }

  var mapEl = document.getElementById('map');
  if (!mapEl) return;

  // Remove placeholder text
  mapEl.innerHTML = '';
  mapInitialized = true;

  try {
    leafletMap = L.map('map', {
      zoomControl: false,
      attributionControl: false,
      dragging: true,
      touchZoom: true,
      scrollWheelZoom: false
    }).setView([48.8566, 2.3522], 14);

    L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png', {
      attribution: '&copy; OpenStreetMap',
      subdomains: 'abcd',
      maxZoom: 19
    }).addTo(leafletMap);

    // User location marker (blue circle)
    var userIcon = L.divIcon({
      html: '<div style="width:18px;height:18px;border-radius:50%;background:#1A35C8;border:3px solid white;box-shadow:0 2px 6px rgba(26,53,200,0.5);"></div>',
      iconSize: [18, 18],
      iconAnchor: [9, 9],
      className: ''
    });
    L.marker([48.8566, 2.3522], { icon: userIcon })
      .addTo(leafletMap)
      .bindPopup('<b>Ma position</b>');

    // Safe place markers (orange)
    var safeIcon = L.divIcon({
      html: '<div style="width:28px;height:28px;border-radius:50%;background:#E84315;display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 6px rgba(232,67,21,0.5);"><svg width="14" height="14" viewBox="0 0 24 24" fill="white"><path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/></svg></div>',
      iconSize: [28, 28],
      iconAnchor: [14, 14],
      className: ''
    });

    var safePlaces = [
      { coords: [48.8610, 2.3480], name: 'Safe Place – Mairie du 10e' },
      { coords: [48.8530, 2.3560], name: 'Safe Place – Commissariat' },
      { coords: [48.8585, 2.3440], name: 'Safe Place – Hôpital Lariboisière' }
    ];

    safePlaces.forEach(function (sp) {
      L.marker(sp.coords, { icon: safeIcon })
        .addTo(leafletMap)
        .bindPopup('<b>' + sp.name + '</b>');
    });

    // Friend marker (orange person)
    var friendIcon = L.divIcon({
      html: '<div style="width:28px;height:28px;border-radius:50%;background:#FF7043;display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 6px rgba(255,112,67,0.5);"><svg width="14" height="14" viewBox="0 0 24 24" fill="white"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg></div>',
      iconSize: [28, 28],
      iconAnchor: [14, 14],
      className: ''
    });

    L.marker([48.8548, 2.3502], { icon: friendIcon })
      .addTo(leafletMap)
      .bindPopup('<b>Ami : Sophie</b><br>Proche');

    // Zoom control (bottom right)
    L.control.zoom({ position: 'bottomright' }).addTo(leafletMap);

    setTimeout(function () {
      if (leafletMap) leafletMap.invalidateSize();
    }, 300);

  } catch (err) {
    console.warn('Map init failed:', err);
    mapInitialized = false;
    var fallback = document.getElementById('map');
    if (fallback) {
      fallback.innerHTML = '<div class="map-placeholder"><svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#9E9E9E" stroke-width="1.5"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg><p>Carte indisponible</p></div>';
    }
  }
}

function updateMap() {
  initMap();
}

/* ---------------------------------------------------------
   NETWORK BADGE
   --------------------------------------------------------- */
function updateNetworkBadge() {
  var badge = document.getElementById('network-badge');
  if (!badge) return;

  var label = 'Hors ligne';
  if (navigator.onLine) {
    // Try to detect type from connection API if available
    var conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (conn && conn.type) {
      if (conn.type === 'wifi') label = 'WiFi';
      else if (conn.type === 'cellular') label = 'Mobile';
      else label = conn.effectiveType ? conn.effectiveType.toUpperCase() : 'En ligne';
    } else {
      // Fallback: try AndroidBridge
      if (window.AndroidBridge && typeof window.AndroidBridge.getNetworkType === 'function') {
        try {
          var nt = window.AndroidBridge.getNetworkType();
          if (nt === 'WIFI') label = 'WiFi';
          else if (nt === 'MOBILE') label = 'Mobile';
          else label = nt || 'En ligne';
        } catch (e) { label = 'WiFi'; }
      } else {
        label = 'WiFi';
      }
    }
  }
  badge.textContent = label;
}

/* ---------------------------------------------------------
   EMERGENCY BUTTON
   --------------------------------------------------------- */
var HOLD_DURATION = 5000; // 5 seconds
var ringCircumference = 2 * Math.PI * 78; // ~490

function startEmergencyHold(e) {
  e.preventDefault();

  if (emergencyActive) {
    // Tap to deactivate when active
    deactivateEmergency();
    return;
  }

  // Start hold timer
  emergencyRingProgress = 0;
  var fillEl = document.getElementById('progress-ring-fill');
  if (fillEl) fillEl.style.strokeDashoffset = ringCircumference;

  var startTime = Date.now();

  emergencyRingInterval = setInterval(function () {
    var elapsed  = Date.now() - startTime;
    var fraction = Math.min(elapsed / HOLD_DURATION, 1);
    emergencyRingProgress = fraction;

    if (fillEl) {
      fillEl.style.strokeDashoffset = ringCircumference * (1 - fraction);
    }

    if (fraction >= 1) {
      clearInterval(emergencyRingInterval);
      emergencyRingInterval = null;
      activateEmergency();
    }
  }, 50);
}

function cancelEmergencyHold(e) {
  if (emergencyActive) return; // Don't cancel if already active

  if (emergencyRingInterval) {
    clearInterval(emergencyRingInterval);
    emergencyRingInterval = null;
  }

  // Reset ring
  var fillEl = document.getElementById('progress-ring-fill');
  if (fillEl) {
    fillEl.style.transition = 'stroke-dashoffset 0.3s ease';
    fillEl.style.strokeDashoffset = ringCircumference;
    setTimeout(function () {
      fillEl.style.transition = 'stroke-dashoffset 0.05s linear';
    }, 300);
  }
}

function activateEmergency() {
  emergencyActive = true;
  localStorage.setItem('marsel_emergency', '1');
  updateEmergencyUI();

  // Shield logic
  var s = JSON.parse(localStorage.getItem('marsel_settings') || '{}');
  if (window.AndroidBridge) {
    if (s.flash && typeof window.AndroidBridge.activateFlash === 'function') {
      try { window.AndroidBridge.activateFlash(); } catch (e) {}
    }
    if (s.audio && typeof window.AndroidBridge.startAudioRecord === 'function') {
      try { window.AndroidBridge.startAudioRecord(); } catch (e) {}
    }
    if (typeof window.AndroidBridge.vibrate === 'function') {
      try { window.AndroidBridge.vibrate(500); } catch (e) {}
    }
  }
}

function deactivateEmergency() {
  emergencyActive = false;
  localStorage.removeItem('marsel_emergency');
  updateEmergencyUI();

  // Reset ring
  var fillEl = document.getElementById('progress-ring-fill');
  if (fillEl) {
    fillEl.style.transition = 'stroke-dashoffset 0.3s ease';
    fillEl.style.strokeDashoffset = ringCircumference;
    setTimeout(function () { fillEl.style.transition = 'stroke-dashoffset 0.05s linear'; }, 300);
  }

  // Stop shield features
  if (window.AndroidBridge) {
    if (typeof window.AndroidBridge.stopFlash === 'function') {
      try { window.AndroidBridge.stopFlash(); } catch (e) {}
    }
    if (typeof window.AndroidBridge.stopAudioRecord === 'function') {
      try { window.AndroidBridge.stopAudioRecord(); } catch (e) {}
    }
  }
}

function updateEmergencyUI() {
  var btn     = document.getElementById('emergency-btn');
  var inner   = document.getElementById('emergency-btn-inner');
  var label   = document.getElementById('emergency-label');
  var chatFab = document.getElementById('chat-fab');

  if (!btn) return;

  if (emergencyActive) {
    btn.classList.add('active');
    if (label)   label.classList.add('visible');
    if (chatFab) chatFab.classList.add('visible');
    if (inner) {
      inner.innerHTML = [
        '<svg width="70" height="70" viewBox="0 0 70 70" fill="#E84315">',
          '<path d="M42 5L18 38h20l-8 27 28-35H38z"/>',
        '</svg>'
      ].join('');
    }
  } else {
    btn.classList.remove('active');
    if (label)   label.classList.remove('visible');
    if (chatFab) chatFab.classList.remove('visible');
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
   CHAT / MESSAGING
   --------------------------------------------------------- */
function renderChatMessages() {
  var area = document.getElementById('chat-area');
  if (!area) return;

  area.innerHTML = '';

  chatMessages.forEach(function (msg) {
    var bubble = document.createElement('div');
    bubble.className = 'chat-bubble ' + msg.type;
    bubble.textContent = msg.text;
    area.appendChild(bubble);
  });

  // Scroll to bottom
  area.scrollTop = area.scrollHeight;
}

function sendChatMessage() {
  var input = document.getElementById('chat-input');
  if (!input) return;

  var text = input.value.trim();
  if (!text) return;

  chatMessages.push({ type: 'sent', text: text });
  input.value = '';
  renderChatMessages();

  // Mock auto-reply after 1s
  setTimeout(function () {
    chatMessages.push({ type: 'received', text: 'Message reçu. Une assistance est en route.' });
    renderChatMessages();
  }, 1200);
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
    'animation:toastIn 0.3s ease;'
  ].join('');

  // Add keyframes if not present
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
    setTimeout(function () { toast.remove(); }, 300);
  }, 2200);
}

/* ---------------------------------------------------------
   UTILITY
   --------------------------------------------------------- */
function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/* ---------------------------------------------------------
   WIFI DIRECT BRIDGE (legacy compatibility)
   --------------------------------------------------------- */
window.recevoirWifiMessage = function (messageJson) {
  try {
    var data = JSON.parse(messageJson);
    var text = (data && data.message) ? data.message : messageJson;
    chatMessages.push({ type: 'received', text: text });
    var area = document.getElementById('chat-area');
    if (area) renderChatMessages();
  } catch (e) {
    console.warn('WiFi message parse error:', e);
  }
};

/* ---------------------------------------------------------
   INIT ON LOAD
   --------------------------------------------------------- */
document.addEventListener('DOMContentLoaded', function () {

  // Show splash, then check auth
  setTimeout(function () {
    var userData = JSON.parse(localStorage.getItem('marsel_user') || 'null');
    var wasEmergency = localStorage.getItem('marsel_emergency') === '1';

    if (userData && userData.loggedIn) {
      // Already logged in – go to home
      if (wasEmergency) {
        emergencyActive = true;
      }
      screenHistory = [];
      showScreen('screen-home');
    } else {
      screenHistory = [];
      showScreen('screen-auth');
    }
  }, 2500);

  // Network change listener
  window.addEventListener('online',  function () { updateNetworkBadge(); });
  window.addEventListener('offline', function () { updateNetworkBadge(); });
});
