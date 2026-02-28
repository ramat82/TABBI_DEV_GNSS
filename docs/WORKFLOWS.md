# Guide d’exploitation terrain — TABBI_DEV_GNSS

## 1. Préparation initiale

1. Ouvrir l’application.
2. Dans l’écran d’accueil chantiers, sélectionner le dossier **Documents**.
3. Créer ou sélectionner un **projet** (`<nom>_proj`).
4. Créer ou ouvrir un **chantier**.

> Recommandation : standardiser les noms de projet/chantier pour faciliter les exports et l’archivage.

---

## 2. Connexion GNSS Bluetooth

1. Activer Bluetooth et localisation sur l’appareil Android.
2. Appairer le récepteur GNSS (si nécessaire).
3. Depuis l’écran GNSS, sélectionner l’appareil.
4. Vérifier la réception des trames (fix, satellites, coordonnées).

Bonnes pratiques :
- attendre un fix stable avant enregistrement,
- surveiller les satellites et la précision.

---

## 3. Levé manuel

1. Ouvrir/créer le fichier de levé CSV.
2. Renseigner l’identifiant de point (ou utiliser l’incrémentation).
3. Appuyer sur **Enregistrer**.
4. Contrôler la présence du point dans la liste/carte.

Chaque point enregistré contient :
- coordonnées INCT (`xinct`,`yinct`,`zinct`),
- WGS84 (`lat`,`lon`,`alt`),
- type et horodatage.

---

## 4. Levé automatique

1. Ouvrir **AUTO**.
2. Choisir le mode :
   - distance (ex. tous les 5 m), ou
   - temps (ex. toutes les 2 s).
3. Optionnel : activer filtres précision et vitesse.
4. Démarrer, puis utiliser pause/stop selon la progression terrain.

Cas recommandé : linéaires routiers et acquisitions régulières.

---

## 5. Superficie

1. Ouvrir **SUPERFICIE**.
2. Démarrer la session.
3. Ajouter les sommets en manuel ou passer en auto.
4. Lire la surface calculée en temps réel.
5. Clore la session.

Conseil : vérifier visuellement la fermeture du contour pour valider le résultat.

---

## 6. Intersection

1. Ouvrir **INTERSECT**.
2. Définir A-B-C-D via :
   - choix de points existants, ou
   - mesure GNSS instantanée.
3. Lancer l’action d’implantation ou d’enregistrement.

---

## 7. Export et exploitation

## 7.1 CSV
- Fichier source de levé, exploitable pour post-traitement.
- Inclut signature CRS en tête.

## 7.2 DXF
- Export destiné aux logiciels DAO/CAO.
- Contient points, textes, polylignes et couches topo.

Avant livraison :
- vérifier nommage chantier/fichiers,
- vérifier cohérence des types/natures de points,
- archiver le dossier chantier complet.

---

## 8. Dépannage rapide

- **Pas de fix GNSS** : vérifier antenne, visibilité ciel, port série/flux NMEA.
- **Bluetooth instable** : rapprocher les appareils, vérifier batterie du rover.
- **Aucun point visible** : confirmer que le bon levé est ouvert.
- **Export incomplet** : vérifier permissions SAF et espace de stockage.
