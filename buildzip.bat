@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  py -3 "%SCRIPT_DIR%scripts\build-local-zip.py" %*
) else (
  python "%SCRIPT_DIR%scripts\build-local-zip.py" %*
)
exit /b %ERRORLEVEL%
