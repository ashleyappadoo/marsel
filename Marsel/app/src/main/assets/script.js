document.addEventListener("DOMContentLoaded", () => {

  const MESSAGE_STATUS = {
    CREATED: "CREATED",
    DIRECT_SENT: "DIRECT_SENT",
    RELAY_SENT: "RELAY_SENT",
    RECEIVED_BY_PEER: "RECEIVED_BY_PEER",
    RELAYED_TO_WEBHOOK: "RELAYED_TO_WEBHOOK",
    FAILED_NO_NETWORK: "FAILED_NO_NETWORK"
  };

  const WEBHOOK_URL =
    "https://hook.eu1.make.com/iegqig2ojgqogoc219mubbappd2khwcd";

  const logs = document.getElementById("logs");
  const tableVoisins = document.getElementById("tableVoisins");
  const userIdDisplay = document.getElementById("userIdDisplay");
  const networkDisplay = document.getElementById("networkStatus");
  const btnUrgence = document.getElementById("btnUrgence");

  const messageInput = document.getElementById("messageInput");
  const sendMessageBtn = document.getElementById("sendMessageBtn");
  const messagesContainer = document.getElementById("messages");

  function log(message) {

    if (!logs) return;

    const ligne = document.createElement("div");

    ligne.textContent =
      new Date().toLocaleTimeString() +
      " - " +
      message;

    logs.appendChild(ligne);

    logs.scrollTop = logs.scrollHeight;
  }

  let userId = localStorage.getItem("userId");

  if (
    !userId ||
    userId === "null" ||
    userId === "undefined"
  ) {

    userId =
      "user-" +
      Date.now() +
      "-" +
      Math.random()
        .toString(36)
        .substring(2, 10);

    localStorage.setItem(
      "userId",
      userId
    );
  }

  if (userIdDisplay) {

    userIdDisplay.textContent =
      "User ID : " + userId;
  }

  function getNetworkType() {

    if (!navigator.onLine) {
      return "Hors ligne";
    }

    const connection =
      navigator.connection ||
      navigator.mozConnection ||
      navigator.webkitConnection;

    if (
      connection &&
      connection.effectiveType
    ) {

      return connection.effectiveType;
    }

    return "Connecté";
  }

  function updateNetworkDisplay() {

    if (!networkDisplay) return;

    const network = getNetworkType();

    if (network === "Hors ligne") {

      networkDisplay.textContent =
        "❌ Hors ligne";

      networkDisplay.className =
        "offline";

    } else {

      networkDisplay.textContent =
        "🌐 " + network;

      networkDisplay.className =
        "online";
    }
  }

  function afficherMessage(data) {

    if (!messagesContainer) return;

    const div =
      document.createElement("div");

    div.className = "message";

    div.innerHTML = `
      <strong>
        ${data.sourceUserId || "Utilisateur"}
      </strong>
      <br>

      <span>
        ${data.message}
      </span>

      <br>

      <small>
        ${new Date(
          data.timestamp
        ).toLocaleTimeString()}
      </small>
    `;

    messagesContainer.appendChild(div);

    messagesContainer.scrollTop =
      messagesContainer.scrollHeight;
  }

  function stockerMessageLocal(data) {

    const messages =
      JSON.parse(
        localStorage.getItem(
          "messagesLocaux"
        )
      ) || [];

    messages.push({
      ...data,
      status:
        MESSAGE_STATUS.FAILED_NO_NETWORK,
      storedAt:
        new Date().toISOString()
    });

    localStorage.setItem(
      "messagesLocaux",
      JSON.stringify(messages)
    );

    log(
      "📦 Message stocké localement"
    );
  }

  async function envoyerDirectWebhook(
    data
  ) {

    try {

      const response = await fetch(
        WEBHOOK_URL,
        {
          method: "POST",

          headers: {
            "Content-Type":
              "application/json"
          },

          body: JSON.stringify(data)
        }
      );

      return response.ok;

    } catch {

      return false;
    }
  }

  async function envoyerVersB(data) {

    try {

      const response = await fetch(
        "/api/voisins",
        {
          method: "POST",

          headers: {
            "Content-Type":
              "application/json"
          },

          body: JSON.stringify(data)
        }
      );

      return response.ok;

    } catch {

      return false;
    }
  }

  async function envoyerMessageAuto() {

    const baseData = {
      id: userId,
      sourceUserId: userId,
      relayDeviceId: "web-client",
      network: getNetworkType(),
      message: "Hello Marsel",
      timestamp:
        new Date().toISOString()
    };

    if (navigator.onLine) {

      const directSent =
        await envoyerDirectWebhook({
          ...baseData,
          status:
            MESSAGE_STATUS.DIRECT_SENT
        });

      if (directSent) {

        log(
          "✅ A connecté → direct webhook"
        );

        await envoyerVersB({
          ...baseData,
          status:
            MESSAGE_STATUS.DIRECT_SENT
        });

        chargerVoisins();

        return;
      }

      log(
        "⚠️ Direct webhook échoué"
      );
    }

    const relayData = {
      ...baseData,
      status:
        MESSAGE_STATUS.RELAY_SENT
    };

    const relaySent =
      await envoyerVersB(relayData);

    if (relaySent) {

      log(
        "📡 Message relayé vers B"
      );

    } else {

      stockerMessageLocal(
        relayData
      );
    }
  }

  async function envoyerMessageTexte(
    message
  ) {

    const data = {
      sourceUserId: userId,
      message,
      timestamp:
        new Date().toISOString()
    };

    afficherMessage(data);

    if (window.AndroidWifiDirect) {

      window.AndroidWifiDirect
        .sendWifiMessage(message);

      log(
        "📶 Message envoyé en Wi-Fi Direct"
      );
    }

    if (navigator.onLine) {

      await envoyerDirectWebhook({
        ...data,
        status:
          MESSAGE_STATUS.DIRECT_SENT
      });
    }
  }

  async function chargerVoisins() {

    try {

      const response = await fetch(
        "/api/voisins"
      );

      const voisins =
        await response.json();

      afficherTable(voisins);

    } catch {

      log(
        "Erreur récupération voisins"
      );
    }
  }

  function afficherTable(voisins) {

    if (!tableVoisins) return;

    tableVoisins.innerHTML = "";

    if (
      !voisins ||
      voisins.length === 0
    ) {

      tableVoisins.innerHTML = `
        <tr>
          <td colspan="6">
            Aucun voisin détecté
          </td>
        </tr>
      `;

      return;
    }

    voisins.forEach(v => {

      const tr =
        document.createElement("tr");

      tr.innerHTML = `
        <td>${v.sourceUserId || ""}</td>

        <td>${v.relayDeviceId || ""}</td>

        <td>${v.network || ""}</td>

        <td>
          ${v.status || ""}
        </td>

        <td>
          ${
            v.firstSeen
              ? new Date(
                  v.firstSeen
                ).toLocaleTimeString()
              : ""
          }
        </td>

        <td>
          ${
            v.lastSeen
              ? new Date(
                  v.lastSeen
                ).toLocaleTimeString()
              : ""
          }
        </td>
      `;

      tableVoisins.appendChild(tr);
    });
  }

  function gererUrgenceSansGps(
    error
  ) {

    log(
      "❌ GPS refusé : " +
      error.message
    );

    alert(
      "GPS refusé ou indisponible"
    );
  }

  function loop() {

    updateNetworkDisplay();

    envoyerMessageAuto();

    chargerVoisins();
  }

  setInterval(loop, 5000);

  loop();

  window.addEventListener(
    "online",
    updateNetworkDisplay
  );

  window.addEventListener(
    "offline",
    updateNetworkDisplay
  );

  if (
    sendMessageBtn &&
    messageInput
  ) {

    sendMessageBtn
      .addEventListener(
        "click",
        () => {

          const texte =
            messageInput.value.trim();

          if (!texte) return;

          envoyerMessageTexte(
            texte
          );

          messageInput.value = "";
        }
      );
  }

  window.recevoirWifiMessage =
    function(message) {

      try {

        const data =
          JSON.parse(message);

        afficherMessage({
          sourceUserId:
            data.sourceUserId,

          message:
            "📶 WIFI DIRECT : " +
            data.message,

          timestamp:
            new Date().toISOString()
        });

        log(
          "📶 Message Wi-Fi Direct reçu"
        );

      } catch(e) {

        console.error(e);
      }
    };

  if (btnUrgence) {

    btnUrgence.addEventListener(
      "click",
      () => {

        log(
          "🚨 Urgence déclenchée"
        );

        if (
          navigator.vibrate
        ) {

          navigator.vibrate([
            200,
            100,
            200
          ]);
        }

        if (
          !navigator.geolocation
        ) {

          gererUrgenceSansGps({
            message:
              "GPS non supporté"
          });

          return;
        }

        navigator.geolocation
          .getCurrentPosition(

            async position => {

              const lat =
                position.coords.latitude;

              const lon =
                position.coords.longitude;

              alert(
                "🚨 URGENCE\n\n" +
                "Latitude : " +
                lat +
                "\nLongitude : " +
                lon
              );

              window.location.href =
                "tel:112";
            },

            gererUrgenceSansGps
          );
      }
    );
  }
});