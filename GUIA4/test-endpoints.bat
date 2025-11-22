@echo off
echo ================================
echo Prueba de Endpoints del Servidor
echo ================================
echo.

echo Verificando si el servidor esta escuchando en puerto 8080...
netstat -ano | findstr :8080 > nul
if %errorlevel% neq 0 (
    echo ERROR: El servidor no esta ejecutandose en puerto 8080
    echo Por favor ejecute start-server.bat primero
    pause
    exit /b 1
)

echo Servidor encontrado en puerto 8080
echo.

echo [HTML Files]
echo [1/8] Probando GET / (index.html)
curl -s -o nul -w "Status: %%{http_code}\n" http://localhost:8080/
echo.

echo [2/8] Probando GET /index.html
curl -s -o nul -w "Status: %%{http_code}\n" http://localhost:8080/index.html
echo.

echo [3/8] Probando GET /login.html
curl -s -o nul -w "Status: %%{http_code}\n" http://localhost:8080/login.html
echo.

echo [4/8] Probando GET /ajax.html
curl -s -o nul -w "Status: %%{http_code}\n" http://localhost:8080/ajax.html
echo.

echo [Data Files]
echo [5/8] Probando GET /documento.txt
curl -s -w "\nStatus: %%{http_code}\n" http://localhost:8080/documento.txt | head -n 3
echo.

echo [6/8] Probando GET /paises.txt
curl -s -w "\nStatus: %%{http_code}\n" http://localhost:8080/paises.txt | findstr "option" | head -n 3
echo.

echo [7/8] Probando GET /paises.json
curl -s -w "\nStatus: %%{http_code}\n" http://localhost:8080/paises.json | head -n 5
echo.

echo [8/8] Probando GET /404.html
curl -s -o nul -w "Status: %%{http_code}\n" http://localhost:8080/404.html
echo.

echo ================================
echo Pruebas completadas
echo ================================
echo.
echo Para probar en el navegador:
echo - http://localhost:8080/         (Pagina principal)
echo - http://localhost:8080/login.html  (Login)
echo - http://localhost:8080/ajax.html   (AJAX Demo)
echo.
pause

