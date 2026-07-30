@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."
set "CHROMA_PATH=%PROJECT_ROOT%\data\chromadb"

echo ========================================
echo   Starting ChromaDB vector database
echo   URL: http://127.0.0.1:8001
echo   Data: %CHROMA_PATH%
echo   Press Ctrl+C to stop
echo ========================================

if not exist "%CHROMA_PATH%" mkdir "%CHROMA_PATH%"

python -m chromadb.cli.cli run --host 127.0.0.1 --port 8001 --path "%CHROMA_PATH%"
pause
