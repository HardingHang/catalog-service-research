-- Simple test for commit_table C function

SELECT * FROM iceberg_catalog.create_namespace('commit_test', '{"owner":"test"}'::jsonb);

SELECT * FROM iceberg_catalog.create_table(
    'commit_test',
    'commit_table',
    '{"type":"struct","fields":[{"id":1,"name":"id","type":"long"}]}'::jsonb
);

-- Get initial location
SELECT metadata_location AS initial_loc FROM iceberg_catalog.tables
WHERE namespace = 'commit_test' AND table_name = 'commit_table';

-- Commit with correct expected location
SELECT * FROM iceberg_catalog.commit_table(
    'commit_test',
    'commit_table',
    's3://demo-bucket/commit_test/commit_table/metadata/00000-d0591a8c-78aa-4eb6-9ea1-1fcd8594d5cd.metadata.json',
    's3://demo-bucket/commit_test/commit_table/metadata/00001-new.metadata.json'
);

-- Check result - should show new location
SELECT metadata_location AS after_commit, previous_metadata_location AS previous
FROM iceberg_catalog.tables
WHERE namespace = 'commit_test' AND table_name = 'commit_table';

-- Cleanup
SELECT * FROM iceberg_catalog.drop_table('commit_test', 'commit_table');
SELECT * FROM iceberg_catalog.drop_namespace('commit_test');