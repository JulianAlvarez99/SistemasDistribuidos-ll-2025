# Separación de Responsabilidades - Servidor HTTP

## Arquitectura Refactorizada

### Antes (Acoplamiento)
```
MultiThreadedHttpServer.java
├── Lógica de servidor HTTP
├── HTML de login (inline)
├── HTML de AJAX demo (inline)
├── HTML de página principal (inline)
└── HTML de 404 (inline)
```
**Problema**: HTML mezclado con código Java. Difícil de mantener y modificar el frontend.

### Después (Separación de Concerns)
```
GUIA4/src/
├── MultiThreadedHttpServer.java   # Solo lógica de servidor
├── login.html                      # Página de login
├── ajax.html                       # Demo AJAX
├── index.html                      # Página principal
├── 404.html                        # Página de error
├── documento.txt                   # Datos para AJAX
├── paises.txt                      # Datos HTML
├── paises.json                     # Datos JSON
└── users.txt                       # Credenciales
```
**Ventaja**: Separación clara entre servidor y presentación.

## Beneficios de la Separación

### 1. Independencia de Funcionalidades
- **Frontend** puede modificarse sin tocar código Java
- **Backend** solo maneja lógica HTTP y autenticación
- Cambios en UI no requieren recompilar servidor

### 2. Mantenibilidad
- HTML en archivos separados es más fácil de editar
- Diseñadores web pueden trabajar sin conocer Java
- CSS y JavaScript separados del servidor

### 3. Reutilización
- Mismo servidor puede servir diferentes frontends
- HTML puede usarse con otros servidores
- Fácil crear versiones alternativas (mobile, desktop)

### 4. Escalabilidad
- Frontend puede servirse desde CDN
- Archivos estáticos cacheables
- Servidor solo procesa lógica de negocio

### 5. Testing
- Probar frontend con archivos locales (file://)
- Probar backend con herramientas como curl
- Mock de servicios más sencillo

## Cambios Realizados

### Servidor Java (MultiThreadedHttpServer.java)

**Eliminado**:
- `sendLoginForm()` - HTML inline
- `sendAjaxDemo()` - HTML inline
- `sendDefaultPage()` - HTML inline
- `sendNotFoundResponse()` - HTML inline

**Agregado**:
- `serveFile(filename, contentType)` - Método genérico para servir archivos

**Router simplificado**:
```java
if (requestLine.startsWith("GET /login.html")) {
    serveFile(out, "login.html", "text/html");
} else if (requestLine.startsWith("GET /ajax.html")) {
    serveFile(out, "ajax.html", "text/html");
} else if (requestLine.startsWith("GET /index.html")) {
    serveFile(out, "index.html", "text/html");
} else if (requestLine.startsWith("GET / ")) {
    serveFile(out, "index.html", "text/html");
} else {
    serveFile(out, "404.html", "text/html");
}
```

### Archivos HTML Creados

#### 1. login.html
- Formulario de autenticación
- CSS embebido
- POST a `/login`

#### 2. ajax.html
- Demo de las 3 funcionalidades AJAX
- JavaScript con XMLHttpRequest
- Navegación entre páginas

#### 3. index.html
- Página principal
- Enlaces a login y AJAX
- Fecha/hora generada en cliente

#### 4. 404.html
- Página de error
- Enlace de retorno

## Flujo de Funcionamiento

### Petición de Página HTML
```
Browser → GET /ajax.html → Servidor
Servidor → lee ajax.html del filesystem
Servidor → HTTP 200 + Content-Type: text/html
Browser ← Recibe HTML completo
Browser → Renderiza página
```

### Petición AJAX (desde ajax.html)
```
JavaScript → XMLHttpRequest GET /documento.txt
Servidor → lee documento.txt
Servidor → HTTP 200 + Content-Type: text/plain
JavaScript ← Recibe texto
JavaScript → Actualiza DOM (div#content)
```

### Autenticación
```
Browser → Carga login.html (archivo estático)
Usuario → Completa formulario
Browser → POST /login con credenciales
Servidor → Valida contra users.txt
Servidor → Genera HTML de respuesta (dinámico)
Browser ← Recibe success/error
```

## Rutas del Servidor

### Archivos Estáticos (filesystem)
- `GET /` → `index.html`
- `GET /index.html` → `index.html`
- `GET /login.html` → `login.html`
- `GET /ajax.html` → `ajax.html`
- `GET /404.html` → `404.html`
- `GET /documento.txt` → `documento.txt`
- `GET /paises.txt` → `paises.txt`
- `GET /paises.json` → `paises.json`

### Contenido Dinámico (generado en código)
- `POST /login` → Valida y genera respuesta HTML

## Patrón de Diseño Aplicado

### Static Content Server Pattern
```
┌─────────────┐
│   Cliente   │
└──────┬──────┘
       │ HTTP Request
       ▼
┌─────────────────────┐
│  HTTP Server        │
│  (Java)             │
├─────────────────────┤
│ Router              │
│ ├─ Static Files     │───┐
│ └─ Dynamic Content  │   │
└─────────────────────┘   │
                          │
                          ▼
                    ┌──────────────┐
                    │  Filesystem  │
                    │  *.html      │
                    │  *.txt       │
                    │  *.json      │
                    └──────────────┘
```

## Ventajas en Sistemas Distribuidos

### 1. Microservicios
- Frontend y backend pueden desplegarse separadamente
- Múltiples frontends pueden consumir mismo backend

### 2. Load Balancing
- Archivos estáticos en servidor web (nginx, Apache)
- Lógica dinámica en servidor de aplicaciones (Java)

### 3. Caching
- HTML estático cacheable en navegador
- CDN puede servir assets sin tocar servidor Java

### 4. Versionado
- Frontend y backend versionados independientemente
- Actualizaciones de UI sin downtime de servidor

## Ejemplo de Despliegue Escalable

```
                ┌──────────────┐
                │   CDN        │
                │  (HTML/CSS)  │
                └──────┬───────┘
                       │
          ┌────────────┴────────────┐
          │                         │
    ┌─────▼─────┐           ┌──────▼──────┐
    │ Frontend  │           │ Frontend    │
    │ Server    │           │ Server      │
    │ (nginx)   │           │ (nginx)     │
    └─────┬─────┘           └──────┬──────┘
          │                        │
          └────────────┬───────────┘
                       │
              ┌────────▼────────┐
              │  Load Balancer  │
              └────────┬────────┘
                       │
          ┌────────────┴────────────┐
          │                         │
    ┌─────▼─────┐           ┌──────▼──────┐
    │ Backend   │           │ Backend     │
    │ Server    │           │ Server      │
    │ (Java)    │           │ (Java)      │
    └───────────┘           └─────────────┘
```

## Comandos Útiles

### Compilar servidor
```bash
javac MultiThreadedHttpServer.java
```

### Ejecutar servidor
```bash
java MultiThreadedHttpServer
```

### Probar archivos estáticos
```bash
curl http://localhost:8080/index.html
curl http://localhost:8080/ajax.html
curl http://localhost:8080/documento.txt
```

### Probar AJAX desde navegador
```
http://localhost:8080/ajax.html
```

## Resumen

✅ **Separación de responsabilidades lograda**
✅ **HTML independiente del código Java**
✅ **Frontend modificable sin recompilar**
✅ **Servidor genérico reutilizable**
✅ **Arquitectura escalable y mantenible**

**Resultado**: Sistema modular con independencia funcional entre presentación y lógica de negocio.

