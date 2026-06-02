-- Test C functions implementation

-- 1. Create namespace (still PL/pgSQL)
SELECT * FROM iceberg_catalog.create_namespace('test_c', '{"owner":"c_test"}'::jsonb);

-- 2. Create table (now C implementation)
SELECT * FROM iceberg_catalog.create_table(
    'test_c',
    'test_table',
    '{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}'::jsonb
);

-- 3. Load table (now C implementation)
SELECT namespace, table_name, metadata_location FROM iceberg_catalog.load_table('test_c', 'test_table');

-- 4. Get current metadata_location for commit test
SELECT metadata_location AS current_loc FROM iceberg_catalog.tables
WHERE namespace = 'test_c' AND table_name = 'test_table';

-- 5. Test commit_table (C implementation) - will use the location from above
DO $$
DECLARE
    v_current TEXT;
BEGIN
    SELECT metadata_location INTO v_current
    FROM iceberg_catalog.tables
    WHERE namespace = 'test_c' AND table_name = 'test_table';

    PERFORM iceberg_catalog.commit_table(
        'test_c',
        'test_table',
        v_current,
        's3://demo-bucket/test_c/test_table/metadata/v2.metadata.json'
    );
    RAISE NOTICE 'C commit_table succeeded';
END $$;

-- 6. Verify commit
SELECT metadata_location, previous_metadata_location FROM iceberg_catalog.tables
WHERE namespace = 'test_c' AND table_name = 'test_table';

-- 7. Test commit conflict (should fail)
DO $$
BEGIN
    PERFORM iceberg_catalog.commit_table(
        'test_c',
        'test_table',
        's3://demo-bucket/test_c/test_table/metadata/old.metadata.json',
        's3://demo-bucket/test_c/test_table/metadata/v3.metadata.json'
    );
EXCEPTION WHEN SQLSTATE '40001' THEN
    RAISE NOTICE 'C commit conflict detected correctly';
END $$;

-- 8. Check function implementation
SELECT proname, prosrc FROM pg_proc
WHERE pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'iceberg_catalog')
AND proname IN ('commit_table', 'create_table', 'load_table');

-- 9. Cleanup
SELECT * FROM iceberg_catalog.drop_table('test_c', 'test_table');
SELECT * FROM iceberg_catalog.drop_namespace('test_c');

SELECT 'C functions test completed!' AS result;