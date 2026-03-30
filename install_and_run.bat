@echo off
title BLE Tool - Complete Setup and Run
echo ================================================
echo      BLE Tool - Complete Installation
echo ================================================
echo.

:: Check Python installation
echo Step 1: Checking Python installation...
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python is not installed
    echo.
    echo Please download and install Python from:
    echo https://www.python.org/downloads/
    echo.
    echo Make sure to check "Add Python to PATH" during installation
    pause
    exit /b 1
)

python --version
echo.

:: Upgrade pip
echo Step 2: Upgrading pip...
python -m pip install --upgrade pip >nul 2>&1
echo pip upgraded successfully
echo.

:: Install all required packages
echo Step 3: Installing required packages...
echo.

echo Installing PyQt5 (GUI framework)...
pip install PyQt5
if errorlevel 1 (
    echo [ERROR] Failed to install PyQt5
    pause
    exit /b 1
)

echo.
echo Installing bleak (Bluetooth LE library)...
pip install bleak
if errorlevel 1 (
    echo [ERROR] Failed to install bleak
    pause
    exit /b 1
)

echo.
echo Installing asyncio (if needed)...
pip install asyncio >nul 2>&1

echo.
echo ================================================
echo     All dependencies installed successfully!
echo ================================================
echo.

:: Run the application
echo Starting BLE Tool...
echo.
echo NOTE: For full functionality (pairing, etc.),
echo       you may need to run as Administrator.
echo ================================================
echo.

python ble_tool.py

if errorlevel 1 (
    echo.
    echo ================================================
    echo Application exited with an error.
    echo.
    echo Common issues:
    echo - Bluetooth adapter not found/enabled
    echo - Missing permissions (try admin mode)
    echo - dbus-python issues on Windows
    echo ================================================
)

echo.
pause