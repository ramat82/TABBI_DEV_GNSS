# TABBI_DEV_GNSS

Application Android de topographie GNSS orientée terrain (levé, implantation, superficie, intersection, export DXF/CSV) avec connexion à un récepteur GNSS Bluetooth et cartographie OSM.

---

## 1) Objectif du projet

`TABBI_DEV_GNSS` fournit une base opérationnelle pour les équipes topographiques qui doivent :
- connecter un rover GNSS en Bluetooth,
- acquérir des points de levé avec horodatage,
- exécuter des programmes topo (auto, superficie, intersection, implantation),
- organiser les données par **projet** et **chantier**,
- exporter les résultats pour DAO/CAO.

L’application est structurée autour d’un stockage SAF (`Documents/TOPOGRAPHIE`) pour rester compatible avec les politiques Android modernes.

---

## 2) Fonctionnalités principales

### Acquisition GNSS
- Connexion Bluetooth au récepteur GNSS externe.
- Parsing NMEA (`GGA`, `RMC`, `GSV`) pour position, altitude, qualité de fix et satellites.
- Reconnexion automatique en cas de perte du lien Bluetooth.

### Programmes topo
- **Levé manuel** avec enregistrement de points.
- **Levé auto** (mode distance ou temps), avec filtres précision/vitesse.
- **Superficie** avec visualisation carte plein écran et calcul de surface en temps réel.
- **Intersection** avec sélection/mesure de points A-B-C-D.
- **Implantation / navigation** via vues dédiées.
- **Plan topo** avec gestion de polylignes.

### Données & export
- CSV de levé avec métadonnée CRS et colonnes INCT/WGS84.
- Lecture de points de levé et candidats station.
- Export DXF (R12) avec entités points, textes, polylignes et couches thématiques.

### Expérience opérateur
- Carte OSMDroid (OSM) avec suivi, traces, marqueurs, compas.
- Voix/retours audio pour assistance terrain.
- Organisation projet > chantier depuis écran d’accueil.

---

## 3) Architecture rapide

- **Entrée application** : `ChantierHomeActivity` (sélection Documents, projet, chantier).
- **Noyau terrain** : `MainActivity` (GNSS, UI multi-écrans, commande des workflows).
- **Modules spécialisés** : `AreaSurveyActivity`, `AutoSurveyActivity`, `IntersectActivity`, `TopoPlanActivity`.
- **Services métier** :
  - `BluetoothGnssClient` (liaison NMEA),
  - `NmeaParser` (décodage trames),
  - `MapController` (état cartographique),
  - `InctFileManager` / `LevePointStore` / `TopoPlanPolylineStore` (fichiers et points),
  - `DxfWriter` (export CAO).

Voir la documentation détaillée dans [`docs/`](docs/).

---

## 4) Prérequis techniques

- Android Studio récent (AGP 8.x, Kotlin 2.x).
- JDK 17.
- SDK Android :
  - `compileSdk = 35`
  - `minSdk = 24`
  - `targetSdk = 35`

Permissions utilisées : localisation, Bluetooth (`SCAN`, `CONNECT`, etc.), audio, Internet.

---

## 5) Installation & lancement

```bash
git clone <repo-url>
cd TABBI_DEV_GNSS
./gradlew assembleDebug
```

Pour exécuter les tests unitaires :

```bash
./gradlew test
```

Pour installer sur un appareil Android connecté :

```bash
./gradlew installDebug
```

---

## 6) Structure des données (SAF)

Hiérarchie cible :

```text
Documents/
└── TOPOGRAPHIE/
    └── <projet>_proj/
        ├── project_settings.json
        ├── REPERES/
        └── <chantier>/
            ├── *.csv   (levés)
            └── *.dxf   (exports)
```

Le CSV de levé inclut une ligne méta CRS puis un en-tête colonnes.

---

## 7) Développement

### Commandes utiles
- Build debug : `./gradlew assembleDebug`
- Tests unitaires : `./gradlew test`
- Lint Android : `./gradlew lint`

### Bonnes pratiques
- Conserver la compatibilité des colonnes CSV (ajouter en fin de ligne uniquement).
- Maintenir le stockage INCT dans les colonnes de base (`xinct`,`yinct`,`zinct`).
- Limiter les régressions UI terrain (boutons critiques : start/pause/stop/enregistrer).

---

## 8) Documentation complémentaire

- [Architecture technique](docs/ARCHITECTURE.md)
- [Guide d’exploitation terrain](docs/WORKFLOWS.md)

---

## 9) État du projet

Version applicative actuelle déclarée : **1.0.3-dev**.

Ce dépôt sert de base active pour les évolutions GNSS/topographie Android.
