# Local development starter — sets ALL env vars and brings up containers + app.
# R07: these are LOCAL-ONLY dev values, never committed with real secrets.
# Usage: .\compose\start-local.ps1                 (starts core 4 containers)
#        .\compose\start-local.ps1 -e2e             (adds Artemis + WireMock)
#        .\compose\start-local.ps1 -WithApp          (containers + Spring Boot)
#        .\compose\start-local.ps1 -WithApp -e2e     (everything)

param([switch]$e2e, [switch]$WithApp)

# --- Compose env vars (R07) ---
$env:POSTGRES_USER              = "ipaas"
$env:POSTGRES_DB                = "ipaas"
$env:POSTGRES_PASSWORD          = "ipaasLocal2026"
$env:KEYCLOAK_DB_NAME           = "keycloak"
$env:KEYCLOAK_DB_USERNAME       = "keycloak"
$env:KEYCLOAK_DB_PASSWORD       = "kcLocal2026"
$env:KEYCLOAK_ADMIN_USERNAME    = "admin"
$env:KEYCLOAK_ADMIN_PASSWORD    = "adminLocal2026"
$env:OIDC_AUDIENCE              = "integration-platform"
$env:CONSOLE_OIDC_CLIENT_SECRET = "console-secret-local"
$env:CONSOLE_OIDC_REDIRECT_URI  = "http://localhost:5173/auth/callback"
$env:CONSOLE_POST_LOGOUT_REDIRECT_URI = "http://localhost:5173"
$env:PLATFORM_ADMIN_CLIENT_SECRET     = "admin-secret-local"
$env:OPERATOR_AGENT_CLIENT_SECRET     = "agent-secret-local"
$env:KAFBAT_UI_USER             = "admin"
$env:KAFBAT_UI_PASSWORD         = "kafbatLocal2026"
$env:ARTEMIS_USER               = "artemis"
$env:ARTEMIS_PASSWORD           = "artemisLocal2026"

# --- App env vars (ADR-009: restricted roles, not superuser) ---
$env:DB_URL                     = "jdbc:postgresql://localhost:5432/ipaas"
$env:DB_USERNAME                = "ipass_app"
$env:DB_PASSWORD                = "appLocal2026"
$env:DB_MIGRATION_USERNAME      = "ipass_migrator"
$env:DB_MIGRATION_PASSWORD      = "migratorLocal2026"
$env:OIDC_ISSUER_URI            = "http://localhost:8180/realms/platform"
$env:CONSOLE_OIDC_CLIENT_ID     = "console"
$env:IPASS_KAFKA_BOOTSTRAP_SERVERS  = "localhost:9092"
$env:IPASS_KAFKA_PUBLISH_CLIENT_ID  = "ipaas-local"

$composeFile = Join-Path $PSScriptRoot "compose.yaml"

if ($e2e) {
    docker compose -f $composeFile --profile e2e up -d
} else {
    docker compose -f $composeFile up -d
}

if ($WithApp) {
    Write-Host "`nStarting Spring Boot..."
    Set-Location (Split-Path $PSScriptRoot)
    .\mvnw.cmd spring-boot:run -pl platform-app
}
