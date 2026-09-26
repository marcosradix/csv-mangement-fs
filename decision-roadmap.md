# Mapa de Decisões Arquiteturais & Roadmap

Este documento formaliza as decisões de arquitetura e tecnologia adotadas no **CSV Management & File Import/Export Service (`csv-management-fs`)**, bem como o roadmap de evolução técnica do projeto.

---

## 1. Mapa de Decisões Técnicas (O que foi feito e por quê)

### 1.1 Arquitetura em Camadas (Layered Architecture)
* **Decisão:** Estrutura tradicional em camadas (`controller` ➔ `service` ➔ `repository` ➔ `domain`).
* **Motivação:** Para o escopo deste projeto, abordagens como *Hexagonal (Ports & Adapters)* ou *Clean Architecture* trariam abstrações e acoplamento indireto excessivos (*over-engineering*). A arquitetura em camadas entrega alta legibilidade, fácil manutenção, velocidade de desenvolvimento e atende com excelência os princípios de responsabilidade única.

### 1.2 Design-First / Contract-First (OpenAPI 3.0 & OpenAPI Generator)
* **Decisão:** A API é desenhada primeiro no arquivo [`src/main/resources/openapi/api-spec.yaml`](file:///Users/marcosferreira/Documents/csv-mangement-fs/src/main/resources/openapi/api-spec.yaml), gerando automaticamente as interfaces de controle e os DTOs (`openapi-generator-maven-plugin`).
* **Motivação:** Garante que o contrato da API seja a fonte única da verdade, elimina divergências entre código e documentação e viabiliza desenvolvimento paralelo entre frontend e backend.
* **Documentação Interativa:** Uso do **SpringDoc OpenAPI** para disponibilizar o Swagger UI interativo em `/swagger-ui.html`.

### 1.3 Core Backend (Java 25 + Spring Boot 3.5.x)
* **Decisão:** Construção sobre a versão mais recente do Java LTS e ecossistema Spring Boot 3.
* **Motivação:** Produtividade acelerada, injeção de dependências robusta, auto-configuração modular e suporte nativo aos padrões modernos da JVM.

### 1.4 Persistência e Concorrência (Spring Data JPA + PostgreSQL + Locking Otimista)
* **Decisão:** Spring Data JPA com PostgreSQL 16 para persistência relacional e controle de concorrência via `@Version` na entidade `CustomerEntity`.
* **Motivação:** 
  - **PostgreSQL:** Banco de dados relacional maduro, confiável, robusto e padrão de mercado corporativo.
  - **Spring Data JPA:** Elimina boilerplate de acesso a dados e fornece paginação e ordenação nativas (`Pageable`).
  - **Optimistic Locking:** Protege contra colisões de escrita concorrente em ambientes multi-usuário sem bloquear tabelas no banco de dados.

### 1.5 Migrações de Banco de Dados (Flyway)
* **Decisão:** Gerenciamento do ciclo de vida do schema via scripts versionados (`V1__...`, `V2__...`).
* **Motivação:** Rastreabilidade rigorosa de alterações no banco de dados, facilidade para pipelines de CI/CD e garantia de consistência entre ambientes de desenvolvimento, homologação e produção.

### 1.6 Banco de Dados para Testes Automatizados (H2 Database)
* **Decisão:** Utilização do H2 em memória (`jdbc:h2:mem:testdb;MODE=PostgreSQL`) sob o profile `test`.
* **Motivação:** Execução rápida e autônoma da suíte de testes unitários e de integração (`mvn test` e IDE) sem necessidade de dependência externa ou banco PostgreSQL ativo na máquina local.

### 1.7 Padrão Strategy para Exportação Multi-formato (Strategy Pattern)
* **Decisão:** Criação da interface `ExportStrategy` com implementações dedicadas:
  - `CsvExportStrategy` (via **Apache Commons CSV**)
  - `TxtExportStrategy` (tabela de texto alinhada com larguras calculadas dinamicamente)
  - `ExcelExportStrategy` (via **Apache POI** gerando planilhas `.xlsx` estilizadas)
  - `ExportService` atuando como contexto de execução.
* **Motivação:** Respeita o princípio *Open/Closed* do SOLID: qualquer novo formato de exportação (ex: PDF ou JSON) pode ser adicionado criando uma nova estratégia sem modificar as existentes.

### 1.8 Redução de Boilerplate e Mapeamento Seguro (Lombok & MapStruct)
* **Decisão:** Uso do **Lombok** para getters/setters/construtores e do **MapStruct** para conversão bidirecional entre Entidades JPA e DTOs da API.
* **Motivação:** Código limpo, manutenível e proteção contra vazamento do modelo de banco de dados diretamente para as respostas HTTP da API.

### 1.9 Containerização Completa (Docker & Docker Compose)
* **Decisão:** Criação de `Dockerfile` multi-stage e `docker-compose.yml` orquestrando:
  - `postgres` (Banco de dados persistido via volume `pgdata`)
  - `db-init` (Garantia de inicialização e criação do schema)
  - `app` (Aplicação Spring Boot com health check de readiness)
  - `prometheus` (Coleta periódica de métricas)
  - `grafana` (Painéis de visualização prontos para uso)
* **Motivação:** Inicialização de todo o ecossistema com um único comando (`docker compose up -d`), sem necessidade de instalar dependências locais.

### 1.10 Observabilidade Integrada (Micrometer + Actuator + Prometheus + Grafana)
* **Decisão:** Pipeline de observabilidade completo:
  - **Micrometer:** Métricas de negócio customizadas (contadores de importação por status, registros válidos/inválidos, timers de importação e exportação) e métricas de JVM/HikariCP.
  - **Spring Boot Actuator:** Exposição dos dados em `/actuator/prometheus` e probes de saúde (`/actuator/health/liveness`, `/actuator/health/readiness`).
  - **Prometheus:** Scraper em intervalos de 5s armazenando séries temporais.
  - **Grafana:** Dashboard provisionado automaticamente (`csv-management.json`) exibindo KPIs, taxas de erro e consumo de recursos.

### 1.11 Padronização de Erros e Rastreabilidade (RFC 9457 & Correlation ID)
* **Decisão:** Respostas de erro no formato padrão `application/problem+json` e injeção de `X-Correlation-ID` em cada requisição via SLF4J MDC.
* **Motivação:** Diagnóstico imediato de incidentes através de logs correlacionados e mensagens de erro compreensíveis para os clientes da API.

### 1.12 Keyset (Cursor-Based) Pagination Pattern na Exportação de Dados
* **Decisão:** Utilização do padrão arquitetural **Keyset Pagination (Seek Method)** em conjunto com estratégias de streaming (`SXSSFWorkbook`, `CSVPrinter`, `BufferedWriter`) e limpeza periódica do contexto de persistência do Hibernate (`entityManager.clear()`).
* **Motivação:** 
  - **Eliminação de OutOfMemoryError:** Consultas convencionais como `findAll()` carregam toda a base de clientes para a memória heap da JVM. Em tabelas volumosas (centenas de milhares ou milhões de registros), isso gera pausas severas de GC e estouro de memória.
  - **Performance e Index Seek:** Ao contrário do `OFFSET/LIMIT` tradicional (que sofre de degradação linear $O(N)$), a busca por `WHERE id > :lastId ORDER BY id ASC LIMIT :batchSize` utiliza diretamente o índice B-Tree da chave primária, mantendo tempo de resposta constante ($O(\log N)$ / microsegundos) independente da profundidade da paginação.
  - **Consumo Previsível de Memória:** O consumo de heap é rigidamente limitado ao tamanho do lote configurável (`app.export.batch-size: 1000`), permitindo exportar bases massivas com pegada de memória constante.

---

## 2. Resumo da Matriz de Decisões

| Dimensão | Decisão Adotada | Alternativa Avaliada | Por que escolhemos a atual? |
| :--- | :--- | :--- | :--- |
| **Arquitetura** | Camadas (Layered) | Hexagonal / Clean | Simplicidade, clareza e adequação exata ao escopo do projeto sem complexidade excessiva. |
| **Contrato de API** | OpenAPI Design-First | Code-First (anotações nos controllers) | Garante especificação como fonte da verdade e gera DTOs/interfaces automaticamente. |
| **Persistência** | PostgreSQL + Spring Data JPA | MongoDB / JDBC puro | Suporte relacional transacional (ACID), validação via Flyway e facilidade de queries. |
| **Exportação** | Strategy Pattern | Switch/Case no Service | Fácil extensão para novos formatos (ex: PDF) mantendo o código desacoplado. |
| **Leitura para Exportação** | Keyset (Cursor-Based) Pagination | `findAll()` em memória / `OFFSET-LIMIT` | Evita `OutOfMemoryError`, consumo $O(1)$ de heap, e seeks de índice B-Tree sem degradação. |
| **Testes** | H2 em memória | Testcontainers com Postgres | Rapidez extrema de execução local e CI sem dependência do daemon Docker ativo. |
| **Observabilidade** | Micrometer + Prometheus + Grafana | Somente Logs / Actuator isolado | Visibilidade visual de métricas de negócio e performance da JVM em tempo real. |

---

## 3. Roadmap & Próximas Evoluções (O que pode ser agregado)

### 3.1 Segurança e Autenticação (Spring Security + JWT)
* **Conceito:** Proteger os endpoints sensíveis via autenticação *Stateless* com tokens **JWT (JSON Web Token)** enviados no cabeçalho `Authorization: Bearer <token>`.
* **Benefício:** Controle de acesso baseado em papéis (RBAC - ex: apenas usuários com role `ROLE_ADMIN` podem importar/exportar dados).

### 3.2 Resiliência e Tolerância a Falhas (Resilience4j)
Para garantir estabilidade em cenários de alta carga ou falhas de dependências externas:
* **Circuit Breaker (Disjuntor):** Interrompe temporariamente chamadas a operações com falhas recorrentes, evitando efeito cascata e dando tempo para o serviço se recuperar.
* **Retry (Novas Tentativas):** Reexecuta operações automaticamente em caso de falhas transitórias (ex: timeout de rede ou lock transitório de banco).
* **Rate Limiter (Limitador de Taxa):** Limita o volume de requisições por cliente em uma janela de tempo, evitando ataques de negação de serviço e sobrecarga no upload de arquivos volumosos.
* **Bulkhead (Anteparo):** Isola pools de threads para operações pesadas (como exportação de relatórios grandes), impedindo que elas esgotem as threads de consultas simples da API.
* **Time Limiter (Timeout):** Define um tempo limite estrito para execução de rotinas, cancelando requisições que excedam o limiar aceitável.

### 3.3 Processamento Assíncrono de Grandes Arquivos (Batch & Mensageria)
* **Conceito:** Utilizar **Spring Batch** ou filas de mensageria (**RabbitMQ** / **Apache Kafka**).
* **Benefício:** Permite processar arquivos CSV com centenas de milhares ou milhões de linhas em segundo plano (background jobs) sem bloquear a conexão HTTP do usuário.

### 3.4 Armazenamento em Nuvem / Object Storage (AWS S3 ou MinIO)
* **Conceito:** Integrar com serviços de Object Storage para armazenamento de arquivos importados e downloads de relatórios exportados.
* **Benefício:** Evita consumo de disco local e memória dos servidores, permitindo escalabilidade horizontal da aplicação.

### 3.5 Caching Distribuído (Redis)
* **Conceito:** Adicionar camada de cache com **Spring Data Redis** para endpoints de leitura frequente (ex: `/api/v1/customers`).
* **Benefício:** Redução de I/O de banco de dados e tempos de resposta inferiores a 5ms para consultas repetitivas.
