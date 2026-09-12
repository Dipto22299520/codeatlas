-- Authentication, asset scope, and retrieval indexes.

CREATE TABLE platform_user (
    username      TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT platform_user_role_ck CHECK (role IN
        ('READER','REVIEWER','OWNER','ASSISTANT'))
);

-- Asset scope is enforced during retrieval, not only at presentation (7.2).
CREATE TABLE user_asset_scope (
    username TEXT NOT NULL REFERENCES platform_user(username) ON DELETE CASCADE,
    asset_id TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    PRIMARY KEY (username, asset_id)
);

-- Full-text retrieval over source symbols and curated meaning.
ALTER TABLE knowledge_node ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english',
            coalesce(name, '') || ' ' ||
            coalesce(qualified_name, '') || ' ' ||
            coalesce(attributes->>'snippet', ''))
    ) STORED;
CREATE INDEX knowledge_node_search_idx ON knowledge_node USING GIN (search_vector);

ALTER TABLE business_meaning_version ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(statement, ''))
    ) STORED;
CREATE INDEX bmv_search_idx ON business_meaning_version USING GIN (search_vector);
