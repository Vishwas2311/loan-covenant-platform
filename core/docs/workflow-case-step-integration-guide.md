# Integrating the Workflow Case downstream steps (`submitCaseStep1/2/3`)

> **Audience:** backend developers who will replace the three placeholder methods on
> `WorkflowClient` (`submitCaseStep1`, `submitCaseStep2`, `submitCaseStep3`) with real HTTP calls to
> the covenant-monitoring workflow engine, once each step's contract (URI, payload, response, auth) is
> published.
>
> **Status today:** the three methods are deliberate **no-op placeholders** (they only log). This guide
> is the step-by-step recipe to turn each one into a real, tested, gate-passing integration without
> destabilising the assessment flow.

---

## 1. Where these calls sit in the bigger picture

A workflow **case** is opened **once per CP when assessment starts**. The engine returns a `caseId`,
which is stamped on every `cov_assessment` row for that CP's run. Immediately after the case is opened,
**three further downstream calls** run, each keyed by that `caseId`. Those three calls are the
placeholders you are here to implement.

### End-to-end flow

```
AssessmentService.runAssessment(appliedId, trigger, user, ctx)
   │
   │  (at assessment start, before MANUAL/AUTO branch)
   ▼
WorkflowCaseService.ensureCase(cpId, ctx)          // one case per CP per run (memoised in ctx)
   │
   ├─ resolveRm(cpId)                               // cache → live lookup → may be blank
   │
   ▼
WorkflowClient.startCase(WorkflowCaseRequest)       // POST /api/runtime/cases  → { "caseId": "..." }
   │        (WebClientWorkflowClient.startCase)
   │
   │  returns WorkflowCase(caseId)
   ▼
WorkflowCaseService.fireDownstreamSteps(caseId)     // fired ONCE, right after the case is opened
   │
   ├─ WorkflowClient.submitCaseStep1(caseId)   ◄── PLACEHOLDER  (this guide)
   ├─ WorkflowClient.submitCaseStep2(caseId)   ◄── PLACEHOLDER  (this guide)
   └─ WorkflowClient.submitCaseStep3(caseId)   ◄── PLACEHOLDER  (this guide)
   │
   ▼
CaseOutcome.ok(caseId)   →  assessment.setWorkflowInstanceId(caseId)  (persisted on every row)
```

### Key files

| Concern | File |
| --- | --- |
| Interface (the 3 placeholders live here) | `backend/.../service/WorkflowClient.java` |
| HTTP implementation (edit this) | `backend/.../service/impl/WebClientWorkflowClient.java` |
| Caller / orchestration | `backend/.../service/WorkflowCaseService.java` (`fireDownstreamSteps`) |
| Request body type (case creation) | `backend/.../dto/WorkflowCaseRequest.java` |
| Response type (case creation) | `backend/.../domain/WorkflowCase.java` |
| Exception | `backend/.../exception/WorkflowException.java` |
| WebClient bean + timeout | `backend/.../config/WebClientsConfig.java` (`workflowWebClient`, `defaultTimeout`) |
| Base-URL config binding | `backend/.../config/ClientsProperties.java` (`covenant.clients.workflow.*`) |
| Runtime config | `backend/src/main/resources/application.yml` (`covenant.clients.workflow.base-url`) |
| Dev HTTP stub | `backend/src/main/resources/wiremock/mappings/workflow-cases.json` |
| Client test (WireMock) | `backend/src/test/.../service/impl/WebClientWorkflowClientTest.java` |
| Service test | `backend/src/test/.../service/WorkflowCaseServiceTest.java` |

---

## 2. The current placeholder code (what you're replacing)

### Interface — `WorkflowClient.java`

```java
/** Downstream case call 1, keyed by {@code caseId}. TODO(workflow): contract not yet defined. */
void submitCaseStep1(String caseId);

/** Downstream case call 2, keyed by {@code caseId}. TODO(workflow): contract not yet defined. */
void submitCaseStep2(String caseId);

/** Downstream case call 3, keyed by {@code caseId}. TODO(workflow): contract not yet defined. */
void submitCaseStep3(String caseId);
```

### Implementation — `WebClientWorkflowClient.java` (log-only today)

```java
@Override
public void submitCaseStep1(String caseId) {
    // TODO(workflow): downstream case call 1, keyed by case_id — real URI/payload TBD.
    LOG.info("Workflow case step 1 placeholder for case {}", sanitise(caseId));
}
// submitCaseStep2 / submitCaseStep3 are identical placeholders.

/** CR/LF-strip an external caseId so it is taint-safe at the log sink. */
private static String sanitise(String value) {
    return value == null ? "null" : value.replace('\n', '_').replace('\r', '_');
}
```

### Caller — `WorkflowCaseService.fireDownstreamSteps`

```java
private void fireDownstreamSteps(String caseId) {
    try {
        workflowClient.submitCaseStep1(caseId);
        workflowClient.submitCaseStep2(caseId);
        workflowClient.submitCaseStep3(caseId);
    } catch (RuntimeException ex) {
        LOG.warn("Workflow downstream step failed for case {}: {}",
                sanitise(caseId), sanitise(ex.getMessage()));
    }
}
```

**Two behaviours to understand before you change anything:**

1. **The steps are best-effort by design.** `fireDownstreamSteps` wraps all three in one `try/catch`.
   A thrown `RuntimeException` (including `WorkflowException`) is logged and swallowed so the already
   obtained `caseId` is **never lost** — the assessment always keeps its case id. Only change this if
   the product decision changes (see §5, Step C).
2. **They share one `try`, so an earlier failure short-circuits later steps.** If `submitCaseStep1`
   throws, `submitCaseStep2/3` do **not** run. If each step must run independently, switch to per-step
   isolation (see §5, Step C).

### The reference pattern — `startCase` (already a real call)

`startCase` is the template to copy. It shows the house style: fixed relative URI, `bodyValue`,
`retrieve`, `block(defaultTimeout())`, and `WebClientException → WorkflowException` wrapping.

```java
@Override
public WorkflowCase startCase(WorkflowCaseRequest request) {
    try {
        return client.post()
                .uri("/api/runtime/cases")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(WorkflowCase.class)
                .block(WebClientsConfig.defaultTimeout());
    } catch (WebClientException ex) {
        throw new WorkflowException(
                "Workflow service unreachable: " + ex.getMessage(), ex);
    }
}
```

---

## 3. Before you write code: pin down the contract

For **each** of the three steps, get these answers from the workflow-engine team and record them in
this doc (§9 has a table to fill in). You cannot implement a step correctly without them.

| # | Question | Why it matters |
| --- | --- | --- |
| 1 | **HTTP method + URI** — e.g. `POST /api/runtime/cases/{caseId}/steps/step1`? Is `caseId` a path segment, query param, or in the body? | Determines the `.uri(...)` call and whether `caseId` needs URL-encoding. |
| 2 | **Request payload** — JSON schema, or is it a bodiless call? Does it need more than `caseId` (RM userId, CP id, assessment metadata)? | Decides whether you add a `*Request` record and whether the **method signature** must change. |
| 3 | **Response payload** — what comes back, and which fields do we consume? | Decides whether you add a `*Response` type and whether `void` return is still right. |
| 4 | **Auth** — bearer token, API key header, mTLS? Where does the secret come from? | Headers must be added to the request and the secret injected via config/env (never hard-coded, never logged). |
| 5 | **Success criteria** — is any 2xx a success, or is there a status flag in the body? | Decides whether a non-flag 200 should still be treated as failure. |
| 6 | **Ordering & data dependency** — must step 2 use step 1's response? Must they run strictly in order? | Decides whether steps return data threaded into the next (signature change) and whether isolation is allowed. |
| 7 | **Idempotency / retry-safety** — safe to call twice? Is there a client idempotency key? | The steps fire once per case per run, but retries/re-runs can repeat them; drives retry config. |
| 8 | **Timeout expectations** — typical + worst-case latency. | Keep `defaultTimeout()` (5s) or add a dedicated resilience time limiter. |
| 9 | **Failure semantics (product call)** — is a step failure **fatal** to the assessment, best-effort (log & continue, current behaviour), or must it be **recorded** on the row? | Decides whether you keep `fireDownstreamSteps` as-is, isolate per step, or surface an error into `CaseOutcome` / `workflow_error`. |

---

## 4. Design decisions the contract forces

Pick the branch that matches the answers above **before** coding; the rest of §5 shows each variant.

- **Bodiless vs. request body** → Step B chooses `.toBodilessEntity()` vs. `.bodyValue(record)`.
- **`void` vs. returns data** → if a later step consumes an earlier step's response, change the
  interface method to return a `domain/*` record and thread it through `fireDownstreamSteps`.
- **Same host vs. different host** → `covenant.clients.workflow.base-url` is reused if the steps live on
  the same engine host (the usual case). A different host needs a new endpoint (Step D).
- **Best-effort vs. resilient vs. fatal** → simple `block + WorkflowException` (Step B, variant 1) for
  best-effort; resilience4j operators (Step B, variant 2) for a flaky engine you must tolerate; remove
  the swallow in `fireDownstreamSteps` only if failures must abort/annotate the assessment (Step C).

---

## 5. Step-by-step integration

The steps below fully implement **`submitCaseStep1`** as the worked example. `submitCaseStep2` and
`submitCaseStep3` follow the identical recipe — repeat A–F for each, or do all three together.

### Step A — Model the request/response types (only if the step has a body/response)

- **Request body** → add a record under `dto/` (the `dto/**` package is **excluded from JaCoCo
  coverage**, so request records cost you nothing in the gate).
- **Response body** → add a record under `domain/`, annotated `@JsonIgnoreProperties(ignoreUnknown =
  true)` so the engine can add fields without breaking us. Consume only what you need. Keep it
  logic-free.

```java
// backend/.../dto/WorkflowStep1Request.java   (only if step 1 sends a body)
package com.crisil.covenant.dto;

/** Body for POST /api/runtime/cases/{caseId}/steps/step1. Fields per the engine contract. */
public record WorkflowStep1Request(String caseId, String action /* , ... */) {
}
```

```java
// backend/.../domain/WorkflowStep1Response.java   (only if you consume the response)
package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowStep1Response(String status /* , ... */) {
}
```

> If the step is a fire-and-forget `POST` with no body and no useful response, **skip this step** and
> use `.toBodilessEntity()` in Step B.

### Step B — Implement the method in `WebClientWorkflowClient`

Copy the `startCase` template. Two important taint rules (FindSecBugs will fail the build otherwise):

- **`caseId` in a URL path must be passed as an encoded path variable**, never string-concatenated —
  it originates from an external service and is tainted. Use the `uriBuilder.path("...{caseId}").build(caseId)`
  form, which URL-encodes the value.
- **Any external value you log must be CR/LF-sanitised** — reuse the existing private `sanitise(...)`.

**Variant 1 — simple synchronous call (best-effort default, mirrors `startCase`):**

```java
@Override
public void submitCaseStep1(String caseId) {
    try {
        client.post()
                // caseId is external → encode it as a path variable (taint-safe), do NOT concatenate.
                .uri(uriBuilder -> uriBuilder
                        .path("/api/runtime/cases/{caseId}/steps/step1")
                        .build(caseId))
                .bodyValue(new WorkflowStep1Request(caseId, "..."))   // or .toBodilessEntity() below
                .retrieve()
                .toBodilessEntity()                                   // use bodyToMono(Type.class) if you consume a body
                .block(WebClientsConfig.defaultTimeout());
        LOG.info("Workflow case step 1 submitted for case {}", sanitise(caseId));
    } catch (WebClientException ex) {
        // WorkflowException is a RuntimeException → caught & swallowed by fireDownstreamSteps (best-effort).
        throw new WorkflowException(
                "Workflow step 1 failed for case " + sanitise(caseId) + ": " + ex.getMessage(), ex);
    }
}
```

`.retrieve()` raises `WebClientResponseException` (a `WebClientException` subclass) on any 4xx/5xx, so
the same `catch` covers both connection errors and non-2xx responses.

**Variant 2 — resilient call (retry + circuit breaker + time limit), for a flaky engine you must
tolerate.** Mirror `WebClientNotificationClient`: inject the resilience registries, resolve one shared
instance, and apply the reactor operators. Use this only if the contract says the step must survive a
degraded engine.

```java
// constructor additions (mirror WebClientNotificationClient)
public WebClientWorkflowClient(WebClient workflowWebClient,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               RetryRegistry retryRegistry,
                               TimeLimiterRegistry timeLimiterRegistry) {
    this.client = workflowWebClient;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("workflowCase");
    this.retry = retryRegistry.retry("workflowCase");
    this.timeLimiter = timeLimiterRegistry.timeLimiter("workflowCase");
}

@Override
public void submitCaseStep1(String caseId) {
    client.post()
            .uri(b -> b.path("/api/runtime/cases/{caseId}/steps/step1").build(caseId))
            .retrieve()
            .toBodilessEntity()
            // innermost → outermost: time limiter, then circuit breaker, then retry.
            .transformDeferred(TimeLimiterOperator.of(timeLimiter))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RetryOperator.of(retry))
            .onErrorResume(ex -> {
                LOG.warn("Workflow step 1 failed for case {}: {}",
                        sanitise(caseId), sanitise(ex.getMessage()));
                return Mono.empty();          // best-effort: absorb; or Mono.error(new WorkflowException(...)) to surface
            })
            .block(WebClientsConfig.defaultTimeout());
}
```

If you take Variant 2, add a `workflowCase` instance to `application.yml` under each resilience4j
section (copy the `notifications` block):

```yaml
resilience4j:
  circuitbreaker:
    instances:
      workflowCase:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      workflowCase:
        max-attempts: 3
        wait-duration: 200ms
  timelimiter:
    instances:
      workflowCase:
        timeout-duration: 5s
        cancel-running-future: true
```

### Step C — Confirm failure semantics and (only if needed) update the caller

The default (`fireDownstreamSteps` swallows and preserves the `caseId`) is usually correct. Adjust only
if the contract in §3 (#6, #9) demands it:

**(a) Run every step independently** (an earlier failure must not skip later steps):

```java
private void fireDownstreamSteps(String caseId) {
    runQuietly("step 1", caseId, () -> workflowClient.submitCaseStep1(caseId));
    runQuietly("step 2", caseId, () -> workflowClient.submitCaseStep2(caseId));
    runQuietly("step 3", caseId, () -> workflowClient.submitCaseStep3(caseId));
}

private void runQuietly(String label, String caseId, Runnable step) {
    try {
        step.run();
    } catch (RuntimeException ex) {
        LOG.warn("Workflow downstream {} failed for case {}: {}",
                label, sanitise(caseId), sanitise(ex.getMessage()));
    }
}
```

**(b) A later step needs an earlier step's response** (data dependency). Change the interface to return
data and thread it through:

```java
// WorkflowClient
WorkflowStep1Response submitCaseStep1(String caseId);
void submitCaseStep2(String caseId, WorkflowStep1Response step1);

// WorkflowCaseService.fireDownstreamSteps
WorkflowStep1Response s1 = workflowClient.submitCaseStep1(caseId);
workflowClient.submitCaseStep2(caseId, s1);
```

**(c) A step failure must be recorded on the assessment.** `CaseOutcome` already carries an `error`
field persisted to the `workflow_error` column. Have `createCaseForCp` capture the step failure and
return `CaseOutcome` with both `caseId` **and** an error note (you would extend `CaseOutcome` to hold
both, since today `ok`/`error` are mutually exclusive). Do this **only** on an explicit product
decision — it changes the "never block the assessment" contract.

### Step D — Wire configuration

- **Same host as case creation (usual case):** nothing to do. The `workflowWebClient` bean already has
  `baseUrl = covenant.clients.workflow.base-url`, so a **relative** URI (`/api/runtime/cases/...`) is
  all you need.
- **Different host/service:** add a new endpoint:
  1. `ClientsProperties` — add a field (e.g. `private Endpoint workflowSteps = new Endpoint();`) with
     getter/setter.
  2. `WebClientsConfig` — add a `@Bean WebClient workflowStepsWebClient(...)`.
  3. `application.yml` — add `covenant.clients.workflow-steps.base-url: ...` under `covenant.clients`.
  4. Inject the new bean into `WebClientWorkflowClient` by its bean name.
- **Timeout:** `WebClientsConfig.defaultTimeout()` is a shared 5s. Keep it unless the step is known to
  be slower — then use a dedicated time limiter (Variant 2).

Current runtime values (see `application.yml`): dev/local `http://localhost:9311` (WireMock stub),
tests `http://localhost:9319`. To hit the **real engine**, set
`covenant.clients.workflow.base-url=http://localhost:9120` (per the existing comment in
`application.yml`).

### Step E — Add a dev WireMock stub for the new endpoint

Dev/local runs everything against WireMock on port 9311. The dev loader (`WireMockDevConfig`,
`@Profile("dev")`) loads **every** `*.json` under `classpath:wiremock/mappings/` — the filename does not
matter, only the content. Add a mapping so dev triggers don't 404:

```json
// backend/src/main/resources/wiremock/mappings/workflow-case-steps.json
{
  "request": {
    "method": "POST",
    "urlPathPattern": "/api/runtime/cases/[^/]+/steps/step1"
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "status": "OK" }
  }
}
```

Add one mapping per step (or one `urlPathPattern` covering all three, e.g. `/steps/step[123]`).

### Step F — Tests (this is a hard quality gate)

The backend gate is **JaCoCo 0.95 line / 0.90 branch (bundle)** plus **SpotBugs + FindSecBugs**. Mockito
runs in **STRICT_STUBS**. `WebClientWorkflowClient` is **not** a DTO, so **every new line/branch you add
there must be covered**, including the `catch` and both `sanitise` branches.

**Client test — `WebClientWorkflowClientTest`** (WireMock on a dynamic port, independent of config;
follow the existing test's setup):

```java
@Test
void submitCaseStep1PostsToEngineOn200() {
    wireMock.stubFor(post(urlPathMatching("/api/runtime/cases/.*/steps/step1"))
            .willReturn(aResponse().withStatus(200)));

    assertThatCode(() -> client.submitCaseStep1("case-1")).doesNotThrowAnyException();

    wireMock.verify(postRequestedFor(urlPathMatching("/api/runtime/cases/.*/steps/step1")));
}

@Test
void submitCaseStep1WrapsNon2xxAsWorkflowException() {
    wireMock.stubFor(post(urlPathMatching("/api/runtime/cases/.*/steps/step1"))
            .willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client.submitCaseStep1("case-1"))
            .isInstanceOf(WorkflowException.class);
}
```

- The **500 test** exercises the `catch → WorkflowException` branch.
- To cover the **`sanitise` null branch**, call one step with a CR/LF-containing id and (where the
  signature allows) `null`, as the existing `downstreamCaseStepPlaceholdersRunWithoutError` test already
  does. Keep that idea when you replace the placeholders.

**Service test — `WorkflowCaseServiceTest`.** If you changed step signatures or `fireDownstreamSteps`
(Step C), update the mocks:

- Happy path already asserts the three steps are invoked once (`verify(workflowClient).submitCaseStepN(...)`).
- The existing `downstreamStepFailureDoesNotLoseCaseId` test asserts a thrown step **does not** lose the
  `caseId`. If you switch to per-step isolation (Step C-a), add a test proving step 2/3 still run after
  step 1 throws (`doThrow(...).when(workflowClient).submitCaseStep1(...)` then
  `verify(workflowClient).submitCaseStep2(...)`).

### Step G — Run the gates

```powershell
# fast loop (unit tests only)
$env:JAVA_HOME='C:\tools\jdk17'
& 'C:\tools\maven\bin\mvn.cmd' -f 'D:\workspace_sv\covenant-monitoring\backend\pom.xml' -P fast clean test

# full gate (JaCoCo + SpotBugs/FindSecBugs + ITs)
& 'C:\tools\maven\bin\mvn.cmd' -f 'D:\workspace_sv\covenant-monitoring\backend\pom.xml' -P no-owasp verify
```

(Nothing is on `PATH` in this workspace — use the absolute JDK 17 + Maven paths and set `JAVA_HOME`
first, per `CLAUDE.md`. If a jar lock blocks `repackage` on Windows because a dev app is running, stop
it first, or append `-Dspring-boot.repackage.skip=true` — that flag is not a quality gate.)

---

## 6. Security & quality checklist (must pass before merge)

- [ ] `caseId` (and any other external value) placed in a URL path is passed as an **encoded path
      variable** (`uriBuilder ... .build(caseId)`), never string-concatenated. *(FindSecBugs taint)*
- [ ] Every external value written to a log is **CR/LF-sanitised** via `sanitise(...)`. *(FindSecBugs
      CRLF log injection)*
- [ ] No secret (auth token/API key) is logged or hard-coded; secrets come from config/env like
      `COVENANT_SUPPORT_TOKEN`.
- [ ] Connection errors **and** non-2xx are wrapped as `WorkflowException` (Variant 1) or absorbed by
      the resilience fallback (Variant 2) — no raw `WebClientException` escapes.
- [ ] The assessment is **never blocked** by a step failure unless product explicitly decided otherwise
      (§5 Step C). The default swallow in `fireDownstreamSteps` preserves the `caseId`.
- [ ] New `WebClientWorkflowClient` lines/branches are covered (success + error + `sanitise` branches);
      `mvn -P no-owasp verify` reports **"All coverage checks have been met."**
- [ ] Request bodies are records under `dto/` (coverage-excluded); response types are
      `@JsonIgnoreProperties(ignoreUnknown = true)` records under `domain/`.
- [ ] A dev WireMock mapping exists for each new endpoint so `-Dspring-boot.run.profiles=dev` works.

---

## 7. Manual verification (dev profile)

1. Start the app on the `dev` profile (port 9310):
   `mvn -P fast "-Dspring-boot.run.profiles=dev" spring-boot:run`.
2. Trigger an assessment (or the daily run) for a CP that has several covenants.
3. Confirm in the WireMock request log that **one** `POST /api/runtime/cases` is made for the CP,
   followed by **one** call to each step endpoint, all carrying the same `caseId`
   (`case-dev-mock-1` from the stub).
4. Confirm every resulting `cov_assessment` row shares that `workflow_instance_id`, and that a step
   failure (simulate by pointing the stub at a 500) is logged but does **not** clear the id or abort the
   run.

---

## 8. Reference — request/response shapes we already have (case creation)

These are the working examples to imitate for the step types.

```java
// dto/WorkflowCaseRequest.java  — request body for POST /api/runtime/cases
public record WorkflowCaseRequest(String workflowCode, Variables variables) {
    public record Variables(String manualAssigneee) { }   // triple-'e' is intentional: matches engine contract
}
```

```java
// domain/WorkflowCase.java  — response; only caseId is consumed
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowCase(String caseId) { }
```

Wire JSON:

```
POST /api/runtime/cases
{ "workflowCode": "Covenant_Monitoring", "variables": { "manualAssigneee": "<RM userId>" } }

200 OK
{ "caseId": "case-123" }
```

---

## 9. Contract worksheet — fill this in with the workflow team, then implement

| Field | Step 1 | Step 2 | Step 3 |
| --- | --- | --- | --- |
| Method + URI | | | |
| `caseId` location (path / query / body) | | | |
| Request payload (schema, or "bodiless") | | | |
| Extra inputs beyond `caseId` | | | |
| Response payload (consumed fields) | | | |
| Auth (header / token source) | | | |
| Success criterion (2xx / body flag) | | | |
| Depends on previous step's output? | | | |
| Idempotent / retry key? | | | |
| Typical + worst-case latency | | | |
| Failure semantics (best-effort / fatal / record) | | | |

Once every column is filled, work §5 Steps A–G for each step and run the gates in Step G.
