#!/usr/bin/env bash
#
# verify-signing.sh — vérifie qu'un keystore est bien lisible avec le mot de
# passe enregistré dans secrets.env, et affiche son empreinte SHA-256.
#
# Usage :  ./verify-signing.sh [dossier]
#
set -euo pipefail

cd "${1:-$(dirname "$0")}"

for f in hastield.jks secrets.env; do
  [ -f "$f" ] || { echo "Fichier manquant : $f (dossier $(pwd))" >&2; exit 1; }
done

command -v docker >/dev/null 2>&1 || { echo "Docker requis." >&2; exit 1; }

# 1) Longueur de la valeur lue (détecte un \r ou une valeur vide)
PW_LEN=$(docker run --rm --env-file ./secrets.env eclipse-temurin:17-jdk \
  bash -lc 'printf "%s" "${ANDROID_KEYSTORE_PASSWORD:-}" | wc -c' | tr -d ' ')
echo "Longueur du mot de passe lu dans secrets.env : ${PW_LEN} caractères"
if [ "${PW_LEN}" -eq 0 ]; then
  echo "La variable ANDROID_KEYSTORE_PASSWORD est vide dans secrets.env." >&2
  exit 1
fi

# 2) Le keystore s'ouvre-t-il avec ce mot de passe ?
if docker run --rm -v "$PWD":/w -w /w --env-file ./secrets.env eclipse-temurin:17-jdk \
     bash -lc 'keytool -list -keystore hastield.jks -storepass "$ANDROID_KEYSTORE_PASSWORD" >/dev/null 2>&1'; then
  echo "Mot de passe accepté par le keystore."
else
  cat >&2 <<'EOF'
Mot de passe REFUSÉ par le keystore.

Cause la plus fréquente : secrets.env ne correspond pas au fichier hastield.jks
(valeur éditée à la main, ou keystore généré lors d'un autre passage).

Réparation :
  rm -f hastield.jks keystore.b64 secrets.env hastield.jks.sha256
  ./setup-signing.sh
EOF
  exit 2
fi

# 3) Empreinte
docker run --rm -v "$PWD":/w -w /w --env-file ./secrets.env eclipse-temurin:17-jdk \
  bash -lc 'keytool -list -v -keystore hastield.jks -storepass "$ANDROID_KEYSTORE_PASSWORD" \
    | grep -iE "Alias name|Owner|Valid from|SHA256:"'

echo
echo "Cette empreinte SHA256 doit être identique à celle affichée par"
echo "  apksigner verify --print-certs app-debug.apk"
echo "(mêmes octets, format different : keytool = MAJUSCULES avec ':', apksigner = minuscules)"
