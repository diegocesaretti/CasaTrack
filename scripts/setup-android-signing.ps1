param(
    [string]$KeyPath = "$env:USERPROFILE\CasaTrack-signing\casatrack-release.jks",
    [string]$Alias = "casatrack"
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "No se encontró '$Name' en PATH. Instalalo antes de continuar."
    }
}

Require-Command "keytool"
Require-Command "gh"

Write-Host "Verificando acceso a GitHub..."
& gh auth status
if ($LASTEXITCODE -ne 0) { throw "Primero ejecutá: gh auth login" }

$secure = Read-Host "Elegí una contraseña fuerte para la firma de CasaTrack" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}

if ([string]::IsNullOrWhiteSpace($password) -or $password.Length -lt 12) {
    throw "Usá una contraseña de al menos 12 caracteres."
}

$dir = Split-Path -Parent $KeyPath
New-Item -ItemType Directory -Force -Path $dir | Out-Null
if (Test-Path $KeyPath) {
    throw "Ya existe $KeyPath. No se sobrescribió: esa clave puede ser la firma que ya estás usando."
}

Write-Host "Generando la clave permanente fuera del repositorio..."
& keytool -genkeypair -v `
    -keystore $KeyPath `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -storepass $password `
    -keypass $password `
    -dname "CN=CasaTrack, O=CasaTrack, C=AR"
if ($LASTEXITCODE -ne 0) { throw "keytool falló." }

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeyPath))

Write-Host "Cargando secretos en GitHub Actions..."
$base64 | & gh secret set ANDROID_KEYSTORE_BASE64
$password | & gh secret set ANDROID_KEYSTORE_PASSWORD
$Alias | & gh secret set ANDROID_KEY_ALIAS
$password | & gh secret set ANDROID_KEY_PASSWORD
if ($LASTEXITCODE -ne 0) { throw "No se pudieron cargar todos los secretos de GitHub." }

Write-Host ""
Write-Host "Firma estable configurada." -ForegroundColor Green
Write-Host "Clave local: $KeyPath"
Write-Host "HACÉ UNA COPIA DE SEGURIDAD de ese archivo y guardá la contraseña fuera de GitHub."
Write-Host "No borres ni regeneres esta clave: todas las futuras actualizaciones de CasaTrack usarán la misma."
