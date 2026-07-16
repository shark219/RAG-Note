@echo off
echo ========================================
echo   启动 ChromaDB 向量数据库
echo   地址: http://localhost:8001
echo   按 Ctrl+C 停止
echo ========================================
python -m chromadb.cli.cli run --host 0.0.0.0 --port 8001 --path "%~dp0data\chromadb"
pause
