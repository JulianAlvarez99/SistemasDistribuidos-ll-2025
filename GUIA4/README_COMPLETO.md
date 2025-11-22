# GUIA4 - Servidor HTTP Multi-threaded con AJAX

## Resumen del Ejercicio

Implementación de un servidor HTTP en Java que sirve páginas HTML con funcionalidades AJAX para:
- a) Cargar contenido de archivo de texto en un `<div>`
- b) Cargar lista de países desde archivo HTML en un `<select>`
- c) Cargar lista de países desde archivo JSON en un `<select>`

## Estructura de Archivos

```
GUIA4/
├── src/
│   ├── MultiThreadedHttpServer.java    # Servidor HTTP principal
│   ├── documento.txt                    # Archivo para AJAX parte (a)
│   ├── paises.txt                       # HTML options para parte (b)
│   ├── paises.json                      # JSON para parte (c)
│   └── users.txt                        # Credenciales de login
├── start-server.bat                     # Script para iniciar servidor
├── test-endpoints.bat                   # Script para probar endpoints
├── test-ajax.html                       # Página de prueba standalone
└── AJAX_README.md                       # Documentación técnica completa

```

## Inicio Rápido

### Opción 1: Usar script de inicio
```bash
# Doble clic o ejecutar:
start-server.bat
```

### Opción 2: Compilar y ejecutar manualmente
```bash
cd C:\Users\julia\IdeaProjects\SistemasDistribuidos-ll-2025\GUIA4\src
javac MultiThreadedHttpServer.java
java MultiThreadedHttpServer
```

## Probar la Aplicación

### Método 1: Desde el servidor (recomendado)
1. Iniciar servidor con `start-server.bat`
2. Abrir navegador en: `http://localhost:8080/ajax`
3. Click en cada botón para probar las 3 funcionalidades

### Método 2: Página standalone
1. Iniciar servidor
2. Abrir archivo `test-ajax.html` en navegador
3. Las peticiones AJAX se harán a `http://localhost:8080`

### Método 3: Línea de comandos
```bash
# Ejecutar:
test-endpoints.bat
```

## Endpoints Disponibles

### Páginas HTML
- `GET /` - Página de inicio
- `GET /login` - Formulario de autenticación
- `GET /ajax` - **Demo AJAX completo** (las 3 funcionalidades)

### Recursos de Datos
- `GET /documento.txt` - Archivo de texto plano (Content-Type: text/plain)
- `GET /paises.txt` - HTML con `<option>` tags (Content-Type: text/html)
- `GET /paises.json` - Array JSON de países (Content-Type: application/json)

## Implementación Técnica

### Servidor HTTP

**Thread Pool**: 10 threads reutilizables (ExecutorService)
**Puerto**: 8080
**Timeout**: 1000ms para aceptar conexiones
**Headers**: Incluye `Access-Control-Allow-Origin: *` para AJAX

### Router (ClientHandler)

```java
if (requestLine.startsWith("GET /ajax")) {
    sendAjaxDemo(out);  // Página con las 3 demos
} else if (requestLine.startsWith("GET /documento.txt")) {
    serveTextFile(out, "documento.txt", "text/plain");
} else if (requestLine.startsWith("GET /paises.txt")) {
    serveTextFile(out, "paises.txt", "text/html");
} else if (requestLine.startsWith("GET /paises.json")) {
    serveTextFile(out, "paises.json", "application/json");
}
```

### Funcionalidades AJAX

#### a) Cargar documento.txt en `<div>`

**JavaScript:**
```javascript
function loadDocument() {
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
        if (xhr.readyState == 4 && xhr.status == 200) {
            document.getElementById('content').innerHTML = 
                xhr.responseText.replace(/\n/g, '<br>');
        }
    };
    xhr.open('GET', '/documento.txt', true);
    xhr.send();
}
```

**Flujo:**
1. Click botón → Crea XMLHttpRequest
2. GET asíncrono a `/documento.txt`
3. Servidor responde con `Content-Type: text/plain`
4. Callback actualiza `innerHTML` del div
5. `\n` convertido a `<br>` para HTML

#### b) Cargar paises.txt (HTML) en `<select>`

**JavaScript:**
```javascript
function loadCountriesHTML() {
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
        if (xhr.readyState == 4 && xhr.status == 200) {
            document.getElementById('countriesHTML').innerHTML = xhr.responseText;
        }
    };
    xhr.open('GET', '/paises.txt', true);
    xhr.send();
}
```

**Flujo:**
1. XMLHttpRequest solicita `/paises.txt`
2. Servidor envía `Content-Type: text/html`
3. Respuesta contiene `<option>` tags completos
4. Inyección directa en `<select>`

**paises.txt:**
```html
<option value="">Seleccione un país</option>
<option value="AR">Argentina</option>
<option value="BO">Bolivia</option>
...
```

#### c) Cargar paises.json en `<select>`

**JavaScript:**
```javascript
function loadCountriesJSON() {
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
        if (xhr.readyState == 4 && xhr.status == 200) {
            var countries = JSON.parse(xhr.responseText);
            var select = document.getElementById('countriesJSON');
            select.innerHTML = '<option value="">Seleccione un país</option>';
            countries.forEach(function(c) {
                select.innerHTML += '<option value="'+c.code+'">'+c.name+'</option>';
            });
        }
    };
    xhr.open('GET', '/paises.json', true);
    xhr.send();
}
```

**Flujo:**
1. XMLHttpRequest solicita `/paises.json`
2. Servidor envía `Content-Type: application/json`
3. `JSON.parse()` convierte string a array
4. `forEach()` construye `<option>` dinámicamente
5. Actualiza `<select>`

**paises.json:**
```json
[
  {"code": "AR", "name": "Argentina"},
  {"code": "BO", "name": "Bolivia"},
  ...
]
```

## Comparación HTML vs JSON

| Característica | paises.txt (HTML) | paises.json (JSON) |
|----------------|-------------------|---------------------|
| Content-Type | text/html | application/json |
| Formato | `<option>` directo | Array de objetos |
| Procesamiento | Inyección directa | Parse + construcción |
| Flexibilidad | Baja | Alta |
| Rendimiento | Rápido | Requiere parsing |
| Uso típico | UI simple | APIs, datos estructurados |

## Conceptos de Sistemas Distribuidos

### Asincronía
XMLHttpRequest ejecuta peticiones sin bloquear el navegador. Callback se ejecuta cuando llega respuesta.

### Content Negotiation
El servidor indica tipo de contenido mediante `Content-Type` header:
- `text/plain` - Texto sin formato
- `text/html` - Fragmentos HTML
- `application/json` - Datos estructurados

### Separación de Responsabilidades
- **Servidor**: Sirve datos en formato estándar
- **Cliente**: Procesa y renderiza según necesidad

### RESTful Pattern
Recursos identificados por URI:
- `/documento.txt` - Recurso de texto
- `/paises.json` - Recurso de datos

### Protocolo HTTP
Comunicación stateless request/response sobre TCP.

## Diagrama de Secuencia

```
Usuario          Navegador         Servidor HTTP      Filesystem
  |                  |                   |                 |
  |--click---------->|                   |                 |
  |                  |---GET /ajax------>|                 |
  |                  |<--HTML con JS-----|                 |
  |                  |                   |                 |
  |--click btn 1---->|                   |                 |
  |                  |---GET /doc.txt--->|                 |
  |                  |                   |---read--------->|
  |                  |                   |<--content-------|
  |                  |<--text/plain------|                 |
  |                  |--innerHTML--------|                 |
  |<--visualiza------|                   |                 |
  |                  |                   |                 |
  |--click btn 2---->|                   |                 |
  |                  |---GET /pai.txt--->|                 |
  |                  |                   |---read--------->|
  |                  |<--text/html-------|                 |
  |                  |--innerHTML--------|                 |
  |                  |                   |                 |
  |--click btn 3---->|                   |                 |
  |                  |---GET /pai.json-->|                 |
  |                  |                   |---read--------->|
  |                  |<--app/json--------|                 |
  |                  |--JSON.parse-------|                 |
  |                  |--forEach----------|                 |
  |<--visualiza------|                   |                 |
```

## Credenciales de Login

Para probar `/login`:
```
admin | admin123 | Administrator
jdoe | pass987 | Jane Doe
testuser | test | Test User
```

## Troubleshooting

### Puerto 8080 ocupado
```bash
netstat -ano | findstr :8080
taskkill /F /PID <PID>
```

### Servidor no inicia
- Verificar que `users.txt` existe en `/src`
- Verificar compilación sin errores
- Verificar Java instalado: `java -version`

### AJAX no funciona
- Verificar servidor ejecutándose: `netstat -ano | findstr :8080`
- Abrir consola del navegador (F12) para ver errores
- Verificar CORS habilitado (ya incluido)

### Archivos no se cargan
- Verificar que `documento.txt`, `paises.txt`, `paises.json` existen en `/src`
- Verificar logs del servidor para errores 404
- Verificar permisos de lectura

## Logs del Servidor

El servidor imprime:
```
Server listening on port 8080
User file: C:/Users/.../users.txt
Access: http://localhost:8080/login
[192.168.1.100] GET /ajax HTTP/1.1
[192.168.1.100] GET /documento.txt HTTP/1.1
```

## Extensiones Posibles

1. **WebSockets**: Para comunicación bidireccional en tiempo real
2. **Cache**: Almacenar respuestas frecuentes en memoria
3. **HTTPS**: Agregar SSL/TLS para comunicación segura
4. **Compresión**: Gzip para reducir tamaño de respuestas
5. **Rate Limiting**: Limitar requests por IP
6. **Sessions**: Mantener estado entre requests

## Referencias Técnicas

- **XMLHttpRequest**: API estándar W3C para AJAX
- **JSON**: RFC 8259 - Formato de intercambio de datos
- **HTTP/1.1**: RFC 2616 - Protocolo de transferencia
- **Content-Type**: RFC 2045 - MIME types

---

**Autor**: Sistema de desarrollo de Sistemas Distribuidos  
**Fecha**: 2025  
**Versión**: 1.0

