# API Testing Guide - CourtBit

## ⚠️ Requisito Crítico: Header Content-Type

**IMPORTANTE**: Todos los endpoints POST y PATCH REQUIEREN el header `Content-Type: application/json`.

Sin este header, recibirás el error:
```json
{
  "error": "Missing 'Content-Type: application/json' header. Please add this header to your request."
}
```

---

## 🔑 Autenticación

Todos los endpoints protegidos requieren el header de autorización:

```bash
-H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

---

## 📋 Ejemplos de Curl para League Endpoints

### Season Schedule Defaults

#### Crear Defaults para una Temporada
```bash
curl -X POST \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "season_id": "a28da3ca-e78f-423d-8d02-1641b8efcf54",
    "default_number_of_courts": 4,
    "default_time_slots": ["18:30:00", "19:45:00", "21:00:00"]
  }' \
  'https://courtbit-api-production.up.railway.app/api/schedule/defaults'
```

#### Obtener Defaults de una Temporada
```bash
curl -X GET \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  'https://courtbit-api-production.up.railway.app/api/schedule/defaults/season/{seasonId}'
```

#### Actualizar Defaults
```bash
curl -X PATCH \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "default_number_of_courts": 5,
    "default_time_slots": ["18:00:00", "19:30:00", "21:00:00"]
  }' \
  'https://courtbit-api-production.up.railway.app/api/schedule/defaults/season/{seasonId}'
```

---

### Matchday Overrides

#### Crear Override para un Matchday
```bash
curl -X POST \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "season_id": "a28da3ca-e78f-423d-8d02-1641b8efcf54",
    "matchday_number": 1,
    "match_date": "2026-01-02",
    "number_of_courts_override": 4,
    "time_slots_override": ["18:30:00"]
  }' \
  'https://courtbit-api-production.up.railway.app/api/schedule/overrides'
```

#### Obtener Overrides de una Temporada
```bash
curl -X GET \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  'https://courtbit-api-production.up.railway.app/api/schedule/overrides/season/{seasonId}'
```

#### Actualizar Override
```bash
curl -X PATCH \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "match_date": "2026-01-03",
    "number_of_courts_override": 5,
    "time_slots_override": ["18:00:00", "19:30:00"]
  }' \
  'https://courtbit-api-production.up.railway.app/api/schedule/overrides/{overrideId}'
```

#### Eliminar Override
```bash
curl -X DELETE \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  'https://courtbit-api-production.up.railway.app/api/schedule/overrides/{overrideId}'
```

---

### Day Group Assignments

#### Asignar Cancha y Horario a un Day Group
```bash
curl -X PATCH \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "match_date": "2026-01-02",
    "time_slot": "18:30:00",
    "court_index": 1
  }' \
  'https://courtbit-api-production.up.railway.app/api/schedule/assignments/{dayGroupId}'
```

#### Limpiar Asignación (unassign)
```bash
curl -X PATCH \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "match_date": null,
    "time_slot": null,
    "court_index": null
  }' \
  'https://courtbit-api-production.up.railway.app/api/schedule/assignments/{dayGroupId}'
```

---

## 🐛 Solución de Problemas Comunes

### Error: "Cannot transform this request's content"

**Causa**: Falta el header `Content-Type: application/json`

**Solución**: Agregar `-H 'Content-Type: application/json'` a tu comando curl

**Ejemplo Incorrecto** ❌:
```bash
curl -X POST -d '{"name":"Test"}' 'http://api.example.com/endpoint'
```

**Ejemplo Correcto** ✅:
```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -d '{"name":"Test"}' \
  'http://api.example.com/endpoint'
```

---

### Error: "Missing Authorization"

**Causa**: No se incluyó el token JWT en el header

**Solución**: Agregar `-H 'Authorization: Bearer YOUR_TOKEN'`

---

### Error: "Invalid JSON"

**Causa**: El JSON en el body está mal formado

**Solución**:
- Verificar que todas las comillas sean dobles (`"`)
- Verificar que no falten comas entre propiedades
- Usar un validador JSON online si es necesario

---

## 📝 Formato de Datos

### Fechas
Formato: `YYYY-MM-DD`
Ejemplo: `"2026-01-02"`

### Horarios
Formato: `HH:mm:ss`
Ejemplo: `"18:30:00"`

### UUIDs
Los IDs siempre son UUIDs en formato:
`a28da3ca-e78f-423d-8d02-1641b8efcf54`

---

## 🔄 Template Reutilizable

Guarda este template y reemplaza los valores según necesites:

```bash
curl -X [METHOD] \
  -H 'Authorization: Bearer [YOUR_JWT_TOKEN]' \
  -H 'Content-Type: application/json' \
  -d '{
    [YOUR_JSON_BODY]
  }' \
  'https://courtbit-api-production.up.railway.app/api/[ENDPOINT]'
```

Métodos comunes:
- `GET` - Obtener datos (no requiere `-d`)
- `POST` - Crear recursos
- `PATCH` - Actualizar recursos
- `DELETE` - Eliminar recursos (no requiere `-d`)

---

## 💡 Tips

1. **Usar variables de ambiente** para el token:
   ```bash
   export JWT_TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
   curl -H "Authorization: Bearer $JWT_TOKEN" ...
   ```

2. **Pretty print JSON** con `jq`:
   ```bash
   curl ... | jq '.'
   ```

3. **Ver headers de respuesta**:
   ```bash
   curl -v ...
   ```

4. **Guardar respuesta en archivo**:
   ```bash
   curl ... -o response.json
   ```

---

## 🎯 Checklist Pre-Request

Antes de ejecutar un POST o PATCH, verificar:

- [ ] Header `Content-Type: application/json` incluido
- [ ] Header `Authorization: Bearer ...` incluido (si es endpoint protegido)
- [ ] JSON bien formado (comillas dobles, sin trailing commas)
- [ ] Formato correcto de fechas y horarios
- [ ] UUIDs válidos en parámetros de ruta

---

Última actualización: 2026-01-02
