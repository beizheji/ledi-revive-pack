@echo off
chcp 936 >nul
setlocal
title Ledi public pack

echo ==========================================================
echo   Ledi robot  -  public pack  -  apply to robot
echo ==========================================================
echo.
echo   Applies : boot animation + wallpaper + app
echo   Needs   : adb in PATH, robot adbd runs as root
echo             (root adb comes with the boot.img in 01_)
echo   Default : DRY RUN - prints commands, changes nothing
echo.

set PY=
for %%P in (python.exe) do set PY=%%~$PATH:P
if "%PY%"=="" goto nopy

set IP=
set /p IP=  Robot IP (e.g. 192.168.1.100), Enter = skip connect: 

set YES=
set /p YES=  Really apply? type Y then Enter (else dry run): 
if /i not "%YES%"=="Y" goto dry

echo.
if "%IP%"=="" goto apply_noconn
"%PY%" "%~dp0_apply_public.py" --ip %IP% --yes
goto done

:apply_noconn
"%PY%" "%~dp0_apply_public.py" --yes
goto done

:dry
echo.
echo (dry run - nothing will be changed on the robot)
if "%IP%"=="" goto dry_noconn
"%PY%" "%~dp0_apply_public.py" --ip %IP%
goto done

:dry_noconn
"%PY%" "%~dp0_apply_public.py"
goto done

:nopy
echo [ERROR] python.exe not found in PATH. Install Python 3 first.
pause
exit /b 1

:done
echo.
pause
exit /b 0
