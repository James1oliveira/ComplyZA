CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Table layout expected by Spring AI's PgVectorStore
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(768)
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- Keyword half of hybrid search
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;

CREATE INDEX IF NOT EXISTS vector_store_tsv_idx ON vector_store USING gin (tsv);

-- Exact section lookups ("what does section 72 say?")
CREATE INDEX IF NOT EXISTS vector_store_act_section_idx
    ON vector_store ((metadata ->> 'act'), (metadata ->> 'section'));

CREATE TABLE IF NOT EXISTS source_document (
    id            serial PRIMARY KEY,
    act           text        NOT NULL,
    version_label text        NOT NULL,
    content_hash  text UNIQUE NOT NULL,
    source_url    text,
    ingestion_id  text        NOT NULL,
    chunk_count   int         NOT NULL,
    ingested_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         bigserial PRIMARY KEY,
    asked_at   timestamptz NOT NULL DEFAULT now(),
    username   text,
    question   text,
    retrieved  jsonb,
    answer     jsonb,
    model      text
);
