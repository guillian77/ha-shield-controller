# Spécifications — HA Shield Control

Version du document : 1.0
Application : `com.dekadmin.hastield` — Android / Android TV
Cible principale : NVIDIA Shield (télécommande, écran 1080p, réseau domestique)

---

## 1. Contexte

Le foyer dispose d'une instance Home Assistant hébergée sur le réseau local.
L'accès habituel passe par un navigateur (téléphone ou ordinateur), peu pratique
depuis un téléviseur. L'objectif est une application dédiée au Shield permettant
de consulter et d'actionner les équipements les plus utilisés (éclairage,
chauffage) sans quitter la télécommande.

Le contexte impose trois contraintes fortes :

- **saisie difficile** : pas de clavier physique, la télécommande est le seul
  périphérique ;
- **réseau local** : l'instance est joignable en HTTP sur le LAN, sans certificat
  ni nom de domaine public ;
- **instantanéité** : l'état affiché doit refléter le serveur à quelques secondes
  près.

## 2. Objectifs

| Réf. | Objectif | Indicateur de réussite |
|---|---|---|
| OBJ-1 | Piloter les équipements principaux depuis le canapé | < 3 actions télécommande pour allumer une lumière |
| OBJ-2 | Supprimer la saisie de jetons sur le téléviseur | 0 caractère saisi lors d'une première connexion nominale |
| OBJ-3 | Rendre l'application utilisable sans documentation | serveur détecté automatiquement en < 10 s sur un réseau standard |
| OBJ-4 | Garantir la continuité de service | mise à jour applicable en 2 clics, sans perte de configuration |

## 3. Périmètre

### Inclus

- découverte des instances Home Assistant du réseau local ;
- authentification OAuth via la page de login de l'instance, repli par jeton ;
- tableau de bord des entités `light.*` et `climate.*` sélectionnées ;
- commande d'allumage/extinction des lumières ;
- sélection des entités affichées ;
- vérification et installation des mises à jour de l'application ;
- export/import de la configuration.

### Exclu

- pilotage complet de Home Assistant (scènes, scripts, automatisations,
  caméras, médias) ;
- réglage de la consigne de température (lecture seule des thermostats) ;
- notification push, widgets, commande vocale ;
- accès distant (hors LAN), HTTPS avec certificat, authentification multi-utilisateur ;
- publication sur le Play Store.

## 4. Acteurs

| Acteur | Description | Besoins |
|---|---|---|
| Utilisateur principal | occupant du foyer, devant la télévision, télécommande en main | voir et actionner rapidement, ne rien configurer deux fois |
| Administrateur Home Assistant | crée les jetons, autorise l'application, gère l'instance | révocabilité, aucune donnée d'identification stockée en clair |
| Administrateur applicatif | publie les versions (dépôt GitHub) | build reproductible, release automatique |
| Système | Android TV, Home Assistant, GitHub Releases | interfaces stables |

## 5. Besoins métier

- **BM-1 — Simplicité d'usage.** L'application ne doit exposer que ce qui est
  utile au quotidien : aucune notion d'API, de jeton ou de port visible sans
  nécessité.
- **BM-2 — Autonomie de configuration.** L'utilisateur doit pouvoir installer et
  connecter l'application sans document technique préalable.
- **BM-3 — Souveraineté des données.** L'application ne communique qu'avec
  l'instance Home Assistant choisie et le dépôt de publication ; aucun service
  tiers, aucune télémétrie.
- **BM-4 — Pérennité.** Une mise à jour de l'application ne doit jamais coûter
  une reconfiguration ni une reconnexion.
- **BM-5 — Sobriété.** Sur un boîtier de salon, l'application doit rester légère
  et afficher un état à jour sans solliciter le serveur de façon excessive.

## 6. Règles métier

| Réf. | Règle |
|---|---|
| RM-1 | Seules les entités des domaines `light` et `climate` sont proposées à l'affichage. |
| RM-2 | Une entité peut être pilotée (lumière) ou seulement consultée (thermostat). |
| RM-3 | Une lumière est considérée allumée lorsque son état est exactement `on`. |
| RM-4 | La sélection d'entités est propre à l'appareil et persiste entre les sessions. |
| RM-5 | L'URL du serveur et la sélection sont des données non sensibles : elles sont conservées même si le stockage sécurisé devient indisponible. |
| RM-6 | Les jetons d'authentification sont des données sensibles : jamais en clair sur disque, jamais journalisés. |
| RM-7 | Une commande n'est appliquée qu'après confirmation d'une session valide ; en cas de rejet (401), la session est renouvelée une fois avant abandon. |
| RM-8 | Le rafraîchissement de l'état du serveur se fait toutes les 5 secondes, uniquement si une session existe. |
| RM-9 | Une mise à jour n'est proposée que si le `versionCode` distant est strictement supérieur à celui installé. |
| RM-10 | L'utilisateur reste maître de l'installation : aucun APK n'est installé sans passer par l'installateur système. |

## 7. Exigences fonctionnelles

### EF-01 — Découverte des serveurs

**Description.** L'utilisateur lance une recherche ; l'application liste les
instances Home Assistant présentes sur le réseau local.

**Règles.** Recherche du service mDNS `_home-assistant._tcp.` ; résolution
sérialisée (une à la fois) ; durée maximale 8 s ; résultats dédoublonnés sur
l'URL ; format d'URL proposé `http://<adresse>:<port>`.

**Critères d'acceptation.**
- un serveur annoncé en mDNS apparaît en moins de 10 s ;
- cliquer un résultat remplit l'URL du serveur ;
- l'absence de résultat est signalée par la fin de la recherche, sans blocage
  de l'interface ;
- la recherche peut être interrompue par l'utilisateur.

### EF-02 — Connexion via la page Home Assistant

**Description.** L'utilisateur s'authentifie dans l'interface Home Assistant
affichée dans l'application ; la session est ensuite gérée automatiquement.

**Règles.** La page de l'instance est ouverte dans une vue web intégrée
(JavaScript et stockage local activés). Après identification, les jetons OAuth
(`access_token`, `refresh_token`, identifiant client, échéance) sont récupérés
et stockés de façon chiffrée. Le jeton d'accès est rafraîchi automatiquement
avant expiration.

**Critères d'acceptation.**
- après saisie des identifiants Home Assistant, l'application affiche les
  entités sans manipulation supplémentaire ;
- aucune donnée n'est à recopier sur le téléviseur dans le cas nominal ;
- la session reste valide au-delà de 30 minutes d'utilisation continue ;
- si les jetons ne sont pas récupérables (instance sans login, stockage
  chiffré indisponible), l'utilisateur en est informé et peut saisir un jeton.

### EF-03 — Connexion par jeton

**Description.** Repli manuel : coller un jeton depuis le presse-papiers ou le
saisir dans une boîte de dialogue.

**Règles.** La saisie d'un jeton manuel annule la session OAuth en cours
(le jeton longue durée n'expire pas, il ne doit pas être rafraîchi).

**Critères d'acceptation.** un jeton longue durée valide donne accès aux
entités ; la saisie n'ouvre le clavier que si l'utilisateur ouvre explicitement
la boîte de dialogue.

### EF-04 — Tableau de bord

**Description.** Affichage des entités sélectionnées sous forme de cartes.

**Règles.** Nom convivial et état affichés ; lumière → interrupteur ; thermostat
→ températures actuelle et cible en lecture seule. Rafraîchissement toutes les
5 s. En l'absence de sélection ou de connexion, un message explicite est
affiché.

**Critères d'acceptation.**
- l'état affiché suit une modification faite hors application en moins de 10 s ;
- actionner l'interrupteur déclenche `light.turn_on` / `light.turn_off` ;
- une erreur de connexion est signalée sans effacer la configuration.

### EF-05 — Sélection des entités

**Description.** L'utilisateur choisit les entités visibles sur le dashboard.

**Règles.** Liste défilante, une ligne par entité (nom, identifiant, état) ;
case à cocher ; compteur « sélectionnées / total » ; actions « Tout » et
« Aucun ». Enregistrement immédiat.

**Critères d'acceptation.**
- toutes les entités reçues sont atteignables à la télécommande ;
- la sélection survit au redémarrage de l'application et à une mise à jour ;
- le compteur reflète la sélection en temps réel.

### EF-06 — Mise à jour de l'application

**Description.** L'application compare sa version à celle publiée et propose
l'installation.

**Règles.** Version distante lue depuis un manifeste `version.json` publié avec
l'APK. Si `versionCode` distant > local : bouton « Mettre à jour x.y.z » visible
en en-tête et dans les réglages. Téléchargement dans le cache privé, puis
ouverture de l'installateur système.

**Critères d'acceptation.**
- le bouton n'apparaît que lorsqu'une version supérieure existe ;
- la version installée est visible sous le titre de l'écran ;
- l'échec (réseau, signature) est signalé par un message lisible.

### EF-07 — Sauvegarde de la configuration

**Description.** Export et import de la configuration via un fichier JSON choisi
par l'utilisateur.

**Règles.** Le fichier contient l'URL, les jetons, l'identifiant client et la
sélection. L'import remplace la configuration existante ; un fichier invalide est
refusé sans modification.

**Critères d'acceptation.** export puis import sur une installation vierge
restaure URL, session et sélection ; un fichier corrompu affiche une erreur et ne
change rien.

### EF-08 — Navigation à la télécommande

**Description.** L'ensemble de l'interface est utilisable avec la croix
directionnelle et le bouton de validation.

**Règles.** Aucun champ de saisie ne prend le focus automatiquement ; un bouton
n'est jamais désactivé pendant une action en cours (le focus sauterait en haut de
l'écran) ; les listes défilent avec la télécommande.

**Critères d'acceptation.** un parcours complet (réglages → détection →
sélection → dashboard) est réalisable sans perdre le focus ni ouvrir le clavier
de façon inattendue.

## 8. Exigences non fonctionnelles

| Réf. | Exigence |
|---|---|
| ENF-1 | Compatibilité Android 8.0 (API 26) à Android 14 (API 34), orientation paysage TV. |
| ENF-2 | Démarrage à froid < 3 s sur NVIDIA Shield. |
| ENF-3 | APK < 15 Mo. |
| ENF-4 | Aucun appel réseau hors : instance Home Assistant configurée, dépôt GitHub de publication. |
| ENF-5 | Jetons stockés chiffrés (Keystore Android) ; URL et sélection en stockage simple. |
| ENF-6 | Trafic HTTP en clair autorisé uniquement parce que l'instance locale n'a pas de certificat (contrainte assumée, réseau domestique). |
| ENF-7 | Build reproductible par CI (JDK 17, SDK 34, Gradle wrapper versionné). |
| ENF-8 | Messages d'interface en français. |

## 9. Modèle de données local

| Clé / fichier | Nature | Contenu | Sensible |
|---|---|---|---|
| `ha_config.xml` | préférences claires | `server_url`, `selected_entities` | non |
| `ha_secure_prefs.xml` | préférences chiffrées | `access_token`, `refresh_token`, `oauth_client_id`, `access_token_expires_at` | oui |
| cache `updates/app-<code>.apk` | fichier temporaire | APK téléchargé pour installation | non |
| export JSON | fichier utilisateur | l'ensemble ci-dessus | oui |

## 10. Interfaces externes

| Interface | Usage | Contraintes |
|---|---|---|
| mDNS `_home-assistant._tcp.` | découverte | dépend du réseau (multicast) |
| `GET /api/states` | liste des entités | jeton Bearer, filtre `light.` / `climate.` |
| `POST /api/services/{domain}/{service}` | commande | corps JSON `{"entity_id": ...}` |
| `POST /auth/token` | rafraîchissement OAuth | formulaire `grant_type=refresh_token` |
| Page frontend de l'instance | login et récupération des jetons | nécessite un compte Home Assistant |
| `releases/latest/download/version.json` et `app-debug.apk` | mises à jour | HTTPS public |

## 11. Contraintes et hypothèses

- L'instance Home Assistant et le Shield sont sur le même réseau local.
- L'utilisateur possède un compte Home Assistant ; l'intégration `discovery` est
  active (comportement par défaut).
- Le mDNS peut être filtré par certains routeurs : la saisie manuelle de l'URL
  reste toujours possible.
- La clé de signature de l'application doit être stable dans le temps, sinon
  Android refuse les mises à jour par-dessus l'installation existante.

## 12. Évolutions envisagées (hors périmètre v1)

- réglage de la consigne des thermostats ;
- scènes et scripts favoris ;
- accès distant via HTTPS / Nabu Casa ;
- profils multi-instances ;
- rafraîchissement par WebSocket au lieu du sondage toutes les 5 s.

## 13. Traçabilité

| Besoin | Exigences |
|---|---|
| BM-1 | EF-04, EF-08 |
| BM-2 | EF-01, EF-02 |
| BM-3 | ENF-4, ENF-5 |
| BM-4 | EF-05, EF-07, EF-06 |
| BM-5 | ENF-2, ENF-3, RM-8 |
| OBJ-2 | EF-02, EF-03 |
| OBJ-4 | EF-06, EF-07 |

## 14. Glossaire

- **Entité** : objet exposé par Home Assistant (`light.salon`, `climate.bureau`).
- **Jeton longue durée** : token d'accès permanent généré par l'utilisateur.
- **Jeton d'accès / refresh token** : couple OAuth délivré après connexion ;
  le second permet de renouveler le premier.
- **mDNS** : résolution de noms sur le réseau local, utilisée par Home Assistant
  pour s'annoncer.
- **versionCode / versionName** : numéro interne de build et version lisible.
