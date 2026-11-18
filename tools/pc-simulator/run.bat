@echo off
rem Live input levels from the WING mock reach the emulator only through this UDP redirect
rem (it is lost when the emulator restarts; an error just means it is already set or no emulator runs).
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 emu redir add udp:1241:1241 >nul 2>&1
py -3.13 "%~dp0pedal_simulator.py"
