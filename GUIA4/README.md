# Multi-threaded HTTP Server

## Descripción Técnica

Servidor HTTP multi-threaded implementado en Java con pool de threads y autenticación basada en archivo.

## Arquitectura

### Componentes principales:

1. **Thread Pool**: ExecutorService con 10 threads fijos
2. **ClientHandler**: Runnable que procesa cada conexión HTTP
3. **Router**: Enrutamiento basado en método y path
4. **Authenticator**: Validación de credenciales contra users.txt

### Rutas disponibles:

- `GET /` - Página principal (muestra hora del servidor)
- `GET /login` - Formulario de login
- `POST /login` - Procesamiento de autenticación

## Flujo de Ejecución

### 1. Inicio del Servidor
```
ServerSocket escucha en puerto 8080
→ Timeout de 1000ms para aceptar conexiones
→ Verifica existencia de users.txt
→ Thread pool inicializado con 10 threads
```

### 2. Solicitud GET /login
```
Navegador → http://localhost:8080/login
ServerSocket acepta conexión
→ Socket delegado a ClientHandler (ejecutado en thread del pool)
→ ClientHandler lee request line y headers
→ Router identifica GET /login
→ sendLoginForm() genera HTML con formulario
→ Respuesta HTTP 200 OK enviada al navegador
```

### 3. Envío de Credenciales
```
Usuario completa formulario (username + password)
→ Navegador serializa datos: "username=admin&password=admin123"
→ POST /login con Content-Type: application/x-www-form-urlencoded
```

### 4. Procesamiento POST /login
```
ServerSocket acepta nueva conexión
→ ClientHandler lee request line: POST /login
→ Lee headers, extrae Content-Length
→ Lee exactamente Content-Length bytes del body
→ parseFormData() decodifica URL encoding (%20, etc)
→ validateUser() lee users.txt línea por línea
→ Compara username|password con formato: user|pass|fullname
```

### 5. Respuestas

**Autenticación exitosa:**
```
validateUser retorna fullName
→ HTML generado con mensaje de bienvenida
→ HTTP 200 OK + HTML personalizado
→ Log: [IP] Login successful: FullName
```

**Autenticación fallida:**
```
validateUser retorna null
→ HTML generado con mensaje de error
→ HTTP 401 Unauthorized + HTML de error
→ Log: [IP] Login failed: username
```

## Mejoras Implementadas

1. **Thread Pool**: Reutilización de threads vs crear thread por conexión
2. **Shutdown Hook**: Cierre limpio del pool al terminar JVM
3. **Logging**: IP cliente + acción en cada request
4. **Timeout**: ServerSocket con timeout para permitir shutdown graceful
5. **HTML Moderno**: CSS inline, responsive, UX mejorada
6. **XSS Protection**: escapeHtml() previene inyección de código
7. **Error Handling**: 400, 401, 404 con páginas personalizadas
8. **Validación robusta**: Manejo de Content-Length malformado

## Seguridad

- Passwords en texto plano (solo para desarrollo)
- Sin HTTPS (HTTP plano)
- Sin rate limiting
- Sin protección CSRF
- Validación básica de inputs

## Ejecución

```bash
# Compilar
javac MultiThreadedHttpServer.java

# Ejecutar
java MultiThreadedHttpServer

# Acceder
http://localhost:8080/login
```

## Credenciales de Prueba (users.txt)

```
admin|admin123|Administrator
jdoe|pass987|Jane Doe
testuser|test|Test User
```

## Tecnologías

- Java SE
- Sockets TCP
- HTTP/1.1
- Thread Pool (ExecutorService)
- HTML5 + CSS3

