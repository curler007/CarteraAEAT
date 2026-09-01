# Bolsa — Gestión de cartera bursátil con FIFO para AEAT

Aplicación web personal y **multiusuario** para gestionar carteras de valores con cálculo FIFO multi-broker y exportación para la declaración de la Renta española (AEAT).

---

## Motivación

Aplicación para calcular ganancias/pérdidas patrimoniales por FIFO (global por ticker, como exige la AEAT), gestionar fracciones de acciones y generar el CSV listo para la declaración de la Renta.

---

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Backend | Spring Boot 3.3.4, Java 17 |
| ORM | Spring Data JPA + Hibernate Community Dialects |
| Base de datos | SQLite (`bolsa.db`, creada automáticamente) |
| Plantillas | Thymeleaf + `thymeleaf-extras-springsecurity6` |
| UI | Bootstrap 5.3.3 (CDN), Bootstrap Icons |
| Seguridad | Spring Security (usuarios en BD, BCrypt, roles) |
| Tests | JUnit 5 + Spring Boot Test (fixture SQLite) |
| CI | GitHub Actions (`mvn -B test` en push a `master`) |
| Build | Maven |

---

## Arranque rápido

### Requisitos

- Java 17+
- Maven 3.8+

### Primer arranque: administrador semilla

En el primer arranque, cuando aún no existe ningún usuario en la BD, se crea el administrador
inicial con las credenciales de `src/main/resources/application.properties`:

```properties
app.security.username=admin
app.security.password=changeme
```

> **Importante:** estos valores **solo se usan la primera vez**. A partir de ahí los usuarios se
> gestionan desde `/admin/users` y cambiar el fichero no tiene ningún efecto. Cambia la contraseña
> desde la aplicación antes de exponerla en internet.

### Ejecutar en modo desarrollo

```bash
mvn spring-boot:run
```

La app arranca en [http://localhost:8080](http://localhost:8080). La base de datos `bolsa.db` se crea/actualiza automáticamente.

### Compilar JAR

```bash
mvn clean package
java -jar target/bolsa-1.0.0-SNAPSHOT.jar
```

### Tests

```bash
mvn test
```

---

## Multiusuario

Cada usuario tiene **su propia cartera**: operaciones, lotes FIFO, ventas y splits llevan `user_id`
y **todas** las consultas filtran por él. El FIFO de un usuario nunca consume lotes de otro, y una
venta pendiente de un usuario no bloquea las ventas de nadie más.

#### `AppUser` / `Role`

- `username` (único), `passwordHash` (BCrypt), `enabled`, `role`, `createdAt`
- Roles: `USER` (su cartera) y `ADMIN` (además, gestión de usuarios en `/admin/users`)

#### Gestión de usuarios (`/admin/users`, solo ADMIN)

- Alta, edición (nombre, rol, activo, cambio de contraseña) y baja
- Al eliminar un usuario se borran también **todos sus datos**
- Reglas de seguridad: no puedes eliminarte ni desactivarte a ti mismo, ni degradar/eliminar al
  último administrador activo

#### Migración desde monousuario (`LegacyDataMigration`)

`ApplicationRunner` idempotente que se ejecuta en cada arranque y normalmente no hace nada:

1. Si no hay usuarios, crea el administrador inicial con las credenciales semilla.
2. Adopta todas las filas con `user_id IS NULL` (datos de la época monousuario) asignándoselas a
   ese administrador.

---

## Arquitectura

### Modelo de dominio

```
AppUser ──┬── Operation ─────────── FifoLot
          │       │                    │
          │       └─── SaleRecord ─────┘
          └── Split (independiente)
```

#### `Operation`
Transacción de compra, venta o canje. Campos clave:
- `type`: `BUY` | `SELL` | `CANJE`
- `ticker`, `assetName` (ISIN), `broker`
- `quantity` (BigDecimal, hasta 8 decimales — soporta fracciones)
- `total`: para BUY = (qty × precio) + comisión; para SELL = (qty × precio) − comisión
- `commission`: informativa, solo para mostrar el precio unitario
- `aeatGroup`: `GROUP_1` (mercado español), `GROUP_2` (europeo, por defecto), `GROUP_3` (extraeuropeo)
- `pendingQty`: cantidad sin emparejar si una VENTA no pudo ser cubierta por lotes previos
- `userId`: propietario

#### `FifoLot`
Lote de compra en espera de ser consumido por ventas futuras. Se crea para cada BUY o CANJE. Registra `remainingQty` y `remainingCost` a medida que se va vendiendo.

#### `SaleRecord`
Una fila por cada lote consumido en una venta. Almacena `costBasis`, `proceeds` y `gainLoss` proporcionales. Es la fuente de verdad para el informe AEAT.

#### `Split`
Registro de splits bursátiles: `ticker`, `date`, `ratio` (multiplicador). Un split 1:10 tiene `ratio = 10`.

---

### FIFO (`FifoService`)

El matching es **global por ticker y por usuario** (no por broker), tal como exige la normativa española.

**`processSell`:**
1. Bloquea la venta si hay ventas anteriores del mismo ticker con `pendingQty > 0` (preserva el orden FIFO temporal).
2. Obtiene los lotes **del usuario** ordenados por `purchaseDate ASC, id ASC`.
3. Consume lotes proporcionalmente: coste y precio de venta se distribuyen por proporción de acciones.
4. Crea un `SaleRecord` por cada lote consumido.

**`processCanje`:**
Redistribuye el coste de los lotes existentes al nuevo lote de canje, proporcional a las cantidades (LIRPF Art. 37.1.a). El lote de canje empieza con coste cero; el coste que absorbe proviene de los lotes preexistentes.

**`recalculateFifo`:**
Recalculo completo para un ticker de un usuario: borra SaleRecords, resetea los lotes a su estado inicial y reprocesa todas las operaciones y splits en orden cronológico. Se activa cuando se inserta una operación con fecha anterior a ventas existentes, cuando se modifica/elimina cualquier operación o split, y tras una importación CSV.

**`applySplitToOpenLots`:**
Multiplica `remainingQty` de todos los lotes abiertos por el ratio del split.

---

### Splits

Cuando se registra un split (o se edita/elimina), se llama a `recalculateFifo()` automáticamente. El recalculo fusiona operaciones y splits en orden cronológico: al llegar a un split, multiplica las cantidades de los lotes abiertos por el ratio.

El "Saldo" en la lista de operaciones se muestra ajustado post-splits usando `SplitService.cumulativeFactor()`.

---

### Recalculo automático (`OperationService`)

Al guardar una operación, si la nueva fecha es **anterior a ventas existentes** del mismo ticker, o si existen ventas pendientes, se borran todos los FifoLots y SaleRecords del ticker (del usuario) y se reconstruyen desde cero en orden cronológico. Esto garantiza la corrección independientemente del orden de inserción.

---

### Cotizaciones en tiempo real (`QuoteService`)

- Fuente: Yahoo Finance (sin API key)
- Entrada: ISIN → búsqueda del símbolo → precio
- Devuelve `QuoteResult(symbol, price, previousClose, originalCurrency, convertedToEur)`:
  - `price`: `regularMarketPrice` si existe; si no, el cierre anterior
  - `previousClose`: cierre de la sesión anterior, tomado de la serie diaria (Yahoo no lo expone
    fiable en `meta` con `range=5d`) y, como respaldo, derivado de `regularMarketChangePercent`.
    Es lo que alimenta la sección **Hoy** del dashboard
- Excepciones: algunos ISINs están mapeados manualmente (ej. Bitcoin)
- Conversión de divisa: GBp/GBX ÷ 100 → GBP; divisas no EUR → conversión via forex (ej. `USDEUR=X`).
  Precio y cierre anterior se convierten **al cambio de hoy**, para que la variación diaria refleje
  el movimiento del activo sin mezclarle el de la divisa
- Timeout: 5s conexión, 10s lectura

---

## Funcionalidades

### Dashboard (`/dashboard`)

- **Resumen de cartera** en dos tarjetas:
  - **Global**: total invertido → valor actual (invertido + latente), en verde o rojo, con la
    ganancia o pérdida en euros y porcentaje
  - **Hoy**: variación de la última sesión (precio actual vs. cierre anterior) en euros y su
    porcentaje sobre el valor de la cartera a ese cierre
- **Cartera actual**: tabla ordenable por posición abierta — nombre, ISIN, cantidad, coste total,
  valor actual, % cartera (por coste), % cartera actual (por valor), ± vs precio medio y ± latente.
  Las columnas de precio medio/actual se muestran u ocultan con el botón *Mostrar precios*
- **Distribución actual de la cartera**: treemap (squarify implementado en JS, sin dependencias)
  dimensionado por valor actual de cada posición
- Los precios se cargan en tiempo real al entrar en la página (paralelo por ISIN)
- **Resultado por ejercicio fiscal**: para cada ejercicio con ventas, ganancia/pérdida total y desglose por ticker

### Operaciones (`/operations`)

- Lista unificada de operaciones y splits, ordenada por fecha DESC
- **Filtros**: por ticker y broker (persistentes en sessionStorage)
- **Resumen filtrado**: cantidad total, coste, valor actual y diferencia de las posiciones filtradas
- **Colores**:
  - Compra consumida → gris
  - Compra parcialmente consumida → amarillo
  - Venta pendiente → ⏳ con cantidad pendiente
- **Tooltip en ventas**: lotes consumidos (fecha compra + cantidad)
- **Saldo por ticker**: balance acumulado post-splits en cada fila
- Botones de **Exportar** e **Importar** CSV de la cartera

### Exportar / importar la cartera en CSV (`OperationCsvService`)

Copia de seguridad y migración entre cuentas o instalaciones, en un único fichero.

- **Exportar** (`/operations/export.csv`): todas las operaciones **y splits** del usuario en orden
  cronológico. Los splits van como filas con `Tipo=SPLIT` usando la columna `Cantidad` para el
  ratio: sin ellos el FIFO de un valor que haya sufrido un split se reconstruiría mal y el error
  sería silencioso
- **Importar** (`/operations/import`) en dos modos:
  - `ADD`: añade las filas a lo que el usuario ya tiene
  - `REPLACE`: borra operaciones, lotes, ventas y splits del usuario y los reconstruye desde el CSV
- **Validación previa todo-o-nada**: si alguna fila es inválida no se escribe nada y se indica el
  número de línea del error. También se avisa si falta la cabecera, en lugar de tragarse la primera fila
- Tras importar se recalcula el FIFO completo
- **Fichero de ejemplo**: `/operations/import/ejemplo.csv`

**Formato** — separador `;`, UTF-8 con BOM, fechas `dd/MM/yyyy` (igual que la exportación AEAT).
Los decimales se escriben con coma (Excel en español), pero al importar se aceptan coma y punto:

```
Fecha;Tipo;Ticker;ISIN;Broker;Cantidad;Total;Comision;Grupo AEAT;Notas
05/08/2025;BUY;APPLE;US0378331005;Trade Republic;2,826455;501;1;GROUP_3;
14/08/2025;SELL;APPLE;US0378331005;Trade Republic;1,5;300,25;1;GROUP_3;venta parcial
10/06/2024;SPLIT;NVIDIA;;;10;;;;split 1:10
```

### Ventas / Informe AEAT (`/sales`)

- Selector de año fiscal
- Tabla expandible por ticker: cabecera con totales, detalle con cada lote consumido
- Columnas separadas Ganancia y Pérdida
- 4 tarjetas resumen: coste total, transmisión total, ganancias, pérdidas
- **Exportar CSV** (`/sales/export.csv?year=YYYY`): UTF-8 con BOM (compatible Excel), útil como referencia para rellenar la declaración manualmente

### Splits (`/splits`)

- CRUD completo: crear, editar, eliminar splits
- Al guardar/modificar/eliminar, se recalcula automáticamente el FIFO del ticker afectado
- Un split con ratio 10 multiplica por 10 las acciones de los lotes abiertos en esa fecha

### Seguridad

- Login en `/login` contra los usuarios de la BD (BCrypt)
- Todas las rutas requieren autenticación; `/admin/**` requiere rol `ADMIN`
- CSRF habilitado
- Logout via POST a `/logout`
- La navbar oculta el menú *Usuarios* a quien no es administrador (`sec:authorize`)

---

## Grupos AEAT

| Grupo | Mercado |
|---|---|
| `GROUP_1` | Español (mercado continuo, BME) |
| `GROUP_2` | Europeo (por defecto) |
| `GROUP_3` | Extraeuropeo (EE. UU., Asia, etc.) |

Se asigna por operación y se propaga a los SaleRecords para el informe fiscal.

---

## Tipos de operación

| Tipo | Descripción | Coste |
|---|---|---|
| `BUY` (Compra) | Compra de acciones | Importe total introducido por el usuario |
| `SELL` (Venta) | Venta de acciones | Importe total introducido por el usuario |
| `CANJE` | Ampliación de capital liberada / scrip dividend | €0 (redistribuye coste por LIRPF Art. 37.1.a) |

---

## Estructura del proyecto

```
src/main/java/com/raul/bolsa/
├── config/
│   ├── LegacyDataMigration.java     # Admin inicial + adopción de filas sin dueño
│   └── SecurityConfig.java          # Spring Security (BCrypt, /admin/** = ADMIN)
├── domain/
│   ├── AeatGroup.java               # Enum: GROUP_1/2/3
│   ├── AppUser.java                 # Usuario de la aplicación
│   ├── FifoLot.java                 # Lote de compra
│   ├── LocalDateConverter.java      # SQLite date ↔ LocalDate
│   ├── Operation.java               # Transacción
│   ├── OperationType.java           # Enum: BUY/SELL/CANJE
│   ├── Role.java                    # Enum: USER/ADMIN
│   ├── SaleRecord.java              # Registro de venta por lote
│   └── Split.java                   # Split bursátil
├── repository/
│   ├── AppUserRepository.java
│   ├── FifoLotRepository.java
│   ├── OperationRepository.java
│   ├── SaleRecordRepository.java
│   └── SplitRepository.java
├── security/
│   ├── AppUserDetailsService.java   # UserDetailsService sobre app_users
│   ├── AppUserPrincipal.java        # Principal con id y rol
│   └── CurrentUser.java             # Usuario autenticado (origen del filtrado por dueño)
├── service/
│   ├── AppUserService.java          # CRUD usuarios + reglas de seguridad
│   ├── FifoService.java             # Lógica FIFO core
│   ├── OperationCsvService.java     # Export/import CSV de la cartera
│   ├── OperationService.java        # Orquestación save/update/delete
│   ├── QuoteService.java            # Cotizaciones Yahoo Finance
│   └── SplitService.java            # CRUD splits + recalculo FIFO
└── web/
    ├── dto/
    │   ├── AppUserForm.java         # Form binding usuarios
    │   ├── CsvImportResult.java     # Resultado de una importación
    │   ├── HistoryRow.java          # Fila unificada operación|split
    │   ├── ImportMode.java          # Enum: ADD/REPLACE
    │   ├── OperationForm.java       # Form binding operaciones
    │   ├── PortfolioItem.java       # Fila de cartera en dashboard
    │   ├── QuoteResult.java         # Resultado cotización
    │   ├── SaleYearSummary.java     # Resumen anual ventas
    │   ├── SplitForm.java           # Form binding splits
    │   ├── TickerInfo.java          # ISIN + grupo AEAT para autocompletar
    │   ├── TickerSaleGroup.java     # Agrupación ventas por ticker
    │   └── TickerYearResult.java    # Resultado anual por ticker
    ├── AdminUserController.java     # /admin/users
    ├── FormatUtils.java             # @fmt.qty() para Thymeleaf
    ├── LoginController.java
    ├── OperationController.java     # /dashboard, /operations
    ├── OperationCsvController.java  # /operations/export.csv, /operations/import
    ├── QuoteController.java         # GET /api/quote?isin=
    ├── ReportController.java        # /sales, /sales/export.csv
    └── SplitController.java         # /splits

src/main/resources/
├── application.properties
└── templates/
    ├── fragments/layout.html        # Navbar, head, scripts
    ├── login.html
    ├── dashboard.html               # Global/Hoy, tabla cartera, treemap
    ├── admin/
    │   ├── form.html
    │   └── users.html
    ├── operations/
    │   ├── form.html
    │   ├── import.html
    │   └── list.html
    ├── sales/
    │   └── list.html
    └── splits/
        ├── form.html
        └── list.html

src/test/java/com/raul/bolsa/
├── CsvRoundTripTest.java            # Export → import reproduce el FIFO
├── FixtureGenerator.java            # Genera src/test/resources/fixture.db
├── MultiUserIsolationTest.java      # Aislamiento entre usuarios
├── ReplayConsistencyTest.java       # Replay = estado FIFO original
└── TestUsers.java
```

---

## Tests

Los tests arrancan el contexto de Spring contra una copia de `src/test/resources/fixture.db`.

**`ReplayConsistencyTest`** — reproduce todas las operaciones y splits del fixture desde cero y
comprueba que el estado FIFO resultante (lotes y ventas) es idéntico al original. Es la red de
seguridad para cualquier refactor de `FifoService`.

**`MultiUserIsolationTest`**
- La venta de un usuario nunca consume lotes de otro, aunque sean más antiguos
- Una venta pendiente de un usuario no bloquea las ventas de otro
- Un split solo multiplica los lotes de quien lo registra
- Un usuario no puede editar ni borrar operaciones de otro
- Cartera y ventas AEAT solo contienen filas propias, y ninguna fila queda sin dueño

**`CsvRoundTripTest`**
- Exportar e importar en otra cuenta reproduce el FIFO exactamente
- Las notas con `;` y comillas sobreviven al viaje
- `REPLACE` deja la cartera igual que el fichero, sin duplicar; `ADD` acumula sin tocar al otro usuario
- Un fichero inválido se rechaza entero, indicando la línea; sin cabecera se avisa
- Se aceptan decimales con punto y con coma

**CI**: `.github/workflows/ci.yml` ejecuta `mvn -B test` en cada push a `master` y en cada tag.

---

## Base de datos

SQLite (`bolsa.db` en el directorio de ejecución). Esquema gestionado automáticamente por Hibernate (`ddl-auto=update`).

**Tablas principales:**

| Tabla | Descripción |
|---|---|
| `app_users` | Usuarios, hash BCrypt y rol |
| `operations` | Todas las compras, ventas y canjes |
| `fifo_lots` | Lotes activos de compra |
| `sale_records` | Lotes consumidos por ventas (AEAT) |
| `splits` | Historial de splits |

Las cuatro últimas llevan `user_id` y **siempre** se consultan filtrando por él.

**Nota técnica:** Hibernate SQLite almacena `LocalDate` como `"yyyy-MM-dd 00:00:00.0"`. El `LocalDateConverter` maneja este formato, ISO `"yyyy-MM-dd"`, y epoch ms (legado).

---

## Endpoints

| Método | URL | Descripción |
|---|---|---|
| GET | `/` o `/dashboard` | Dashboard con cartera, treemap y resumen fiscal |
| GET | `/operations` | Lista de operaciones y splits |
| GET | `/operations/new` | Formulario nueva operación |
| POST | `/operations` | Crear operación |
| GET | `/operations/{id}/edit` | Formulario editar operación |
| POST | `/operations/{id}/edit` | Actualizar operación |
| POST | `/operations/{id}/delete` | Eliminar operación |
| GET | `/operations/ticker-names` | JSON: tickers conocidos (para autocompletar) |
| GET | `/operations/export.csv` | Descargar la cartera completa (operaciones + splits) |
| GET | `/operations/import` | Formulario de importación |
| POST | `/operations/import` | Importar CSV (`mode=ADD` \| `REPLACE`) |
| GET | `/operations/import/ejemplo.csv` | CSV de ejemplo con el formato exacto |
| GET | `/sales?year=YYYY` | Informe AEAT ventas |
| GET | `/sales/export.csv?year=YYYY` | Descargar CSV de ventas del ejercicio |
| GET | `/splits` | Lista de splits |
| GET | `/splits/new` | Formulario nuevo split |
| POST | `/splits` | Crear split |
| GET | `/splits/{id}/edit` | Formulario editar split |
| POST | `/splits/{id}/edit` | Actualizar split |
| POST | `/splits/{id}/delete` | Eliminar split |
| GET | `/api/quote?isin=<ISIN>` | Cotización actual (JSON) |
| GET | `/admin/users` | Lista de usuarios *(ADMIN)* |
| GET | `/admin/users/new` | Formulario nuevo usuario *(ADMIN)* |
| POST | `/admin/users` | Crear usuario *(ADMIN)* |
| GET | `/admin/users/{id}/edit` | Formulario editar usuario *(ADMIN)* |
| POST | `/admin/users/{id}/edit` | Actualizar usuario *(ADMIN)* |
| POST | `/admin/users/{id}/delete` | Eliminar usuario y todos sus datos *(ADMIN)* |
| GET | `/login` | Página de login |
| POST | `/logout` | Cerrar sesión |

---

## Configuración (`application.properties`)

```properties
# Base de datos
spring.datasource.url=jdbc:sqlite:bolsa.db

# Semilla del primer administrador — solo se usa si la BD no tiene usuarios
app.security.username=admin
app.security.password=changeme

# Puerto
server.port=8080

# Detrás de un proxy inverso: escuchar solo en local y respetar las cabeceras X-Forwarded-*
server.address=127.0.0.1
server.forward-headers-strategy=framework
```

---

## Despliegue

```bash
mvn clean package -DskipTests
java -jar target/bolsa-1.0.0-SNAPSHOT.jar \
  --app.security.password=contraseña_segura \
  --spring.datasource.url=jdbc:sqlite:/ruta/bolsa/bolsa.db
```

Con `server.address=127.0.0.1` la app solo acepta conexiones locales; se expone al exterior a
través de un proxy inverso (nginx, Caddy…) que termina TLS y reenvía las cabeceras `X-Forwarded-*`.

---

## Casos de uso

### Añadir una compra
1. Ir a **Operaciones → Nueva operación**
2. Tipo: Compra, rellenar ticker/ISIN/broker/fecha/cantidad/total/comisión
3. Guardar → se crea el lote FIFO automáticamente

### Registrar una venta
1. Nueva operación, tipo: Venta
2. Al guardar, el sistema empareja automáticamente los lotes por FIFO y crea los SaleRecords

### Registrar un split
1. Ir a **Splits → Nuevo split**
2. Ticker, fecha y ratio (ej. ratio=10 para un split 1:10)
3. Al guardar, el FIFO del ticker se recalcula automáticamente

### Exportar CSV de ventas
1. Ir a **Ventas**, seleccionar el año fiscal
2. Pulsar **Exportar CSV**
3. Usar el fichero como referencia para rellenar manualmente el modelo 100

### Copia de seguridad / mover la cartera a otra cuenta
1. En **Operaciones**, pulsar **Exportar** → se descarga toda la cartera (operaciones y splits)
2. Entrar con la cuenta destino y en **Operaciones → Importar** subir el fichero
3. Elegir **Añadir** o **Reemplazar**; el FIFO se recalcula al terminar

### Dar de alta a otro usuario
1. Como administrador, ir a **Usuarios → Nuevo usuario**
2. Nombre, contraseña y rol
3. El nuevo usuario entra con sus credenciales y arranca con una cartera vacía e independiente

---

## Notas legales / fiscales

- El cálculo FIFO sigue la normativa española: **global por valor** (no por broker ni cuenta).
- Los canjes (ampliaciones liberadas) siguen el **art. 37.1.a LIRPF**: se redistribuye el coste de los lotes existentes proporcionalmente, sin generar ganancia en el momento del canje.
- El campo **Total** que introduce el usuario es el importe que se usa directamente como valor de adquisición (compra) o de transmisión (venta). El campo **Comisión** es meramente informativo: solo sirve para calcular el precio unitario que se muestra en pantalla (`precio = (total ∓ comisión) / cantidad`), pero no afecta al cálculo FIFO.
- Esta aplicación es una herramienta de ayuda. Verifica siempre los resultados con un asesor fiscal antes de presentar tu declaración.
