@echo off
chcp 65001 >nul
title BlindAssist Server

echo ========================================
echo   BlindAssist Server
echo ========================================
echo.

:: Проверяем наличие Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Python не найден. Установите Python 3.9+
    echo https://www.python.org/downloads/
    pause
    exit /b 1
)

:: Переходим в папку сервера
cd /d "%~dp0"

:: Устанавливаем зависимости
echo [1/2] Проверка зависимостей...
pip install -q flask Pillow pytesseract requests --trusted-host pypi.org --trusted-host files.pythonhosted.org
if errorlevel 1 (
    echo [ОШИБКА] Не удалось установить зависимости
    pause
    exit /b 1
)

:: Проверяем Ollama
echo [2/2] Проверка Ollama...
curl -s http://localhost:11434 >nul 2>&1
if errorlevel 1 (
    echo.
    echo [ВНИМАНИЕ] Ollama не запущена!
    echo Запускаем Ollama...
    start "" ollama serve
    timeout /t 3 /nobreak >nul
)

echo.
echo ========================================
echo  Сервер запускается...
echo  Не закрывай это окно!
echo ========================================
echo.

python server.py

pause
