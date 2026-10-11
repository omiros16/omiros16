@echo off
REM Runs the bot and restarts it if it stops. Add to Windows Task Scheduler ("At log on").
REM Usage:  run_bot.bat                 (paper, or broker.allow_orders: true in the config)
REM         run_bot.bat --allow-orders  (broker.type: mt5)
REM Exit code 2 = configuration problem (or orders not confirmed): fix it, do not loop.
cd /d %~dp0
:loop
python -m tradingbot run -c config\config.yaml %*
if %errorlevel%==2 (
    echo Configuration error, see the messages above and logs\bot.log. Not restarting.
    pause
    exit /b 2
)
echo Bot exited with code %errorlevel%. Restarting in 60 seconds (Ctrl+C to stop)...
timeout /t 60 /nobreak >nul
goto loop
