# GUIA4-PT2-CHAT-WEBSOCKET - Chat Modular

## ✅ Mejoras Implementadas

### 1. Scroll Arreglado
- El área de mensajes ahora es completamente scrolleable
- El área de input siempre visible en la parte inferior
- No se oculta cuando hay muchos mensajes

### 2. Arquitectura Modular - Independencia de Funcionalidades

**Separación de archivos**:
- `ChatServer.java` - **Solo servidor** (backend)
- `index.html` - **Solo estructura** (HTML)
- `styles.css` - **Solo estilos** (CSS)
- `client.js` - **Solo lógica cliente** (JavaScript)

## Estructura del Proyecto

```
GUIA4-PT2-CHAT-WEBSOCKET/
├── src/
│   ├── ChatServer.java              # Servidor HTTP + WebSocket
│   ├── index.html                   # Estructura HTML
│   ├── styles.css                   # Estilos CSS
│   ├── client.js                    # Lógica cliente JS
│   └── ChatWebSocketServer.java     # Versión monolítica (legacy)
├── GUIA4-PT2-CHAT-WEBSOCKET.iml
└── README_MODULAR.md
```

## Comparación: Antes vs Después

### Antes (Monolítico)
```
ChatWebSocketServer.java (556 líneas)
└── Servidor + HTML + CSS + JavaScript todo junto
```
**Problemas**:
- ❌ Difícil de mantener
- ❌ HTML en strings Java
- ❌ CSS mezclado con lógica
- ❌ Recompilar para cambiar UI

### Después (Modular)
```
ChatServer.java (305 líneas)  → Backend puro
index.html (30 líneas)        → Estructura
styles.css (210 líneas)       → Diseño
client.js (90 líneas)         → Lógica cliente
```
**Ventajas**:
- ✅ Separación de responsabilidades
- ✅ Fácil mantenimiento
- ✅ Modificar UI sin recompilar
- ✅ Reutilizable
- ✅ Código más legible

## Ejecución

### Desde IntelliJ IDEA

1. **Ejecutar servidor**
   ```
   Click derecho en ChatServer.java
   Run 'ChatServer.main()'
   ```

2. **Abrir navegador**
   ```
   http://localhost:8080
   ```

3. **Probar**
   - Abrir múltiples pestañas
   - Login con diferentes nicknames
   - Enviar mensajes
   - Verificar scroll funciona correctamente

## Archivos Detallados

### 1. ChatServer.java (Backend)

**Responsabilidades**:
- Servidor HTTP (puerto 8080) - Sirve archivos estáticos
- Servidor WebSocket (puerto 8081) - Conexiones en tiempo real
- Gestión de clientes conectados
- Broadcast de mensajes
- Lista de usuarios

**Rutas HTTP**:
- `GET /` → index.html
- `GET /styles.css` → styles.css
- `GET /client.js` → client.js

**Protocolo WebSocket**:
- Handshake HTTP 101
- Frame encoding/decoding
- Mensajes JSON

### 2. index.html (Estructura)

**Contenido**:
- Pantalla de login
- Pantalla de chat
- Área de usuarios
- Área de mensajes
- Input para escribir

**Enlaces**:
```html
<link rel="stylesheet" href="/styles.css">
<script src="/client.js"></script>
```

### 3. styles.css (Diseño)

**Estilos**:
- Layout responsive (CSS Grid)
- Gradiente de fondo
- Estilos de mensajes (propios, otros, sistema)
- Scroll en área de mensajes
- **ARREGLO**: `min-height: 0` en `#messages`
- **ARREGLO**: `flex-shrink: 0` en header e input

### 4. client.js (Lógica Cliente)

**Funciones**:
- `join()` - Conectar al servidor WebSocket
- `sendMessage()` - Enviar mensaje
- `addMessage()` - Agregar mensaje al DOM
- `updateUserList()` - Actualizar lista de usuarios

**Eventos**:
- WebSocket `onopen`, `onmessage`, `onerror`, `onclose`
- Enter para enviar mensaje

## Cómo Modificar

### Cambiar Estilos
```css
/* Editar styles.css */
#messages {
    background: #fff;  /* Cambiar color de fondo */
}
```
**No requiere recompilar servidor** - Solo refrescar navegador (F5)

### Cambiar Estructura HTML
```html
<!-- Editar index.html -->
<h1>Mi Chat Personalizado</h1>
```
**No requiere recompilar servidor** - Solo refrescar navegador (F5)

### Cambiar Lógica Cliente
```javascript
// Editar client.js
function sendMessage() {
    // Nueva lógica
}
```
**No requiere recompilar servidor** - Solo refrescar navegador (F5)

### Cambiar Servidor
```java
// Editar ChatServer.java
private static final int WS_PORT = 9000;
```
**Requiere recompilar**:
```bash
javac ChatServer.java
java ChatServer
```

## Solución del Problema de Scroll

### Problema Original
```css
#messages {
    flex: 1;
    overflow-y: auto;
}
```
**Issue**: Sin `min-height: 0`, el flex container no respeta overflow

### Solución
```css
#messages {
    flex: 1;
    overflow-y: auto;
    min-height: 0;  /* ← Clave para scroll correcto */
}

#chatHeader, #inputArea {
    flex-shrink: 0;  /* ← No comprimir header ni input */
}
```

**Resultado**: 
- Área de mensajes scrolleable
- Input siempre visible
- Header siempre visible

## Testing

### Verificar Scroll
1. Enviar 20+ mensajes
2. Área de mensajes debe tener scroll
3. Input debe permanecer visible
4. Nuevos mensajes auto-scroll al final

### Verificar Separación
1. Modificar `styles.css` (cambiar color)
2. Refrescar navegador → Ver cambio
3. Sin recompilar servidor ✅

## Ventajas de la Arquitectura Modular

| Aspecto | Monolítico | Modular |
|---------|-----------|---------|
| Archivos | 1 | 4 |
| Líneas por archivo | 556 | ~100-300 |
| Editar UI | Recompilar | F5 |
| Claridad | Baja | Alta |
| Mantenibilidad | Difícil | Fácil |
| Reutilización | No | Sí |
| Debugging | Complejo | Simple |

## Conceptos Aplicados

### Separación de Concerns
- **Backend**: Lógica de negocio
- **HTML**: Estructura
- **CSS**: Presentación
- **JavaScript**: Comportamiento

### MVC Pattern
- **Model**: Estado en servidor (usuarios, mensajes)
- **View**: HTML + CSS
- **Controller**: JavaScript + ChatServer

### Modularidad
- Cada archivo una responsabilidad
- Bajo acoplamiento
- Alta cohesión

## Resumen

✅ **Scroll arreglado** - `min-height: 0` en mensajes
✅ **Servidor modular** - ChatServer.java solo backend
✅ **HTML separado** - index.html solo estructura
✅ **CSS separado** - styles.css solo estilos
✅ **JavaScript separado** - client.js solo lógica
✅ **No requiere Tomcat** - Servidor nativo Java
✅ **Fácil de mantener** - Un cambio, un archivo

---

**Ejecutar**: `ChatServer.main()` desde IntelliJ  
**Acceder**: `http://localhost:8080`  
**Modificar UI**: Editar HTML/CSS/JS → F5  
**Estado**: ✅ Completamente modular y funcional

