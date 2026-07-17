-- =============================================================================
-- Aurum — Phase 1 sync foundation
-- Run this in the Supabase SQL editor (same project mobile already uses).
-- Adds what's needed for TV <-> Mobile continue-listening / queue / settings
-- / premium sync. Existing tables (playlists, favorites, followed_artists,
-- followed_albums) are untouched.
-- =============================================================================

-- ── playback_state ───────────────────────────────────────────────────────
-- One row per user. Whichever device last wrote wins — the OTHER device's
-- realtime subscription picks up the change and offers to resume there.
create table if not exists playback_state (
  user_id uuid primary key references auth.users not null,
  song_id text,
  song_data jsonb,           -- denormalized snapshot (title/artist/art) so
                              -- the other device can show it without a
                              -- second round trip
  position_ms bigint not null default 0,
  is_playing boolean not null default false,
  device text not null default 'unknown',   -- 'mobile' | 'tv'
  updated_at timestamptz not null default now()
);
alter table playback_state enable row level security;
create policy "own playback state" on playback_state
  for all using (auth.uid() = user_id);

-- ── queue ─────────────────────────────────────────────────────────────────
-- Whole queue stored as one jsonb array under one row per user — cheaper
-- to sync as a single realtime event than N rows, and queues are small
-- (tens of songs, not thousands).
create table if not exists playback_queue (
  user_id uuid primary key references auth.users not null,
  items jsonb not null default '[]',   -- [{id, title, artist, albumArtUrl, ...}]
  current_index int not null default 0,
  updated_at timestamptz not null default now()
);
alter table playback_queue enable row level security;
create policy "own queue" on playback_queue
  for all using (auth.uid() = user_id);

-- ── recently_played ──────────────────────────────────────────────────────
create table if not exists recently_played (
  id bigint generated always as identity primary key,
  user_id uuid references auth.users not null,
  song_id text not null,
  song_data jsonb not null,
  played_at timestamptz not null default now()
);
alter table recently_played enable row level security;
create policy "own recently played" on recently_played
  for all using (auth.uid() = user_id);
create index if not exists recently_played_user_time
  on recently_played (user_id, played_at desc);

-- ── user_settings ─────────────────────────────────────────────────────────
create table if not exists user_settings (
  user_id uuid primary key references auth.users not null,
  theme text not null default 'dark',
  audio_quality text not null default 'auto',
  autoplay boolean not null default true,
  crossfade_seconds int not null default 0,
  normalize_volume boolean not null default false,
  updated_at timestamptz not null default now()
);
alter table user_settings enable row level security;
create policy "own settings" on user_settings
  for all using (auth.uid() = user_id);

-- ── profiles (server-side premium flag) ──────────────────────────────────
-- Mobile currently only caches premium status LOCALLY after a Cashfree
-- payment (see payment_service.dart). TV can't see that. This table is the
-- one place premium status should live going forward — write to it from
-- the Cloudflare Worker's payment-verify step (server-side, trusted),
-- never directly from a client, so a device can't just set itself premium.
create table if not exists profiles (
  user_id uuid primary key references auth.users not null,
  is_premium boolean not null default false,
  premium_plan text,             -- 'monthly' | 'sixMonths' | 'lifetime'
  premium_expires_at timestamptz,  -- null for lifetime
  updated_at timestamptz not null default now()
);
alter table profiles enable row level security;
create policy "read own profile" on profiles
  for select using (auth.uid() = user_id);
-- No client-side insert/update policy on purpose — only the Worker
-- (using the service_role key) should be able to write is_premium.

-- ── Enable Realtime on the sync-relevant tables ──────────────────────────
alter publication supabase_realtime add table playback_state;
alter publication supabase_realtime add table playback_queue;
alter publication supabase_realtime add table profiles;
alter publication supabase_realtime add table playlists;
alter publication supabase_realtime add table favorites;
