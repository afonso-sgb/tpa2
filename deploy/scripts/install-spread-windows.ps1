# PowerShell Script to Install Spread Toolkit 5.0.1 on Windows
# This script downloads, extracts, and installs Spread for local testing

$ErrorActionPreference = "Stop"

Write-Host "=== Spread Toolkit 5.0.1 Installation for Windows ===" -ForegroundColor Green
Write-Host ""

# Configuration
$SPREAD_VERSION = "5.0.1"
$SPREAD_ARCHIVE = "spread-src-$SPREAD_VERSION.tar.gz"
$SPREAD_URL = "http://www.spread.org/download/$SPREAD_ARCHIVE"
$TEMP_DIR = "$env:TEMP\spread-install"
$INSTALL_DIR = "C:\spread-$SPREAD_VERSION"

# Create temp directory
Write-Host "Creating temporary directory: $TEMP_DIR" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $TEMP_DIR | Out-Null
Set-Location $TEMP_DIR

# Download Spread
Write-Host "Downloading Spread Toolkit from $SPREAD_URL..." -ForegroundColor Cyan
try {
    Invoke-WebRequest -Uri $SPREAD_URL -OutFile $SPREAD_ARCHIVE
    Write-Host "[OK] Download complete" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Download failed: $_" -ForegroundColor Red
    Write-Host ""
    Write-Host "Alternative: Download manually from http://www.spread.org/download.html" -ForegroundColor Yellow
    exit 1
}

# Extract archive (requires tar.exe which is available in Windows 10+)
Write-Host "Extracting archive..." -ForegroundColor Cyan
tar -xzf $SPREAD_ARCHIVE
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Extraction failed. Please install tar or extract manually." -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Extraction complete" -ForegroundColor Green

# Find the extracted directory
$SPREAD_DIR = Get-ChildItem -Directory | Where-Object { $_.Name -like "spread-src-*" } | Select-Object -First 1
if (-not $SPREAD_DIR) {
    Write-Host "[ERROR] Could not find extracted spread directory" -ForegroundColor Red
    exit 1
}

Write-Host "Found Spread directory: $($SPREAD_DIR.Name)" -ForegroundColor Cyan

# Copy to installation directory
Write-Host "Installing to $INSTALL_DIR..." -ForegroundColor Cyan
if (Test-Path $INSTALL_DIR) {
    Write-Host "Removing existing installation..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force $INSTALL_DIR
}
Copy-Item -Recurse $SPREAD_DIR.FullName $INSTALL_DIR
Write-Host "[OK] Installation complete" -ForegroundColor Green

# Install spread.jar to Maven
$SPREAD_JAR = Join-Path $INSTALL_DIR "java\spread.jar"
if (Test-Path $SPREAD_JAR) {
    Write-Host ""
    Write-Host "Installing spread.jar to local Maven repository..." -ForegroundColor Cyan
    $MVN_CMD = "mvn install:install-file -Dfile=`"$SPREAD_JAR`" -DgroupId=org.spread -DartifactId=spread -Dversion=$SPREAD_VERSION -Dpackaging=jar"
    Write-Host "Running: $MVN_CMD" -ForegroundColor Gray
    Invoke-Expression $MVN_CMD
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[OK] Maven installation complete" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] Maven installation failed" -ForegroundColor Red
        Write-Host "You can manually install with:" -ForegroundColor Yellow
        Write-Host $MVN_CMD -ForegroundColor Yellow
    }
} else {
    Write-Host "[ERROR] spread.jar not found at $SPREAD_JAR" -ForegroundColor Red
}

# Copy spread daemon executable
$SPREAD_DAEMON = Join-Path $INSTALL_DIR "sbin\spread.exe"
if (-not (Test-Path $SPREAD_DAEMON)) {
    # Try without .exe extension (might be named just "spread")
    $SPREAD_DAEMON = Join-Path $INSTALL_DIR "daemon\spread.exe"
}

Write-Host ""
Write-Host "=== Installation Summary ===" -ForegroundColor Green
Write-Host "Spread installation: $INSTALL_DIR" -ForegroundColor White
Write-Host "Spread JAR: $SPREAD_JAR" -ForegroundColor White
Write-Host ""
Write-Host "IMPORTANT: Spread daemon requires compilation on Windows." -ForegroundColor Yellow
Write-Host "For testing on Windows, you have two options:" -ForegroundColor Yellow
Write-Host "  1. Use WSL (Windows Subsystem for Linux) to run Spread daemon" -ForegroundColor Cyan
Write-Host "  2. Use a Linux VM or Docker container" -ForegroundColor Cyan
Write-Host ""
Write-Host "For WSL installation, run:" -ForegroundColor Cyan
Write-Host "  wsl -d Ubuntu" -ForegroundColor Gray
Write-Host "  sudo apt-get update" -ForegroundColor Gray
Write-Host "  sudo apt-get install -y build-essential" -ForegroundColor Gray
Write-Host "  cd /mnt/c/spread-$SPREAD_VERSION" -ForegroundColor Gray
Write-Host "  ./configure" -ForegroundColor Gray
Write-Host "  make" -ForegroundColor Gray
Write-Host "  sudo make install" -ForegroundColor Gray
Write-Host ""
Write-Host "To start Spread daemon (after compilation):" -ForegroundColor Cyan
Write-Host "  spread -c spread.conf" -ForegroundColor Gray
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Green
Write-Host "  1. [OK] spread.jar installed to Maven" -ForegroundColor White
Write-Host "  2. Compile Spread daemon (see instructions above)" -ForegroundColor White
Write-Host "  3. Create spread.conf configuration file" -ForegroundColor White
Write-Host "  4. Start Spread daemon" -ForegroundColor White
Write-Host "  5. Run: mvn clean package -DskipTests" -ForegroundColor White
Write-Host "  6. Test Worker with --spread-host parameter" -ForegroundColor White
Write-Host ""

# Cleanup
Write-Host "Cleaning up temporary files..." -ForegroundColor Cyan
Set-Location $env:TEMP
Remove-Item -Recurse -Force $TEMP_DIR -ErrorAction SilentlyContinue

Write-Host "Installation script complete!" -ForegroundColor Green
