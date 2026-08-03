# ShopMall Project Instructions

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
