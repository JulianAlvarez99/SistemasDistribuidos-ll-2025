# GUIA4-PT2-CHAT-WEBSOCKET - Chat en Tiempo Real

## Descripción

Chat de mensajería instantánea usando **WebSocket nativo en Java** (sin Tomcat). Comunicación bidireccional en tiempo real con lista de usuarios conectados.

## ❌ NO Requiere Apache Tomcat

Este servidor usa:
- **Sockets TCP nativos** de Java
- **WebSocket protocol** implementado manualmente
- **HTTP server embebido** para servir HTML
- **Thread pool** para concurrencia

## Características Implementadas

### ✅ Ingreso con Nickname
- Pantalla de login al iniciar
- Validación de nickname
- Conexión WebSocket después del login

### ✅ Lista de Usuarios Conectados
- Panel lateral con usuarios activos
- Actualización automática cuando alguien se une/sale
- Indicador de conexión (🟢)

### ✅ Mensajes en Tiempo Real
- Broadcast a todos los usuarios conectados
- Mensajes propios alineados a la derecha
- Mensajes de otros alineados a la izquierda
- Timestamp en cada mensaje

### ✅ Notificaciones de Sistema
- Aviso cuando alguien se une
- Aviso cuando alguien sale
- Mensajes destacados en amarillo

## Tecnología

### Backend (Java)
```java
HTTP Server: Puerto 8080 (sirve HTML)
WebSocket Server: Puerto 8081 (comunicación en tiempo real)
```

**Componentes**:
- `ChatWebSocketServer` - Servidor principal
- `WebSocketClient` - Manejador de cada conexión
- `ConcurrentHashMap` - Thread-safe storage

**WebSocket Protocol**:
- Handshake HTTP/1.1 101
- Frame encoding/decoding manual
- Text frames (opcode 0x81)

### Frontend (HTML + JavaScript)
- **WebSocket API** nativa del navegador
- **JSON** para mensajes estructurados
- **CSS Grid** para layout
- **Vanilla JavaScript** (sin frameworks)

## Estructura de Mensajes JSON

### Cliente → Servidor

**Join**:
```json
{
  "type": "join",
  "nickname": "Juan"
}
```

**Mensaje**:
```json
{
  "type": "message",
  "message": "Hola a todos"
}
```

### Servidor → Cliente

**Mensaje**:
```json
{
  "type": "message",
  "sender": "Juan",
  "message": "Hola a todos",
  "timestamp": "Thu Nov 22 10:30:45 BOT 2024"
}
```

**Lista de usuarios**:
```json
{
  "type": "userlist",
  "users": ["Juan", "María", "Carlos"]
}
```

## Ejecución desde IntelliJ IDEA

### Paso 1: Abrir Proyecto
```
File → Open → GUIA4-PT2-CHAT-WEBSOCKET
```

### Paso 2: Ejecutar Servidor
```
Click derecho en ChatWebSocketServer.java
Run 'ChatWebSocketServer.main()'
```

### Paso 3: Verificar Consola
```
Chat Server started
HTTP Server: http://localhost:8080
WebSocket Server: ws://localhost:8081
HTTP Server listening on port 8080
WebSocket Server listening on port 8081
```

### Paso 4: Abrir Navegador
```
http://localhost:8080
```

### Paso 5: Probar Chat
1. **Primera pestaña**: Ingresar nickname "Usuario1" → Join
2. **Segunda pestaña**: Abrir `http://localhost:8080` → "Usuario2" → Join
3. **Usuario1** verá: "Usuario2 se unió al chat"
4. **Lista de usuarios** se actualiza automáticamente
5. **Enviar mensajes** y ver broadcast en tiempo real

### Paso 6: Detener
```
Click STOP en consola de IntelliJ
O Ctrl+F2
```

## Flujo de Funcionamiento

### 1. Conexión Inicial
```
Usuario abre navegador
    ↓
Pantalla de login
    ↓
Ingresa nickname
    ↓
Click "Unirse al Chat"
    ↓
JavaScript crea WebSocket('ws://localhost:8081')
    ↓
Handshake HTTP → 101 Switching Protocols
    ↓
Conexión WebSocket establecida
    ↓
Cliente envía {"type":"join", "nickname":"..."}
```

### 2. Usuario se Une
```
Servidor recibe mensaje de join
    ↓
Registra nickname en clientNicknames
    ↓
Agrega cliente a lista de conexiones
    ↓
broadcastUserList() → Envía lista actualizada a TODOS
    ↓
broadcastMessage("system", "X se unió al chat")
    ↓
Todos los clientes reciben actualizaciones
```

### 3. Envío de Mensaje
```
Usuario escribe mensaje y presiona Enter/Enviar
    ↓
JavaScript envía {"type":"message", "message":"..."}
    ↓
Servidor recibe mensaje
    ↓
Extrae nickname del remitente
    ↓
broadcastMessage(nickname, mensaje) a TODOS
    ↓
Cada cliente recibe {"type":"message", "sender":"...", ...}
    ↓
Cada navegador muestra mensaje (derecha si es propio, izquierda si es de otro)
```

### 4. Usuario se Desconecta
```
Usuario cierra pestaña/navegador
    ↓
Conexión WebSocket se cierra
    ↓
Servidor detecta desconexión
    ↓
Elimina cliente de lista
    ↓
Elimina nickname de map
    ↓
broadcastUserList() → Lista actualizada
    ↓
broadcastMessage("system", "X salió del chat")
    ↓
Todos los clientes actualizan UI
```

## Implementación WebSocket Manual

### Handshake
```java
// Cliente envía:
GET / HTTP/1.1
Upgrade: websocket
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==

// Servidor responde:
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

**Accept Key**: SHA-1(key + magic) en Base64

### Frame Structure
```
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127  |
+ - - - - - - - - - - - - - - - +-------------------------------+
|                               |Masking-key, if MASK set to 1  |
+-------------------------------+-------------------------------+
| Masking-key (continued)       |          Payload Data         |
+-------------------------------- - - - - - - - - - - - - - - - +
:                     Payload Data continued ...                :
+ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
|                     Payload Data continued ...                |
+---------------------------------------------------------------+
```

**Opcode**: 0x1 = Text, 0x8 = Close  
**Mask**: Cliente → Servidor siempre masked

## Concurrencia Thread-Safe

```java
// Set thread-safe para clientes
private static final Set<WebSocketClient> clients = 
    ConcurrentHashMap.newKeySet();

// Map thread-safe para nicknames
private static final Map<WebSocketClient, String> clientNicknames = 
    new ConcurrentHashMap<>();
```

**Broadcast**:
```java
for (WebSocketClient client : clients) {
    client.sendMessage(json);
}
```

Cada cliente en su propio thread - sin locks necesarios para iteración.

## Interfaz de Usuario

### Login Screen
```
┌─────────────────────────┐
│   💬 Chat WebSocket     │
│                         │
│  ┌──────────────────┐  │
│  │ Ingrese nickname │  │
│  └──────────────────┘  │
│                         │
│  ┌──────────────────┐  │
│  │  Unirse al Chat  │  │
│  └──────────────────┘  │
└─────────────────────────┘
```

### Chat Screen
```
┌────────────┬─────────────────────────────────┐
│👥 Usuarios │ 💬 Chat en Tiempo Real          │
│Conectados  │ Nickname                        │
│            ├─────────────────────────────────┤
│🟢 Usuario1 │                                 │
│🟢 Usuario2 │ [Usuario2]: Hola                │
│🟢 Usuario3 │                                 │
│            │        [Mi mensaje aquí]        │
│            │                                 │
│            │ Usuario3 se unió al chat        │
│            │                                 │
│            ├─────────────────────────────────┤
│            │ ┌─────────────────┐  ┌──────┐ │
│            │ │ Escribe mensaje │  │Enviar│ │
│            │ └─────────────────┘  └──────┘ │
└────────────┴─────────────────────────────────┘
```

## Características Avanzadas

### 1. Auto-scroll
```javascript
messagesDiv.scrollTop = messagesDiv.scrollHeight;
```
Se desplaza automáticamente al último mensaje.

### 2. Enter para Enviar
```javascript
messageInput.addEventListener('keypress', function(e) {
    if (e.key === 'Enter') sendMessage();
});
```

### 3. Timestamp Formateado
```javascript
new Date(timestamp).toLocaleTimeString()
```

### 4. Distinción Visual
- **Mensajes propios**: Fondo violeta, derecha
- **Mensajes otros**: Fondo gris, izquierda
- **Mensajes sistema**: Fondo amarillo, centrado

### 5. Escape JSON
```java
text.replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
```
Previene errores de sintaxis JSON.

## Ventajas vs Tomcat

| Aspecto | WebSocket Nativo | Tomcat + javax.websocket |
|---------|------------------|--------------------------|
| Setup | ✅ Ninguno | ❌ Configurar Tomcat |
| Código | ✅ Todo visible | ❌ Oculto en framework |
| Control | ✅ Total | ❌ Limitado |
| Aprendizaje | ✅ Profundo | ❌ Superficial |
| Peso | ✅ ~20KB | ❌ ~10MB |
| Despliegue | ✅ java ChatWebSocketServer | ❌ WAR + Tomcat |

**Para este ejercicio**: WebSocket nativo es PERFECTO para entender el protocolo.

## Testing Multi-usuario

### Escenario 1: Dos Usuarios
1. Abrir `http://localhost:8080` en Chrome
2. Login como "Alice"
3. Abrir `http://localhost:8080` en Firefox (o pestaña incógnita)
4. Login como "Bob"
5. Alice ve: "Bob se unió al chat"
6. Bob envía: "Hola Alice"
7. Alice recibe mensaje inmediatamente

### Escenario 2: Múltiples Usuarios
1. Abrir 5 pestañas diferentes
2. Login: User1, User2, User3, User4, User5
3. Cada uno ve lista con 5 usuarios
4. User3 envía mensaje → Todos lo reciben
5. User1 cierra pestaña → Todos ven "User1 salió del chat"
6. Lista actualiza a 4 usuarios

## Troubleshooting

### Puerto 8080/8081 ocupado
```bash
netstat -ano | findstr :8080
netstat -ano | findstr :8081
taskkill /F /PID <PID>
```

### WebSocket no conecta
- Verificar que servidor esté corriendo
- Abrir DevTools (F12) → Network → WS
- Ver handshake 101 Switching Protocols

### Mensajes no llegan
- F12 → Console → Ver errores
- Verificar formato JSON en servidor
- Ver logs en consola de IntelliJ

## Conceptos de Sistemas Distribuidos

### 1. Comunicación Bidireccional
- Cliente → Servidor
- Servidor → Cliente
- Sin polling, push real

### 2. Broadcast
- Un mensaje → N destinatarios
- Distribución simultánea

### 3. Eventos
- Join/Leave detectados automáticamente
- Estado compartido (lista de usuarios)

### 4. Protocolo de Aplicación
- Sobre WebSocket (sobre TCP)
- Mensajes JSON estructurados
- Type-based routing

### 5. Concurrencia
- Múltiples clientes simultáneos
- Thread pool
- Estructuras thread-safe

## Resumen

✅ **Chat en tiempo real** con WebSocket  
✅ **Login con nickname** obligatorio  
✅ **Lista de usuarios** actualizada automáticamente  
✅ **Notificaciones** de join/leave  
✅ **Broadcast** de mensajes a todos  
✅ **Sin Tomcat** - Servidor nativo Java  
✅ **Thread-safe** - ConcurrentHashMap  
✅ **UI moderna** - CSS Grid, gradientes  

---

**Ejecutar**: `ChatWebSocketServer.main()` desde IntelliJ  
**Acceder**: `http://localhost:8080`  
**Probar**: Múltiples pestañas/navegadores  
**Estado**: ✅ Listo para demostración

