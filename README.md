# HA Shield Control

Application Android / Android TV (NVIDIA Shield) pour piloter une instance
**Home Assistant** depuis la télévision : lumières, thermostats et état des
entités, avec découverte automatique du serveur sur le réseau local et
connexion sans recopier de jeton.

- Dépôt : <https://github.com/guillian77/ha-shield-controller>
- Dernière version : <https://github.com/guillian77/ha-shield-controller/releases/latest>
- Spécifications : [`docs/specifications.md`](docs/specifications.md)

## Fonctionnalités

| Domaine | Détail |
|---|---|
| Découverte | Recherche mDNS du service `_home-assistant._tcp.` sur le réseau local, liste des instances trouvées, sélection en un clic |
| Authentification | Connexion via la page de login Home Assistant dans une WebView intégrée (aucun navigateur requis), ou token longue durée collé/saisi |
| Session | Rafraîchissement automatique du jeton OAuth (30 min) et rejeu de la requête sur `401` |
| Dashboard | Cartes des entités sélectionnées : interrupteur pour les lumières, consigne − / + et presets pour les thermostats |
| Sélection | Choisir les entités affichées, avec compteur et raccourcis « Tout » / « Aucun » |
| Mises à jour | Détection d'une nouvelle version, téléchargement et lancement de l'installateur depuis l'application |
| Sauvegarde | Export/import de la configuration (URL, jetons, sélection) vers un fichier JSON |
| Télécommande | Navigation complète à la croix directionnelle, pas d'ouverture intempestive du clavier |

## Installation

### NVIDIA Shield / Android TV (Downloader)

1. Autoriser les sources inconnues : *Réglages → Applications → Downloader →
   Autoriser l'installation d'applications inconnues*.
2. Dans l'application **Downloader**, saisir l'URL :

   ```
   https://github.com/guillian77/ha-shield-controller/releases/latest/download/app-debug.apk
   ```

3. Lancer l'installation. Une fois installée, l'application propose elle-même
   les mises à jour (bouton « Mettre à jour x.y.z » en haut de l'écran).

### ADB

```bash
adb install -r app-debug.apk
```

## Configuration

1. **Serveur** — bouton *Détecter sur le réseau*. Si le serveur n'est pas
   annoncé en mDNS, saisir l'URL manuellement (`http://192.168.x.x:8123`).
2. **Connexion** — *Connexion Home Assistant* ouvre la page de login de
   l'instance : après identification, les jetons OAuth sont récupérés
   automatiquement et la session se renouvelle seule.
   Repli possible : *Coller un token* (presse-papiers) ou *Saisir un token*
   (token longue durée créé dans *Profil → Sécurité → Jetons d'accès longue
   durée*).
3. **Sélection** — cocher les entités à afficher, puis revenir au dashboard.

L'URL et la sélection sont conservées lors des mises à jour. Utiliser
*Exporter* avant une désinstallation : le fichier JSON permet de tout restaurer
avec *Importer*.

## Développement

### Prérequis

- JDK 17
- Android SDK 34 (`platforms;android-34`, `build-tools;34.0.0`)

### Build

```bash
./gradlew assembleDebug          # APK debug
./gradlew assembleRelease        # APK release (non minifié)
```

L'APK est produit dans `app/build/outputs/apk/debug/app-debug.apk`.

Les versions sont injectées par la CI ; en local, les valeurs par défaut sont
`versionCode 1` / `versionName 1.0` :

```bash
./gradlew assembleDebug -PversionCode=42 -PversionName=1.0.42
```

### Structure

```
app/src/main/java/com/dekadmin/hastield/
├── ha/
│   ├── Discovery.kt          # découverte mDNS (_home-assistant._tcp.)
│   ├── HaAuth.kt             # jetons OAuth : import, rafraîchissement
│   ├── HomeAssistantApi.kt   # REST /api/states et /api/services (retry 401)
│   └── SettingsManager.kt    # stockage config (clair) + jetons (chiffré)
├── ui/MainActivity.kt        # écrans Compose : dashboard, réglages, login
└── update/Updater.kt         # vérification de version, téléchargement, install
```

### Pile technique

| Élément | Version |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7 |
| Kotlin | 1.9.24 |
| Compose compiler | 1.5.14 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |

## Intégration continue

`.github/workflows/build_apk.yml` : à chaque push sur `main`, build de l'APK,
publication de l'artefact et mise à jour de la release `latest` (APK +
`version.json` utilisé par la vérification de mise à jour).

`versionCode` = numéro de run GitHub Actions, `versionName` = `1.0.<run>`.

### Signature (obligatoire pour les mises à jour)

Android refuse d'installer un APK dont le certificat diffère de celui de la
version installée. Les runners GitHub régénèrent la clé debug à chaque job : il
faut fournir une clé stable via les secrets du dépôt.

```bash
keytool -genkeypair -v -keystore hastield.jks -alias hastield \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 hastield.jks > keystore.b64

gh secret set ANDROID_KEYSTORE_BASE64 < keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD   # mot de passe du keystore
gh secret set ANDROID_KEY_ALIAS           # hastield
gh secret set ANDROID_KEY_PASSWORD        # mot de passe de la clé
```

Conserver `hastield.jks` : sans lui, aucune mise à jour ne pourra plus être
installée par-dessus l'application existante. Le premier passage à cette clé
impose une réinstallation (désinstallation préalable) — exporter la
configuration avant.

## Dépannage

| Symptôme | Cause probable |
|---|---|
| `Connexion impossible` avec une URL correcte | instance en `http://` : le trafic clair est autorisé par l'app, vérifier le port (8123) et le réseau |
| Aucun serveur détecté | mDNS filtré par le routeur / VLAN IoT, ou intégration `discovery` désactivée → saisir l'URL à la main |
| Déconnexion après ~30 min | pas de `refresh_token` (token longue durée utilisé) : se reconnecter via la page de login |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | APK signé avec une autre clé : configurer les secrets ci-dessus, désinstaller puis réinstaller |
| Mise à jour absente | comparer la version affichée sous le titre avec celle de `version.json` |

## Licence

Projet personnel, non publié sur le Play Store.
