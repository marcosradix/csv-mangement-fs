CREATE TABLE imports (
    id UUID PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_records INTEGER NOT NULL,
    successful_records INTEGER NOT NULL,
    failed_records INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
