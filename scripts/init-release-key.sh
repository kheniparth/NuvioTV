#!/usr/bin/env bash
# Initialize release signing key: create keystore and release.env if absent, idempotent.
# Never prints secret values, only paths.

set -euo pipefail

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

generate_password() {
    openssl rand -base64 32 | tr -d '/' | cut -c1-16
}

key_password=$(generate_password)
store_password=$(generate_password)

# Create the keystore with the signing key
keytool -genkey -v \
    -keystore "$keystore_path" \
    -alias "$key_alias" \
    -keyalg RSA \
    -keysize 2048 \
    -sigalg SHA256withRSA \
    -validity 36500 \
    -keypass "$key_password" \
    -storepass "$store_password" \
    -dname "CN=Nuvio TV Release,O=Nuvio,C=CA" \
    >/dev/null 2>&1

# Create the release.env file with the signing values
cat > "$env_file" <<EOF
export NUVIO_RELEASE_STORE_FILE=$keystore_path
export NUVIO_RELEASE_KEY_ALIAS=$key_alias
export NUVIO_RELEASE_KEY_PASSWORD=$key_password
export NUVIO_RELEASE_STORE_PASSWORD=$store_password
EOF

chmod 600 "$env_file"

echo "Release key initialized:"
echo "  Keystore: $keystore_path"
echo "  Env file: $env_file"
