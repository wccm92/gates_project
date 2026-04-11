# ms-gates — Sistema de Control de Acceso para Molinetes

Microservicio Java que controla el acceso físico de personas a través de **molinetes/torniquetes**. Cuando un lector de cédulas lee a alguien, el sistema verifica si tiene permiso y abre el molinete automáticamente enviando una señal al hardware.

---

## Stack Tecnológico

| Componente | Detalle |
|---|---|
| Lenguaje | Java 17 |
| Framework | Spring Boot 3.4.4 |
| Base de datos | PostgreSQL (puerto 5432) |
| Migraciones | Flyway (automáticas al arrancar) |
| Build | Maven |
| Reduce boilerplate | Lombok |
| Validaciones | Bean Validation (`@Valid`) |
| Hardware | Dispositivos Axis (API VAPIX) |

---

## Arquitectura — Hexagonal (Puertos y Adaptadores)

```
┌─────────────────────────────────────────────────────┐
│                     ENTRYPOINTS                      │
│  AccesoController  PersonaController  PermisoController │
│  LectorBridge (stdin del lector USB)                 │
└────────────────────────┬────────────────────────────┘
                         │ puertos IN
┌────────────────────────▼────────────────────────────┐
│                      DOMAIN                          │
│  ValidarAccesoService                                │
│  GestionarPersonaService                             │
│  GestionarPermisoService                             │
│  ConsultarRegistrosService                           │
└──────────┬─────────────────────────┬────────────────┘
           │ puertos OUT             │ puertos OUT
┌──────────▼──────────┐   ┌─────────▼──────────────┐
│  PERSISTENCE        │   │  AXIS ADAPTER           │
│  (PostgreSQL / JPA) │   │  HTTP → dispositivo Axis│
└─────────────────────┘   └────────────────────────┘
```

**Regla clave:** el `domain/` no importa nada de Spring, JPA ni HTTP. Es lógica pura.

---

## Estructura de Carpetas

```
src/main/java/com/gates/msgates/
│
├── bridge/
│   └── LectorBridge.java         ← Captura stdin del lector USB
│
├── domain/
│   ├── model/                    ← Entidades de negocio (records Java)
│   │   ├── Persona.java
│   │   ├── ControlAcceso.java    ← Permiso de acceso
│   │   ├── RegistroAcceso.java   ← Log de auditoría
│   │   └── ResultadoAcceso.java  ← Enum: AUTORIZADO / DENEGADO / ERROR
│   ├── port/
│   │   ├── in/                   ← Interfaces que llaman al dominio
│   │   └── out/                  ← Interfaces que el dominio necesita
│   └── usecase/                  ← Lógica de negocio
│
├── adapters/
│   ├── axis/
│   │   ├── AxisHttpAdapter.java  ← Llama al dispositivo físico Axis
│   │   └── AxisProperties.java   ← Config de molinetes (application.yml)
│   └── persistence/
│       ├── entity/               ← Entidades JPA (@Entity)
│       ├── repository/           ← Spring Data JPA interfaces
│       ├── AccesoPersistenceAdapter.java
│       └── PersonaPersistenceAdapter.java
│
└── entrypoints/
    ├── AccesoController.java     ← /api/v1/acceso
    ├── PersonaController.java    ← /api/v1/personas
    ├── PermisoController.java    ← /api/v1/permisos
    ├── dto/                      ← Request/Response records
    └── exception/
        └── GlobalExceptionHandler.java ← Manejo centralizado de errores
```

---

## API REST

### Control de acceso — `AccesoController`

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/acceso/validar` | Valida cédula y abre molinete |
| `GET` | `/api/v1/acceso/registros` | Historial de accesos (filtros opcionales) |

**Body del POST:**
```json
{ "cedula": "12345678", "molineteId": 1 }
```

**Parámetros opcionales de GET registros:**
```
?cedula=12345678&molineteId=1&desde=2026-01-01T00:00:00&hasta=2026-12-31T23:59:59&resultado=AUTORIZADO
```

### Personas — `PersonaController`

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/personas` | Registrar persona nueva |
| `GET` | `/api/v1/personas/{cedula}` | Buscar por cédula |
| `GET` | `/api/v1/personas?soloActivos=true` | Listar personas |
| `PUT` | `/api/v1/personas/{cedula}` | Actualizar datos |
| `PATCH` | `/api/v1/personas/{cedula}/estado?activo=false` | Activar/desactivar |

### Permisos — `PermisoController`

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/permisos` | Otorgar permiso a persona |
| `DELETE` | `/api/v1/permisos/{id}` | Revocar permiso |
| `GET` | `/api/v1/permisos?cedula=` | Permisos de una persona |
| `GET` | `/api/v1/permisos?molineteId=` | Permisos de un molinete |

---

## Base de Datos — 3 tablas

| Tabla | Descripción |
|---|---|
| `personas` | Personas registradas (cédula única) |
| `control_acceso` | Permisos por persona/molinete. `molinete_id=NULL` significa todos los molinetes. Fechas `NULL` = permiso permanente |
| `registro_accesos` | Auditoría de cada intento: `AUTORIZADO`, `DENEGADO`, `ERROR` |

**Credenciales de BD** (`application.yml`):
```
BD: gates_db  |  Usuario: gates_user  |  Password: gates_password  |  Puerto: 5432
```

Las migraciones Flyway crean las tablas automáticamente al primer arranque.
Los datos de ejemplo (V2) incluyen 4 personas y permisos de prueba listos para usar.

---

## Flujo completo de un acceso

```
1. Lector USB lee cédula → emite trama: "6|1110294635|MEDINA CARDONA JULIANA |"
2. LectorBridge.java captura stdin y parsea: molineteId=6, cedula=1110294635
3. Llama a ValidarAccesoService (sin HTTP, directo en memoria)
4. Servicio verifica:
   a. ¿Existe la cédula?
   b. ¿El usuario está activo?
   c. ¿Tiene permiso para ese molinete en este momento?
5. Si OK → AxisHttpAdapter hace GET a http://{ip}:{puerto}/axis-cgi/io/port.cgi?action=6:1/
6. El relay del molinete se activa físicamente
7. Se graba el resultado en registro_accesos (siempre, éxito o fallo)
```

### Formato de trama del lector USB

```
6|1110294635|MEDINA CARDONA JULIANA |
│ │           │
│ │           └── Nombre (ignorado, el servidor lo obtiene de BD)
│ └────────────── Cédula
└──────────────── ID del molinete
```

El lector funciona en modo **HID teclado**: envía la trama como si el usuario la tipeara, seguido de Enter. `LectorBridge` captura ese stdin y ejecuta la validación directamente en el mismo proceso de Spring Boot.

---

## Configuración de molinetes (`application.yml`)

```yaml
axis:
  timeout-ms: 3000
  molinetes:
    1:
      ip: 192.168.1.100
      puerto: 80
      usuario: root
      password: changeme
      endpoint: /axis-cgi/io/port.cgi?action=6:1/
    2:
      ip: 192.168.1.101
      puerto: 80
      usuario: root
      password: changeme
      endpoint: /axis-cgi/io/port.cgi?action=6:1/
```

Para agregar molinetes, solo se añade una entrada nueva con su id, ip y credenciales. **No hay que tocar código.**

---

## Cómo levantar el proyecto

### 1. Pre-requisito: levantar PostgreSQL

Con Docker (recomendado):
```cmd
docker-compose up -d

db:
    image: "postgres"
    container_name: "fua-db"
    environment:
      - POSTGRES_DB=fua_db
      - POSTGRES_USER=gates_user
      - POSTGRES_PASSWORD=gates_password
    ports:
      - "5432:5432"
```

O creando la BD manualmente si ya tienes PostgreSQL instalado:
```sql
CREATE USER gates_user WITH PASSWORD 'gates_password';
CREATE DATABASE gates_db OWNER gates_user;
```

### 2. Arrancar el microservicio

```cmd
mvn spring-boot:run
```

Al arrancar verás en los logs:
```
LectorBridge activo — esperando tramas del lector USB
Formato esperado: molineteId|cedula|nombre|
```

Flyway aplica las migraciones automáticamente. El servidor REST queda en `http://localhost:8080` y el `LectorBridge` queda escuchando en la misma terminal.

### 3. Simular una lectura (prueba sin lector físico)

Con el proceso corriendo, tipea en esa misma terminal:
```
6|12345678|JUAN PEREZ |
```
y presiona Enter. Verás en los logs el resultado:
```
[AUTORIZADO] cedula=12345678 molinete=6 — Acceso autorizado: Juan Pérez
```

### 4. Probar los endpoints REST

```cmd
# Validar acceso
curl -X POST http://localhost:8080/api/v1/acceso/validar ^
  -H "Content-Type: application/json" ^
  -d "{\"cedula\":\"12345678\",\"molineteId\":1}"

# Listar personas
curl http://localhost:8080/api/v1/personas

# Ver historial de accesos
curl http://localhost:8080/api/v1/acceso/registros
```

---

## Pendientes / Lo que falta

| Item | Descripción |
|---|---|
| `docker-compose.yml` | Para levantar PostgreSQL fácilmente |
| Autenticación | No hay seguridad en los endpoints. Cualquiera puede llamar a `/validar` |
| Tests | Solo existe `MsGatesApplicationTests` vacío |
| Manejo de múltiples lectores | Actualmente se asume un lector USB por proceso |
