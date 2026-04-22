# Bolsa — Gestión de cartera bursátil con FIFO para AEAT

Aplicación web personal para gestionar una cartera de valores con cálculo FIFO multi-broker y exportación para la declaración de la Renta española (AEAT).

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
| Plantillas | Thymeleaf |
| UI | Bootstrap 5.3.3 (CDN), Bootstrap Icons |
| Seguridad | Spring Security (in-memory, BCrypt) |
| Build | Maven |

---

## Arranque rápido

### Requisitos

- Java 17+
- Maven 3.8+

### Configurar credenciales

Antes de la primera ejecución, edita `src/main/resources/application.properties`:

```properties
app.security.username=admin
app.security.password=tu_contraseña_segura
```

> **Importante:** Cambia siempre la contraseña por defecto antes de exponer la app en la red.

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

---

## Arquitectura

### Modelo de dominio

```
Operation ─────────── FifoLot
    │                    │
    └─── SaleRecord ─────┘
Split (independiente)
```

#### `Operation`
Transacción de compra, venta o canje. Campos clave:
- `type`: `BUY` | `SELL` | `CANJE`
- `ticker`, `assetName` (ISIN), `broker`
- `quantity` (BigDecimal, hasta 8 decimales — soporta fracciones)
- `total`: para BUY = (qty × precio) + comisión; para SELL = (qty × precio) − comisión
- `commission`: incluida en la base de coste (BUY) o deducida de la transmisión (SELL)
- `aeatGroup`: `GROUP_1` (mercado español), `GROUP_2` (europeo, por defecto), `GROUP_3` (extraeuropeo)
- `pendingQty`: cantidad sin emparejar si una VENTA no pudo ser cuberta por lotes previos

#### `FifoLot`
Lote de compra en espera de ser consumido por ventas futuras. Se crea para cada BUY o CANJE. Registra `remainingQty` y `remainingCost` a medida que se va vendiendo.

#### `SaleRecord`
Una fila por cada lote consumido en una venta. Almacena `costBasis`, `proceeds` y `gainLoss` proporcionales. Es la fuente de verdad para el informe AEAT.

#### `Split`
Registro de splits bursátiles: `ticker`, `date`, `ratio` (multiplicador). Un split 1:10 tiene `ratio = 10`.

---

### FIFO (`FifoService`)

El matching es **global por ticker** (no por broker), tal como exige la normativa española.

**`processSell`:**
1. Bloquea la venta si hay ventas anteriores del mismo ticker con `pendingQty > 0` (preserva el orden FIFO temporal).
2. Obtiene lotes ordenados por `purchaseDate ASC, id ASC`.
3. Consume lotes proporcionalmente: coste y precio de venta se distribuyen por proporción de acciones.
4. Crea un `SaleRecord` por cada lote consumido.

**`processCanje`:**
Redistribuye el coste de los lotes existentes al nuevo lote de canje, proporcional a las cantidades (LIRPF Art. 37.1.a). El lote de canje empieza con coste cero; el coste que absorbe proviene de los lotes preexistentes.

**`recalculateFifo`:**
Recalculo completo para un ticker: borra SaleRecords, resetea los lotes a su estado inicial y reprocesa todas las operaciones y splits en orden cronológico. Se activa cuando se inserta una operación con fecha anterior a ventas existentes, o cuando se modifica/elimina cualquier operación o split.

**`applySplitToOpenLots`:**
Multiplica `remainingQty` de todos los lotes abiertos por el ratio del split.

---

### Splits

Cuando se registra un split (o se edita/elimina), se llama a `recalculateFifo()` automáticamente. El recalculo fusiona operaciones y splits en orden cronológico: al llegar a un split, multiplica las cantidades de los lotes abiertos por el ratio.

El "Saldo" en la lista de operaciones se muestra ajustado post-splits usando `SplitService.cumulativeFactor()`.

---

### Recalculo automático (`OperationService`)

Al guardar una operación, si la nueva fecha es **anterior a ventas existentes** del mismo ticker, o si existen ventas pendientes, se borran todos los FifoLots y SaleRecords del ticker y se reconstruyen desde cero en orden cronológico. Esto garantiza la corrección independientemente del orden de inserción.

---

### Cotizaciones en tiempo real (`QuoteService`)

- Fuente: Yahoo Finance (sin API key)
- Entrada: ISIN → búsqueda del símbolo → precio
- Excepciones: algunos ISINs están mapeados manualmente (ej. Bitcoin)
- Conversión de divisa: GBp/GBX ÷ 100 → GBP; divisas no EUR → conversión via forex (ej. `USDEUR=X`)
- Timeout: 5s conexión, 10s lectura

---

## Funcionalidades

### Dashboard (`/dashboard`)

- **Cartera actual**: tabla con posición abierta por ticker — cantidad, coste total, % cartera, coste medio, precio actual, ± vs coste medio, ganancia/pérdida latente (EUR)
- Los precios se cargan en tiempo real al entrar en la página (paralelo por ISIN)
- **Resumen fiscal por año**: para cada ejercicio con ventas, muestra la ganancia/pérdida total y el desglose por ticker

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

- Login en `/login` con usuario/contraseña configurables en `application.properties`
- Todas las rutas requieren autenticación
- CSRF habilitado
- Logout via POST a `/logout`

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
│   └── SecurityConfig.java          # Spring Security (in-memory, BCrypt)
├── domain/
│   ├── AeatGroup.java               # Enum: GROUP_1/2/3
│   ├── FifoLot.java                 # Lote de compra
│   ├── LocalDateConverter.java      # SQLite date ↔ LocalDate
│   ├── Operation.java               # Transacción
│   ├── OperationType.java           # Enum: BUY/SELL/CANJE
│   ├── SaleRecord.java              # Registro de venta por lote
│   └── Split.java                   # Split bursátil
├── repository/
│   ├── FifoLotRepository.java
│   ├── OperationRepository.java
│   ├── SaleRecordRepository.java
│   └── SplitRepository.java
├── service/
│   ├── FifoService.java             # Lógica FIFO core
│   ├── OperationService.java        # Orquestación save/update/delete
│   ├── QuoteService.java            # Cotizaciones Yahoo Finance
│   └── SplitService.java            # CRUD splits + recalculo FIFO
└── web/
    ├── dto/
    │   ├── HistoryRow.java          # Fila unificada operación|split
    │   ├── OperationForm.java       # Form binding operaciones
    │   ├── PortfolioItem.java       # Fila de cartera en dashboard
    │   ├── QuoteResult.java         # Resultado cotización
    │   ├── SaleYearSummary.java     # Resumen anual ventas
    │   ├── SplitForm.java           # Form binding splits
    │   ├── TickerSaleGroup.java     # Agrupación ventas por ticker
    │   └── TickerYearResult.java    # Resultado anual por ticker
    ├── FormatUtils.java             # @fmt.qty() para Thymeleaf
    ├── LoginController.java
    ├── OperationController.java     # /dashboard, /operations
    ├── QuoteController.java         # GET /api/quote?isin=
    ├── ReportController.java        # /sales, /sales/export.csv
    └── SplitController.java         # /splits

src/main/resources/
├── application.properties
└── templates/
    ├── fragments/layout.html        # Navbar, head, scripts
    ├── login.html
    ├── dashboard.html
    ├── operations/
    │   ├── form.html
    │   └── list.html
    ├── sales/
    │   └── list.html
    └── splits/
        ├── form.html
        └── list.html
```

---

## Base de datos

SQLite (`bolsa.db` en el directorio de ejecución). Esquema gestionado automáticamente por Hibernate (`ddl-auto=update`).

**Tablas principales:**

| Tabla | Descripción |
|---|---|
| `operations` | Todas las compras, ventas y canjes |
| `fifo_lots` | Lotes activos de compra |
| `sale_records` | Lotes consumidos por ventas (AEAT) |
| `splits` | Historial de splits |

**Nota técnica:** Hibernate SQLite almacena `LocalDate` como `"yyyy-MM-dd 00:00:00.0"`. El `LocalDateConverter` maneja este formato, ISO `"yyyy-MM-dd"`, y epoch ms (legado).

---

## Endpoints

| Método | URL | Descripción |
|---|---|---|
| GET | `/` o `/dashboard` | Dashboard con cartera y resumen fiscal |
| GET | `/operations` | Lista de operaciones y splits |
| GET | `/operations/new` | Formulario nueva operación |
| POST | `/operations` | Crear operación |
| GET | `/operations/{id}/edit` | Formulario editar operación |
| POST | `/operations/{id}/edit` | Actualizar operación |
| POST | `/operations/{id}/delete` | Eliminar operación |
| GET | `/operations/ticker-names` | JSON: tickers conocidos (para autocompletar) |
| GET | `/sales?year=YYYY` | Informe AEAT ventas |
| GET | `/sales/export.csv?year=YYYY` | Descargar CSV de ventas del ejercicio |
| GET | `/splits` | Lista de splits |
| GET | `/splits/new` | Formulario nuevo split |
| POST | `/splits` | Crear split |
| GET | `/splits/{id}/edit` | Formulario editar split |
| POST | `/splits/{id}/edit` | Actualizar split |
| POST | `/splits/{id}/delete` | Eliminar split |
| GET | `/api/quote?isin=<ISIN>` | Cotización actual (JSON) |
| GET | `/login` | Página de login |
| POST | `/logout` | Cerrar sesión |

---

## Configuración (`application.properties`)

```properties
# Base de datos
spring.datasource.url=jdbc:sqlite:bolsa.db

# Seguridad — CAMBIAR antes de poner en producción
app.security.username=admin
app.security.password=changeme

# Puerto
server.port=8080
```

---

## Despliegue

```bash
mvn clean package -DskipTests
java -jar target/bolsa-1.0.0-SNAPSHOT.jar \
  --app.security.password=contraseña_segura \
  --spring.datasource.url=jdbc:sqlite:/ruta/bolsa/bolsa.db
```

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

---

## Notas legales / fiscales

- El cálculo FIFO sigue la normativa española: **global por valor** (no por broker ni cuenta).
- Los canjes (ampliaciones liberadas) siguen el **art. 37.1.a LIRPF**: se redistribuye el coste de los lotes existentes proporcionalmente, sin generar ganancia en el momento del canje.
- El campo **Total** que introduce el usuario es el importe que se usa directamente como valor de adquisición (compra) o de transmisión (venta). El campo **Comisión** es meramente informativo: solo sirve para calcular el precio unitario que se muestra en pantalla (`precio = (total ∓ comisión) / cantidad`), pero no afecta al cálculo FIFO.
- Esta aplicación es una herramienta de ayuda. Verifica siempre los resultados con un asesor fiscal antes de presentar tu declaración.
