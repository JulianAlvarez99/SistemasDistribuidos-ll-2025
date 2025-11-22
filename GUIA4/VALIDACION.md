# VALIDACIÓN DE IMPLEMENTACIÓN - GUIA4

## Ejercicio Solicitado

Utilizando su propio Servidor HTTP desarrollado en JAVA implementar las páginas HTML que cumplan con:

### ✅ a) Mostrar el contenido de un archivo de texto en un bloque de tipo `<div>` utilizando AJAX (documento.txt)

**Implementado**: SÍ

**Archivos**:
- `src/documento.txt` - Archivo de texto con contenido de ejemplo
- `MultiThreadedHttpServer.java` - Ruta `GET /documento.txt` con Content-Type: text/plain
- Página `/ajax` - Función JavaScript `loadDocument()` con XMLHttpRequest

**Código JavaScript**:
```javascript
function loadDocument(){
  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function(){
    if(xhr.readyState == 4 && xhr.status == 200){
      document.getElementById('content').innerHTML = xhr.responseText.replace(/\n/g,'<br>');
    }
  };
  xhr.open('GET','/documento.txt',true);
  xhr.send();
}
```

**Elemento HTML**:
```html
<div id="content"></div>
```

**Verificación**: Click en "Cargar documento.txt" → Contenido aparece en div

---

### ✅ b) Cargar una lista de países en un elemento de tipo `<select>`. El archivo debe contener la lista con una estructura HTML (paises.txt)

**Implementado**: SÍ

**Archivos**:
- `src/paises.txt` - Archivo con `<option>` tags HTML
- `MultiThreadedHttpServer.java` - Ruta `GET /paises.txt` con Content-Type: text/html
- Página `/ajax` - Función JavaScript `loadCountriesHTML()`

**Contenido paises.txt**:
```html
<option value="">Seleccione un país</option>
<option value="AR">Argentina</option>
<option value="BO">Bolivia</option>
<option value="BR">Brasil</option>
...
```

**Código JavaScript**:
```javascript
function loadCountriesHTML(){
  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function(){
    if(xhr.readyState == 4 && xhr.status == 200){
      document.getElementById('countriesHTML').innerHTML = xhr.responseText;
    }
  };
  xhr.open('GET','/paises.txt',true);
  xhr.send();
}
```

**Elemento HTML**:
```html
<select id="countriesHTML" size="5"></select>
```

**Verificación**: Click en "Cargar países HTML" → Opciones aparecen en select

---

### ✅ c) Cargar la lista de países contenido en un archivo de tipo JSON (paises.json)

**Implementado**: SÍ

**Archivos**:
- `src/paises.json` - Archivo JSON con array de objetos
- `MultiThreadedHttpServer.java` - Ruta `GET /paises.json` con Content-Type: application/json
- Página `/ajax` - Función JavaScript `loadCountriesJSON()` con JSON.parse()

**Contenido paises.json**:
```json
[
  {"code": "AR", "name": "Argentina"},
  {"code": "BO", "name": "Bolivia"},
  {"code": "BR", "name": "Brasil"},
  ...
]
```

**Código JavaScript**:
```javascript
function loadCountriesJSON(){
  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function(){
    if(xhr.readyState == 4 && xhr.status == 200){
      var countries = JSON.parse(xhr.responseText);
      var select = document.getElementById('countriesJSON');
      select.innerHTML = '<option value="">Seleccione un país</option>';
      countries.forEach(function(c){
        select.innerHTML += '<option value="'+c.code+'">'+c.name+'</option>';
      });
    }
  };
  xhr.open('GET','/paises.json',true);
  xhr.send();
}
```

**Elemento HTML**:
```html
<select id="countriesJSON" size="5"></select>
```

**Verificación**: Click en "Cargar países JSON" → Opciones construidas dinámicamente aparecen

---

## Servidor HTTP Propio en Java

✅ **Requisito**: "Utilizando su propio Servidor HTTP desarrollado en JAVA"

**Archivo**: `MultiThreadedHttpServer.java`

**Características**:
- Servidor HTTP desde cero (sin frameworks)
- Socket TCP en puerto 8080
- Thread pool con ExecutorService
- Parsing de HTTP requests (método, headers, body)
- Construcción manual de HTTP responses
- Manejo de Content-Type dinámico
- Routing personalizado

**Código core**:
```java
try (ServerSocket serverSocket = new ServerSocket(PORT)) {
    while (running) {
        Socket clientSocket = serverSocket.accept();
        threadPool.execute(new ClientHandler(clientSocket));
    }
}
```

**No se usó**: Tomcat, Jetty, Spring Boot, ni ningún framework HTTP

---

## Checklist de Cumplimiento

| Requisito | Implementado | Archivo/Método |
|-----------|--------------|----------------|
| Servidor HTTP en Java | ✅ | MultiThreadedHttpServer.java |
| AJAX con XMLHttpRequest | ✅ | sendAjaxDemo() |
| Cargar texto en `<div>` | ✅ | loadDocument() + /documento.txt |
| Archivo documento.txt | ✅ | src/documento.txt |
| Cargar HTML en `<select>` | ✅ | loadCountriesHTML() + /paises.txt |
| Archivo paises.txt con HTML | ✅ | src/paises.txt |
| Cargar JSON en `<select>` | ✅ | loadCountriesJSON() + /paises.json |
| Archivo paises.json | ✅ | src/paises.json |
| Parsear JSON con JavaScript | ✅ | JSON.parse() |
| Content-Type correcto | ✅ | text/plain, text/html, application/json |
| Página HTML funcional | ✅ | GET /ajax |

---

## Prueba de Funcionamiento

### Paso 1: Iniciar Servidor
```bash
cd C:\Users\julia\IdeaProjects\SistemasDistribuidos-ll-2025\GUIA4
start-server.bat
```

**Output esperado**:
```
Compilando MultiThreadedHttpServer...
Servidor compilado exitosamente.
Iniciando servidor en puerto 8080...
Server listening on port 8080
User file: C:/.../users.txt
Access: http://localhost:8080/login
```

### Paso 2: Acceder a la página
Abrir navegador: `http://localhost:8080/ajax`

### Paso 3: Probar funcionalidad (a)
1. Click en botón "Cargar documento.txt"
2. **Resultado esperado**: Texto aparece en el div con saltos de línea

### Paso 4: Probar funcionalidad (b)
1. Click en botón "Cargar países HTML"
2. **Resultado esperado**: Select se llena con opciones de países

### Paso 5: Probar funcionalidad (c)
1. Click en botón "Cargar países JSON"
2. **Resultado esperado**: Select se llena con países parseados desde JSON

### Paso 6: Verificar logs del servidor
Consola debe mostrar:
```
[127.0.0.1] GET /ajax HTTP/1.1
[127.0.0.1] GET /documento.txt HTTP/1.1
[127.0.0.1] GET /paises.txt HTTP/1.1
[127.0.0.1] GET /paises.json HTTP/1.1
```

---

## Archivos Entregables

```
GUIA4/
├── src/
│   ├── MultiThreadedHttpServer.java    ← Servidor HTTP propio
│   ├── documento.txt                    ← Archivo para AJAX (a)
│   ├── paises.txt                       ← Archivo HTML para (b)
│   ├── paises.json                      ← Archivo JSON para (c)
│   └── users.txt                        ← Datos de autenticación
├── start-server.bat                     ← Script de inicio
├── test-endpoints.bat                   ← Script de pruebas
├── test-ajax.html                       ← Página de prueba standalone
├── README_COMPLETO.md                   ← Documentación completa
└── VALIDACION.md                        ← Este archivo
```

---

## Tecnologías Utilizadas

### Backend (Servidor)
- Java SE (Sockets, I/O, Threads)
- TCP/IP
- HTTP/1.1 manual
- ExecutorService (thread pool)

### Frontend (Cliente)
- HTML5
- CSS3 (inline styles)
- JavaScript vanilla
- XMLHttpRequest API
- JSON.parse()
- DOM manipulation

### Protocolos
- HTTP GET
- Content-Type negotiation
- CORS (Access-Control-Allow-Origin)

---

## Conceptos de Sistemas Distribuidos Aplicados

1. **Cliente-Servidor**: Arquitectura HTTP request/response
2. **Asincronía**: AJAX no bloqueante
3. **Concurrencia**: Thread pool en servidor
4. **Protocolo estándar**: HTTP/1.1
5. **Formato de datos**: text/plain, HTML, JSON
6. **RESTful**: Recursos identificados por URI
7. **Stateless**: Cada request independiente
8. **Content Negotiation**: Content-Type determina interpretación

---

## Cumplimiento Total

✅ **TODAS las funcionalidades solicitadas están implementadas y funcionando**

- Servidor HTTP propio en Java (no framework)
- Tres funcionalidades AJAX distintas
- Archivos de datos en formatos solicitados
- Código compilable y ejecutable
- Documentación completa

**Estado**: LISTO PARA ENTREGA

