-- CodeAtlas core knowledge model (README section 5).
-- Generated knowledge is disposable and rebuildable (DR-7); curated content
-- lives in separate tables with immutable history.

-- ---------------------------------------------------------------- registry

CREATE TABLE asset (
    id                  TEXT PRIMARY KEY,
    business_name       TEXT        NOT NULL,
    technical_type      TEXT        NOT NULL,
    role                TEXT        NOT NULL,
    owner               TEXT        NOT NULL,
    source_locator      TEXT        NOT NULL,
    sensitivity         TEXT        NOT NULL DEFAULT 'internal',
    scope_status        TEXT        NOT NULL DEFAULT 'in_scope',
    language            TEXT        NOT NULL,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT asset_scope_status_ck CHECK (scope_status IN ('in_scope','excluded','unsupported')),
    CONSTRAINT asset_sensitivity_ck  CHECK (sensitivity IN ('public','internal','confidential'))
);

CREATE TABLE asset_exclusion (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    pattern     TEXT NOT NULL,
    reason      TEXT NOT NULL
);

-- ------------------------------------------------------- source provenance

CREATE TABLE source_revision (
    id              TEXT PRIMARY KEY,
    asset_id        TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    revision_label  TEXT        NOT NULL,
    content_digest  TEXT        NOT NULL,
    manifest_hash   TEXT        NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, content_digest)
);

CREATE TABLE source_location (
    id              TEXT PRIMARY KEY,
    asset_id        TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    revision_id     TEXT NOT NULL REFERENCES source_revision(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    symbol_key      TEXT,
    start_line      INT  NOT NULL,
    end_line        INT  NOT NULL,
    content_hash    TEXT NOT NULL
);
CREATE INDEX source_location_revision_idx ON source_location (revision_id);
CREATE INDEX source_location_symbol_idx   ON source_location (asset_id, symbol_key);

-- ------------------------------------------------- generations and the graph

CREATE TABLE knowledge_generation (
    id           TEXT PRIMARY KEY,
    state        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    notes        TEXT,
    CONSTRAINT knowledge_generation_state_ck
        CHECK (state IN ('candidate','active','historical','failed'))
);

CREATE TABLE knowledge_node (
    id              TEXT PRIMARY KEY,
    generation_id   TEXT NOT NULL REFERENCES knowledge_generation(id) ON DELETE CASCADE,
    node_type       TEXT NOT NULL,
    name            TEXT NOT NULL,
    qualified_name  TEXT,
    asset_id        TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    location_id     TEXT REFERENCES source_location(id) ON DELETE SET NULL,
    extractor       TEXT NOT NULL,
    extractor_version TEXT NOT NULL,
    attributes      JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Unknown node types must fail visibly (BR-12).
    CONSTRAINT knowledge_node_type_ck CHECK (node_type IN (
        'application','module','class','method','endpoint','datastore',
        'table','integration','configuration_item','source_document'))
);
CREATE INDEX knowledge_node_generation_idx ON knowledge_node (generation_id);
CREATE INDEX knowledge_node_asset_idx      ON knowledge_node (generation_id, asset_id);
CREATE INDEX knowledge_node_qname_idx      ON knowledge_node (generation_id, qualified_name);

CREATE TABLE knowledge_edge (
    id              TEXT PRIMARY KEY,
    generation_id   TEXT NOT NULL REFERENCES knowledge_generation(id) ON DELETE CASCADE,
    edge_type       TEXT NOT NULL,
    source_node_id  TEXT NOT NULL REFERENCES knowledge_node(id) ON DELETE CASCADE,
    target_node_id  TEXT NOT NULL REFERENCES knowledge_node(id) ON DELETE CASCADE,
    provenance      TEXT NOT NULL,
    inference_reason TEXT,
    evidence_ids    TEXT[] NOT NULL DEFAULT '{}',
    attributes      JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT knowledge_edge_type_ck CHECK (edge_type IN (
        'contains','belongs_to','invokes','reads','writes','exposes',
        'calls_endpoint','uses_config')),
    CONSTRAINT knowledge_edge_provenance_ck CHECK (provenance IN ('derived','inferred'))
);
CREATE INDEX knowledge_edge_generation_idx ON knowledge_edge (generation_id);
CREATE INDEX knowledge_edge_source_idx     ON knowledge_edge (generation_id, source_node_id);
CREATE INDEX knowledge_edge_target_idx     ON knowledge_edge (generation_id, target_node_id);

-- Unresolved constructs are recorded, never silently dropped (BR-13, CC-3).
CREATE TABLE coverage_finding (
    id            BIGSERIAL PRIMARY KEY,
    generation_id TEXT NOT NULL REFERENCES knowledge_generation(id) ON DELETE CASCADE,
    asset_id      TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    finding_type  TEXT NOT NULL,
    path          TEXT,
    symbol_key    TEXT,
    detail        TEXT NOT NULL,
    CONSTRAINT coverage_finding_type_ck CHECK (finding_type IN (
        'unsupported_construct','unresolved_dynamic_call','parse_failure',
        'excluded_file','unsupported_language'))
);
CREATE INDEX coverage_finding_generation_idx ON coverage_finding (generation_id);
