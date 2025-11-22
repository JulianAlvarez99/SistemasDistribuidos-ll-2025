@echo off
echo ========================================
echo Multi-threaded HTTP Server con AJAX
echo ========================================
echo.
echo Compilando MultiThreadedHttpServer...
cd /d C:\Users\julia\IdeaProjects\SistemasDistribuidos-ll-2025\GUIA4\src
javac MultiThreadedHttpServer.java

if %errorlevel% neq 0 (
    echo Error en compilacion
    pause
    exit /b 1
)

echo Servidor compilado exitosamente.
echo.
echo Archivos HTML disponibles:
echo - index.html (pagina principal)
echo - login.html (autenticacion)
echo - ajax.html (demo AJAX)
echo - 404.html (error)
echo.
echo Datos para AJAX:
echo - documento.txt
echo - paises.txt
echo - paises.json
echo.
echo Iniciando servidor en puerto 8080...
echo.
echo URLs disponibles:
echo   http://localhost:8080/
echo   http://localhost:8080/login.html
echo   http://localhost:8080/ajax.html
echo.
echo Presiona Ctrl+C para detener el servidor
echo ========================================
echo.
java MultiThreadedHttpServer

