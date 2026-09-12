-- Curated business meaning, processes, reference data, and governance.
-- These tables survive rebuilds of generated knowledge (DR-3, DR-7).

CREATE TABLE business_meaning (
    id             TEXT PRIMARY KEY,
    meaning_type   TEXT NOT NULL,
    business_name  TEXT NOT NULL,
    current_version INT NOT NULL DEFAULT 0,
    CONSTRAINT business_meaning_type_ck CHECK (meaning_type IN (
        'capability','rule','process','entity','stakeholder'))
);

-- Immutable version history (BR-19, DR-3).
CREATE TABLE business_meaning_version (
    id             TEXT PRIMARY KEY,
    meaning_id     TEXT NOT NULL REFERENCES business_meaning(id) ON DELETE CASCADE,
    version        INT  NOT NULL,
    statement      TEXT NOT NULL,
    owner          TEXT NOT NULL,
    status         TEXT NOT NULL,
    author         TEXT NOT NULL,
    authored_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason         TEXT NOT NULL,
    reviewer       TEXT,
    reviewed_at    TIMESTAMPTZ,
    review_note    TEXT,
    source_origin  TEXT NOT NULL DEFAULT 'human',
    UNIQUE (meaning_id, version),
    CONSTRAINT bmv_status_ck CHECK (status IN ('draft','reviewed','rejected','superseded')),
    CONSTRAINT bmv_origin_ck CHECK (source_origin IN ('human','ai_suggestion'))
);

-- Every published statement needs specific software anchors (BR-16, BR-17).
CREATE TABLE meaning_anchor (
    id                  TEXT PRIMARY KEY,
    meaning_version_id  TEXT NOT NULL REFERENCES business_meaning_version(id) ON DELETE CASCADE,
    asset_id            TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    path                TEXT NOT NULL,
    symbol_key          TEXT NOT NULL,
    confidence_basis    TEXT NOT NULL,
    resolution_status   TEXT NOT NULL DEFAULT 'unresolved',
    resolved_location_id TEXT REFERENCES source_location(id) ON DELETE SET NULL,
    CONSTRAINT meaning_anchor_resolution_ck
        CHECK (resolution_status IN ('resolved','broken','unresolved'))
);
CREATE INDEX meaning_anchor_version_idx ON meaning_anchor (meaning_version_id);

-- ------------------------------------------------------------- processes

CREATE TABLE business_process (
    id            TEXT PRIMARY KEY,
    business_name TEXT NOT NULL,
    description   TEXT NOT NULL,
    owner         TEXT NOT NULL,
    order_basis   TEXT NOT NULL DEFAULT 'curated',
    CONSTRAINT business_process_order_basis_ck CHECK (order_basis IN ('curated','derived'))
);

CREATE TABLE process_stage (
    id              TEXT PRIMARY KEY,
    process_id      TEXT NOT NULL REFERENCES business_process(id) ON DELETE CASCADE,
    stage_order     INT  NOT NULL,
    name            TEXT NOT NULL,
    description     TEXT NOT NULL,
    asset_id        TEXT REFERENCES asset(id) ON DELETE SET NULL,
    stage_kind      TEXT NOT NULL DEFAULT 'step',
    branch_condition TEXT,
    symbol_key      TEXT,
    path            TEXT,
    UNIQUE (process_id, stage_order),
    CONSTRAINT process_stage_kind_ck CHECK (stage_kind IN
        ('entry','step','check','effect','handover','failure','completion'))
);

-- ------------------------------------------------------- reference data

CREATE TABLE reference_allowlist (
    id           BIGSERIAL PRIMARY KEY,
    asset_id     TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    config_key   TEXT NOT NULL,
    value_type   TEXT NOT NULL,
    approved_by  TEXT NOT NULL,
    approved_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, config_key),
    CONSTRAINT reference_allowlist_type_ck CHECK (value_type IN ('number','boolean','string','duration'))
);

CREATE TABLE reference_snapshot (
    id            TEXT PRIMARY KEY,
    asset_id      TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    config_key    TEXT NOT NULL,
    value_type    TEXT NOT NULL,
    value_text    TEXT NOT NULL,
    source_path   TEXT NOT NULL,
    revision_id   TEXT NOT NULL REFERENCES source_revision(id) ON DELETE CASCADE,
    location_id   TEXT REFERENCES source_location(id) ON DELETE SET NULL,
    checksum      TEXT NOT NULL,
    snapshot_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ
);
CREATE INDEX reference_snapshot_key_idx ON reference_snapshot (asset_id, config_key);

-- ------------------------------------------------------------ governance

CREATE TABLE refresh_job (
    id                TEXT PRIMARY KEY,
    scope             TEXT NOT NULL,
    scope_asset_id    TEXT,
    state             TEXT NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    candidate_generation_id TEXT REFERENCES knowledge_generation(id) ON DELETE SET NULL,
    published_generation_id TEXT REFERENCES knowledge_generation(id) ON DELETE SET NULL,
    requested_by      TEXT NOT NULL,
    failure_reason    TEXT,
    counts            JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT refresh_job_state_ck CHECK (state IN
        ('queued','running','succeeded','failed','cancelled'))
);

CREATE TABLE refresh_job_stage (
    id           BIGSERIAL PRIMARY KEY,
    job_id       TEXT NOT NULL REFERENCES refresh_job(id) ON DELETE CASCADE,
    stage_order  INT  NOT NULL,
    name         TEXT NOT NULL,
    state        TEXT NOT NULL,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    detail       TEXT
);
CREATE INDEX refresh_job_stage_job_idx ON refresh_job_stage (job_id, stage_order);

CREATE TABLE answer_run (
    id             TEXT PRIMARY KEY,
    caller         TEXT NOT NULL,
    service_id     TEXT NOT NULL,
    question       TEXT,
    authorized_scope TEXT[] NOT NULL DEFAULT '{}',
    generation_id  TEXT,
    status         TEXT NOT NULL,
    evidence_ids   TEXT[] NOT NULL DEFAULT '{}',
    model_name     TEXT,
    tokens         INT,
    cost           NUMERIC,
    latency_ms     INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_event (
    id           BIGSERIAL PRIMARY KEY,
    actor        TEXT NOT NULL,
    client       TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    action       TEXT NOT NULL,
    target       TEXT,
    result       TEXT NOT NULL,
    request_id   TEXT
);
CREATE INDEX audit_event_time_idx ON audit_event (occurred_at DESC);

-- Platform accountability (BR-73).
CREATE TABLE platform_settings (
    id             INT PRIMARY KEY DEFAULT 1,
    platform_owner TEXT NOT NULL,
    refresh_cadence TEXT NOT NULL,
    active_generation_id TEXT REFERENCES knowledge_generation(id) ON DELETE SET NULL,
    CONSTRAINT platform_settings_single_row CHECK (id = 1)
);
