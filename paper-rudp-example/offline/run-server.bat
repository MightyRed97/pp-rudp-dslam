@echo off
rem Starts the relay with the Node.js of this folder. Options: --sip-port 5070 --irudp-port 5080 --public-ip <ip> --data <dir>
"%~dp0node\node.exe" "%~dp0..\server\src\main.js" %*
