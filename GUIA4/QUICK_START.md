# GUIA4 - INSTRUCCIONES RÁPIDAS

## Para Ejecutar (3 pasos)

1. **Abrir terminal** en la carpeta GUIA4
   ```
   cd C:\Users\julia\IdeaProjects\SistemasDistribuidos-ll-2025\GUIA4
   ```

2. **Ejecutar servidor**
   ```
   start-server.bat
   ```

3. **Abrir navegador**
   ```
   http://localhost:8080/ajax
   ```

## Para Probar

### Funcionalidad A: Cargar texto en div
- Click: "Cargar documento.txt"
- ✅ Debe aparecer texto en el recuadro

### Funcionalidad B: Cargar países HTML
- Click: "Cargar países HTML"
- ✅ Debe llenar select con países

### Funcionalidad C: Cargar países JSON
- Click: "Cargar países JSON"
- ✅ Debe llenar select con países desde JSON

## Archivos Importantes

| Archivo | Descripción |
|---------|-------------|
| `src/MultiThreadedHttpServer.java` | Servidor HTTP |
| `src/documento.txt` | Texto para AJAX (a) |
| `src/paises.txt` | HTML para (b) |
| `src/paises.json` | JSON para (c) |
| `start-server.bat` | Inicia servidor |
| `VALIDACION.md` | Verificación completa |
| `README_COMPLETO.md` | Documentación técnica |

## Endpoints

- `http://localhost:8080/` - Inicio
- `http://localhost:8080/ajax` - **DEMO PRINCIPAL**
- `http://localhost:8080/login` - Login
- `http://localhost:8080/documento.txt` - Texto
- `http://localhost:8080/paises.txt` - HTML
- `http://localhost:8080/paises.json` - JSON

## Solución de Problemas

**Puerto ocupado?**
```bash
netstat -ano | findstr :8080
taskkill /F /PID <numero>
```

**No compila?**
```bash
cd src
javac MultiThreadedHttpServer.java
```

**AJAX no funciona?**
- F12 en navegador → Ver consola de errores
- Verificar que servidor esté corriendo

## Estado

✅ IMPLEMENTACIÓN COMPLETA Y FUNCIONAL

Todas las funcionalidades (a, b, c) implementadas correctamente.

