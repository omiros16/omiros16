@echo off
REM Runs the bot and restarts it if it stops. Add to Windows Task Scheduler ("At log on").
REM Usage:  run_bot.bat                 (paper)
REM         run_bot.bat --allow-orders  (broker.type: mt5)
cd /d %~dp0
:loop
python -m tradingbot run -c config\config.yaml %*
echo Bot exited with code %errorlevel%. Restarting in 60 seconds (Ctrl+C to stop)...
timeout /t 60 /nobreak >nul
goto loop
