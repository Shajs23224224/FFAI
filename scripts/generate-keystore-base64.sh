#!/bin/bash

# Helper script to generate a keystore and encode it for GitHub Actions
# Usage: ./generate-keystore-base64.sh [keystore_name] [alias_name]

set -e

KEYSTORE_NAME="${1:-ffai.keystore}"
ALIAS_NAME="${2:-ffai-key}"
KEYSTORE_PASS="${KEYSTORE_PASSWORD:-$(openssl rand -base64 32)}"
KEY_PASS="${KEY_PASSWORD:-$KEYSTORE_PASS}"
VALIDITY_DAYS="${VALIDITY:-10000}"

echo "=========================================="
echo "FFAI Keystore Generator"
echo "=========================================="
echo ""

# Check if keytool is available
if ! command -v keytool &> /dev/null; then
    echo "Error: keytool not found. Please install JDK."
    exit 1
fi

# Generate keystore
echo "📋 Keystore Configuration:"
echo "  - Name: $KEYSTORE_NAME"
echo "  - Alias: $ALIAS_NAME"
echo "  - Validity: $VALIDITY_DAYS days"
echo ""

echo "🔐 Generating keystore..."
keytool -genkey -v \
    -keystore "$KEYSTORE_NAME" \
    -alias "$ALIAS_NAME" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$KEYSTORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=FFAI, OU=Development, O=FFAI Project, L=Unknown, ST=Unknown, C=US" \
    2>/dev/null

echo "✅ Keystore generated: $KEYSTORE_NAME"
echo ""

# Encode to base64
echo "🔄 Encoding to base64..."
BASE64_KEYSTORE=$(base64 -w 0 "$KEYSTORE_NAME")

echo ""
echo "=========================================="
echo "🔑 GitHub Secrets Configuration"
echo "=========================================="
echo ""
echo "Add these secrets to your GitHub repository:"
echo ""
echo "Name: KEYSTORE_BASE64"
echo "Value: (copy the base64 string below)"
echo ""
echo "----- BEGIN BASE64 KEYSTORE -----"
echo "$BASE64_KEYSTORE"
echo "----- END BASE64 KEYSTORE -----"
echo ""
echo "Name: KEYSTORE_PASSWORD"
echo "Value: $KEYSTORE_PASS"
echo ""
echo "Name: KEY_ALIAS"
echo "Value: $ALIAS_NAME"
echo ""
echo "Name: KEY_PASSWORD"
echo "Value: $KEY_PASS"
echo ""
echo "=========================================="
echo "⚠️  IMPORTANT SECURITY NOTES"
echo "=========================================="
echo ""
echo "1. Store the keystore file ($KEYSTORE_NAME) in a secure location"
echo "2. Backup the passwords - they cannot be recovered!"
echo "3. The base64 output above is your keystore - keep it confidential"
echo "4. Delete this terminal history after copying the values"
echo "5. Consider using a hardware security module (HSM) for production"
echo ""
echo "📁 Local files:"
ls -lh "$KEYSTORE_NAME"
echo ""
echo "🔍 Keystore info:"
keytool -list -v -keystore "$KEYSTORE_NAME" -storepass "$KEYSTORE_PASS" 2>/dev/null | head -20
