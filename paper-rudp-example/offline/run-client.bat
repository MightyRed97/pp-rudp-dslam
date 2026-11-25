@echo off
rem A command-line chat client: run-client.bat --user bob [--server 127.0.0.1] [--port 5070]
"%~dp0node\node.exe" "%~dp0..\server\client\cli.js" %*
