# GUIA4-PT2-JQUERY - Servidor HTTP con jQuery

## Descripción

Implementación de servidor HTTP en Java con páginas HTML que utilizan **jQuery** para realizar las 3 funcionalidades AJAX solicitadas.

## Estructura

```
GUIA4-PT2-JQUERY/
├── src/
│   ├── MultiThreadedHttpServer.java  # Servidor HTTP
│   ├── index.html                     # Página principal
│   ├── login.html                     # Login con jQuery
│   ├── ajax-jquery.html               # Demo AJAX con jQuery
│   ├── agenda.html                    # Agenda de contactos (XML)
│   ├── agenda.xml                     # Documento XML con DTD
│   ├── comparacion.html               # Comparación visual
│   ├── 404.html                       # Página de error
│   ├── documento.txt                  # Datos para AJAX
│   ├── paises.txt                     # HTML con opciones
│   ├── paises.json                    # JSON con países
│   └── users.txt                      # Credenciales
└── README.md
```

## Funcionalidades Implementadas

### a) Cargar documento.txt en `<div>` - jQuery.ajax()
```javascript
$.ajax({
    url: '/documento.txt',
    method: 'GET',
    dataType: 'text',
    success: function(data) {
        $('#content').html(data.replace(/\n/g, '<br>'));
    },
    error: function(xhr, status, error) {
        $('#content').html('<span style="color:red;">Error: ' + error + '</span>');
    }
});
```

### b) Cargar paises.txt (HTML) en `<select>` - $.get()
```javascript
$.get('/paises.txt', function(data) {
    $('#countriesHTML').html(data);
}).fail(function(error) {
    console.error('Error:', error);
});
```

### c) Cargar paises.json en `<select>` - $.getJSON()
```javascript
$.getJSON('/paises.json', function(countries) {
    var $select = $('#countriesJSON');
    $select.empty();
    $select.append('<option value="">Seleccione un país</option>');
    
    $.each(countries, function(index, country) {
        $select.append($('<option></option>')
            .val(country.code)
            .text(country.name)
        );
    });
}).fail(function(error) {
    console.error('Error:', error);
});
```

## ✨ Funcionalidad XML - Agenda de Contactos

### Documento XML con DTD

**agenda.xml** - Documento XML que cumple con el DTD especificado:
```xml
<!DOCTYPE agenda [
<!ELEMENT agenda (contacto)*>
<!ELEMENT contacto (nombre,direccion,telefono,email)>
<!ELEMENT nombre (#PCDATA)>
<!ELEMENT direccion (#PCDATA)>
<!ELEMENT telefono (#PCDATA)>
<!ELEMENT email (#PCDATA)>
]>
<agenda>
    <contacto>
        <nombre>Juan Pérez García</nombre>
        <direccion>Av. Arce 2345, La Paz, Bolivia</direccion>
        <telefono>+591 2 2234567</telefono>
        <email>juan.perez@email.com</email>
    </contacto>
    <!-- 7 contactos más... -->
</agenda>
```

### Visualización HTML con jQuery

**agenda.html** - Página que carga y muestra el XML:
```javascript
$.ajax({
    url: '/agenda.xml',
    type: 'GET',
    dataType: 'xml',
    success: function(xml) {
        $(xml).find('contacto').each(function() {
            var contacto = {
                nombre: $(this).find('nombre').text(),
                direccion: $(this).find('direccion').text(),
                telefono: $(this).find('telefono').text(),
                email: $(this).find('email').text()
            };
            // Mostrar contacto en tarjeta
        });
    }
});
```

### Características de la Agenda

✅ **Carga AJAX** - Documento XML cargado con $.ajax()
✅ **Parseo jQuery** - Uso de .find() para extraer elementos
✅ **Grid responsivo** - Tarjetas adaptables
✅ **Búsqueda en tiempo real** - Filtrado por nombre, dirección, teléfono, email
✅ **Estadísticas** - Total de contactos y contactos mostrados
✅ **Animaciones** - Entrada escalonada de tarjetas
✅ **Diseño moderno** - Gradientes, sombras, hover effects
✅ **Íconos** - Emojis para dirección, teléfono, email

## Ventajas de jQuery vs XMLHttpRequest

| Característica | XMLHttpRequest | jQuery |
|----------------|----------------|--------|
| Código | ~10 líneas | ~3 líneas |
| Compatibilidad | Manual | Automática |
| Manejo de errores | Complejo | Simplificado |
| Parsing JSON | Manual | Automático |
| Sintaxis | Verbosa | Concisa |
| Cross-browser | Problemas IE | Resuelto |

## Métodos jQuery Utilizados

### 1. $.ajax()
- Método completo y configurable
- Control total de la petición
- Manejo de success/error explícito
```javascript
$.ajax({
    url: '/ruta',
    method: 'GET',
    dataType: 'text',
    success: function(data) { },
    error: function(xhr, status, error) { }
});
```

### 2. $.get()
- Atajo para GET requests
- Sintaxis más simple
- Ideal para HTML/text
```javascript
$.get('/ruta', function(data) {
    // procesar data
}).fail(function(error) { });
```

### 3. $.getJSON()
- Especializado para JSON
- Parsea automáticamente
- No requiere JSON.parse()
```javascript
$.getJSON('/ruta', function(jsonData) {
    // jsonData ya es objeto JavaScript
});
```

### 4. $(document).ready()
- Ejecuta código cuando DOM está listo
- Equivalente a DOMContentLoaded
```javascript
$(document).ready(function() {
    // código de inicialización
});
```

### 5. .serialize()
- Serializa formularios automáticamente
- Genera string URL-encoded
```javascript
$('#myForm').serialize(); // username=admin&password=123
```

### 6. Selectores y Manipulación DOM
```javascript
$('#id')              // Selecciona por ID
$('.class')           // Selecciona por clase
$('element')          // Selecciona por tag
.html(content)        // Establece HTML
.text(content)        // Establece texto
.val(value)           // Establece valor
.append(element)      // Agrega elemento
.empty()              // Vacía contenedor
```

## CDN de jQuery

Se utiliza la versión 3.7.1 desde CDN oficial:
```html
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
```

**Ventajas del CDN**:
- No requiere descarga local
- Cacheado en navegador
- Siempre actualizado
- Alta disponibilidad

## Ejecución desde IDE

### IntelliJ IDEA

1. **Abrir proyecto**
   - File → Open → Seleccionar carpeta `GUIA4-PT2-JQUERY`

2. **Compilar**
   - Click derecho en `MultiThreadedHttpServer.java`
   - Build → Build Module

3. **Ejecutar**
   - Click derecho en `MultiThreadedHttpServer.java`
   - Run 'MultiThreadedHttpServer.main()'

4. **Verificar salida**
   ```
   Server listening on port 8080
   User file: .../users.txt
   Access: http://localhost:8080/login
   ```

5. **Abrir navegador**
   ```
   http://localhost:8080/
   http://localhost:8080/ajax-jquery.html
   ```

### Detener Servidor

- Click en botón STOP (cuadrado rojo) en consola del IDE
- O presionar Ctrl+F2

## Rutas Disponibles

### HTML
- `GET /` → index.html (página principal)
- `GET /login.html` → Formulario de login
- `GET /ajax-jquery.html` → **Demo AJAX con jQuery**
- `GET /agenda.html` → **Agenda de contactos (XML)**
- `GET /comparacion.html` → Comparación visual
- `GET /404.html` → Página de error

### Datos
- `GET /documento.txt` → text/plain
- `GET /paises.txt` → text/html
- `GET /paises.json` → application/json
- `GET /agenda.xml` → **application/xml (DTD incluido)**

### Dinámico
- `POST /login` → Validación de credenciales

## Credenciales de Prueba

```
admin | admin123 | Administrator
jdoe | pass987 | Jane Doe
testuser | test | Test User
```

## Comparación: Vanilla JS vs jQuery

### Vanilla JavaScript (XMLHttpRequest)
```javascript
var xhr = new XMLHttpRequest();
xhr.onreadystatechange = function() {
    if (xhr.readyState == 4 && xhr.status == 200) {
        var countries = JSON.parse(xhr.responseText);
        var select = document.getElementById('countriesJSON');
        select.innerHTML = '<option value="">Seleccione</option>';
        countries.forEach(function(c) {
            select.innerHTML += '<option value="'+c.code+'">'+c.name+'</option>';
        });
    }
};
xhr.open('GET', '/paises.json', true);
xhr.send();
```

### jQuery
```javascript
$.getJSON('/paises.json', function(countries) {
    var $select = $('#countriesJSON');
    $select.empty().append('<option value="">Seleccione</option>');
    $.each(countries, function(i, c) {
        $select.append($('<option>').val(c.code).text(c.name));
    });
});
```

**Resultado**: 50% menos código, más legible, más mantenible.

## Event Handlers con jQuery

### Click Event
```javascript
$('#btnLoadDoc').click(function() {
    // código al hacer click
});
```

### Form Submit
```javascript
$('#loginForm').submit(function(e) {
    e.preventDefault(); // prevenir submit normal
    $.ajax({ /* petición AJAX */ });
});
```

### Document Ready
```javascript
$(document).ready(function() {
    // inicialización cuando DOM está listo
});
```

## Debugging

### Consola del Navegador (F12)
```javascript
console.log('Documento cargado exitosamente');
console.error('Error:', error);
```

### Network Tab
- Ver todas las peticiones HTTP
- Verificar headers (Content-Type)
- Inspeccionar respuestas

### jQuery Console Commands
```javascript
// Verificar versión de jQuery
$.fn.jquery // "3.7.1"

// Contar elementos seleccionados
$('button').length

// Ver contenido de elemento
$('#content').html()
```

## Características Adicionales Implementadas

### 1. Loading States
Los botones muestran feedback visual al cargar

### 2. Error Handling
Todos los métodos AJAX tienen manejo de errores:
```javascript
.fail(function(error) {
    console.error('Error:', error);
});
```

### 3. Console Logging
Mensajes informativos en consola del navegador

### 4. Reloj en Tiempo Real (index.html)
Actualización cada segundo usando `setInterval()`:
```javascript
setInterval(updateClock, 1000);
```

### 5. Navegación entre Páginas
Barra de navegación consistente en todas las páginas

## Conceptos de Sistemas Distribuidos

### Cliente-Servidor
- Cliente (navegador) solicita recursos
- Servidor (Java) responde con datos
- Protocolo HTTP/1.1

### Asincronía
jQuery maneja peticiones asíncronas sin bloquear UI:
```javascript
$.ajax({ async: true }); // por defecto
```

### Content Negotiation
Servidor envía Content-Type apropiado:
- `text/plain` → documento.txt
- `text/html` → paises.txt
- `application/json` → paises.json

### Separación de Concerns
- **Frontend**: HTML + CSS + jQuery
- **Backend**: Java HTTP Server
- **Datos**: TXT, HTML, JSON

## Tecnologías

- **Backend**: Java SE, Sockets, Threads
- **Frontend**: HTML5, CSS3, JavaScript
- **Librería**: jQuery 3.7.1
- **Protocolo**: HTTP/1.1
- **Formato de datos**: Text, HTML, JSON

## Ventajas de esta Implementación

✅ **Código más limpio** - jQuery reduce verbosidad
✅ **Cross-browser** - Compatible con todos los navegadores
✅ **Mantenible** - Sintaxis intuitiva y clara
✅ **Productivo** - Desarrollo más rápido
✅ **Robusto** - Manejo de errores integrado
✅ **Moderno** - Uso de CDN y mejores prácticas

## Troubleshooting

### Error: jQuery is not defined
Verificar que CDN esté cargado:
```html
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
```

### Puerto 8080 ocupado
```bash
netstat -ano | findstr :8080
taskkill /F /PID <PID>
```

### AJAX no funciona
- Verificar servidor ejecutándose
- Abrir DevTools (F12) → Console
- Verificar errores de red en Network tab

## Referencias

- **jQuery API**: https://api.jquery.com/
- **jQuery AJAX**: https://api.jquery.com/category/ajax/
- **jQuery Selectors**: https://api.jquery.com/category/selectors/

---

**Módulo**: GUIA4-PT2-JQUERY  
**Objetivo**: Implementar AJAX usando jQuery  
**Estado**: ✅ Completado  
**Ejecutar desde**: IntelliJ IDEA

