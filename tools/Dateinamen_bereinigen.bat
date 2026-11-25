@echo off
chcp 65001 >nul
rem Ordner (z. B. Roms auf dem Stick) auf diese Datei ziehen oder den Pfad eingeben.
set "ORDNER=%~1"
if "%ORDNER%"=="" set /p ORDNER=Ordner mit den ROMs (z. B. E:\Roms): 
echo.
echo 1 = nur anzeigen (Test)
echo 2 = wirklich umbenennen
set /p WAHL=Auswahl (1 oder 2): 
if "%WAHL%"=="1" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Dateinamen_bereinigen.ps1" "%ORDNER%" -Test
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Dateinamen_bereinigen.ps1" "%ORDNER%"
)
echo.
pause
