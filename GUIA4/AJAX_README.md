# Servidor HTTP con AJAX - GUIA4

## Archivos Creados

### 1. documento.txt
Archivo de texto plano que se carga dinámicamente mediante AJAX en un `<div>`.

### 2. paises.txt
Archivo con estructura HTML que contiene `<option>` elements para cargar en un `<select>`.

### 3. paises.json
Archivo JSON con array de objetos país (code, name) que se parsea con `JSON.parse()`.

## Rutas Implementadas

### Páginas HTML
- `GET /` - Página principal con enlaces
- `GET /login` - Formulario de autenticación
- `GET /ajax` - **Demo AJAX con las 3 funcionalidades**

### Endpoints de Datos
- `GET /documento.txt` - Content-Type: text/plain
- `GET /paises.txt` - Content-Type: text/html
- `GET /paises.json` - Content-Type: application/json

## Funcionalidades Implementadas

### a) Cargar archivo de texto en `<div>` con AJAX
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

**Flujo:**
1. Usuario hace clic en botón "Cargar documento.txt"
2. JavaScript crea XMLHttpRequest
3. Envía GET asíncrono a /documento.txt
4. Servidor responde con Content-Type: text/plain
5. Callback actualiza innerHTML del `<div id="content">`
6. Saltos de línea convertidos a `<br>`

### b) Cargar países desde HTML (paises.txt)
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

**Flujo:**
1. Botón "Cargar países HTML" ejecuta función
2. XMLHttpRequest solicita /paises.txt
3. Servidor envía Content-Type: text/html
4. Respuesta contiene `<option>` tags directamente
5. Se inyecta HTML directamente en `<select id="countriesHTML">`

### c) Cargar países desde JSON (paises.json)
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

**Flujo:**
1. Click en "Cargar países JSON"
2. XMLHttpRequest solicita /paises.json
3. Servidor envía Content-Type: application/json
4. `JSON.parse()` convierte string a array de objetos
5. `forEach()` itera y construye `<option>` dinámicamente
6. Se actualiza `<select id="countriesJSON">`

## Diferencias Técnicas

| Aspecto | HTML (paises.txt) | JSON (paises.json) |
|---------|-------------------|-------------------|
| Content-Type | text/html | application/json |
| Formato | `<option>` directo | Array de objetos |
| Procesamiento | Inyección directa | Parse + construcción |
| Ventajas | Simple, rápido | Estructurado, flexible |
| Desventajas | Rígido, no escalable | Requiere parsing |

## Servidor HTTP - Cambios Implementados

### Router extendido
```java
if (requestLine.startsWith("GET /ajax")) {
    sendAjaxDemo(out);
} else if (requestLine.startsWith("GET /documento.txt")) {
    serveTextFile(out, "documento.txt", "text/plain");
} else if (requestLine.startsWith("GET /paises.txt")) {
    serveTextFile(out, "paises.txt", "text/html");
} else if (requestLine.startsWith("GET /paises.json")) {
    serveTextFile(out, "paises.json", "application/json");
}
```

### Método serveTextFile
Lee archivos del filesystem y envía con Content-Type apropiado:
```java
private void serveTextFile(PrintWriter out, String filename, String contentType) {
    String filePath = USER_FILE.substring(0, USER_FILE.lastIndexOf('/') + 1) + filename;
    try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        sendHttpResponse(out, "200 OK", contentType, content.toString());
    } catch (Exception e) {
        sendHttpResponse(out, "404 Not Found", "text/html", errorHtml);
    }
}
```

### CORS Header añadido
```java
out.println("Access-Control-Allow-Origin: *");
```
Permite solicitudes AJAX desde cualquier origen (necesario para desarrollo).

## Ejecución

```bash
# Compilar
javac MultiThreadedHttpServer.java

# Ejecutar
java MultiThreadedHttpServer

# Acceder
http://localhost:8080/ajax
```

## Pruebas

1. Abrir navegador en `http://localhost:8080/ajax`
2. Click "Cargar documento.txt" → Ver contenido en div
3. Click "Cargar países HTML" → Ver lista en select
4. Click "Cargar países JSON" → Ver lista parseada en select

## Arquitectura de Comunicación

```
Browser                    HTTP Server               Filesystem
  |                             |                          |
  |---GET /ajax---------------->|                          |
  |<--HTML (AJAX Demo)----------|                          |
  |                             |                          |
  |---GET /documento.txt------->|                          |
  |                             |---read------------------>|
  |                             |<--content----------------|
  |<--text/plain (content)------|                          |
  |                             |                          |
  |---GET /paises.txt---------->|                          |
  |                             |---read------------------>|
  |<--text/html (<option>s)-----|                          |
  |                             |                          |
  |---GET /paises.json--------->|                          |
  |                             |---read------------------>|
  |<--application/json----------|                          |
  |  (parse con JSON.parse)     |                          |
```

## Conceptos de Sistemas Distribuidos

- **Asincronía**: XMLHttpRequest no bloquea el navegador
- **Protocolo HTTP**: GET stateless para recursos
- **Content Negotiation**: Content-Type determina interpretación
- **Separación de Concerns**: Servidor sirve datos, cliente renderiza
- **RESTful**: Recursos identificados por URI (/paises.json)

