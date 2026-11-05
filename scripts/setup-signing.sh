#!/usr/bin/env bash
#
# setup-signing.sh — crée la clé de signature de HA Shield Control et déclare
# les secrets GitHub nécessaires. Tout ce qui demande du JDK/keytool se passe
# dans un conteneur Docker : seule l'image est requise sur la machine.
#
# Usage :  ./scripts/setup-signing.sh
#
set -euo pipefail

IMAGE="eclipse-temurin:17-jdk"
DEFAULT_REPO="guillian77/ha-shield-controller"
DEFAULT_ALIAS="hastield"
DEFAULT_DNAME="CN=HA Shield Control, O=Dekadmin, C=FR"

step() { printf '\n=== %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*"; }

command -v docker >/dev/null 2>&1 || {
  echo "Docker est requis (https://docs.docker.com/get-docker/)." >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Le démon Docker ne répond pas : démarre Docker puis relance." >&2
  exit 1
}

step "Destination des fichiers"
read -rp "Dossier de travail [$PWD] : " WORK_DIR
WORK_DIR=${WORK_DIR:-$PWD}
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

step "Paramètres de la clé"
read -rp "Alias de la clé [$DEFAULT_ALIAS] : " KEY_ALIAS
KEY_ALIAS=${KEY_ALIAS:-$DEFAULT_ALIAS}

read -rp "Identité du certificat [$DEFAULT_DNAME] : " DNAME
DNAME=${DNAME:-$DEFAULT_DNAME}

read -rp "Dépôt GitHub (owner/repo) [$DEFAULT_REPO] : " REPO
REPO=${REPO:-$DEFAULT_REPO}

step "Mot de passe du keystore"
while :; do
  read -rsp "Mot de passe (12 caractères minimum) : " PASS1; echo
  read -rsp "Confirmation : " PASS2; echo
  if [ "$PASS1" != "$PASS2" ]; then
    warn "Les deux saisies diffèrent, recommence."
    continue
  fi
  if [ "${#PASS1}" -lt 12 ]; then
    warn "Trop court (${#PASS1} caractères)."
    continue
  fi
  break
done

if [ -f hastield.jks ]; then
  read -rp "hastield.jks existe déjà : l'archiver et en créer un nouveau ? [o/N] : " OVERWRITE
  case "$OVERWRITE" in
    o|O|y|Y)
      BACKUP="hastield.jks.bak.$(date +%Y%m%d%H%M%S)"
      mv hastield.jks "$BACKUP"
      ln -sfn "$BACKUP" hastield.jks.bak
      warn "Ancienne clé conservée dans $BACKUP"
      ;;
    *)
      echo "Abandon : aucune modification."
      exit 1
      ;;
  esac
fi

step "Génération de la clé (conteneur $IMAGE)"
docker run --rm \
  -v "$PWD":/work -w /work \
  -e STORE_PASS="$PASS1" \
  -e KEY_ALIAS="$KEY_ALIAS" \
  -e DNAME="$DNAME" \
  "$IMAGE" bash -lc '
    set -e
    keytool -genkeypair -noprompt -storetype PKCS12 \
      -keystore hastield.jks -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -dname "$DNAME"

    keytool -list -v -keystore hastield.jks -storepass "$STORE_PASS" \
      | grep -E "Alias name|Owner|Valid from" || true

    base64 -w0 hastield.jks > keystore.b64
    printf "  keystore : %s octets\n  base64   : %s caractères\n" \
      "$(wc -c < hastield.jks)" "$(wc -c < keystore.b64)"
  '

chmod 600 hastield.jks keystore.b64
printf 'ANDROID_KEYSTORE_PASSWORD=%s\nANDROID_KEY_ALIAS=%s\nANDROID_KEY_PASSWORD=%s\n' \
  "$PASS1" "$KEY_ALIAS" "$PASS1" > secrets.env
chmod 600 secrets.env

step "Déclaration des secrets GitHub"
SECRETS_SET=0
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO" < keystore.b64
  gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO" --body "$PASS1"
  gh secret set ANDROID_KEY_ALIAS --repo "$REPO" --body "$KEY_ALIAS"
  gh secret set ANDROID_KEY_PASSWORD --repo "$REPO" --body "$PASS1"
  SECRETS_SET=1
  echo "  4 secrets configurés sur $REPO :"
  gh secret list --repo "$REPO" | sed 's/^/    /'
else
  warn "gh CLI absent ou non authentifié (gh auth login) : déclaration manuelle."
  if command -v pbcopy >/dev/null 2>&1; then
    pbcopy < keystore.b64
    echo "  Le keystore base64 est dans le presse-papiers (pbcopy)."
  elif command -v xclip >/dev/null 2>&1; then
    xclip -selection clipboard < keystore.b64
    echo "  Le keystore base64 est dans le presse-papiers (xclip)."
  else
    echo "  Contenu à copier : $(pwd)/keystore.b64"
  fi
  cat <<EOF

  GitHub -> dépôt $REPO -> Settings -> Secrets and variables -> Actions :
    ANDROID_KEYSTORE_BASE64   = contenu de keystore.b64 (une seule ligne)
    ANDROID_KEYSTORE_PASSWORD = le mot de passe choisi
    ANDROID_KEY_ALIAS         = $KEY_ALIAS
    ANDROID_KEY_PASSWORD      = le même mot de passe
EOF
fi

step "Empreinte du keystore (à conserver)"
if [ "$SECRETS_SET" -eq 1 ]; then
  read -rp "Supprimer keystore.b64 et secrets.env (la clé reste) ? [O/n] : " CLEAN
  case "${CLEAN:-O}" in
    n|N) ;;
    *) rm -f keystore.b64 secrets.env; echo "  Fichiers temporaires supprimés." ;;
  esac
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum hastield.jks | tee hastield.jks.sha256 | sed 's/^/  /'
else
  shasum -a 256 hastield.jks | tee hastield.jks.sha256 | sed 's/^/  /'
fi

cat <<EOF

=== Terminé

Fichiers dans $(pwd) :
  hastield.jks            -> À SAUVEGARDER (avec le mot de passe), ne jamais publier
  hastield.jks.sha256     -> empreinte de contrôle
  secrets.env             -> mots de passe (supprimé si tu as dit oui)

Étapes suivantes :
  1. Vérifier la CI : un run doit passer SANS le message
     "::warning::Aucun keystore".
  2. Sur la Shield : Réglages -> Exporter la configuration.
  3. Désinstaller l'application (obligatoire, la clé de signature change).
  4. Réinstaller l'APK, puis Réglages -> Importer.
  5. Les mises à jour suivantes s'installeront normalement.

Sans hastield.jks + son mot de passe, plus aucune mise à jour ne sera
installable par-dessus l'application existante.
EOF
