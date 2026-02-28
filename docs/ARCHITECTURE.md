# Architecture technique — TABBI_DEV_GNSS

## 1. Vue d’ensemble

L’application est une solution Android native (Kotlin) monomodule (`app`) orientée opérations topographiques GNSS : acquisition, calcul, visualisation cartographique et export.

Principes clés :
- **SAF-first** pour la persistance (pas d’accès direct au système de fichiers classique).
- **Orchestration centrale dans `MainActivity`** avec modules spécialisés.
- **Séparation progressive** des responsabilités métier (Bluetooth, parsing, carte, stockage, export).

---

## 2. Couches logiques

## 2.1 Présentation
- Activités de workflow :
  - `ChantierHomeActivity`
  - `MainActivity`
  - `AutoSurveyActivity`
  - `AreaSurveyActivity`
  - `IntersectActivity`
  - `TopoPlanActivity`
- Fragments d’écran dans `MainActivity` : navigation, gestion, programmes topo, survey, implantation, map.

## 2.2 Domaine GNSS / Topo
- `NmeaParser` : transforme les trames NMEA en données utiles.
- `GnssStateStore` : état courant GNSS (lat/lon/alt/fix/satellites).
- `CoordinateTransformer`, `GeoConversions` : transformations de coordonnées.
- Contrôleurs topo : `AutoSurveyController`, `AreaSurveyController`.

## 2.3 Infrastructure
- `BluetoothGnssClient` : connexion, lecture flux, gestion reconnexion.
- `MapController` : overlays/trace/marqueurs/compas/suivi carte.
- `InctFileManager`, `LevePointStore`, `TopoPlanPolylineStore` : CSV et polylignes.
- `DxfWriter` : export DXF R12.

---

## 3. Flux principaux

## 3.1 Démarrage & contexte chantier
1. L’utilisateur choisit un dossier Documents (permission persistée).
2. L’app prépare `TOPOGRAPHIE/` puis sélectionne un projet.
3. L’utilisateur ouvre/crée un chantier.
4. Les URIs SAF sont propagées aux écrans métier.

## 3.2 Acquisition GNSS
1. `BluetoothGnssClient` lit les trames NMEA.
2. `NmeaParser` décode `GGA`/`RMC`/`GSV`.
3. `MainActivity` met à jour l’état GNSS + UI + broadcasts locaux.
4. Les modules (superficie, intersection, etc.) consomment les données courantes.

## 3.3 Levé
1. Un levé CSV est ouvert/créé via `InctFileManager`.
2. Chaque point enregistré contient INCT + WGS84 + métadonnées.
3. Les points sont relus via `LevePointStore` pour implantation/édition/export.

## 3.4 Export DXF
1. Les points/polylignes sont assemblés en entités topo.
2. `DxfWriter` génère un fichier R12 avec couches thématiques.
3. Le fichier est écrit dans le chantier via URI SAF.

---

## 4. Contrat de données CSV

- Ligne de métadonnée CRS : `# CRS: <signature>`.
- En-tête standard :

```text
id,xinct,yinct,zinct,zone,timestamp,lat,lon,alt,type,xwgs84_utm,ywgs84_utm
```

Règles de compatibilité :
- colonnes historiques conservées,
- toute nouvelle colonne est ajoutée en fin,
- stockage principal INCT maintenu en tête.

---

## 5. Dépendances techniques

- AndroidX (core, appcompat, activity, lifecycle)
- Material Components
- OSMDroid
- LocalBroadcastManager
- Kotlin Coroutines
- Proj4J + base EPSG

JVM target / Java : 17.

---

## 6. Points d’attention mainteneurs

- `MainActivity` reste volumineuse : privilégier l’extraction continue vers services dédiés.
- Toute évolution CSV doit préserver la rétrocompatibilité des levés existants.
- Les permissions Bluetooth Android 12+ (`SCAN`, `CONNECT`) doivent rester gérées explicitement.
- Les écrans terrain doivent conserver des actions robustes hors-ligne.
