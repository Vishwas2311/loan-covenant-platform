# Load test

Scripts that load the local stack (`deploy/docker-compose.yml`) and measure it. Results from 2026-11-03 are below.

| Script | What it does |
|---|---|
| `seed.py --cps 700` | Creates CPs `LT-0001`… through Core's API (4 DAILY covenants each, Quartz jobs included), their metrics in the demo SQL table, and WireMock answers for the bank's CP and attribute services. Owners: `rm-load` / `wrm-load`. Re-runnable. |
| `load.py --users 50 --seconds 60` | Concurrent users replaying a session: portfolio summary, analytics, a CP's audit, review and covenants, the library, and sometimes the open-breach report and its Excel export. |
| `uploads.py --count 50` | Uploads 50 agreements at once and waits for every DPS job. |

The daily run is timed with `POST /api/v1/monitoring/notification-runs?date=<tomorrow>` (seeded covenants start today, so tomorrow everything is due).

## Results (2026-11-03)

Machine: one Windows laptop running every container (Core, SQL Server, DPS, UI, WireMock) and the load client, so all numbers share the same CPUs. A production deployment with separate database and application hosts will do better.

Data: 725 CPs, 2,995 applied covenants, 1 RM owning 700 of them.

### Daily assessment run

| | Covenants | CPs | Time | Rate |
|---|---|---|---|---|
| Before fixes | 2,812 | 712 | 142 s | 20 / s |
| After fixes | 2,812 | 712 | **96 s** | **29 / s** |

At this rate a bank-sized book of 50,000 covenants all due on one day takes about 30 minutes, well inside the overnight window before the 06:00 run sends notifications.

### Concurrent users (after fixes)

50 users, 60 s, 7,608 requests, 127 requests/s, **0 errors**:

| Page | p50 | p95 | p99 |
|---|---|---|---|
| Portfolio summary | 244 ms | 553 ms | 796 ms |
| Analytics | 318 ms | 703 ms | 885 ms |
| CP status audit | 423 ms | 820 ms | 991 ms |
| CP review | 634 ms | 1,082 ms | 1,372 ms |
| CP covenants | 420 ms | 869 ms | 1,105 ms |
| Covenant library | 266 ms | 605 ms | 777 ms |
| Open-breach report | 237 ms | 558 ms | 810 ms |
| Open-breach Excel | 237 ms | 584 ms | 817 ms |

20 users: 107 requests/s, every p95 under 0.75 s. Throughput levels off at 20–50 users because the laptop's CPUs are full (Core about 3.3 cores, SQL Server 1.2).

### Upload burst

50 golden-set agreements uploaded at once (each made unique), as `rm-load`:

| Standard workers | Upload p50 | All 50 processed | Throughput | Outcomes |
|---|---|---|---|---|
| 1 | 1.3 s | 129 s | 23 / min | 45 published, 5 sent to the heavy pool and held for review |
| 3 | 0.2 s | **84 s** | **36 / min** | same |

Per document: 1.2–1.8 s median on the standard pool, about 20 s for the slowest (heavy pool). One worker handles about 1,400 agreements an hour. Add workers with `docker compose up --scale dps-worker=N` (each worker is named after its container host name, and jobs are claimed with a compare-and-set update, so they never clash). The 3-worker run is held back by the single heavy worker and by the shared laptop CPUs.

## What the test found and fixed

1. **Parallel covenant applies failed (57% of 8 at once).** Two RMs onboarding at the same time deadlocked in SQL Server: one transaction held Quartz's lock row and waited for the neighbouring CP's uncommitted covenant row (SQL Server's locking reads lock the next key), the other the reverse. Quartz then retried its lock in a fresh transaction, so the error surfaced as a foreign-key failure. Fixed with:
   - migration `V25__ReadCommittedSnapshot` turns on `READ_COMMITTED_SNAPSHOT`, so reads never wait for writers (57% → 1.4% failures);
   - `TransientRetry` re-runs an apply that lost a deadlock on Quartz's own lock rows. 200 CPs applied by 16 parallel workers: 0 failures, 15 deadlocks retried.
2. **Every ID lookup scanned its index.** All string columns are `VARCHAR`, but the SQL Server driver sends strings as `NVARCHAR`, so SQL Server converted every row (`CONVERT_IMPLICIT`) and scanned instead of seeking: about 5 ms of CPU per single-row lookup. `sendStringParametersAsUnicode=false` in the `prod` and `local-stack` profiles. SQL Server CPU under 50 users: 2.6 → 1.2 cores.
3. **An exception on every query with an entity graph.** Spring Data looked up a named entity graph that did not exist, caught the `IllegalArgumentException` and built the graph each time (about 10% of request CPU). The graphs are now declared with `@NamedEntityGraph` and referenced by name.
4. **Scaled DPS workers all had the same name.** Compose fixed `DPS_WORKER_ID`, so replicas logged and leased under one ID. The ID now defaults to the host name.

## Not covered

- A database on its own host, and more than one Core instance (Quartz clustering under load).
- The heavy (Docling) pool under a burst: the golden-set agreements all parse on the standard pool.
- The REST data sources of the bank (stubbed here).
