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

API provider settings are persisted in the `api_providers` and
`api_provider_models` SQLite tables. The API management page uses the
authenticated provider endpoints to create, update, enable/disable, delete,
and reload provider configurations. Credential values are stored server-side
but are never returned by list or detail responses. Provider API responses
use private no-store caching, and unauthenticated API calls return JSON 401
responses instead of browser redirects.

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
