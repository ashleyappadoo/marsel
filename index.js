const express = require("express");
const path = require("path");

const app = express();

app.use(express.json());

app.use(express.static(__dirname));

let voisins = [];

app.get("/api/voisins", (req, res) => {
  res.json(voisins);
});

app.post("/api/voisins", (req, res) => {

  const data = {
    ...req.body,
    firstSeen: new Date(),
    lastSeen: new Date()
  };

  voisins.push(data);

  res.json({
    success: true
  });
});

app.get("/", (req, res) => {
  res.sendFile(
    path.join(__dirname, "app.html")
  );
});

app.listen(3000, "0.0.0.0", () => {
  console.log("Marsel lancé sur port 3000");
});