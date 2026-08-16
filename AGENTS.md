# ShopMall Project Instructions

## Git Commit Conventions

- Write Git commit subjects and body descriptions in Simplified Chinese. Conventional Commit type and
  scope prefixes, such as `feat(frontend):`, may remain in English.

## Frontend Directory Responsibilities

- `AdminPanelUI/` is the administration frontend used by administrators and internal operators.
- `frontend/` is the customer-facing frontend used by ShopMall customers.
- Place frontend changes in the directory matching the intended audience; do not treat these two
  applications as interchangeable.
- In `AdminPanelUI/`, validation feedback must state the exact accepted format or constraint so an
  administrator can correct the input directly. Do not use only vague messages such as "invalid format",
  "invalid value", or "enter a valid value" when the actual rule is known.
- In `AdminPanelUI/`, show known input format and constraint rules as concise secondary text directly below
  the relevant input control, in addition to retaining validation feedback shown after invalid submission.

## Controller Conventions

Follow `docs/CONTROLLER_CONVENTIONS.md` and the established style in `UserController`,
`OrderController`, `FileController`, and the product controllers.

- Declare HTTP inputs directly on each controller method with explicit `@PathVariable`,
  `@RequestHeader`, `@RequestParam`, or `@RequestPart` annotations. Use snake_case wire names.
- Do not introduce request wrapper DTOs or helper functions that construct controller or service inputs.
  Construct a required domain command directly in the endpoint method or service call.
- Declare each endpoint's `data class Response` and any `XxxData` item classes inside that endpoint method.
- Map service results and construct `val rs = Response(...)` directly inside the endpoint method.
- Do not create controller-level or file-level response DTOs. Do not create `toResponse()`,
  `buildResponse()`, mapper, factory, or other helper functions for response construction, even when
  several endpoints return identical fields. Explicit duplication is the project convention.
- Rename JSON fields with `@param:JsonProperty("snake_case")`, return
  `ResponseEntity<shared.Response>`, and produce responses through the injected `ResponseBuilder`.

## Private Function Conventions

- Do not extract a private function that has only one call site. Keep one-off logic at its call site;
  extract a private function only when it has multiple call sites or represents a meaningful independent
  abstraction.

## Database Migration Scope

- Database migrations are out of scope for this project. Do not create or modify migration scripts,
  and do not report missing migrations as a defect, unless the user explicitly requests migration work.

## Date And Time Conventions

- Use `java.time.LocalDateTime` for new or modified application and database business timestamps unless
  an external protocol explicitly requires epoch/offset semantics.
- Serialize and parse date-time text with ISO-8601 (`ISO_LOCAL_DATE_TIME`); do not introduce custom
  date-time string patterns.
- Frontend and backend date-time fields must be transmitted as ISO-8601 strings. For `LocalDateTime`,
  use the offset-free `ISO_LOCAL_DATE_TIME` representation (for example, `2026-08-06T19:25:14`).
- Bind `LocalDateTime` directly for JDBC date-time parameters. Do not pass `Instant` directly to
  `JdbcTemplate`; convert it to the intended local date-time first when integration boundaries require it.

## Currency Convention

- The project's default business and order currency is `USD`.
