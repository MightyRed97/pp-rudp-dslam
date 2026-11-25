@echo off
rem The server's unit tests and end-to-end test, with the Node.js of this folder.
"%~dp0node\node.exe" "%~dp0..\server\test\unit-test.js" && "%~dp0node\node.exe" "%~dp0..\server\test\e2e-test.js"
