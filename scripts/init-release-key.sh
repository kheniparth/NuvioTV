#!/usr/bin/env bash
# Initialize release signing key: create keystore and release.env if absent, idempotent.
# Never prints secret values, only paths.

set -euo pipefail

# Match .factory/build.sh so keytool works even when the runner PATH is trimmed.
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/sdk/jdk-21}"
KEYTOOL="${JAVA_HOME}/bin/keytool"
if [[ ! -x "$KEYTOOL" ]]; then
    echo "Error: keytool not found at $KEYTOOL" >&2
    exit 1
fi

secrets_dir="${NUVIO_SECRETS_DIR:-/mnt/d/secrets/nuvio}"
keystore_path="$secrets_dir/nuvio-release.jks"
env_file="$secrets_dir/release.env"
key_alias="nuvio"

mkdir -p "$secrets_dir"

if [[ -f "$keystore_path" && -f "$env_file" ]]; then
    echo "Release key already initialized at $keystore_path"
    exit 0
fi

if [[ -f "$keystore_path" ]]; then
    echo "Error: keystore exists but release.env missing at $env_file" >&2
    exit 1
fi

if [[ -f "$env_file" ]]; then
    echo "Error: release.env exists but keystore missing at $keystore_path" >&2
    exit 1
fi

# Hex-only: base64 can include '+' which breaks keytool -storepass argv parsing.
# Same password for store and key: PKCS12 rejects different passwords.
generate_password() {
    openssl rand -hex 16
}

release_password=$(generate_password)

# Create the keystore with the signing key
if ! "$KEYTOOL" -genkey \
    -keystore "$keystore_path" \
    -alias "$key_alias" \
    -keyalg RSA \
    -keysize 2048 \
    -sigalg SHA256withRSA \
    -validity 36500 \
    -keypass "$release_password" \
    -storepass "$release_password" \
    -storetype PKCS12 \
    -dname "CN=Nuvio TV Release,O=Nuvio,C=CA" \
    >/dev/null; then
    echo "Error: keytool failed creating $keystore_path" >&2
    rm -f "$keystore_path"
    exit 1
fi

# Create the release.env file with the signing values
cat > "$env_file" <<EOF
export NUVIO_RELEASE_STORE_FILE=$keystore_path
export NUVIO_RELEASE_KEY_ALIAS=$key_alias
export NUVIO_RELEASE_KEY_PASSWORD=$release_password
export NUVIO_RELEASE_STORE_PASSWORD=$release_password
EOF

chmod 600 "$env_file"

echo "Release key initialized:"
echo "  Keystore: $keystore_path"
echo "  Env file: $env_file"
