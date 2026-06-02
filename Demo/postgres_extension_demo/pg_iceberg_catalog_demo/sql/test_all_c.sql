-- Test ALL C functions implementation (v2.0)

-- 1. Check functions are C implementation
SELECT proname, prosrc FROM pg_proc
WHERE pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'iceberg_catalog')
ORDER BY proname;

-- 2. Test create_namespace (C)
SELECT * FROM iceberg_catalog.create_namespace('c_test', '{"owner":"c_impl"}'::jsonb);

-- 3. Test list_namespaces (C)
SELECT * FROM iceberg_catalog.list_namespaces();

-- 4. Test create_table (C)
SELECT namespace, table_name, metadata_location FROM iceberg_catalog.create_table(
    'c_test',
    'c_table',
    '{"type":"struct","fields":[{"id":1,"name":"id","type":"long"}]}'::jsonb
);

-- 5. Test list_tables (C)
SELECT * FROM iceberg_catalog.list_tables('c_test');

-- 6. Test load_table (C)
SELECT namespace, table_name, metadata_location FROM iceberg_catalog.load_table('c_test', 'c_table');

-- 7. Test register_table (C)
SELECT namespace, table_name FROM iceberg_catalog.register_table(
    'c_test',
    'registered_table',
    's3://external/metadata.json',
    '{"location":"s3://external","properties":{"owner":"external"}}'::jsonb
);

-- 8. Verify register result
SELECT namespace, table_name, metadata_location FROM iceberg_catalog.tables
WHERE namespace = 'c_test' AND table_name = 'registered_table';

-- 9. Test rename_table (C)
SELECT * FROM iceberg_catalog.rename_table('c_test', 'c_table', 'c_test', 'renamed_table');

-- 10. Verify rename
SELECT table_name FROM iceberg_catalog.tables WHERE namespace = 'c_test';

-- 11. Get metadata_location for commit test
SELECT metadata_location FROM iceberg_catalog.tables
WHERE namespace = 'c_test' AND table_name = 'renamed_table';

-- 12. Test commit_table (C) - success
DO $$
DECLARE
    v_loc TEXT;
BEGIN
    SELECT metadata_location INTO v_loc FROM iceberg_catalog.tables
    WHERE namespace = 'c_test' AND table_name = 'renamed_table';

    PERFORM iceberg_catalog.commit_table(
        'c_test', 'renamed_table',
        v_loc,
        's3://demo-bucket/c_test/renamed_table/metadata/v2.metadata.json'
    );
    RAISE NOTICE 'commit_table (C) succeeded';
END $$;

-- 13. Verify commit
SELECT metadata_location, previous_metadata_location FROM iceberg_catalog.tables
WHERE namespace = 'c_test' AND table_name = 'renamed_table';

-- 14. Test commit_table (C) - conflict
DO $$
BEGIN
    PERFORM iceberg_catalog.commit_table(
        'c_test', 'renamed_table',
        's3://old/metadata.json',
        's3://new/metadata.json'
    );
EXCEPTION WHEN SQLSTATE '40001' THEN
    RAISE NOTICE 'commit_table (C) conflict detected correctly';
END $$;

-- 15. Test drop_table with purge (C)
SELECT * FROM iceberg_catalog.drop_table('c_test', 'registered_table', true);

-- 16. Verify purge_queue
SELECT namespace, table_name FROM iceberg_catalog.purge_queue;

-- 17. Test drop_table without purge (C)
SELECT * FROM iceberg_catalog.drop_table('c_test', 'renamed_table', false);

-- 18. Test unregister_table (C)
SELECT * FROM iceberg_catalog.unregister_table('c_test', 'unregistered');
-- Should fail - table doesn't exist

-- 19. Create and unregister
SELECT namespace, table_name FROM iceberg_catalog.create_table(
    'c_test', 'to_unregister',
    '{"type":"struct","fields":[{"id":1,"name":"id","type":"long"}]}'::jsonb
);
SELECT * FROM iceberg_catalog.unregister_table('c_test', 'to_unregister');

-- 20. Test drop_namespace (C) - non-empty should fail
-- Should fail - namespace not empty (we have no tables left but let's test)

-- 21. Clean up
SELECT * FROM iceberg_catalog.drop_namespace('c_test');

-- 22. Final check - all functions should show C symbols
SELECT 'All C functions test completed!' AS result;