# Nuxt Minimal Starter

Look at the [Nuxt documentation](https://nuxt.com/docs/getting-started/introduction) to learn more.

## Setup

Make sure to install dependencies:

```bash
# npm
npm install

# pnpm
pnpm install

# yarn
yarn install

# bun
bun install
```

Before the first run, copy `.env.example` to `.env` and replace the sample
initial password with a private value.

`TEMPLATE_DATABASE_INITIALIZATION_ENABLED` controls destructive database
initialization. Set it to `true` (also accepts `1`, `yes`, or `on`) when a
clean database is required. When enabled, every process startup deletes
`template-generation.sqlite` and its SQLite journal/WAL sidecar files before creating
the schema and seed records; existing projects, assets, providers, tasks,
results, users and sessions are not compatible and cannot be recovered.
Leave this value as `false` in any environment that contains data you need to
keep.

Set `TEMPLATE_STORAGE_BASE_PATH` to the file storage root used by the
application. Absolute paths are recommended in production; relative paths
are resolved from the TemplateGeneration process working directory. The
default is `storage`.

## Development Server

Start the development server on `http://localhost:8040`:

```bash
# npm
npm run dev

# pnpm
pnpm dev

# yarn
yarn dev

# bun
bun run dev
```

The Nitro server initializes SQLite at startup. The database file is named
`template-generation.sqlite` and is created in the process working directory.
For example, running `pnpm dev` from this directory creates
`TemplateGeneration/template-generation.sqlite`.
Run the production server from the directory where this database file should
be stored.

Storage readiness is available at `GET /api/health/storage`.

## User authentication

On the first startup, set `TEMPLATE_INITIAL_USERNAME` and
`TEMPLATE_INITIAL_PASSWORD` in the environment. The password must contain at
least 8 characters and the username must contain 3-64 characters. The first
user is stored in SQLite as a scrypt hash; changing the environment variables
later does not overwrite an existing user.

The application uses an HttpOnly cookie backed by the `user_sessions` SQLite
table. Users can update their password at `/account`; changing it invalidates
older sessions and keeps the current browser signed in.

File uploads are stored under `TEMPLATE_STORAGE_BASE_PATH`, while metadata is
stored in the `stored_files` SQLite table. Asset records in `asset_library`
reference those file records, so deleting an asset also removes its stored
file. Supported uploads are JPG, PNG, WebP, GIF, MP4, WebM and PDF, with a
maximum size of 25 MB per file.

## Project workspaces and sharing scopes

The studio is organized around project workspaces. Project lifecycle operations
are available from `/projects` and the authenticated API endpoints:

- `GET/POST /api/projects` to list or create projects;
- `GET/PATCH/DELETE /api/projects/:projectId` to inspect, update, archive or
  restore a project. The DELETE-compatible endpoint archives the project so
  its workflows, generation tasks and results remain recoverable.

Assets are either `PROJECT` (visible only in that project) or `GLOBAL` (visible
in every project). Uploads accept a `scope` field and
`PATCH /api/projects/:projectId/assets/:assetId` converts an asset between the
two scopes. Workflows, generation tasks and results remain project-scoped,
while API provider management is global and lives at `/api-management`.

API provider settings are persisted in the `api_providers` and
`api_provider_models` SQLite tables. The API management page uses the
authenticated provider endpoints to create, update, enable/disable, delete,
and reload provider configurations. Credential values are stored server-side
but are never returned by list or detail responses. Provider API responses
use private no-store caching, and unauthenticated API calls return JSON 401
responses instead of browser redirects.

`GET /api/providers/:id/models` or `POST /api/providers/:id/models` requests the provider's OpenAI-compatible
`GET {基础地址}/v1/models` endpoint through the local Node.js server and reads model identifiers exclusively from
the response's `data[].id` entries. A POST with draft connection fields only previews the list;
the API management page lets an operator manually choose the models before `PUT /models` persists
the selected catalog. It returns each model's stable SQLite ID together with the upstream model ID as its name. Generation task submissions include both
`provider_id` and `model_id`; the server verifies that the model belongs to the
selected enabled provider and stores both IDs in the task audit snapshot.

`POST /api/providers/:id/test` also runs through the local Node.js server. It can test a saved
model ID or an unsaved draft model using the draft connection fields, without exposing the upstream
API request to the browser.

## Workflow persistence

The workflow builder reads the current project's assets and saved workflow
versions from SQLite. Authenticated clients can use:

- `GET /api/projects/:projectId/workflows` to list versions;
- `POST /api/projects/:projectId/workflows` to save a new immutable version;
- `GET /api/projects/:projectId/workflows/:workflowId` to read one version.

The save endpoint verifies that the garment and model assets belong to the
project, have the expected asset types, and that the model has confirmed
authorization. Versions are assigned per project and are never overwritten.

## Generation task persistence

Authenticated clients can use:

- `GET /api/projects/:projectId/generation-tasks` to query the latest 200 persisted tasks;
- `POST /api/projects/:projectId/generation-tasks` to create a batch of tasks with an enabled provider;
- `PATCH /api/projects/:projectId/generation-tasks/:taskId` to update task status and progress.

Each task stores a snapshot of the selected provider, model, workflow name/version, media type,
batch position and prompt in SQLite, so later provider or workflow changes do not alter audit history.

## Production

Build the application for production:

```bash
# npm
npm run build

# pnpm
pnpm build

# yarn
yarn build

# bun
bun run build
```

Locally preview production build:

```bash
# npm
npm run preview

# pnpm
pnpm preview

# yarn
yarn preview

# bun
bun run preview
```

Check out the [deployment documentation](https://nuxt.com/docs/getting-started/deployment) for more information.
