ALTER TABLE import_errors ADD COLUMN filename VARCHAR(255);

UPDATE import_errors
SET filename = (SELECT i.filename FROM imports i WHERE i.id = import_errors.import_id)
WHERE filename IS NULL;
