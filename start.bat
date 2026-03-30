@echo off
chcp 65001 >nul
title BLE Tool

cd /d "%~dp0"

:: Check for virtual environment
if exist ".venv\Scripts\activate.bat" (
    call .venv\Scripts\activate.bat
) else if exist "venv\Scripts\activate.bat" (
    call venv\Scripts\activate.bat
)

:: Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Please install Python 3.8+.
    pause
    exit /b 1
)

:: Check dependencies
python -c "import PyQt5, bleak" >nul 2>&1
if errorlevel 1 (
    echo [INFO] Installing dependencies...
    pip install PyQt5 bleak
    if errorlevel 1 (
        echo [ERROR] Failed to install dependencies.
        pause
        exit /b 1
    )
)

echo [INFO] Starting BLE Tool...
python ble_tool.py %*

if errorlevel 1 (
    echo.
    echo [ERROR] BLE Tool exited with an error.
    pause
)
