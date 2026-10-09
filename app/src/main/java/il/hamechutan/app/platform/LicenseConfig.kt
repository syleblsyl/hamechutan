package il.hamechutan.app.platform

/**
 * License server settings, compiled into the app.
 *  - SERVER_URL: the Apps Script web app URL (".../exec"), from "Deploy > Manage deployments".
 *  - PUBLIC_KEY: written by tools/license-keys.sh (base64 X.509 RSA public key). Not secret.
 * Both empty = a development build without licensing. Release builds in CI refuse to build that way.
 */
object LicenseConfig {
    const val SERVER_URL = ""
    const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAo4dKJFMYPST+tEOInDWjgMBIdo+7GbaIJ3WZ5kIznXaQfV2210piyD1MqBT3imC1lJerh7ylvEjdFxUZoZ0936268RjGap1RCPQkXtP3BrBPzTcQV8B6qh5SC8zShZSWjGcpJTgmmKE+VZSOESsy1/SHKh1UbsvHyDN7KJ3HzorgXXe3cfizQXbRgYNCgT5DDMMbXOirJts5U+JECyd7Sgz0llcJn7XairCN+6kUxqW2KZfM24hChSKJTBDyIzsGrXPEsXAR0/9t5OOM4/bLKvIhY8H6DY6+l1mtMcYhayElhFgfsqAaC44pgfcnF6yCvMUiG5BB5t/0scH/l21uFQIDAQAB"
}
