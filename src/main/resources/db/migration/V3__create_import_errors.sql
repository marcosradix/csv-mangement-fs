CREATE TABLE import_errors (
    id BIGSERIAL PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES imports(id) ON DELETE CASCADE,
    row_number INTEGER NOT NULL,
    field_name VARCHAR(100),
    error_message VARCHAR(500) NOT NULL,
    raw_data TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_import_errors_import_id ON import_errors(import_id);
