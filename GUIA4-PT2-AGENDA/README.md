# GUIA4-PT2-AGENDA - Gestión de Contactos CRUD con XML

## Descripción

Aplicación web completa para gestión de contactos personales con operaciones CRUD (Create, Read, Update, Delete). Los datos se almacenan en XML en el servidor HTTP.

## Estructura

```
GUIA4-PT2-AGENDA/
├── src/
│   ├── AgendaServer.java    # Servidor HTTP con API REST
│   ├── index.html            # Interfaz web CRUD
│   └── agenda.xml            # Base de datos XML (auto-generada)
├── GUIA4-PT2-AGENDA.iml
└── README.md
```

## Características

### ✅ CRUD Completo
- **Create**: Agregar nuevos contactos
- **Read**: Listar y buscar contactos
- **Update**: Modificar contactos existentes
- **Delete**: Eliminar contactos

### ✅ Almacenamiento XML con DTD
```xml
<!DOCTYPE agenda [
<!ELEMENT agenda (contacto)*>
<!ELEMENT contacto (nombre,direccion,telefono,email)>
<!ATTLIST contacto id CDATA #REQUIRED>
<!ELEMENT nombre (#PCDATA)>
<!ELEMENT direccion (#PCDATA)>
<!ELEMENT telefono (#PCDATA)>
<!ELEMENT email (#PCDATA)>
]>
```

### ✅ API REST
- `GET /api/contactos` - Listar todos los contactos (JSON)
- `POST /api/contactos` - Crear nuevo contacto
- `PUT /api/contactos/{id}` - Actualizar contacto
- `DELETE /api/contactos/{id}` - Eliminar contacto
- `GET /agenda.xml` - Ver XML crudo

### ✅ Interfaz Web Moderna
- Grid responsivo de tarjetas
- Búsqueda en tiempo real
- Formulario de edición in-place
- Confirmación de eliminación
- Alertas de éxito/error
- Animaciones CSS
- Diseño gradiente violeta/púrpura

## Tecnologías

### Backend
- **Java SE** - Socket programming
- **DOM API** - Manipulación de XML
- **Thread Pool** - Concurrencia
- **Sincronización** - Lock para escritura XML

### Frontend
- **HTML5** - Estructura
- **CSS3** - Grid, gradientes, animaciones
- **jQuery 3.7.1** - AJAX, DOM manipulation
- **JSON** - Comunicación cliente-servidor

## Ejecución desde IntelliJ IDEA

### 1. Abrir Proyecto
```
File → Open → Seleccionar GUIA4-PT2-AGENDA
```

### 2. Ejecutar Servidor
```
Click derecho en AgendaServer.java
Run 'AgendaServer.main()'
```

### 3. Verificar Consola
```
Agenda Server listening on port 8080
XML file: .../agenda.xml
Access: http://localhost:8080/
```

### 4. Abrir en Navegador
```
http://localhost:8080/
```

### 5. Detener
```
Click botón STOP (cuadrado rojo) en consola
O presionar Ctrl+F2
```

## Flujo de Operaciones

### Agregar Contacto
```
1. Usuario llena formulario
2. Click "Agregar Contacto"
3. jQuery $.ajax() POST /api/contactos con JSON
4. Servidor parsea JSON
5. Carga agenda.xml con DOM
6. Genera ID único (timestamp)
7. Crea elemento <contacto> con atributo id
8. Agrega <nombre>, <direccion>, <telefono>, <email>
9. Guarda XML con formato (indent)
10. Responde 201 Created con JSON
11. Cliente recarga lista
12. Muestra alerta de éxito
```

### Listar Contactos
```
1. Página carga
2. jQuery $.ajax() GET /api/contactos
3. Servidor parsea agenda.xml
4. Extrae todos los <contacto>
5. Convierte a JSON array
6. Responde con JSON
7. jQuery recibe array
8. Genera tarjetas HTML para cada contacto
9. Muestra en grid con animación
10. Actualiza estadísticas
```

### Modificar Contacto
```
1. Usuario click "Editar" en tarjeta
2. Datos cargan en formulario
3. Botón cambia a "Actualizar"
4. Usuario modifica campos
5. Click "Actualizar Contacto"
6. jQuery $.ajax() PUT /api/contactos/{id} con JSON
7. Servidor busca contacto por id
8. Actualiza elementos XML
9. Guarda XML
10. Responde 200 OK
11. Cliente recarga lista
12. Formulario resetea
```

### Eliminar Contacto
```
1. Usuario click "Eliminar" en tarjeta
2. Muestra confirmación JavaScript
3. Usuario confirma
4. jQuery $.ajax() DELETE /api/contactos/{id}
5. Servidor busca contacto por id
6. Elimina nodo del DOM
7. Guarda XML
8. Responde 200 OK
9. Cliente recarga lista
10. Muestra alerta de éxito
```

### Buscar Contactos
```
1. Usuario escribe en barra de búsqueda
2. jQuery captura evento keyup
3. Filtra array local de contactos
4. Compara con nombre, dirección, teléfono, email
5. Re-renderiza grid con resultados
6. Actualiza estadísticas (mostrando X de Y)
7. Sin llamada al servidor (filtro local)
```

## Sincronización y Concurrencia

### Thread Safety
```java
private static final Object xmlLock = new Object();

synchronized (xmlLock) {
    // Operaciones de lectura/escritura XML
}
```

- **Lock exclusivo** para evitar race conditions
- **Thread pool** de 10 threads para múltiples clientes
- **Atomic operations** en modificación XML

### Manejo de Errores

**Servidor**:
- Try-catch en todas las operaciones
- Respuestas HTTP apropiadas (200, 201, 404, 500)
- Logs en consola con [IP] y operación

**Cliente**:
- .fail() en todos los $.ajax()
- Alertas visuales de error
- Console.log para debugging

## Estructura XML Generada

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE agenda [
<!ELEMENT agenda (contacto)*>
<!ELEMENT contacto (nombre,direccion,telefono,email)>
<!ATTLIST contacto id CDATA #REQUIRED>
<!ELEMENT nombre (#PCDATA)>
<!ELEMENT direccion (#PCDATA)>
<!ELEMENT telefono (#PCDATA)>
<!ELEMENT email (#PCDATA)>
]>
<agenda>
  <contacto id="1732234567890">
    <nombre>Juan Pérez García</nombre>
    <direccion>Av. Arce 2345, La Paz, Bolivia</direccion>
    <telefono>+591 2 2234567</telefono>
    <email>juan.perez@email.com</email>
  </contacto>
  <contacto id="1732234568901">
    <nombre>María López Fernández</nombre>
    <direccion>Calle Comercio 789, Santa Cruz</direccion>
    <telefono>+591 3 3456789</telefono>
    <email>maria.lopez@email.com</email>
  </contacto>
</agenda>
```

## API REST Endpoints

### GET /api/contactos
**Request**: Ninguno  
**Response**: JSON array
```json
[
  {
    "id": "1732234567890",
    "nombre": "Juan Pérez García",
    "direccion": "Av. Arce 2345, La Paz",
    "telefono": "+591 2 2234567",
    "email": "juan.perez@email.com"
  }
]
```

### POST /api/contactos
**Request**: JSON
```json
{
  "nombre": "María López",
  "direccion": "Calle Comercio 789",
  "telefono": "+591 3 3456789",
  "email": "maria.lopez@email.com"
}
```
**Response**: JSON
```json
{
  "success": true,
  "id": "1732234568901",
  "message": "Contacto agregado"
}
```

### PUT /api/contactos/{id}
**Request**: JSON (mismos campos que POST)  
**Response**: JSON
```json
{
  "success": true,
  "message": "Contacto actualizado"
}
```

### DELETE /api/contactos/{id}
**Request**: Ninguno  
**Response**: JSON
```json
{
  "success": true,
  "message": "Contacto eliminado"
}
```

## Características Avanzadas

### 1. ID Único por Timestamp
```java
String newId = String.valueOf(System.currentTimeMillis());
```
- Garantiza unicidad
- Ordenamiento cronológico
- Simple y eficiente

### 2. Parseo JSON Simple
```java
private Map<String, String> parseJSON(String json) {
    // Parseo manual sin dependencias externas
}
```
- No requiere librerías externas
- Parsing básico de JSON

### 3. Escape de Caracteres
```java
private String escapeJSON(String text) {
    return text.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("\n", "\\n");
}
```
- Previene errores de sintaxis
- Seguridad básica

### 4. Formateo XML
```java
transformer.setOutputProperty(OutputKeys.INDENT, "yes");
transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
```
- XML legible y formateado
- Indentación de 2 espacios

### 5. CORS Headers
```java
out.println("Access-Control-Allow-Origin: *");
out.println("Access-Control-Allow-Methods: GET, POST, PUT, DELETE");
```
- Permite requests desde cualquier origen
- Necesario para desarrollo

## Validaciones

### Frontend (HTML5)
```html
<input type="text" required>
<input type="email" required>
<input type="tel" required>
```

### Backend
- Verificación de campos no vacíos
- Validación de ID en update/delete
- Manejo de contactos no encontrados (404)

## Ventajas vs Apache Tomcat

| Aspecto | Servidor Propio | Apache Tomcat |
|---------|-----------------|---------------|
| Configuración | Ninguna | Compleja |
| Despliegue | java AgendaServer | WAR deployment |
| Aprendizaje | Comprensión profunda | Caja negra |
| Control | Total | Limitado |
| Peso | ~15KB | ~10MB |
| Simplicidad | Muy alta | Media-baja |

**Para este ejercicio**: Servidor propio es IDEAL - cumple todos los requisitos sin complejidad innecesaria.

## Testing

### Probar CRUD Completo
1. **Agregar** 3 contactos diferentes
2. **Buscar** por nombre parcial
3. **Editar** un contacto (cambiar teléfono)
4. **Eliminar** un contacto
5. **Verificar** que XML persiste (cerrar servidor, reiniciar, ver datos)

### Verificar XML Crudo
```
http://localhost:8080/agenda.xml
```
Ver estructura XML directamente en navegador.

### Probar API con Curl (opcional)
```bash
# Listar
curl http://localhost:8080/api/contactos

# Agregar
curl -X POST http://localhost:8080/api/contactos \
  -H "Content-Type: application/json" \
  -d '{"nombre":"Test","direccion":"Test","telefono":"123","email":"test@test.com"}'
```

## Troubleshooting

### Puerto 8080 ocupado
```bash
netstat -ano | findstr :8080
taskkill /F /PID <PID>
```

### XML no se guarda
- Verificar permisos de escritura en carpeta src
- Ver logs en consola del servidor

### Contactos no aparecen
- F12 → Console → Ver errores JavaScript
- F12 → Network → Ver requests fallidos

## Resumen

✅ **CRUD completo** implementado  
✅ **Almacenamiento XML** con DTD  
✅ **API REST** funcional  
✅ **Interfaz moderna** con jQuery  
✅ **Thread-safe** con sincronización  
✅ **Sin dependencias externas** (solo jQuery CDN)  
✅ **No requiere Tomcat** - Servidor HTTP propio  

---

**Ejecutar**: `AgendaServer.main()` desde IntelliJ IDEA  
**Acceder**: `http://localhost:8080/`  
**Estado**: ✅ Listo para usar

