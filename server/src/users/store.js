/**
 * User repository over the JSON-file store (data/users.json).
 *
 * Records never leave this module in raw form: `publicAccount()` is the only
 * projection used by HTTP responses, and it can never contain passwordHash
 * (or any hash field) because it is built explicitly.
 */
import crypto from 'node:crypto';
import { HttpError } from '../util/http.js';
import { createJsonStore } from '../util/jsonStore.js';

function normalizeUsername(username) {
  return String(username).trim().toLowerCase();
}

function normalizeEmail(email) {
  return String(email).trim().toLowerCase();
}

export function createUserStore({ config }) {
  const store = createJsonStore(`${config.DATA_DIR}/users.json`, {
    defaultValue: { users: {} },
  });

  function sanitize(user) {
    if (!user) return null;
    return {
      id: user.id,
      username: user.username,
      email: user.email,
      provider: user.provider,
      googleSub: user.googleSub ?? null,
      emailVerified: Boolean(user.emailVerified),
      passwordHash: user.passwordHash ?? null,
      createdAt: user.createdAt,
      updatedAt: user.updatedAt,
    };
  }

  return {
    store,

    async findById(id) {
      const data = await store.read();
      return sanitize(data.users?.[id] ?? null);
    },

    async findByUsername(username) {
      const target = normalizeUsername(username);
      const data = await store.read();
      for (const user of Object.values(data.users ?? {})) {
        if (normalizeUsername(user.username) === target) return sanitize(user);
      }
      return null;
    },

    async findByEmail(email) {
      const target = normalizeEmail(email);
      if (!target) return null;
      const data = await store.read();
      for (const user of Object.values(data.users ?? {})) {
        if (user.email && normalizeEmail(user.email) === target) return sanitize(user);
      }
      return null;
    },

    async findByGoogleSub(sub) {
      if (!sub) return null;
      const data = await store.read();
      for (const user of Object.values(data.users ?? {})) {
        if (user.googleSub && user.googleSub === sub) return sanitize(user);
      }
      return null;
    },

    async usernameTaken(username) {
      return (await this.findByUsername(username)) !== null;
    },

    async emailTaken(email) {
      return (await this.findByEmail(email)) !== null;
    },

    /**
     * Insert a new user. Uniqueness (username + email, case-insensitive) is
     * enforced inside the serialized read-modify-write, so concurrent
     * registrations cannot both succeed.
     */
    async create(fields) {
      const username = String(fields.username ?? '').trim();
      const email = fields.email == null ? null : String(fields.email).trim();
      const record = {
        id: crypto.randomUUID(),
        username,
        email,
        provider: fields.provider ?? 'local',
        googleSub: fields.googleSub ?? null,
        emailVerified: Boolean(fields.emailVerified),
        passwordHash: fields.passwordHash ?? null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      await store.update((data) => {
        data.users ??= {};
        const un = normalizeUsername(record.username);
        for (const user of Object.values(data.users)) {
          if (normalizeUsername(user.username) === un) {
            throw new HttpError(409, 'username_taken', 'That username is already taken.');
          }
          if (record.email && user.email && normalizeEmail(user.email) === normalizeEmail(record.email)) {
            throw new HttpError(409, 'email_taken', 'That email is already registered.');
          }
        }
        data.users[record.id] = record;
      });
      return sanitize(record);
    },

    async update(id, patch) {
      let updated = null;
      await store.update((data) => {
        const user = data.users?.[id];
        if (!user) return;
        Object.assign(user, patch, { updatedAt: new Date().toISOString() });
        updated = sanitize(user);
      });
      if (!updated) throw new HttpError(404, 'not_found', 'Account not found.');
      return updated;
    },

    async remove(id) {
      await store.update((data) => {
        if (data.users) delete data.users[id];
      });
    },
  };
}

/** Safe public projection of a user record (never includes hashes). */
export function publicAccount(user) {
  if (!user) return null;
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    provider: user.provider,
  };
}
